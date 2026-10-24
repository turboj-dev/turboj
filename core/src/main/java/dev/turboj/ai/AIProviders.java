package dev.turboj.ai;

import java.util.Objects;
import java.util.function.Supplier;

public final class AIProviders {
    private AIProviders() {}

    public static AICompletionProvider create(AIConfig config, Supplier<String> credential) {
        Objects.requireNonNull(config).validate();
        if (config.mode() == AIConfig.Mode.DISABLED)
            throw new IllegalStateException("AI completion is disabled.");
        return new HttpAICompletionProvider(config, credential, null);
    }
}
