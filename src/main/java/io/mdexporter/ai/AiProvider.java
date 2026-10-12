package io.mdexporter.ai;

/** Kinds of AI services an agent can talk to. */
public enum AiProvider {

    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com", "claude-opus-5-5", "ANTHROPIC_API_KEY", 64000),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY", 32000),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
            "GEMINI_API_KEY", 32000),
    OPENAI_COMPATIBLE("OpenAI-compatible (Ollama, LM Studio, Mistral, OpenRouter...)", "http://localhost:11434/v1",
            "llama3.2", null, 8192);

    private final String displayName;
    private final String defaultEndpoint;
    private final String defaultModel;
    private final String apiKeyVariable;
    private final int defaultMaxTokens;

    AiProvider(String displayName, String defaultEndpoint, String defaultModel, String apiKeyVariable,
               int defaultMaxTokens) {
        this.displayName = displayName;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultModel = defaultModel;
        this.apiKeyVariable = apiKeyVariable;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultEndpoint() {
        return defaultEndpoint;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** Environment variable used when the agent has no API key; {@code null} if none. */
    public String apiKeyVariable() {
        return apiKeyVariable;
    }

    public int defaultMaxTokens() {
        return defaultMaxTokens;
    }

    /** Whether an API key is mandatory (local OpenAI-compatible servers usually need none). */
    public boolean requiresApiKey() {
        return this != OPENAI_COMPATIBLE;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** Creates the client talking to this kind of service. */
    public AiClient createClient(AiAgent agent) {
        return switch (this) {
            case ANTHROPIC -> new AnthropicAiClient(agent);
            case GEMINI -> new GeminiAiClient(agent);
            case OPENAI, OPENAI_COMPATIBLE -> new OpenAiAiClient(agent);
        };
    }
}
