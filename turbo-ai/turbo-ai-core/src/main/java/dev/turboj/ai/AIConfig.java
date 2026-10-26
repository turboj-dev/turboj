package dev.turboj.ai;

import java.net.URI;
import java.util.Locale;

/** Immutable non-secret preferences. Constructing or loading these values performs no I/O. */
public record AIConfig(
        Mode mode,
        LocalBackend localBackend,
        String localEndpoint,
        String localModel,
        String cloudEndpoint,
        String cloudModel,
        boolean automaticLocal,
        boolean automaticCloud,
        boolean cloudConsent,
        int delayMillis,
        int timeoutSeconds,
        int maxTokens,
        int contextChars,
        String exclusions,
        boolean diagnostics,
        CloudProvider cloudProvider,
        LocalRuntime localRuntime,
        int keepAliveMinutes,
        String localFallbackModel,
        boolean cloudFallback) {
    /**
     * The context window every local request and warm-up uses. Ollama reloads a model when a request asks
     * for a different one, so it must not vary between requests.
     */
    public static final int LOCAL_CONTEXT_TOKENS = 8_192;

    public enum Mode {
        DISABLED,
        LOCAL,
        CLOUD
    }

    public enum LocalBackend {
        OLLAMA,
        LLAMA_CPP
    }

    /** Who owns the local inference server. */
    public enum LocalRuntime {
        /** Reuse a server already answering at the endpoint; otherwise start and own one. */
        AUTOMATIC,
        /** Always start and own a loopback server at the endpoint's port. */
        MANAGED,
        /** Only connect; never start, restart or stop anything. */
        EXTERNAL
    }

    public enum CloudProvider {
        OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-6-luna"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-opus-5-5"),
        /** xAI publishes its model list at {@code GET /v1/models}; TurboJ does not guess a current id. */
        XAI("xAI", "https://api.x.ai/v1", "");

        private final String label, endpoint, model;

        CloudProvider(String label, String endpoint, String model) {
            this.label = label;
            this.endpoint = endpoint;
            this.model = model;
        }

        public String label() {
            return label;
        }

        public String defaultEndpoint() {
            return endpoint;
        }

        public String defaultModel() {
            return model;
        }
    }

    public AIConfig {
        mode = mode == null ? Mode.DISABLED : mode;
        localBackend = localBackend == null ? LocalBackend.OLLAMA : localBackend;
        cloudProvider = cloudProvider == null ? CloudProvider.OPENAI : cloudProvider;
        localRuntime = localRuntime == null ? LocalRuntime.AUTOMATIC : localRuntime;
        localEndpoint = defaultString(localEndpoint, "http://127.0.0.1:11434");
        localModel = defaultString(localModel, "qwen2.5-coder:1.5b-base");
        cloudEndpoint = defaultString(cloudEndpoint, cloudProvider.defaultEndpoint());
        cloudModel = defaultString(cloudModel, cloudProvider.defaultModel());
        localFallbackModel = localFallbackModel == null ? "" : localFallbackModel.trim();
        delayMillis = Math.clamp(delayMillis, 150, 2_000);
        timeoutSeconds = Math.clamp(timeoutSeconds, 1, 120);
        maxTokens = Math.clamp(maxTokens, 16, 2_048);
        contextChars = Math.clamp(contextChars, 1_024, 32_768);
        // 0 is what older preferences without the field load as.
        keepAliveMinutes = keepAliveMinutes == 0 ? 30 : Math.clamp(keepAliveMinutes, 1, 1_440);
        exclusions =
                exclusions == null
                        ? ".env\n.env.*\n*.pem\n*.key\n*credentials*\n*secrets*"
                        : exclusions;
        if (exclusions.length() > 8_192)
            throw new IllegalArgumentException("AI exclusions are too long.");
    }

    /** The preferences of TurboJ 1.0.5 to 1.0.10, before runtime management and more providers. */
    public AIConfig(
            Mode mode,
            LocalBackend localBackend,
            String localEndpoint,
            String localModel,
            String cloudEndpoint,
            String cloudModel,
            boolean automaticLocal,
            boolean automaticCloud,
            boolean cloudConsent,
            int delayMillis,
            int timeoutSeconds,
            int maxTokens,
            int contextChars,
            String exclusions,
            boolean diagnostics) {
        this(mode, localBackend, localEndpoint, localModel, cloudEndpoint, cloudModel, automaticLocal,
                automaticCloud, cloudConsent, delayMillis, timeoutSeconds, maxTokens, contextChars, exclusions,
                diagnostics, CloudProvider.OPENAI, LocalRuntime.AUTOMATIC, 30, "", false);
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static AIConfig defaults() {
        return new AIConfig(
                Mode.DISABLED,
                LocalBackend.OLLAMA,
                null,
                null,
                null,
                null,
                true,
                false,
                false,
                200,
                30,
                256,
                12_288,
                null,
                false);
    }

    public AIConfig normalized() {
        return this;
    }

    public AIConfig withMode(Mode value) {
        return new AIConfig(value, localBackend, localEndpoint, localModel, cloudEndpoint, cloudModel,
                automaticLocal, automaticCloud, cloudConsent, delayMillis, timeoutSeconds, maxTokens, contextChars,
                exclusions, diagnostics, cloudProvider, localRuntime, keepAliveMinutes, localFallbackModel,
                cloudFallback);
    }

    public AIConfig withLocal(LocalBackend backend, String endpoint, String model, LocalRuntime runtime) {
        return new AIConfig(mode, backend, endpoint, model, cloudEndpoint, cloudModel, automaticLocal,
                automaticCloud, cloudConsent, delayMillis, timeoutSeconds, maxTokens, contextChars, exclusions,
                diagnostics, cloudProvider, runtime, keepAliveMinutes, localFallbackModel, cloudFallback);
    }

    public AIConfig withCloud(CloudProvider provider, String endpoint, String model, boolean consent) {
        return new AIConfig(mode, localBackend, localEndpoint, localModel, endpoint, model, automaticLocal,
                automaticCloud, consent, delayMillis, timeoutSeconds, maxTokens, contextChars, exclusions,
                diagnostics, provider, localRuntime, keepAliveMinutes, localFallbackModel, cloudFallback);
    }

    public AIConfig withLocalFallback(String model, boolean cloud) {
        return new AIConfig(mode, localBackend, localEndpoint, localModel, cloudEndpoint, cloudModel,
                automaticLocal, automaticCloud, cloudConsent, delayMillis, timeoutSeconds, maxTokens, contextChars,
                exclusions, diagnostics, cloudProvider, localRuntime, keepAliveMinutes, model, cloud);
    }

    public AIConfig withKeepAlive(int minutes) {
        return new AIConfig(mode, localBackend, localEndpoint, localModel, cloudEndpoint, cloudModel,
                automaticLocal, automaticCloud, cloudConsent, delayMillis, timeoutSeconds, maxTokens, contextChars,
                exclusions, diagnostics, cloudProvider, localRuntime, minutes, localFallbackModel, cloudFallback);
    }

    /** The same preferences with another model, for a fallback route. */
    AIConfig withModel(String model) {
        return mode == Mode.CLOUD
                ? withCloud(cloudProvider, cloudEndpoint, model, cloudConsent)
                : withLocal(localBackend, localEndpoint, model, localRuntime);
    }

    /** Ollama's keep_alive: how long the server keeps the model loaded after a request. */
    public String keepAlive() {
        return keepAliveMinutes + "m";
    }

    /** Short name of the provider in use, for messages. */
    public String providerLabel() {
        return mode == Mode.CLOUD ? cloudProvider.label() : localBackend == LocalBackend.OLLAMA ? "Ollama" : "llama.cpp";
    }

    public boolean automatic() {
        return mode == Mode.LOCAL
                ? automaticLocal
                : mode == Mode.CLOUD && cloudConsent && automaticCloud;
    }

    public String model() {
        return mode == Mode.CLOUD ? cloudModel : localModel;
    }

    /** Base URL; API paths are appended rather than replacing an optional reverse-proxy path. */
    public URI endpoint() {
        return checkedEndpoint(
                mode == Mode.CLOUD ? cloudEndpoint : localEndpoint, mode == Mode.CLOUD);
    }

    public void validate() {
        checkedEndpoint(localEndpoint, false);
        checkedEndpoint(cloudEndpoint, true);
        for (String model : new String[] {localModel, cloudModel, localFallbackModel}) {
            if (model.length() > 200 || model.chars().anyMatch(c -> Character.isISOControl(c)))
                throw new IllegalArgumentException("AI model name is invalid.");
        }
        if (localModel.toLowerCase(Locale.ROOT).contains(":cloud")
                || localModel.toLowerCase(Locale.ROOT).endsWith("-cloud"))
            throw new IllegalArgumentException("Local AI cannot use an Ollama cloud model.");
        if (mode == Mode.CLOUD && cloudModel.isBlank())
            throw new IllegalArgumentException(
                    "Choose a " + cloudProvider.label() + " model; Test lists the models your key can use.");
        if (cloudFallback && !cloudConsent)
            throw new IllegalArgumentException(
                    "Falling back to cloud completion requires explicit source-sharing consent.");
        if (mode == Mode.CLOUD && !cloudConsent)
            throw new IllegalArgumentException(
                    "Cloud completion requires explicit source-sharing consent.");
    }

    static URI checkedEndpoint(String text, boolean cloud) {
        final URI uri;
        try {
            uri = URI.create(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("AI endpoint is invalid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || uri.getPort() == 0
                || text.length() > 2_048)
            throw new IllegalArgumentException(
                    "AI endpoint must be a base URL without credentials, query or fragment.");
        if (cloud ? !scheme.equals("https") : !(scheme.equals("http") || scheme.equals("https")))
            throw new IllegalArgumentException(
                    cloud ? "Cloud AI requires HTTPS." : "Local AI requires HTTP or HTTPS.");
        if (!cloud && !isLoopback(host))
            throw new IllegalArgumentException(
                    "Local AI endpoints must use localhost, 127.0.0.1 or ::1.");
        return uri;
    }

    static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
