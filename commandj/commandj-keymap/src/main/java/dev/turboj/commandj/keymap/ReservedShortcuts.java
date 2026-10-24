package dev.turboj.commandj.keymap;

import java.util.*;

public final class ReservedShortcuts {
    private ReservedShortcuts() {}

    public static Optional<String> warning(KeySequence sequence) {
        for (var key : sequence.strokes()) {
            String s = key.external();

            if (Set.of("META+SPACE", "CTRL+META+SPACE", "CTRL+ALT+META+POWER", "META+TAB",
                    "SHIFT+META+TAB").contains(s))
                return Optional
                        .of("macOS may reserve this shortcut; CommandJ cannot override system shortcuts.");
            if (s.equals("CTRL+SPACE") || s.equals("CTRL+ALT+SPACE"))
                return Optional
                        .of("This shortcut may be assigned to an input source in macOS Keyboard settings.");
        }
        return Optional.empty();
    }
}
