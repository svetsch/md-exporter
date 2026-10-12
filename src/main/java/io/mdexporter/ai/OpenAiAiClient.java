package io.mdexporter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** OpenAI Chat Completions API, also spoken by Ollama, LM Studio, Mistral, OpenRouter, vLLM, ... */
final class OpenAiAiClient implements AiClient {

    private final AiAgent agent;

    OpenAiAiClient(AiAgent agent) {
        this.agent = agent;
    }

    private HttpRequest.Builder request(String path) throws AiException {
        HttpRequest.Builder b = HttpRequest.newBuilder(HttpAiSupport.uri(agent.endpoint(), path));
        String key = agent.resolvedApiKey();
        if (!key.isEmpty()) {
            b.header("Authorization", "Bearer " + key);
        } else if (agent.provider().requiresApiKey()) {
            throw new AiException("No API key: enter one in the agent settings or set "
                    + agent.provider().apiKeyVariable() + ".");
        }
        return b;
    }

    @Override
    public void stream(AiRequest request, Consumer<String> onText, Cancellation cancel) throws AiException {
        ObjectNode body = HttpAiSupport.JSON.createObjectNode();
        body.put("model", agent.model());
        body.put("stream", true);
        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : agent.maxTokens();
        // OpenAI renamed the parameter; compatible servers still expect the original name
        body.put(agent.provider() == AiProvider.OPENAI ? "max_completion_tokens" : "max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        messages.addObject().put("role", "user").put("content", request.user());
        HttpAiSupport.postStream(request("/chat/completions"), body, cancel, data -> {
            if (data.has("error")) {
                throw HttpAiSupport.error(0, data.toString());
            }
            for (JsonNode choice : data.path("choices")) {
                HttpAiSupport.emit(choice.path("delta").path("content"), onText);
                if ("content_filter".equals(choice.path("finish_reason").asText())) {
                    throw new AiException("The answer was blocked by the provider's content filter.");
                }
            }
        });
    }

    @Override
    public List<String> listModels() throws AiException {
        JsonNode json = HttpAiSupport.getJson(request("/models"));
        List<String> ids = new ArrayList<>();
        for (JsonNode model : json.path("data")) {
            ids.add(model.path("id").asText());
        }
        ids.sort(null);
        return ids;
    }
}
