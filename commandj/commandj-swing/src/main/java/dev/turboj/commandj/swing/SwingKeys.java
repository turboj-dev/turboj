package dev.turboj.commandj.swing;

import dev.turboj.commandj.keymap.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.*;
import javax.swing.KeyStroke;

/** AWT supplies logical key codes for the active keyboard layout; no US scan-code assumptions. */
public final class SwingKeys {
    private static final Map<Integer, String> NAMES = new HashMap<> ();
    private static final Map<String, Integer> CODES = new HashMap<> ();
    static {
        for (Field field : KeyEvent.class.getFields())
            if (field.getName().startsWith("VK_") && field.getType() == int.class)
                try {
                    int value = field.getInt(null);
                    String key = field.getName().substring(3);
                    NAMES.putIfAbsent(value, key);
                    CODES.put(key, value);
                } catch (IllegalAccessException impossible) {
                    throw new ExceptionInInitializerError(impossible);
                }
    }

    private SwingKeys() {}

    /** The portable key name (ENTER, OPEN_BRACKET…) of an AWT key code. */
    public static Optional<String> keyName(int code) {
        return Optional.ofNullable(NAMES.get(code));
    }

    public static Optional<KeyStrokeSpec> fromEvent(KeyEvent event) {
        if (event.isAltGraphDown())
            return Optional.empty();
        String key = NAMES.get(event.getKeyCode());

        if (key == null || key.equals("UNDEFINED") || key.startsWith("DEAD_") || Set.of("SHIFT",
                "CONTROL", "ALT", "META", "ALT_GRAPH", "CAPS_LOCK").contains(key))
            return Optional.empty();
        var modifiers = EnumSet.noneOf(KeyStrokeSpec.Modifier.class);

        if (event.isControlDown())
            modifiers.add(KeyStrokeSpec.Modifier.CTRL);
        if (event.isAltDown())
            modifiers.add(KeyStrokeSpec.Modifier.ALT);
        if (event.isShiftDown())
            modifiers.add(KeyStrokeSpec.Modifier.SHIFT);
        if (event.isMetaDown())
            modifiers.add(KeyStrokeSpec.Modifier.META);
        return Optional.of(new KeyStrokeSpec(key, modifiers));
    }

    public static KeyStroke toSwing(KeyStrokeSpec key) {
        Integer code = CODES.get(key.key());

        if (code == null)
            throw new IllegalArgumentException("Unknown AWT logical key: " + key.key());
        int mask = 0;

        for (var m : key.modifiers())
            mask |= switch (m) {
                case CTRL -> InputEvent.CTRL_DOWN_MASK;
                case ALT -> InputEvent.ALT_DOWN_MASK;
                case SHIFT -> InputEvent.SHIFT_DOWN_MASK;
                case META -> InputEvent.META_DOWN_MASK;
            };
        return KeyStroke.getKeyStroke(code, mask);
    }

    public static KeySequence parseLegacy(String value) {
        KeyStroke stroke = KeyStroke.getKeyStroke(value);

        if (stroke == null)
            throw new IllegalArgumentException("Invalid legacy shortcut");
        KeyEvent event = new KeyEvent(new javax.swing.JPanel(), KeyEvent.KEY_PRESSED, 0, stroke
                .getModifiers(), stroke.getKeyCode(), KeyEvent.CHAR_UNDEFINED);
        return new KeySequence(List.of(fromEvent(event)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported shortcut"))));
    }
}
