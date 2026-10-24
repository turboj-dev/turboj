package dev.turboj.commandj.keymap;

import java.util.*;

/** Verified common macOS conventions; product-specific commands are explicit TurboJ additions. */
public final class BundledKeymaps {
    private BundledKeymaps() {}

    private static Map<String, List<KeyBinding>> common() {
        var b = new LinkedHashMap<String, List<KeyBinding>> ();
        bind(b, "new", "META+N");
        bind(b, "open", "META+O");
        bind(b, "save", "META+S");
        bind(b, "saveAs", "META+SHIFT+S");
        bind(b, "quit", "META+Q");
        bind(b, "find", "META+F");
        bind(b, "findNext", "META+G");
        bind(b, "findPrevious", "META+SHIFT+G");
        bind(b, "undo", "META+Z");
        bind(b, "redo", "META+SHIFT+Z");
        bind(b, "cut", "META+X");
        bind(b, "copy", "META+C");
        bind(b, "paste", "META+V");
        bind(b, "selectAll", "META+A");
        bind(b, "comment", "META+SLASH");
        bind(b, "fullscreen", "META+CTRL+F");
        bind(b, "fontUp", "META+EQUALS");
        bind(b, "fontDown", "META+MINUS");
        bind(b, "menu", "F10");
        bind(b, "ai.request", "ALT+SPACE");
        bind(b, "ai.another", "ALT+SHIFT+SPACE");
        bind(b, "escape", "ESCAPE");
        b.put("completion.accept", List.of(new KeyBinding(KeySequence.parse("TAB"), BindingContext
                .requiring("completion")), new KeyBinding(KeySequence.parse("ENTER"), BindingContext
                .requiring("completion-popup"))));
        b.put("completion.acceptWord", List.of(new KeyBinding(KeySequence.parse("CTRL+ALT+ENTER"),
                BindingContext.requiring("completion"))));
        b.put("completion.next", List.of(new KeyBinding(KeySequence.parse("DOWN"), BindingContext
                .requiring("completion-popup"))));
        b.put("completion.previous", List.of(new KeyBinding(KeySequence.parse("UP"), BindingContext
                .requiring("completion-popup"))));
        b.put("indent", List.of(new KeyBinding(KeySequence.parse("TAB"), new BindingContext(Set
                .of("editor"), Set.of("completion")))));
        b.put("unindent", List.of(KeyBinding.editor("SHIFT+TAB")));
        return b;
    }

    private static void bind(Map<String, List<KeyBinding>> b, String id, String... keys) {
        b.put(id, Arrays.stream(keys).map(KeyBinding::of).toList());
    }

    public static List<KeymapProfile> profiles() {
        var classic = common();
        bind(classic, "documentation", "F1");
        bind(classic, "declaration", "F12", "META+B");
        bind(classic, "complete", "CTRL+SPACE");
        bind(classic, "back", "ALT+LEFT");
        bind(classic, "forward", "ALT+RIGHT");
        bind(classic, "save", "F2", "META+S");
        bind(classic, "open", "F3", "META+O");
        bind(classic, "zoom", "F5");
        bind(classic, "focus", "F6");
        bind(classic, "compile", "F9", "ALT+F9");
        bind(classic, "run", "CTRL+F9", "META+R", "META+ENTER");
        bind(classic, "quit", "ALT+X", "META+Q");
        bind(classic, "format.document", "META+ALT+L");
        bind(classic, "format.selection", "META+ALT+SHIFT+L");
        bind(classic, "palette", "META+SHIFT+P");
        bind(classic, "goto", "META+L");
        bind(classic, "stop", "META+PERIOD");
        bind(classic, "keymap", "META+K, META+S");
        var idea = common();
        bind(idea, "palette", "META+SHIFT+A");
        bind(idea, "documentation", "F1", "CTRL+J");
        bind(idea, "complete", "CTRL+SPACE");
        bind(idea, "signature", "META+P");
        bind(idea, "declaration", "META+B");
        bind(idea, "implementation", "META+ALT+B");
        bind(idea, "references", "ALT+F7");
        bind(idea, "back", "META+ALT+LEFT");
        bind(idea, "forward", "META+ALT+RIGHT");
        bind(idea, "goto", "META+L");
        bind(idea, "goFile", "META+SHIFT+O");
        bind(idea, "goSymbol", "META+ALT+O");
        bind(idea, "recentFiles", "META+E");
        bind(idea, "replace", "META+R");
        bind(idea, "format.document", "META+ALT+L");
        bind(idea, "format.selection", "META+ALT+SHIFT+L");
        bind(idea, "compile", "META+F9");
        bind(idea, "run", "CTRL+R");
        bind(idea, "stop", "META+F2");
        bind(idea, "duplicateLine", "META+D");
        bind(idea, "deleteLine", "META+BACK_SPACE");
        bind(idea, "refactor.rename", "SHIFT+F6");
        bind(idea, "refactor.extract-method", "META+ALT+M");
        bind(idea, "refactor.extract-variable", "META+ALT+V");
        bind(idea, "refactor.extract-constant", "META+ALT+C");
        bind(idea, "refactor.extract-field", "META+ALT+F");
        bind(idea, "refactor.extract-parameter", "META+ALT+P");
        bind(idea, "refactor.inline", "META+ALT+N");
        bind(idea, "refactor.change-signature", "META+F6");
        bind(idea, "refactor.move", "F6");
        bind(idea, "refactor.safe-delete", "META+DELETE");
        bind(idea, "refactor.this", "CTRL+T");
        bind(idea, "refactor.context-actions", "ALT+ENTER");
        bind(idea, "organizeImports", "CTRL+ALT+O");
        bind(idea, "keymap", "META+COMMA");
        var eclipse = common();
        bind(eclipse, "palette", "META+3");
        bind(eclipse, "declaration", "F3");
        bind(eclipse, "references", "META+SHIFT+G");
        eclipse.remove("findPrevious");
        bind(eclipse, "documentation", "F2");
        bind(eclipse, "complete", "CTRL+SPACE");
        bind(eclipse, "back", "ALT+LEFT");
        bind(eclipse, "forward", "ALT+RIGHT");
        bind(eclipse, "goto", "META+L");
        bind(eclipse, "goType", "META+SHIFT+T");
        bind(eclipse, "goFile", "META+SHIFT+R");
        bind(eclipse, "goSymbol", "META+O");
        eclipse.remove("open");
        bind(eclipse, "organizeImports", "META+SHIFT+O");
        bind(eclipse, "format.document", "META+SHIFT+F");
        bind(eclipse, "refactor.rename", "ALT+SHIFT+R");
        bind(eclipse, "refactor.extract-method", "ALT+SHIFT+M");
        bind(eclipse, "refactor.extract-variable", "ALT+SHIFT+L");
        bind(eclipse, "refactor.inline", "ALT+SHIFT+I");
        bind(eclipse, "refactor.change-signature", "ALT+SHIFT+C");
        bind(eclipse, "refactor.move", "ALT+SHIFT+V");
        bind(eclipse, "refactor.this", "ALT+SHIFT+T");
        bind(eclipse, "refactor.context-actions", "META+1");
        bind(eclipse, "run", "META+F11");
        bind(eclipse, "keymap", "META+SHIFT+L");
        var vscode = common();
        bind(vscode, "palette", "META+SHIFT+P", "F1");
        bind(vscode, "keymap", "META+K, META+S");
        bind(vscode, "format.document", "ALT+SHIFT+F");
        bind(vscode, "format.selection", "META+K, META+F");
        bind(vscode, "declaration", "F12");
        bind(vscode, "references", "SHIFT+F12");
        bind(vscode, "documentation", "META+K, META+I");
        bind(vscode, "signature", "META+SHIFT+SPACE");
        bind(vscode, "complete", "CTRL+SPACE", "META+I");
        bind(vscode, "goto", "CTRL+G");
        bind(vscode, "goFile", "META+P");
        bind(vscode, "goSymbol", "META+SHIFT+O");
        bind(vscode, "goType", "META+T");
        bind(vscode, "replace", "META+ALT+F");
        bind(vscode, "back", "CTRL+MINUS");
        bind(vscode, "forward", "CTRL+SHIFT+MINUS");
        bind(vscode, "refactor.rename", "F2");
        bind(vscode, "refactor.context-actions", "META+PERIOD");
        bind(vscode, "deleteLine", "META+SHIFT+K");
        bind(vscode, "moveLineUp", "ALT+UP");
        bind(vscode, "moveLineDown", "ALT+DOWN");
        bind(vscode, "duplicateLine", "ALT+SHIFT+DOWN");
        bind(vscode, "run", "CTRL+F5");
        return List.of(new KeymapProfile("intellij-macos", "IntelliJ IDEA macOS", null, idea, true),
                new KeymapProfile("eclipse-macos", "Eclipse macOS", null, eclipse, true),
                new KeymapProfile("vscode-macos", "VS Code macOS", null, vscode, true),
                new KeymapProfile("turboj-classic", "TurboJ Classic", null, classic, true));
    }
}
