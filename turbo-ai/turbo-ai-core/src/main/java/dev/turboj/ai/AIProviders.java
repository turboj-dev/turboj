package dev.turboj.ai;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class AIProviders {
    private AIProviders() {}

    /** The configured provider alone, with no readiness gate. */
    public static AICompletionProvider create(AIConfig config, Supplier<String> credential) {
        return create(config, credential, null);
    }

    /**
     * The fallback chain for a configuration, cheapest and most private first. Local mode: the model,
     * then the local fallback model, then the cloud only if {@link AIConfig#cloudFallback()} was chosen
     * with source-sharing consent. Cloud mode: the cloud model, then the local model when it is ready.
     * {@code localReady} gates local routes (a model still loading is skipped, not failed); null means
     * "always ready".
     */
    public static AICompletionProvider create(AIConfig config, Supplier<String> credential, BooleanSupplier localReady) {
        Objects.requireNonNull(config).validate();
        if (config.mode() == AIConfig.Mode.DISABLED)
            throw new IllegalStateException("AI completion disabled.");
        BooleanSupplier ready = localReady == null ? () -> true : localReady;
        var routes = new ArrayList<FallbackChain.Route>();
        AIConfig local = config.withMode(AIConfig.Mode.LOCAL);
        if (config.mode() == AIConfig.Mode.LOCAL) {
            routes.add(route("local:" + config.localModel(), local, credential, ready));
            if (!config.localFallbackModel().isEmpty() && !config.localFallbackModel().equals(config.localModel()))
                routes.add(route("local:" + config.localFallbackModel(), local.withModel(config.localFallbackModel()), credential, ready));
            if (config.cloudFallback() && config.cloudConsent() && !config.cloudModel().isBlank())
                routes.add(route(cloudId(config), config.withMode(AIConfig.Mode.CLOUD), credential, null));
        } else {
            routes.add(route(cloudId(config), config, credential, null));
            if (localReady != null) routes.add(route("local:" + config.localModel(), local, credential, ready));
        }
        return new FallbackChain(routes);
    }

    private static String cloudId(AIConfig config) {
        return "cloud:" + config.cloudProvider().name().toLowerCase(java.util.Locale.ROOT) + "/" + config.cloudModel();
    }

    private static FallbackChain.Route route(String id, AIConfig config, Supplier<String> credential, BooleanSupplier ready) {
        return new FallbackChain.Route(id, new HttpAICompletionProvider(config, credential, null), new CircuitBreaker(), ready);
    }
}
