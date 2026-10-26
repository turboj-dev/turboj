package dev.turboj.formatting;

import static org.junit.jupiter.api.Assertions.*;
import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.LineEndingPolicy;
import dev.javafmt.core.TextEdit;
import dev.javafmt.core.TextRange;
import dev.turboj.ui.EditorComponent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class StandardFormattersTest {
    private static final List<StandardFormatters> GOOGLE_JAVA_FORMAT = List.of(StandardFormatters.AOSP, StandardFormatters.CLASSIC_80,
            StandardFormatters.WIDE_120, StandardFormatters.GOOGLE);
    /** Unsorted, partly unused imports; a long string; a Javadoc with its own layout; a text block; modifiers out of order. */
    private static final String SOURCE = """
            import org.junit.jupiter.api.Test;
            import java.util.List;
            import java.util.Map;
            public class CalculatorTest {
                /**   Keeps   its    spacing.
                 *  <pre>   example();   </pre> */
                final static String LONG = "a string literal that is longer than eighty columns, so it must never be split by TurboJ";
                static final String BLOCK = \"""
                      two spaces,  then three.\\tTab escape \\
                    continued
                    \""";
                Calculator calculator=new Calculator();
                @Test public void testAdd(){ var list=List.of(1,2,3);
                String s = switch (list.size()) { case 0 -> "none"; default -> "many"; };
                record Point(int x,int y){}
                Object o=new Point(1,2); if(o instanceof Point(int x,int y)&&x>0){System.out.println(x+y);} }
            }
            """;

    private static String format(StandardFormatters style, String source, TextRange range) {
        var result = style.engine().format(new FormatRequest(source, 7, LineEndingPolicy.PRESERVE), range, "25");
        assertTrue(result.success(), () -> style + ": " + result.diagnostics());
        assertEquals(7, result.sourceVersion());
        assertEquals(result.formattedText(), TextEdit.apply(source, result.edits()), "edits reproduce the formatted text");
        return result.formattedText();
    }

    @Test void presetsUseTheEnginesOwnStylesAndWidths() {
        assertEquals(StandardFormatters.INTELLIJ, StandardFormatters.DEFAULT, "IntelliJ IDEA's default style is TurboJ's default");
        assertEquals(4, StandardFormatters.DEFAULT.indent());
        assertEquals("1.37.0", StandardFormatters.GOOGLE_JAVA_FORMAT_VERSION);
        for (var style : GOOGLE_JAVA_FORMAT) {
            var options = style.googleOptions();
            assertEquals(style.width(), options.maxLineLength(), style.title());
            assertEquals(style == StandardFormatters.GOOGLE ? 1 : 2, options.style().indentationMultiplier(), style.title());
            assertEquals(style != StandardFormatters.GOOGLE, options.style().isAosp(), style.title());
            assertFalse(options.formatJavadoc() || options.reorderModifiers(), "layout only");
        }
        assertEquals(List.of(80, 100, 120), List.of(StandardFormatters.CLASSIC_80.width(), StandardFormatters.AOSP.width(), StandardFormatters.WIDE_120.width()));
        assertFalse(StandardFormatters.JAVAFMT.offered(), "JavaFMT is on hold: kept, not offered");
    }

    @Test void layoutOnlyImportsLiteralsJavadocAndModifiersStayAsWritten() {
        for (var style : GOOGLE_JAVA_FORMAT) {
            String formatted = format(style, SOURCE, null);
            assertTrue(formatted.startsWith("import org.junit.jupiter.api.Test;\nimport java.util.List;\nimport java.util.Map;\n"),
                    style + " leaves imports unsorted and keeps the unused one: " + formatted);
            assertTrue(formatted.contains("\"a string literal that is longer than eighty columns, so it must never be split by TurboJ\""), style + ": " + formatted);
            assertTrue(formatted.contains("      two spaces,  then three.\\tTab escape \\\n"), style + " keeps the text block: " + formatted);
            assertTrue(formatted.contains("/**   Keeps   its    spacing."), style + " leaves Javadoc as written");
            assertTrue(formatted.contains("final static String LONG"), style + " keeps the modifier order");
            assertTrue(formatted.contains("if (o instanceof Point(int x, int y) && x > 0) {"), style + ": Java 25 record patterns");
            assertEquals(formatted, format(style, formatted, null), style + " is idempotent");
        }
        assertTrue(format(StandardFormatters.GOOGLE, SOURCE, null).contains("\n  Calculator calculator = new Calculator();\n"), "Google: 2 spaces");
        assertTrue(format(StandardFormatters.AOSP, SOURCE, null).contains("\n    Calculator calculator = new Calculator();\n"), "AOSP: 4 spaces");
    }

    @Test void widthsDecideWhereAChainBreaks() {
        String chain = "class C {\n    int total(java.util.List<Integer> values) {\n        return values.stream().filter(value -> value > 0).mapToInt(Integer::intValue).sum();\n    }\n}\n";
        String classic = format(StandardFormatters.CLASSIC_80, chain, null), aosp = format(StandardFormatters.AOSP, chain, null);
        assertTrue(classic.lines().allMatch(line -> line.length() <= 80), classic);
        assertTrue(classic.contains("\n                .filter(value -> value > 0)\n"), "Classic 80 breaks the chain: " + classic);
        assertTrue(aosp.contains("return values.stream().filter(value -> value > 0).mapToInt(Integer::intValue).sum();"), "AOSP keeps it whole: " + aosp);
        assertTrue(format(StandardFormatters.WIDE_120, chain, null).lines().allMatch(line -> line.length() <= 120));
    }

    @Test void aSelectionFormatsOnlyItsLinesWithinTheFile() {
        String source = "class C {\n  int a=1;\n  int b=2;\n}\n";
        int start = source.indexOf("int b");
        String formatted = format(StandardFormatters.AOSP, source, new TextRange(start, start + 8));
        assertTrue(formatted.contains("  int a=1;") && formatted.contains("    int b = 2;"), formatted);
    }

    @Test void invalidJavaIsReportedAndLeftUnchanged() {
        var result = StandardFormatters.AOSP.engine().format(new FormatRequest("class C { void f( }", 1, LineEndingPolicy.PRESERVE), null, "25");
        assertFalse(result.success());
        assertTrue(result.edits().isEmpty());
        assertFalse(result.diagnostics().getFirst().message().isBlank());
    }

    @Test void theRealEngineNeverOverwritesTypingAndUndoRedoAreOneStep() throws Exception {
        var editor = edt(() -> { var value = new EditorComponent(); value.setText("class X{int a=1;}"); return value; });
        var controller = edt(() -> new FormattingController(editor, ignored -> {}, ignored -> {}));
        try {
            edt(() -> { controller.setEngine(StandardFormatters.AOSP.engine()); return null; });
            var outcome = new AtomicReference<FormattingController.Outcome>();
            var done = new CountDownLatch(1);
            edt(() -> { controller.format(false, value -> { outcome.set(value); done.countDown(); }); return null; });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(FormattingController.Outcome.APPLIED, outcome.get());
            assertEquals("class X {\n    int a = 1;\n}\n", edt(editor::getText));
            edt(() -> { editor.undo(); return null; });
            assertEquals("class X{int a=1;}", edt(editor::getText), "one Undo restores the source");
            edt(() -> { editor.redo(); return null; });
            assertEquals("class X {\n    int a = 1;\n}\n", edt(editor::getText), "one Redo reapplies it");

            // Typing while the engine works: the old result is discarded, the typing kept.
            var slow = new CountDownLatch(1);
            var stale = new CountDownLatch(1);
            var engine = StandardFormatters.AOSP.engine();
            edt(() -> {
                editor.setText("class Y{int b=2;}");
                controller.setEngine((request, range) -> { await(slow); return engine.format(request, range, "25"); });
                controller.format(false, value -> { outcome.set(value); stale.countDown(); });
                editor.gotoOffset(editor.getText().length());
                editor.replaceSelection("// typed meanwhile");
                return null;
            });
            slow.countDown();
            assertTrue(stale.await(10, TimeUnit.SECONDS));
            assertEquals(FormattingController.Outcome.STALE, outcome.get());
            assertEquals("class Y{int b=2;}// typed meanwhile", edt(editor::getText));
        } finally {
            edt(() -> { controller.close(); return null; });
        }
    }

    @Test void editsAreTheChangedWhitespaceOnly() {
        assertEquals(List.of(new TextEdit(1, 1, " "), new TextEdit(2, 2, " ")), StandardFormatters.edits("a+b", "a + b"));
        assertEquals(List.of(new TextEdit(6, 11, "\n  ")), StandardFormatters.edits("{\n  a;\n    b;}", "{\n  a;\n  b;}"));
        assertEquals(1, StandardFormatters.edits("import b;\nimport a;\n", "import a;\nimport b;\n").size(), "reordered tokens: one span");
        assertEquals(StandardFormatters.INTELLIJ, StandardFormatters.of("unknown"));
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
