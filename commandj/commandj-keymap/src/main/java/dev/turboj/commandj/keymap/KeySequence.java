package dev.turboj.commandj.keymap;

import java.util.*;

public record KeySequence(List<KeyStrokeSpec> strokes) {
    public KeySequence {
        strokes = List.copyOf(strokes);
        if (strokes.isEmpty() || strokes.size() > 4)
            throw new IllegalArgumentException("Shortcut needs 1–4 strokes");
    }

    public static KeySequence parse(String text) {
        return new KeySequence(Arrays.stream(text.split(",")).map(KeyStrokeSpec::parse).toList());
    }

    public boolean startsWith(List<KeyStrokeSpec> prefix) {
        if (strokes.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) if (!strokes.get(i).equals(prefix.get(i))) return false;
        return true;
    }

    public String external() {
        return String.join(", ", strokes.stream().map(KeyStrokeSpec::external).toList());
    }

    public String display() {
        return KeyStrokeSpec.usesSymbols() ? String.join(" ", strokes.stream().map(KeyStrokeSpec::symbols).toList()) : words();
    }

    public String words() {
        return String.join(", ", strokes.stream().map(KeyStrokeSpec::words).toList());
    }
}
