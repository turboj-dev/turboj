package com.turboj.ai;

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
        boolean diagnostics) {
    public enum Mode {
        DISABLED,
        LOCAL,
        CLOUD
    }

    public enum LocalBackend {
        OLLAMA,
        LLAMA_CPP
    }

    public AIConfig {
        mode = mode == null ? Mode.DISABLED : mode;
        localBackend = localBackend == null ? LocalBackend.OLLAMA : localBackend;
        localEndpoint = defaultString(localEndpoint, "http://127.0.0.1:11434");
        localModel = defaultString(localModel, "qwen2.5-coder:1.5b-base");
        cloudEndpoint = defaultString(cloudEndpoint, "https://api.openai.com/v1");
        cloudModel = defaultString(cloudModel, "gpt-6-luna");
        delayMillis = Math.clamp(delayMillis, 150, 2_000);
        timeoutSeconds = Math.clamp(timeoutSeconds, 1, 120);
        maxTokens = Math.clamp(maxTokens, 16, 2_048);
        contextChars = Math.clamp(contextChars, 1_024, 32_768);
        exclusions =
                exclusions == null
                        ? ".env\n.env.*\n*.pem\n*.key\n*credentials*\n*secrets*"
                        : exclusions;
        if (exclusions.length() > 8_192)
            throw new IllegalArgumentException("AI exclusions are too long.");
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
        return new AIConfig(
                value,
                localBackend,
                localEndpoint,
                localModel,
                cloudEndpoint,
                cloudModel,
                automaticLocal,
                automaticCloud,
                cloudConsent,
                delayMillis,
                timeoutSeconds,
                maxTokens,
                contextChars,
                exclusions,
                diagnostics);
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
        for (String model : new String[] {localModel, cloudModel}) {
            if (model.length() > 200 || model.chars().anyMatch(c -> Character.isISOControl(c)))
                throw new IllegalArgumentException("AI model name is invalid.");
        }
        if (localModel.toLowerCase(Locale.ROOT).contains(":cloud")
                || localModel.toLowerCase(Locale.ROOT).endsWith("-cloud"))
            throw new IllegalArgumentException("Local AI cannot use an Ollama cloud model.");
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
