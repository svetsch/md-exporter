package io.mdexporter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Claude through the Anthropic Messages API over plain HTTP (streaming, server-sent events). */
final class AnthropicAiClient implements AiClient {

    private static final String API_VERSION = "2023-06-01";
    /** Server-side refusal fallback: Anthropic re-runs a declined request on its recommended fallback model. */
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AiAgent agent;

    AnthropicAiClient(AiAgent agent) {
        this.agent = agent;
    }

    private HttpRequest.Builder request(String path) throws AiException {
        String key = agent.resolvedApiKey();
        if (key.isEmpty()) {
            throw new AiException("No API key: enter one in the agent settings or set "
                    + agent.provider().apiKeyVariable() + ".");
        }
        return HttpRequest.newBuilder(HttpAiSupport.uri(agent.endpoint(), path))
                .header("x-api-key", key)
                .header("anthropic-version", API_VERSION);
    }

    @Override
    public void stream(AiRequest request, Consumer<String> onText, Cancellation cancel) throws AiException {
        ObjectNode body = HttpAiSupport.JSON.createObjectNode();
        body.put("model", agent.model());
        body.put("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : agent.maxTokens());
        body.put("stream", true);
        body.put("system", request.system());
        body.putArray("messages").addObject().put("role", "user").put("content", request.user());
        if (!agent.effort().isEmpty()) {
            body.putObject("output_config").put("effort", agent.effort());
        }
        HttpRequest.Builder http = request("/v1/messages");
        if (agent.fallback()) {
            http.header("anthropic-beta", FALLBACK_BETA);
            body.put("fallbacks", "default");
        }
        StringBuilder refusal = new StringBuilder();
        HttpAiSupport.postStream(http, body, cancel, data -> {
            switch (data.path("type").asText()) {
                case "content_block_delta" -> {
                    JsonNode delta = data.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())) {
                        HttpAiSupport.emit(delta.path("text"), onText);
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = data.path("delta");
                    if ("refusal".equals(delta.path("stop_reason").asText())) {
                        refusal.append("The model declined the request");
                        String explanation = delta.path("stop_details").path("explanation").asText("");
                        if (!explanation.isEmpty()) {
                            refusal.append(": ").append(explanation);
                        }
                    }
                }
                case "error" -> {
                    JsonNode error = data.path("error");
                    String type = error.path("type").asText("error");
                    throw new AiException(("overloaded_error".equals(type)
                            ? "Claude is overloaded, try again later" : "Claude API error (" + type + ")")
                            + ": " + error.path("message").asText(""));
                }
                default -> {
                    // message_start, content_block_start/stop, message_stop, ping
                }
            }
        });
        if (!refusal.isEmpty()) {
            throw new AiException(refusal.toString());
        }
    }

    @Override
    public List<String> listModels() throws AiException {
        List<String> ids = new ArrayList<>();
        String after = "";
        boolean more = true;
        while (more) {
            String path = "/v1/models?limit=1000"
                    + (after.isEmpty() ? "" : "&after_id=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
            JsonNode page = HttpAiSupport.getJson(request(path));
            for (JsonNode model : page.path("data")) {
                ids.add(model.path("id").asText());
            }
            after = page.path("last_id").asText("");
            more = page.path("has_more").asBoolean(false) && !after.isEmpty();
        }
        return ids;
    }
}
