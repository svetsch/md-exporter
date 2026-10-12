package io.mdexporter.ai;

import io.mdexporter.settings.SettingsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A configured AI service ("agent"). Several can be configured, one is active at a time.
 *
 * @param id        stable identifier
 * @param name      display name
 * @param provider  kind of service
 * @param endpoint  base URL of the API
 * @param model     model id
 * @param apiKey    API key; empty to use the provider's environment variable
 * @param maxTokens maximum length of the answer
 * @param effort    Claude effort level ({@code low} ... {@code max}); empty for the model default
 * @param fallback  Claude: let the API re-run declined requests on a fallback model (server-side fallback)
 */
public record AiAgent(String id, String name, AiProvider provider, String endpoint, String model, String apiKey,
                      int maxTokens, String effort, boolean fallback) {

    private static final String PREFIX = "ai.agent.";

    public AiAgent {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = name == null || name.isBlank() ? provider.displayName() : name.trim();
        endpoint = endpoint == null || endpoint.isBlank() ? provider.defaultEndpoint() : endpoint.trim();
        model = model == null || model.isBlank() ? provider.defaultModel() : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        maxTokens = maxTokens <= 0 ? provider.defaultMaxTokens() : maxTokens;
        effort = effort == null ? "" : effort.trim();
    }

    public static AiAgent defaults(AiProvider provider) {
        return new AiAgent(null, provider.displayName(), provider, null, null, "", 0, "",
                provider == AiProvider.ANTHROPIC);
    }

    /** API key of the agent, else the provider's environment variable, else empty. */
    public String resolvedApiKey() {
        if (!apiKey.isEmpty()) {
            return apiKey;
        }
        String variable = provider.apiKeyVariable();
        String value = variable == null ? null : System.getenv(variable);
        return value == null ? "" : value.trim();
    }

    public AiAgent withModel(String newModel) {
        return new AiAgent(id, name, provider, endpoint, newModel, apiKey, maxTokens, effort, fallback);
    }

    @Override
    public String toString() {
        return name + " (" + model + ")";
    }

    // ------------------------------------------------------------------ persistence

    /** Loads the configured agents ({@code ai.agent.<n>.<field>} keys). */
    public static List<AiAgent> load(SettingsStore store) {
        List<AiAgent> agents = new ArrayList<>();
        int count = store.getInt(PREFIX + "count", 0);
        for (int i = 0; i < count; i++) {
            String p = PREFIX + i + ".";
            AiProvider provider;
            try {
                provider = AiProvider.valueOf(store.get(p + "provider", AiProvider.ANTHROPIC.name()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            agents.add(new AiAgent(store.get(p + "id", null), store.get(p + "name", null), provider,
                    store.get(p + "endpoint", null), store.get(p + "model", null), store.get(p + "apiKey", ""),
                    store.getInt(p + "maxTokens", 0), store.get(p + "effort", ""),
                    store.getBoolean(p + "fallback", provider == AiProvider.ANTHROPIC)));
        }
        return agents;
    }

    public static void save(SettingsStore store, List<AiAgent> agents) {
        int old = store.getInt(PREFIX + "count", 0);
        for (int i = 0; i < old; i++) {
            for (String field : List.of("id", "name", "provider", "endpoint", "model", "apiKey", "maxTokens",
                    "effort", "fallback")) {
                store.remove(PREFIX + i + "." + field);
            }
        }
        for (int i = 0; i < agents.size(); i++) {
            AiAgent a = agents.get(i);
            String p = PREFIX + i + ".";
            store.put(p + "id", a.id());
            store.put(p + "name", a.name());
            store.put(p + "provider", a.provider().name());
            store.put(p + "endpoint", a.endpoint());
            store.put(p + "model", a.model());
            store.put(p + "apiKey", a.apiKey().isEmpty() ? null : a.apiKey());
            store.putInt(p + "maxTokens", a.maxTokens());
            store.put(p + "effort", a.effort().isEmpty() ? null : a.effort());
            store.putBoolean(p + "fallback", a.fallback());
        }
        store.putInt(PREFIX + "count", agents.size());
        store.flush();
    }
}
