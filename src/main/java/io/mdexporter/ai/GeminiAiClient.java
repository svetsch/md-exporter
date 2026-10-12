package io.mdexporter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Google Gemini API ({@code streamGenerateContent} with server-sent events). */
final class GeminiAiClient implements AiClient {

    private final AiAgent agent;

    GeminiAiClient(AiAgent agent) {
        this.agent = agent;
    }

    private HttpRequest.Builder request(String path) throws AiException {
        String key = agent.resolvedApiKey();
        if (key.isEmpty()) {
            throw new AiException("No API key: enter one in the agent settings or set "
                    + agent.provider().apiKeyVariable() + ".");
        }
        return HttpRequest.newBuilder(HttpAiSupport.uri(agent.endpoint(), path)).header("x-goog-api-key", key);
    }

    @Override
    public void stream(AiRequest request, Consumer<String> onText, Cancellation cancel) throws AiException {
        ObjectNode body = HttpAiSupport.JSON.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", request.user());
        body.putObject("generationConfig")
                .put("maxOutputTokens", request.maxTokens() > 0 ? request.maxTokens() : agent.maxTokens());
        String model = agent.model().startsWith("models/") ? agent.model().substring(7) : agent.model();
        String path = "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":streamGenerateContent?alt=sse";
        HttpAiSupport.postStream(request(path), body, cancel, data -> {
            if (data.has("error")) {
                throw HttpAiSupport.error(0, data.toString());
            }
            String blocked = data.path("promptFeedback").path("blockReason").asText("");
            if (!blocked.isEmpty()) {
                throw new AiException("The request was blocked by Gemini (" + blocked + ").");
            }
            for (JsonNode candidate : data.path("candidates")) {
                for (JsonNode part : candidate.path("content").path("parts")) {
                    if (!part.path("thought").asBoolean(false)) {
                        HttpAiSupport.emit(part.path("text"), onText);
                    }
                }
                String finish = candidate.path("finishReason").asText("");
                if (finish.equals("SAFETY") || finish.equals("PROHIBITED_CONTENT") || finish.equals("BLOCKLIST")) {
                    throw new AiException("The answer was blocked by Gemini (" + finish + ").");
                }
            }
        });
    }

    @Override
    public List<String> listModels() throws AiException {
        List<String> ids = new ArrayList<>();
        String pageToken = "";
        do {
            String path = "/models?pageSize=1000" + (pageToken.isEmpty() ? "" : "&pageToken=" + pageToken);
            JsonNode json = HttpAiSupport.getJson(request(path));
            for (JsonNode model : json.path("models")) {
                boolean generates = false;
                for (JsonNode method : model.path("supportedGenerationMethods")) {
                    generates |= "generateContent".equals(method.asText());
                }
                if (generates) {
                    String name = model.path("name").asText();
                    ids.add(name.startsWith("models/") ? name.substring(7) : name);
                }
            }
            pageToken = json.path("nextPageToken").asText("");
        } while (!pageToken.isEmpty());
        return ids;
    }
}
