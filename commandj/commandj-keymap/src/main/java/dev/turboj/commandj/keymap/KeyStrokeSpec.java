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

    /** Apple's notation (⌃⌥⇧⌘ then the key, no plus signs) instead of words; TurboJ turns it on for macOS users. */
    private static volatile boolean symbols;

    public static void useSymbols(boolean value) { symbols = value; }

    public static boolean usesSymbols() { return symbols; }

    /** How shortcuts are shown everywhere: {@link #symbols()} or {@link #words()}, as the user prefers. */
    public String display() {
        return symbols ? symbols() : words();
    }

    public String words() {
        return external().replace("META+", "Cmd+").replace("CTRL+", "Ctrl+").replace("ALT+",
                "Option+").replace("SHIFT+", "Shift+");
    }

    public String symbols() {
        var s = new StringBuilder();
        if (modifiers.contains(Modifier.CTRL)) s.append('⌃');
        if (modifiers.contains(Modifier.ALT)) s.append('⌥');
        if (modifiers.contains(Modifier.SHIFT)) s.append('⇧');
        if (modifiers.contains(Modifier.META)) s.append('⌘');
        return s.append(keySymbol(key)).toString();
    }

    /** The key as macOS menus print it. */
    public static String keySymbol(String key) {
        return switch (key) {
            case "ENTER" -> "↩"; case "SPACE" -> "Space"; case "TAB" -> "⇥"; case "BACK_SPACE" -> "⌫";
            case "ESCAPE" -> "Esc"; case "LEFT" -> "←"; case "RIGHT" -> "→"; case "UP" -> "↑"; case "DOWN" -> "↓";
            // MacBook keyboards have none of these keys: they are fn with ⌫ and the arrows.
            case "DELETE" -> "fn⌫"; case "HOME" -> "fn←"; case "END" -> "fn→"; case "PAGE_UP" -> "fn↑"; case "PAGE_DOWN" -> "fn↓";
            case "OPEN_BRACKET" -> "["; case "CLOSE_BRACKET" -> "]"; case "SLASH" -> "/"; case "BACK_SLASH" -> "\\";
            case "PERIOD" -> "."; case "COMMA" -> ","; case "QUOTE" -> "'"; case "BACK_QUOTE" -> "`"; case "SEMICOLON" -> ";";
            case "MINUS" -> "-"; case "EQUALS" -> "="; case "PLUS" -> "+";
            default -> key;
        };
    }
}
