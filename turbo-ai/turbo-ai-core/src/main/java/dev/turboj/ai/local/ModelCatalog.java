package dev.turboj.ai.local;

import java.util.List;
import java.util.Optional;

/**
 * Local FIM models TurboAI knows how to describe before a download: size, license and source were read
 * from the Ollama registry manifests on 26 October 2026. Qwen2.5-Coder 3B is absent on purpose: its Qwen
 * Research license forbids commercial use, unlike the Apache-2.0 0.5B, 1.5B and 7B.
 */
public final class ModelCatalog {
    public record Model(String name, String label, long bytes, String license, String role) {
        public String source() {
            return "registry.ollama.ai/library/" + name.replace(':', '/');
        }

        public String size() {
            return bytes >= 1_000_000_000L ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
                    : String.format(java.util.Locale.ROOT, "%d MB", Math.round(bytes / 1e6));
        }
    }

    public static final String DEFAULT = "qwen2.5-coder:1.5b-base";

    private static final List<Model> MODELS = List.of(
            new Model("qwen2.5-coder:0.5b-base", "Qwen2.5-Coder 0.5B (Q4_K_M)", 531_080_002L, "Apache-2.0", "Small memory"),
            new Model(DEFAULT, "Qwen2.5-Coder 1.5B (Q4_K_M)", 986_060_385L, "Apache-2.0", "Balanced (default)"),
            new Model("qwen2.5-coder:7b-base", "Qwen2.5-Coder 7B (Q4_K_M)", 4_683_085_412L, "Apache-2.0", "Higher quality"));

    private ModelCatalog() {}

    public static List<Model> models() {
        return MODELS;
    }

    public static Optional<Model> find(String name) {
        String wanted = canonical(name);
        return MODELS.stream().filter(m -> m.name().equals(wanted)).findFirst();
    }

    /** Ollama treats a name without a tag as {@code :latest}. */
    public static String canonical(String name) {
        String trimmed = name.strip();
        int slash = trimmed.lastIndexOf('/');
        return trimmed.indexOf(':', slash + 1) < 0 ? trimmed + ":latest" : trimmed;
    }
}
