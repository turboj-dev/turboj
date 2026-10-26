package dev.turboj.help;

import dev.turboj.commandj.core.Command;
import dev.turboj.commandj.core.CommandRegistry;
import dev.turboj.commandj.keymap.KeyBinding;
import dev.turboj.commandj.keymap.KeyStrokeSpec;
import dev.turboj.commandj.keymap.ShortcutReference;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A two-page landscape A4 keyboard cheat sheet built from the live registry and keymap, so it always shows the user's
 * own bindings. The daily loop comes first, then every other bound command by category; the type is sized so the
 * two pages fill completely. Styled like turboj.dev: IBM Plex Sans, JetBrains Mono and the Turbo blue.
 */
public final class CheatSheet {
    record Section(String title, List<String> commands) {}
    private record Row(String title, String keys) {}
    /** A titled list of rows. */
    private record Block(String title, List<Row> rows, List<String> paragraphs, String style) {}
    private record Fragment(Block block, List<Row> rows, boolean continued) {}

    /** Ordered by how often a session reaches for them; rows without a shortcut are left out. */
    static final List<Section> SECTIONS = List.of(
            new Section("Do the next thing", List.of("smart.action", "complete", "refactor.context-actions", "completion.smart",
                    "completion.parameterInformation", "documentation", "java.completeStatement", "ai.request", "ai.another",
                    "completion.accept", "completion.acceptWord", "completion.acceptLine")),
            new Section("Build · Run · Test", List.of("make", "compile", "compileProject", "run", "run.configuration", "stop",
                    "tests.current", "tests.class", "tests.all", "tests.failed", "tests.results", "tests.nextFailure",
                    "tests.previousFailure", "navigation.nextError",
                    "navigation.previousError", "error")),
            new Section("Find code", List.of("navigation.searchEverywhere", "navigation.goToType", "navigation.goToFile",
                    "navigation.goToSymbol", "declaration", "navigation.goToImplementation", "navigation.findReferences",
                    "navigation.typeHierarchy", "navigation.callHierarchy", "navigation.goToTest", "goto", "navigation.recentFiles", "navigation.recentLocations")),
            new Section("Move around", List.of("back", "forward", "navigation.previousEditLocation", "navigation.nextEditorTab",
                    "navigation.previousEditorTab", "navigation.nextMethod", "navigation.previousMethod", "navigation.matchingBrace")),
            new Section("Edit", List.of("undo", "redo", "cut", "copy", "paste", "selectAll", "comment", "editor.toggleBlockComment",
                    "moveLineUp", "moveLineDown", "editor.copyLineDown", "editor.copyLineUp", "duplicateLine", "deleteLine",
                    "editor.insertLineBelow", "editor.insertLineAbove", "editor.expandSelection", "editor.shrinkSelection",
                    "editor.selectNextOccurrence", "indent", "unindent")),
            new Section("Search text", List.of("find", "findNext", "findPrevious", "replace")),
            new Section("Files", List.of("new", "file.newJavaClass", "open", "save", "file.saveAll", "saveAs", "file.close", "quit")),
            new Section("Refactor", List.of("refactor.this", "refactor.rename", "refactor.extract-method", "refactor.extract-variable",
                    "refactor.extract-constant", "refactor.extract-field", "refactor.extract-parameter", "refactor.inline",
                    "refactor.move", "refactor.change-signature", "refactor.safe-delete")),
            new Section("Source", List.of("format.document", "format.selection", "organizeImports", "java.generate", "java.generateGetterSetter",
                    "java.generateConstructor", "java.generateToString", "java.generateEquals", "java.generateHashCode",
                    "java.implementMethods", "java.overrideMethods", "java.surroundTryFinally", "java.surroundIf", "java.generateJavadoc")),
            new Section("Panels", List.of("view.toggleExplorer", "view.toggleConsole", "view.focusExplorer", "view.focusConsole", "view.focusDiagnostics",
                    "view.focusTestResults", "view.focusJShell", "focus", "view.focusNextPanel", "view.focusPreviousPanel", "zoom",
                    "view.maximizeEditor", "fullscreen", "escape")),
            new Section("More editing", List.of("editor.smartHome", "editor.smartEnd", "editor.addCaretAbove", "editor.addCaretBelow", "editor.selectAllOccurrences",
                    "editor.toggleColumnSelection", "editor.joinLines", "editor.changeCase", "editor.deletePreviousWord",
                    "editor.deleteNextWord", "editor.smartHome", "editor.smartEnd")),
            new Section("Project · Maven", List.of("project.open", "openFolder", "workspace.select", "file.newJavaProject",
                    "file.newMavenProject", "project.reload", "project.explorer", "maven.build.test", "maven.build.package")),
            new Section("Java tools", List.of("repl", "evaluateSelection", "evaluateStatement", "isolated", "eof", "clear", "output")),
            new Section("TurboJ", List.of("palette", "menu", "keymap", "settings.general", "help", "help.cheatSheet", "fontUp", "fontDown",
                    "quit")));
    /** Shorter names where the menu title would be cut in a column. */
    private static final Map<String, String> SHORT = Map.ofEntries(Map.entry("complete", "Completions"),
            Map.entry("refactor.context-actions", "Quick Fix / Assist"), Map.entry("ai.another", "Another AI completion"),
            Map.entry("navigation.goToTest", "Source ↔ test"), Map.entry("run.configuration", "Arguments and input"),
            Map.entry("indent", "Indent / next placeholder"), Map.entry("unindent", "Unindent / previous placeholder"),
            Map.entry("navigation.nextEditorTab", "Next tab"), Map.entry("navigation.previousEditorTab", "Previous tab"),
            Map.entry("completion.accept", "Accept suggestion"), Map.entry("completion.acceptWord", "Accept AI word"),
            Map.entry("completion.acceptLine", "Accept AI line"));

    /**
     * Keys the editor itself handles, in every profile: listed only where the profile does not give the same
     * key to an always-active command, so the sheet never promises a key that does something else.
     */
    private static final List<Section> NATIVE = List.of(
            new Section("Caret and selection", List.of("Previous word=ALT+LEFT", "Next word=ALT+RIGHT", "Line start=META+LEFT",
                    "Line end=META+RIGHT", "Document start=META+UP", "Document end=META+DOWN", "Page up=PAGE_UP", "Page down=PAGE_DOWN",
                    "Select previous word=SHIFT+ALT+LEFT", "Select next word=SHIFT+ALT+RIGHT", "Select to line start=SHIFT+META+LEFT",
                    "Select to line end=SHIFT+META+RIGHT", "Select to document start=SHIFT+META+UP", "Select to document end=SHIFT+META+DOWN",
                    "Delete to line start=META+BACK_SPACE", "Delete to line end=META+DELETE", "Delete previous word=ALT+BACK_SPACE",
                    "Delete next word=ALT+DELETE")),
            new Section("Inline templates", List.of("Next field=TAB", "Previous field=SHIFT+TAB", "Confirm the template=ENTER",
                    "Take its edits back=ESCAPE")));
    /** Bound, but too obvious to spend a line on. */
    private static final Set<String> SKIPPED = Set.of("completion.next", "completion.previous", "completion.dismiss");

    // Landscape A4 in millimetres: four columns under the title band, above the footer.
    private static final int COLUMNS_PER_PAGE = 4, PAGES = 2;
    private static final double COLUMN_HEIGHT = 166, COLUMN_WIDTH = (297 - 2 * 9 - 3 * 5.5) / 4;
    private static final double MIN_ROW = 3.9, MAX_ROW = 7.5, HEADER = 1.6, GAP = 0.75;

    private CheatSheet() {}

    /** A self-contained HTML page (fonts embedded) laid out as two landscape A4 pages for printing. */
    public static String html(CommandRegistry registry, Map<String, List<KeyBinding>> bindings, String profileId, String profile) {
        String palette = keys(ShortcutReference.bindings(registry, bindings, registry.canonicalId("palette")));
        var blocks = blocks(registry, bindings);
        // The largest type that still fits; below the smallest, the least used rows give way.
        double row = MIN_ROW;
        while (pack(blocks, MIN_ROW, COLUMN_HEIGHT / MIN_ROW) == null && trimmed(blocks)) {}
        for (double size = MAX_ROW; size >= MIN_ROW; size -= 0.05) if (pack(blocks, size, COLUMN_HEIGHT / size) != null) { row = size; break; }
        // Then the shortest columns that still hold everything, so the eight columns end level and none is left half empty.
        double full = COLUMN_HEIGHT / row, capacity = full;
        for (double cap = full; cap >= full / 2; cap -= 0.25) if (pack(blocks, row, cap) != null) capacity = cap; else break;
        var columns = pack(blocks, row, capacity);

        double font = Math.min(11, row * 1.9);
        var out = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>TurboJ · ")
                .append(escape(profile)).append(" cheat sheet</title><style>").append(fonts())
                .append(":root{--row:").append(String.format(Locale.ROOT, "%.2fmm", row)).append(";--font:")
                .append(String.format(Locale.ROOT, "%.2fpt", font)).append("}").append(STYLE).append("</style></head><body>");
        for (int page = 0; page < PAGES; page++) {
            out.append("<section class=\"page\"><header><span class=\"brand\">TurboJ</span><span class=\"profile\">")
                    .append(escape(profile)).append(" keymap</span>").append(legend()).append("<span class=\"page-number\">").append(page + 1).append(" / ")
                    .append(PAGES).append("</span></header><div class=\"columns\">");
            for (int column = page * COLUMNS_PER_PAGE; column < (page + 1) * COLUMNS_PER_PAGE; column++) {
                var fragments = columns.get(column);
                // Each column's rows grow a little so that it ends at the foot of the page like the others (the last
                // section draws no bottom margin, hence one gap less).
                double lists = 0;
                for (Fragment fragment : fragments) lists += HEADER + fragment.rows().size() + GAP;
                double stretch = lists == 0 ? 1 : Math.clamp(COLUMN_HEIGHT / row / (lists - GAP), 1, 1.8);
                out.append("<div class=\"column\" style=\"--row:").append(String.format(Locale.ROOT, "%.2fmm", row * stretch)).append("\">");
                for (Fragment fragment : fragments) out.append(render(fragment));
                out.append("</div>");
            }
            out.append("</div><footer><span>Everything else: Find Action ").append(palette.isEmpty() ? "(Help menu)" : palette)
                    .append("</span><span>").append(escape(profile)).append(" · generated by TurboJ on ").append(LocalDate.now())
                    .append("</span></footer></section>");
        }
        return out.append("<script>if(location.hash==='#print')addEventListener('load',()=>print())</script></body></html>").toString();
    }

    /** The curated sections, then every other bound command grouped by its category. */
    private static List<Block> blocks(CommandRegistry registry, Map<String, List<KeyBinding>> bindings) {
        var blocks = new ArrayList<Block>();
        var shown = new HashSet<String>();
        for (Section section : SECTIONS) {
            var rows = new ArrayList<Row>();
            for (String id : section.commands()) row(registry, bindings, id, shown).ifPresent(rows::add);
            if (!rows.isEmpty()) blocks.add(new Block(section.title(), rows, List.of(), ""));
        }
        var taken = new HashSet<String>();
        for (var entry : bindings.values())
            for (KeyBinding binding : entry)
                if (binding.context().required().isEmpty() || binding.context().required().equals(Set.of("editor")))
                    taken.add(binding.sequence().external());
        for (Section section : NATIVE) {
            var rows = new ArrayList<Row>();
            for (String entry : section.commands()) {
                String title = entry.substring(0, entry.indexOf('=')), key = entry.substring(entry.indexOf('=') + 1);
                var binding = KeyBinding.of(key);
                boolean template = section.title().equals("Inline templates");
                if (template || !taken.contains(binding.sequence().external())) rows.add(new Row(title, keys(List.of(binding))));
            }
            if (!rows.isEmpty()) blocks.add(new Block(section.title(), rows, List.of(), ""));
        }
        var rest = new TreeMap<String, List<Row>>();
        for (Command command : registry.commands().stream().sorted(Comparator.comparing(Command::title)).toList()) {
            if (SKIPPED.contains(command.id()) || command.id().startsWith("keymap.profile.")) continue;
            row(registry, bindings, command.id(), shown).ifPresent(row -> rest.computeIfAbsent(command.category(), c -> new ArrayList<>()).add(row));
        }
        rest.forEach((category, rows) -> blocks.add(new Block("More " + category, rows, List.of(), "")));
        return blocks;
    }

    private static Optional<Row> row(CommandRegistry registry, Map<String, List<KeyBinding>> bindings, String id, Set<String> shown) {
        Optional<Command> command = registry.find(registry.canonicalId(id));
        if (command.isEmpty() || !shown.add(command.get().id())) return Optional.empty();
        var values = ShortcutReference.bindings(registry, bindings, command.get().id());
        if (values.isEmpty()) return Optional.empty();
        String title = SHORT.getOrDefault(command.get().id(), command.get().title().replace("…", ""));
        return Optional.of(new Row(title.startsWith("Keyboard Profile: ") ? title.substring(18) : title, keys(values)));
    }

    /**
     * Lists fill every column from the top, splitting between columns, each column holding at most
     * {@code capacity} rows; null when the blocks need more than two pages.
     */
    private static List<List<Fragment>> pack(List<Block> blocks, double row, double capacity) {
        int count = COLUMNS_PER_PAGE * PAGES;
        var columns = new ArrayList<List<Fragment>>();
        for (int i = 0; i < count; i++) columns.add(new ArrayList<>());
        int column = 0;
        double used = 0;
        for (Block block : blocks) {
            int next = 0;
            while (next < block.rows().size()) {
                if (column >= count) return null;
                int fits = (int) Math.floor(capacity - used - HEADER);
                int left = block.rows().size() - next;
                // A list never starts with a lone row at the foot of a column.
                if (fits < Math.min(2, left)) { column++; used = 0; continue; }
                int take = Math.min(fits, left);
                if (left - take == 1 && take > 2) take--; // no single orphan row in the next column
                columns.get(column).add(new Fragment(block, block.rows().subList(next, next + take), next > 0));
                used += HEADER + take + GAP;
                next += take;
            }
        }
        return columns;
    }

    /** Drops the last row of the least used list; false when nothing is left to drop. */
    private static boolean trimmed(List<Block> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (block.rows().isEmpty()) continue;
            if (block.rows().size() == 1) blocks.remove(i);
            else blocks.set(i, new Block(block.title(), block.rows().subList(0, block.rows().size() - 1), List.of(), block.style()));
            return true;
        }
        return false;
    }

    private static String render(Fragment fragment) {
        Block block = fragment.block();
        var out = new StringBuilder("<div class=\"section").append(block.style().isEmpty() ? "" : " " + block.style()).append("\"><h2>")
                .append(escape(block.title())).append(fragment.continued() ? " <small>cont.</small>" : "").append("</h2>");
        for (Row row : fragment.rows())
            out.append("<div class=\"row\"><span class=\"what\">").append(escape(row.title()))
                    .append("</span><span class=\"keys\">").append(row.keys()).append("</span></div>");
        return out.append("</div>").toString();
    }

    /** Up to two bindings, each written the macOS way (⌃⌥⇧⌘ then the key). */
    static String keys(List<KeyBinding> bindings) {
        var out = new StringBuilder();
        for (KeyBinding binding : bindings.stream().limit(2).toList()) {
            if (!out.isEmpty()) out.append("<i>or</i>");
            for (KeyStrokeSpec stroke : binding.sequence().strokes()) out.append(k(stroke.symbols()));
        }
        return out.toString();
    }

    private static String k(String keys) { return "<kbd>" + glyphs(keys) + "</kbd>"; }

    /** The modifiers named in words: ⇧, ⌥ and ⌃ look alike to anyone new to the Mac. */
    private static String legend() {
        return "<span class=\"legend\">" + k("⌘") + "Command" + k("⌥") + "Option" + k("⇧") + "Shift"
            + k("⌃") + "Control" + k("↩") + "Return" + k("⌫") + "Delete" + k("fn⌫") + "Forward delete</span>";
    }

    // 24×24 stroked outlines, so every key symbol has one size and one baseline whatever font prints the letters.
    private static final Map<Character, String> ICONS = Map.ofEntries(
            Map.entry('⌘', "M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"),
            Map.entry('⌥', "M3 6h6l6 12h6M14 6h7"),
            Map.entry('⇧', "M12 4 4 12h4.5v8h7v-8H20z"),
            Map.entry('⌃', "M5 15l7-7 7 7"),
            Map.entry('↩', "M20 5v6a4 4 0 0 1-4 4H5M9 11l-4 4 4 4"),
            Map.entry('⇥', "M3 12h15M13 7l5 5-5 5M21 5v14"),
            Map.entry('⌫', "M21 6H9l-6 6 6 6h12zM12 9.5l5 5M17 9.5l-5 5"),
            Map.entry('⌦', "M3 6h12l6 6-6 6H3zM7 9.5l5 5M12 9.5l-5 5"),
            Map.entry('←', "M20 12H4M10 6l-6 6 6 6"),
            Map.entry('→', "M4 12h16M14 6l6 6-6 6"),
            Map.entry('↑', "M12 20V4M6 10l6-6 6 6"),
            Map.entry('↓', "M12 4v16M6 14l6 6 6-6"),
            Map.entry('↖', "M18 18 6 6M6 15V6h9"),
            Map.entry('↘', "M6 6l12 12M18 9v9H9"),
            Map.entry('⇞', "M12 20V8M6 13l6-6 6 6M5 4h14"),
            Map.entry('⇟', "M12 4v12M6 11l6 6 6-6M5 20h14"));

    static String glyphs(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            String path = ICONS.get(c);
            if (path == null) out.append(escape(String.valueOf(c)));
            else out.append("<svg class=\"k\" viewBox=\"0 0 24 24\" role=\"img\" aria-label=\"").append(c).append("\"><path d=\"")
                    .append(path).append("\"/></svg>");
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String fonts() {
        return face("IBM Plex Sans", "100 700", "ibm-plex-sans-latin-variable.woff2")
                + face("JetBrains Mono", "100 800", "jetbrains-mono-latin-variable.woff2");
    }

    private static String face(String family, String weights, String resource) {
        try (InputStream font = CheatSheet.class.getResourceAsStream(resource)) {
            if (font == null) return "";
            return "@font-face{font-family:'" + family + "';font-weight:" + weights + ";src:url(data:font/woff2;base64,"
                    + Base64.getEncoder().encodeToString(font.readAllBytes()) + ") format('woff2')}";
        } catch (IOException unavailable) { return ""; }
    }

    // turboj.dev tokens: paper, ink, Turbo blue heading/editor, the grey menu bar and cyan messages.
    private static final String STYLE = """
            :root{--paper:#F4F4F0;--ink:#111827;--muted:#4B5563;--rule:#687585;--blue:#0000AA;--menu:#AAAAAA;--cyan:#05AAAA;--yellow:#FFFF55;
            --sans:'IBM Plex Sans',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;--mono:'JetBrains Mono','SFMono-Regular',Menlo,monospace}
            @page{size:A4 landscape;margin:0}
            *{box-sizing:border-box}html,body{margin:0;background:#fff;color:var(--ink);font-family:var(--sans);-webkit-print-color-adjust:exact;print-color-adjust:exact}
            .page{width:297mm;height:210mm;padding:8mm 9mm 7mm;background:var(--paper);display:flex;flex-direction:column;overflow:hidden;page-break-after:always;break-after:page}
            .page:last-child{page-break-after:auto;break-after:auto}
            header{display:flex;align-items:center;gap:4mm;background:var(--blue);color:#fff;padding:2.2mm 4mm;font-family:var(--mono);height:11mm}
            .brand{font-weight:700;font-size:15pt;color:var(--yellow);letter-spacing:.02em}.profile{font-size:10.5pt;font-weight:500;flex:1}
            .page-number{font-size:8.5pt;opacity:.85}
            .legend{display:flex;align-items:center;gap:1.2mm;font:500 8pt var(--sans);color:#fff;margin-right:3mm}
            .legend kbd{font-size:8pt;margin-left:2.2mm}
            .columns{display:grid;grid-template-columns:repeat(4,1fr);column-gap:5.5mm;height:166mm;margin-top:4mm}
            .column{overflow:hidden;display:flex;flex-direction:column}
            .column .section:last-child{margin-bottom:0}
            .section{margin:0 0 calc(var(--row)*.75)}
            h2{font:600 calc(var(--font)*1.02)/1 var(--mono);color:var(--ink);background:var(--menu);margin:0;height:calc(var(--row)*1.6);
            display:flex;align-items:center;padding:0 2mm;text-transform:uppercase;letter-spacing:.04em}
            h2 small{font-size:.75em;font-weight:500;text-transform:none;margin-left:1.5mm;color:#333}
            .row{display:flex;justify-content:space-between;align-items:center;gap:2mm;height:var(--row);padding-right:.6mm;border-bottom:.2mm solid #DADDE2;font-size:var(--font)}
            .what{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.keys{white-space:nowrap;display:flex;align-items:center}
            kbd{font:600 calc(var(--font)*.94)/1 var(--mono);color:var(--blue);background:#fff;border:.25mm solid var(--rule);border-bottom-width:.5mm;
            border-radius:1mm;padding:.35mm .9mm;margin-left:.6mm;display:inline-flex;align-items:center;gap:.15mm}
            svg.k{width:1.02em;height:1.02em;fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round;flex:none}
            i{font:italic calc(var(--font)*.8) var(--sans);color:var(--muted);margin:0 .3mm 0 .9mm}
            footer{margin-top:auto;display:flex;justify-content:space-between;gap:4mm;border-top:.3mm solid var(--rule);padding-top:1.5mm;font:500 7pt var(--mono);color:var(--muted)}
            footer kbd{font-size:6.8pt}
            @media screen{body{background:#C7CCD3}.page{margin:8mm auto;box-shadow:0 1mm 4mm rgba(0,0,0,.25)}}
            """;
}
