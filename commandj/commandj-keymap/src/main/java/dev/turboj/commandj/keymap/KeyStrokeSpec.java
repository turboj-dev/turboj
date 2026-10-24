package dev.turboj.commandj.keymap;

import java.util.*;

/** Portable logical key, independent of AWT and physical US keyboard positions. */
public record KeyStrokeSpec(String key, Set<Modifier> modifiers) {
    public enum Modifier {
        CTRL,
        ALT,
        SHIFT,
        META
    }

    public KeyStrokeSpec {
        key = Objects.requireNonNull(key).toUpperCase(Locale.ROOT);
        modifiers = Set.copyOf(modifiers);
        if (key.isBlank() || key.length() > 40)
            throw new IllegalArgumentException("Invalid key");
    }

    public static KeyStrokeSpec parse(String value) {
        var mods = EnumSet.noneOf(Modifier.class);
        String key = null;

        for (String part : value.trim().replace('+', ' ').split("\\s+")) {
            String token = part.toUpperCase(Locale.ROOT);

            switch (token) {
                case "CTRL", "CONTROL", "⌃" -> mods.add(Modifier.CTRL);
                case "ALT", "OPTION", "OPT", "⌥" -> mods.add(Modifier.ALT);
                case "SHIFT", "⇧" -> mods.add(Modifier.SHIFT);
                case "META", "CMD", "COMMAND", "⌘" -> mods.add(Modifier.META);
                case "PRESSED" -> {}
                default -> {
                    if (key != null)
                        throw new IllegalArgumentException("More than one key in stroke: " + value);
                    key = switch (token) {
                        case "ESC" -> "ESCAPE";
                        case "RETURN" -> "ENTER";
                        case "FORWARD_DELETE" -> "DELETE";
                        default -> token;
                    };
                }
            }
        }
        if (key == null)
            throw new IllegalArgumentException("Missing key");
        return new KeyStrokeSpec(key, mods);
    }

    public String external() {
        StringBuilder s = new StringBuilder();

        for (Modifier m : Modifier.values())
            if (modifiers.contains(m))
                s.append(m.name()).append('+');
        return s.append(key).toString();
    }

    public String display() {
        return external().replace("META+", "Cmd+").replace("CTRL+", "Ctrl+").replace("ALT+",
                "Option+").replace("SHIFT+", "Shift+");
    }
}
