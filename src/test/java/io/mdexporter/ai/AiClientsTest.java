package io.mdexporter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AI clients against a local server speaking each provider's streaming protocol. */
class AiClientsTest {

    private HttpServer server;
    private String base;
    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private final Map<String, String> headers = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            requests.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            respond(exchange, status, contentType, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String stream(AiAgent agent) throws AiException {
        StringBuilder out = new StringBuilder();
        agent.provider().createClient(agent).stream(new AiClient.AiRequest("system text", "user text", 1000),
                out::append, new AiClient.Cancellation());
        return out.toString();
    }

    @Test
    void claudeStreaming() throws Exception {
        handle("/v1/messages", 200, "text/event-stream", """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"**world**"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """);
        AiAgent agent = new AiAgent(null, "Claude", AiProvider.ANTHROPIC, base, "claude-opus-5-5", "test-key", 2000,
                "high", true);
        assertEquals("Hello **world**", stream(agent));
        JsonNode body = HttpAiSupport.JSON.readTree(requests.get("/v1/messages"));
        assertEquals("claude-opus-5-5", body.path("model").asText());
        assertEquals("system text", body.path("system").asText());
        assertEquals("user text", body.path("messages").get(0).path("content").asText());
        assertEquals(1000, body.path("max_tokens").asInt());
        assertEquals("high", body.path("output_config").path("effort").asText());
        assertEquals("default", body.path("fallbacks").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals("test-key", headers.get("x-api-key"));
        assertEquals("2023-06-01", headers.get("anthropic-version"));
        assertTrue(headers.get("anthropic-beta").contains("server-side-fallback-2026-07-01"));
    }

    @Test
    void claudeRefusalIsReported() {
        handle("/v1/messages", 200, "text/event-stream", """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null,"stop_details":{"type":"refusal","category":"cyber","explanation":"Not allowed"}},"usage":{"output_tokens":0}}

                event: message_stop
                data: {"type":"message_stop"}

                """);
        AiAgent agent = new AiAgent(null, "Claude", AiProvider.ANTHROPIC, base, "m", "k", 100, "", false);
        AiException e = assertThrows(AiException.class, () -> stream(agent));
        assertEquals("The model declined the request: Not allowed", e.getMessage());
        assertTrue(!requests.get("/v1/messages").contains("fallbacks"), "fallback disabled");
    }

    @Test
    void claudeErrorsAndModels() throws Exception {
        handle("/v1/messages", 401, "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        handle("/v1/models", 200, "application/json",
                "{\"data\":[{\"id\":\"claude-opus-5-5\"},{\"id\":\"claude-sonnet-5-5\"}],\"has_more\":false,"
                        + "\"last_id\":\"claude-sonnet-5-5\"}");
        AiAgent agent = new AiAgent(null, "Claude", AiProvider.ANTHROPIC, base, "m", "bad", 100, "", false);
        AiException e = assertThrows(AiException.class, () -> stream(agent));
        assertEquals("The API key was rejected (401): invalid x-api-key", e.getMessage());
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5-5"), agent.provider().createClient(agent).listModels());
    }

    @Test
    void claudeStreamErrorEvent() {
        handle("/v1/messages", 200, "text/event-stream", """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """);
        AiAgent agent = new AiAgent(null, "Claude", AiProvider.ANTHROPIC, base, "m", "k", 100, "", false);
        AiException e = assertThrows(AiException.class, () -> stream(agent));
        assertEquals("Claude is overloaded, try again later: Overloaded", e.getMessage());
    }

    @Test
    void openAiCompatibleStreaming() throws Exception {
        handle("/v1/chat/completions", 200, "text/event-stream", """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """);
        handle("/v1/models", 200, "application/json", "{\"data\":[{\"id\":\"b\"},{\"id\":\"a\"}]}");
        AiAgent agent = new AiAgent(null, "Local", AiProvider.OPENAI_COMPATIBLE, base + "/v1", "llama", "", 500, "",
                false);
        assertEquals("Hello", stream(agent));
        JsonNode body = HttpAiSupport.JSON.readTree(requests.get("/v1/chat/completions"));
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals(1000, body.path("max_tokens").asInt());
        assertEquals(List.of("a", "b"), agent.provider().createClient(agent).listModels());
        assertTrue(!headers.containsKey("authorization"), "no key sent");
    }

    @Test
    void openAiErrorsAreReadable() {
        handle("/v1/chat/completions", 401, "application/json",
                "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}");
        AiAgent agent = new AiAgent(null, "OpenAI", AiProvider.OPENAI, base + "/v1", "gpt", "bad", 500, "", false);
        AiException e = assertThrows(AiException.class, () -> stream(agent));
        assertEquals("The API key was rejected (401): Incorrect API key provided", e.getMessage());
        assertEquals("Bearer bad", headers.get("authorization"));
    }

    @Test
    void geminiStreaming() throws Exception {
        handle("/v1beta/models/gemini-x:streamGenerateContent", 200, "text/event-stream", """
                data: {"candidates":[{"content":{"parts":[{"text":"Bon"}],"role":"model"}}]}

                data: {"candidates":[{"content":{"parts":[{"text":"jour"}],"role":"model"},"finishReason":"STOP"}]}

                """);
        AiAgent agent = new AiAgent(null, "Gemini", AiProvider.GEMINI, base + "/v1beta", "gemini-x", "g-key", 500, "",
                false);
        assertEquals("Bonjour", stream(agent));
        JsonNode body = HttpAiSupport.JSON.readTree(requests.get("/v1beta/models/gemini-x:streamGenerateContent"));
        assertEquals("system text", body.path("systemInstruction").path("parts").get(0).path("text").asText());
        assertEquals(1000, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals("g-key", headers.get("x-goog-api-key"));
    }

    @Test
    void unreachableServerHasAClearMessage() {
        server.stop(0);
        AiAgent agent = new AiAgent(null, "Local", AiProvider.OPENAI_COMPATIBLE, base + "/v1", "m", "", 100, "",
                false);
        AiException e = assertThrows(AiException.class, () -> stream(agent));
        assertTrue(e.getMessage().startsWith("Cannot connect to http://127.0.0.1:"), e.getMessage());
    }
}
