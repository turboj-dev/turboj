package dev.turboj.commandj.keymap;

import java.util.Objects;

public record KeyBinding(KeySequence sequence, BindingContext context) {
    public KeyBinding {
        Objects.requireNonNull(sequence);
        Objects.requireNonNull(context);
    }

    public static KeyBinding of(String sequence) {
        return new KeyBinding(KeySequence.parse(sequence), BindingContext.any());
    }

    public static KeyBinding editor(String sequence) {
        return new KeyBinding(KeySequence.parse(sequence), BindingContext.requiring("editor"));
    }
}
