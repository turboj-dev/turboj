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
                BindingContext.requiring("completion")), new KeyBinding(KeySequence.parse("ALT+RIGHT"),
                BindingContext.requiring("ai-completion"))));
        // Only while an AI suggestion shows; otherwise ⌥→ and ⌥⇧→ stay word motions.
        b.put("completion.acceptLine", List.of(new KeyBinding(KeySequence.parse("ALT+SHIFT+RIGHT"),
                BindingContext.requiring("ai-completion"))));
        b.put("completion.next", List.of(new KeyBinding(KeySequence.parse("DOWN"), BindingContext
                .requiring("completion-popup"))));
        b.put("completion.previous", List.of(new KeyBinding(KeySequence.parse("UP"), BindingContext
                .requiring("completion-popup"))));
        b.put("indent", List.of(new KeyBinding(KeySequence.parse("TAB"), new BindingContext(Set
                .of("editor"), Set.of("completion")))));
        b.put("unindent", List.of(KeyBinding.editor("SHIFT+TAB")));
        editor(b, "editor.deletePreviousWord", "ALT+BACK_SPACE");
        editor(b, "editor.deleteNextWord", "ALT+DELETE");
        editor(b, "editor.smartHome", "HOME");
        editor(b, "editor.smartEnd", "END");
        bind(b, "navigation.nextEditor", "CTRL+TAB");
        bind(b, "navigation.previousEditor", "CTRL+SHIFT+TAB");
        bind(b, "navigation.closeEditor", "META+W");
        bind(b, "view.focusNextPanel", "CTRL+F6");
        bind(b, "view.focusPreviousPanel", "CTRL+SHIFT+F6");
        return b;
    }

    private static void bind(Map<String, List<KeyBinding>> b, String id, String... keys) {
        b.put(id, Arrays.stream(keys).map(KeyBinding::of).toList());
    }

    private static void editor(Map<String, List<KeyBinding>> b, String id, String... keys) {
        b.put(id, Arrays.stream(keys).map(KeyBinding::editor).toList());
    }

    /** The default profile: TurboJ's own map. */
    public static final String DEFAULT = "turboj-macos";
    /** Bundled profiles that were replaced: a saved selection moves to the successor, the stored copy is dropped. */
    public static final Map<String, String> RETIRED = Map.of("turboj-classic", DEFAULT);

    public static String current(String id) { return RETIRED.getOrDefault(id, id); }

    /**
     * Turbo C's grammar on macOS (docs/keymap-turboj-macos.md): Cmd+letter is the verb, Shift widens or reverses it,
     * Cmd+Option is the code-transform family. Option alone never takes a letter (it types characters) and Ctrl
     * takes only Space and Tab (Ctrl+letters are the system's text keys). Turbo's function keys stay as second
     * bindings: most Macs need fn for them, and Ctrl+F1…F8 belong to macOS keyboard navigation.
     */
    private static Map<String, List<KeyBinding>> turbo() {
        var t = common();
        // The edit–compile–run loop: Turbo F9 make, Alt+F9 compile, Ctrl+F9 run, Alt+F7/F8 errors, F2 save, F1 help.
        bind(t, "smart.action", "META+ENTER"); // Complete, import, or Run when there is nothing to complete.
        bind(t, "make", "META+B", "F9");
        bind(t, "compile", "META+ALT+B", "ALT+F9");
        bind(t, "compileProject", "META+SHIFT+B");
        bind(t, "run", "META+R", "CTRL+F9");
        bind(t, "run.configuration", "META+SHIFT+R");
        bind(t, "tests.current", "META+U");
        bind(t, "tests.all", "META+SHIFT+U");
        bind(t, "tests.failed", "META+ALT+U");
        bind(t, "stop", "META+PERIOD");
        bind(t, "navigation.nextError", "META+QUOTE", "ALT+F8");
        bind(t, "navigation.previousError", "META+SHIFT+QUOTE", "ALT+F7");
        bind(t, "save", "META+S", "F2");
        bind(t, "file.saveAll", "META+ALT+S");
        bind(t, "documentation", "META+J", "F1");
        bind(t, "help", "SHIFT+F1");
        bind(t, "zoom", "F5");
        bind(t, "focus", "F6");
        // The Enter family: Cmd does the next thing, Option lists the choices.
        // ⌃Space is every IDE's completion, but macOS gives it to the input-source switch; ⌥Space always reaches TurboJ.
        bind(t, "complete", "ALT+SPACE", "CTRL+SPACE");
        bind(t, "ai.request", "ALT+SHIFT+SPACE");
        bind(t, "ai.another");
        bind(t, "refactor.context-actions", "ALT+ENTER", "META+1");
        editor(t, "java.completeStatement", "META+SHIFT+ENTER");
        editor(t, "editor.insertLineBelow", "SHIFT+ENTER");
        // Finding code.
        bind(t, "navigation.searchEverywhere", "META+P", "F3");
        bind(t, "navigation.goToType", "META+T");
        bind(t, "navigation.goToSymbol", "META+SHIFT+O");
        bind(t, "navigation.goToFile", "META+SHIFT+P");
        bind(t, "navigation.typeHierarchy", "META+SHIFT+H");
        bind(t, "navigation.callHierarchy", "META+ALT+SHIFT+H");
        bind(t, "navigation.previousEditLocation", "META+SHIFT+BACK_SPACE");
        bind(t, "navigation.matchingBrace", "META+SHIFT+BACK_SLASH");
        bind(t, "navigation.goToTest", "META+SHIFT+T");
        bind(t, "declaration", "META+D", "F12");
        bind(t, "navigation.findReferences", "META+SHIFT+D");
        bind(t, "navigation.goToImplementation", "META+I");
        bind(t, "back", "META+OPEN_BRACKET", "CTRL+META+LEFT");
        bind(t, "forward", "META+CLOSE_BRACKET", "CTRL+META+RIGHT");
        bind(t, "goto", "META+L");
        bind(t, "navigation.recentFiles", "META+E");
        bind(t, "navigation.recentLocations", "META+SHIFT+E");
        bind(t, "navigation.nextEditorTab", "META+SHIFT+CLOSE_BRACKET");
        bind(t, "navigation.previousEditorTab", "META+SHIFT+OPEN_BRACKET");
        bind(t, "replace", "META+ALT+F");
        // Lines and selections.
        editor(t, "moveLineUp", "ALT+UP");
        editor(t, "moveLineDown", "ALT+DOWN");
        editor(t, "editor.copyLineUp", "META+ALT+UP");
        editor(t, "editor.copyLineDown", "META+ALT+DOWN");
        editor(t, "deleteLine", "META+SHIFT+K");
        editor(t, "editor.toggleBlockComment", "META+ALT+SLASH");
        editor(t, "editor.selectNextOccurrence", "META+ALT+G");
        editor(t, "editor.selectAllOccurrences", "META+ALT+SHIFT+G");
        // MacBooks have no Home or End key: ⌘← and ⌘→ are the Mac's line start and end (fn← and fn→ still work).
        editor(t, "editor.smartHome", "META+LEFT", "HOME");
        editor(t, "editor.smartEnd", "META+RIGHT", "END");
        editor(t, "editor.expandSelection", "CTRL+SHIFT+RIGHT");
        editor(t, "editor.shrinkSelection", "CTRL+SHIFT+LEFT");
        editor(t, "navigation.nextMethod", "CTRL+SHIFT+DOWN");
        editor(t, "navigation.previousMethod", "CTRL+SHIFT+UP");
        // Cmd+Option transforms the code.
        bind(t, "refactor.this", "META+ALT+T");
        bind(t, "refactor.rename", "META+ALT+R");
        bind(t, "refactor.extract-method", "META+ALT+M");
        bind(t, "refactor.extract-variable", "META+ALT+V");
        bind(t, "refactor.extract-constant", "META+ALT+C");
        bind(t, "refactor.extract-parameter", "META+ALT+P");
        bind(t, "refactor.inline", "META+ALT+I");
        bind(t, "refactor.safe-delete", "META+ALT+BACK_SPACE");
        bind(t, "format.document", "META+ALT+L");
        bind(t, "format.selection", "META+ALT+SHIFT+L");
        bind(t, "organizeImports", "META+ALT+O");
        bind(t, "java.generate", "META+ALT+N");
        // Panels and the application.
        bind(t, "view.toggleExplorer", "META+0");
        bind(t, "view.focusConsole", "META+SHIFT+C");
        bind(t, "view.toggleConsole", "META+SHIFT+Y");
        bind(t, "view.focusDiagnostics", "META+SHIFT+M");
        bind(t, "file.newJavaProject", "META+SHIFT+N");
        bind(t, "palette", "META+SHIFT+A");
        bind(t, "settings.general", "META+COMMA");
        bind(t, "keymap", "META+K");
        return t;
    }

    public static List<KeymapProfile> profiles() {
        var idea = common();
        idea.remove("open");
        bind(idea, "goType", "META+O");
        bind(idea, "palette", "META+SHIFT+A");
        bind(idea, "documentation", "F1", "CTRL+J");
        bind(idea, "complete", "CTRL+SPACE");
        editor(idea, "completion.smart", "META+SHIFT+SPACE");
        bind(idea, "signature", "META+P");
        bind(idea, "declaration", "META+B");
        bind(idea, "implementation", "META+ALT+B");
        bind(idea, "references", "ALT+F7");
        bind(idea, "back", "META+OPEN_BRACKET", "META+ALT+LEFT");
        bind(idea, "forward", "META+CLOSE_BRACKET", "META+ALT+RIGHT");
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
        editor(idea, "moveLineUp", "ALT+SHIFT+UP");
        editor(idea, "moveLineDown", "ALT+SHIFT+DOWN");
        editor(idea, "editor.moveStatementUp", "META+SHIFT+UP");
        editor(idea, "editor.moveStatementDown", "META+SHIFT+DOWN");
        editor(idea, "editor.moveElementLeft", "META+ALT+SHIFT+LEFT");
        editor(idea, "editor.moveElementRight", "META+ALT+SHIFT+RIGHT");
        editor(idea, "editor.insertLineBelow", "SHIFT+ENTER");
        editor(idea, "editor.insertLineAbove", "META+ALT+ENTER");
        editor(idea, "editor.expandSelection", "ALT+UP");
        editor(idea, "editor.shrinkSelection", "ALT+DOWN");
        editor(idea, "editor.joinLines", "CTRL+SHIFT+J");
        editor(idea, "editor.changeCase", "META+SHIFT+U");
        editor(idea, "editor.toggleBlockComment", "META+ALT+SLASH");
        editor(idea, "editor.autoIndentLines", "CTRL+ALT+I");
        editor(idea, "editor.selectNextOccurrence", "CTRL+G");
        editor(idea, "editor.selectAllOccurrences", "META+CTRL+G");
        editor(idea, "editor.toggleColumnSelection", "META+SHIFT+8");
        editor(idea, "navigation.matchingBrace", "CTRL+M");
        editor(idea, "navigation.nextMethod", "CTRL+SHIFT+DOWN");
        editor(idea, "navigation.previousMethod", "CTRL+SHIFT+UP");
        bind(idea, "navigation.previousEdit", "META+SHIFT+BACK_SPACE");
        bind(idea, "view.focusExplorer", "META+1");
        bind(idea, "view.focusConsole", "META+4");
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
        bind(eclipse, "saveAs");
        bind(eclipse, "file.saveAll", "META+SHIFT+S");
        bind(eclipse, "palette", "META+3");
        bind(eclipse, "declaration", "F3");
        bind(eclipse, "references", "META+SHIFT+G");
        eclipse.remove("findPrevious");
        bind(eclipse, "documentation", "F2");
        bind(eclipse, "complete", "CTRL+SPACE");
        bind(eclipse, "back", "META+ALT+LEFT");
        bind(eclipse, "forward", "META+ALT+RIGHT");
        bind(eclipse, "goto", "META+L");
        bind(eclipse, "smart.action", "META+ENTER");
        bind(eclipse, "goType", "META+SHIFT+T");
        bind(eclipse, "goFile", "META+SHIFT+R");
        bind(eclipse, "goSymbol", "META+O");
        eclipse.remove("open");
        bind(eclipse, "organizeImports", "META+SHIFT+O");
        bind(eclipse, "format.document", "META+SHIFT+F");
        bind(eclipse, "refactor.rename", "META+ALT+R");
        bind(eclipse, "refactor.extract-method", "META+ALT+M");
        bind(eclipse, "refactor.extract-variable", "META+ALT+L");
        bind(eclipse, "refactor.inline", "META+ALT+I");
        // Current JDT metadata explicitly removes the generic binding on macOS.
        bind(eclipse, "refactor.change-signature");
        bind(eclipse, "refactor.move", "META+ALT+V");
        bind(eclipse, "refactor.this", "META+ALT+T");
        bind(eclipse, "refactor.context-actions", "META+1");
        bind(eclipse, "run", "META+SHIFT+F11");
        bind(eclipse, "keymap", "META+SHIFT+L");
        editor(eclipse, "deleteLine", "META+D");
        editor(eclipse, "moveLineUp", "ALT+UP");
        editor(eclipse, "moveLineDown", "ALT+DOWN");
        editor(eclipse, "editor.copyLineUp", "META+ALT+UP");
        editor(eclipse, "editor.copyLineDown", "META+ALT+DOWN");
        editor(eclipse, "editor.insertLineBelow", "SHIFT+ENTER");
        editor(eclipse, "editor.insertLineAbove", "META+SHIFT+ENTER");
        // Cmd+Option+J is Javadoc in JDT; the text editor removes Join Lines on macOS.
        editor(eclipse, "editor.expandSelection", "CTRL+SHIFT+PAGE_UP");
        editor(eclipse, "editor.shrinkSelection", "CTRL+SHIFT+PAGE_DOWN");
        editor(eclipse, "editor.toggleColumnSelection", "META+ALT+A");
        editor(eclipse, "navigation.nextMethod", "CTRL+ALT+DOWN");
        editor(eclipse, "navigation.previousMethod", "CTRL+ALT+UP");
        bind(eclipse, "navigation.typeHierarchy", "F4");
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
        editor(vscode, "editor.copyLineUp", "ALT+SHIFT+UP");
        editor(vscode, "editor.copyLineDown", "ALT+SHIFT+DOWN");
        editor(vscode, "editor.insertLineBelow", "META+ENTER");
        editor(vscode, "editor.insertLineAbove", "META+SHIFT+ENTER");
        editor(vscode, "editor.toggleBlockComment", "ALT+SHIFT+A");
        editor(vscode, "editor.selectNextOccurrence", "META+D");
        editor(vscode, "editor.selectAllOccurrences", "META+SHIFT+L", "META+F2");
        editor(vscode, "editor.selectLine", "META+L");
        editor(vscode, "editor.addCaretAbove", "META+ALT+UP");
        editor(vscode, "editor.addCaretBelow", "META+ALT+DOWN");
        editor(vscode, "editor.expandSelection", "META+CTRL+SHIFT+RIGHT");
        editor(vscode, "editor.shrinkSelection", "META+CTRL+SHIFT+LEFT");
        editor(vscode, "editor.trimTrailingWhitespace", "META+K, META+X");
        editor(vscode, "navigation.matchingBrace", "META+SHIFT+BACK_SLASH");
        bind(vscode, "navigation.nextError", "F8");
        bind(vscode, "navigation.previousError", "SHIFT+F8");
        bind(vscode, "navigation.reopenEditor", "META+SHIFT+T");
        bind(vscode, "view.focusExplorer", "META+SHIFT+E");
        bind(vscode, "view.focusConsole", "META+SHIFT+U");
        bind(vscode, "view.focusDiagnostics", "META+SHIFT+M");
        bind(vscode, "view.toggleExplorer", "META+B");
        bind(vscode, "run", "CTRL+F5");
        return List.of(new KeymapProfile(DEFAULT, "TurboJ macOS", null, turbo(), true),
                new KeymapProfile("eclipse-macos", "Eclipse macOS", null, eclipse, true),
                new KeymapProfile("intellij-macos", "IntelliJ IDEA macOS", null, idea, true),
                new KeymapProfile("vscode-macos", "VS Code macOS", null, vscode, true));
    }
}
