package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LineIntelligenceEditorTest {
    private static final String SOURCE = "class Demo {\n    int value = 1;\n    int other = 2;\n}\n";

    @Test void independentLanesPaintFailingCoveredAndPassingPartialLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            assertTrue(editor.setLineIntelligence(snapshot(editor, Map.of(
                    2, new Line(true, List.of(test(TestStatus.FAILED, Freshness.CURRENT)), coverage(CoverageState.COVERED, Freshness.CURRENT)),
                    3, new Line(false, List.of(test(TestStatus.PASSED, Freshness.CURRENT)), coverage(CoverageState.PARTIAL, Freshness.CURRENT))), List.of(), true, true)));
            BufferedImage image = paint(editor);
            assertInk(image, editor.lineIntelligenceBounds(2, Lane.GIT), theme.color("git", "added"));
            assertInk(image, editor.lineIntelligenceBounds(2, Lane.TEST), theme.color("test", "failure"));
            assertInk(image, editor.lineIntelligenceBounds(2, Lane.COVERAGE), theme.color("coverage", "covered"));
            assertInk(image, editor.lineIntelligenceBounds(3, Lane.TEST), theme.color("test", "success"));
            assertInk(image, editor.lineIntelligenceBounds(3, Lane.COVERAGE), theme.color("coverage", "covered"));
            assertInk(image, editor.lineIntelligenceBounds(3, Lane.COVERAGE), theme.color("coverage", "uncovered"));
            assertTrue(editor.lineIntelligenceDescription(2, Lane.TEST).contains("FAILED"));
            assertTrue(editor.lineIntelligenceDescription(2, Lane.COVERAGE).contains("COVERED"));
        });
    }

    @Test void gutterClickTooltipKeyboardAndDeletedBoundariesPreserveSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            editor.setSelection(SOURCE.indexOf("value"), SOURCE.indexOf("value") + 5);
            var location = editor.captureLocation();
            assertTrue(editor.setLineIntelligence(snapshot(editor, Map.of(2, new Line(true,
                    List.of(test(TestStatus.FAILED, Freshness.CURRENT)), coverage(CoverageState.UNCOVERED, Freshness.CURRENT))),
                    List.of(new Deletion(0, "old first\n"), new Deletion(editor.document().lineCount(), "old last\n")), true, true)));
            AtomicReference<EditorComponent.LineInspection> clicked = new AtomicReference<>();
            editor.setOnInspectLine(clicked::set);
            for (Lane lane : Lane.values()) {
                Rectangle bounds = editor.lineIntelligenceBounds(2, lane);
                MouseEvent event = press(editor, bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
                editor.dispatchEvent(event);
                assertEquals(new EditorComponent.LineInspection(2, lane, null), clicked.get());
                assertEquals(location, editor.captureLocation());
                assertNotNull(editor.getToolTipText(event));
            }
            Rectangle first = editor.lineIntelligenceBounds(1, Lane.GIT);
            editor.dispatchEvent(press(editor, first.x + 2, first.y + 1));
            assertEquals(0, clicked.get().deletionAnchor());
            Rectangle last = editor.lineIntelligenceBounds(editor.document().lineCount(), Lane.GIT);
            editor.dispatchEvent(press(editor, last.x + 2, last.y + last.height - 2));
            assertEquals(editor.document().lineCount(), clicked.get().deletionAnchor());
            editor.inspectLineIntelligence(null);
            assertEquals(new EditorComponent.LineInspection(2, null, null), clicked.get());
            assertTrue(editor.getAccessibleContext().getAccessibleDescription().contains("Coverage: UNCOVERED"));
            assertEquals(SOURCE, editor.getText());
        });
    }

    @Test void editsInvalidateEvidenceWithoutCollapsingTheTestingLanesOrAcceptingLateResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            Snapshot old = snapshot(editor, Map.of(2, new Line(false, List.of(test(TestStatus.PASSED, Freshness.CURRENT)), coverage(CoverageState.COVERED, Freshness.CURRENT))), List.of(), false, true);
            editor.setLineIntelligence(old);
            int width = editor.offsetBounds(0).x;
            editor.gotoOffset(SOURCE.indexOf('1')); editor.replaceSelection("3");
            assertNull(editor.lineIntelligence());
            assertEquals(width, editor.offsetBounds(0).x);
            assertFalse(editor.setLineIntelligence(old));
            editor.setLineIntelligence(snapshot(editor, Map.of(2, new Line(false,
                    List.of(test(TestStatus.PASSED, Freshness.STALE)), coverage(CoverageState.COVERED, Freshness.STALE))), List.of(), false, true));
            assertTrue(editor.lineIntelligenceDescription(2, Lane.TEST).contains("STALE"));
            assertTrue(editor.lineIntelligenceDescription(2, Lane.COVERAGE).contains("STALE"));
            editor.undo(); assertEquals(SOURCE, editor.getText());
            editor.setLineIntelligence(snapshot(editor, Map.of(), List.of(), false, false));
            assertTrue(editor.offsetBounds(0).x < width);
            assertEquals(0, editor.lineIntelligenceBounds(2, Lane.TEST).width);
        });
    }

    @Test void aiVirtualRowsAcquireNoMarkersAndFollowingSourceRemainsAligned() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor("int value = ;\nreturn value;");
            editor.gotoOffset(editor.getText().indexOf(';'));
            editor.setLineIntelligence(snapshot(editor, Map.of(2, new Line(true, List.of(test(TestStatus.FAILED, Freshness.CURRENT)),
                    coverage(CoverageState.COVERED, Freshness.CURRENT))), List.of(), true, true));
            int caret = editor.caretOffset();
            assertTrue(editor.setAIInlineCompletion(new EditorComponent.InlineCompletion(editor.version(), caret, caret, caret, "42\n + 1", 7)));
            Rectangle actual = editor.lineIntelligenceBounds(2, Lane.GIT);
            int rowHeight = actual.height;
            assertEquals(rowHeight * 2, actual.y);
            MouseEvent ghost = press(editor, 2, rowHeight + 2);
            assertNull(editor.getToolTipText(ghost));
            assertNotNull(editor.getToolTipText(press(editor, 2, actual.y + rowHeight / 2)));
            BufferedImage image = paint(editor);
            Theme theme = Theme.bundled("classic");
            assertInk(image, actual, theme.color("git", "added"));
            assertEquals("int value = ;\nreturn value;", editor.getText());
        });
    }

    @Test void switchingBuffersRetainsLaneGeometryButNeverPreviousBufferEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            editor.setLineIntelligence(snapshot(editor, Map.of(2, new Line(true,
                    List.of(test(TestStatus.FAILED, Freshness.CURRENT)), coverage(CoverageState.UNCOVERED, Freshness.CURRENT))),
                    List.of(new Deletion(0, "removed\n")), true, true));
            EditorComponent.EditorState first = editor.captureState();
            int sourceStart = editor.offsetBounds(0).x;
            Rectangle lane = editor.lineIntelligenceBounds(2, Lane.TEST);
            editor.setText("class Other {\n    int other = 7;\n}\n");
            assertEquals(sourceStart, editor.offsetBounds(0).x);
            assertEquals(lane, editor.lineIntelligenceBounds(2, Lane.TEST));
            assertNull(editor.lineIntelligence());
            assertNull(editor.getToolTipText(press(editor, lane.x + 1, lane.y + 1)));
            assertTrue(editor.lineIntelligenceDescription(2, Lane.TEST).contains("refreshed"));
            editor.setLineIntelligence(snapshot(editor, Map.of(), List.of(), true, true));
            editor.restoreState(first);
            assertEquals(SOURCE, editor.getText());
            assertEquals(sourceStart, editor.offsetBounds(0).x);
            assertNull(editor.lineIntelligence(), "Restoring a buffer must not restore unvalidated evidence");
            editor.setLineIntelligence(null);
            assertTrue(editor.offsetBounds(0).x < sourceStart);
            for (Lane kind : Lane.values()) assertEquals(0, editor.lineIntelligenceBounds(2, kind).width);
        });
    }

    @Test void all103ThemesAndZoomKeepDataAndCaretStable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            editor.gotoLine(2);
            Snapshot snapshot = snapshot(editor, Map.of(2, new Line(true, List.of(test(TestStatus.FAILED, Freshness.CURRENT)),
                    coverage(CoverageState.UNCOVERED, Freshness.CURRENT))), List.of(), true, true);
            editor.setLineIntelligence(snapshot);
            int caret = editor.caretOffset(); long version = editor.version();
            for (String id : Theme.bundledResources().keySet()) for (int size : new int[]{10, 24, 48}) {
                Theme theme = Theme.bundled(id); editor.setTheme(theme); editor.setFontSize(size);
                BufferedImage image = paint(editor);
                assertInk(image, editor.lineIntelligenceBounds(2, Lane.GIT), theme.color("git", "added"));
                assertInk(image, editor.lineIntelligenceBounds(2, Lane.TEST), theme.color("test", "failure"));
                assertInk(image, editor.lineIntelligenceBounds(2, Lane.COVERAGE), theme.color("coverage", "uncovered"));
                assertSame(snapshot, editor.lineIntelligence());
                assertEquals(caret, editor.caretOffset()); assertEquals(version, editor.version()); assertEquals(SOURCE, editor.getText());
            }
        });
    }

    private static EditorComponent editor(String source) {
        EditorComponent editor = new EditorComponent(); editor.setSize(1200, 600); editor.setText(source); return editor;
    }
    private static Snapshot snapshot(EditorComponent editor, Map<Integer, Line> lines, List<Deletion> deleted, boolean git, boolean testing) {
        SourceIdentity source = new SourceIdentity(null, null, "demo", "demo", Path.of("Demo.java"), "buffer", editor.version(), "hash", "compile");
        return new Snapshot(source, lines, deleted, git, testing, "HEAD → editor", "");
    }
    private static TestResult test(TestStatus status, Freshness freshness) {
        return new TestResult("test", "run", "suite", "example", "demo", "module", "", "unit", "JUnit", "1", status, freshness, 1, "", List.of());
    }
    private static Coverage coverage(CoverageState state, Freshness freshness) {
        return new Coverage(state, freshness, Availability.AVAILABLE, Precision.INSTRUCTIONS_AND_BRANCHES,
                state == CoverageState.UNCOVERED ? 0 : 3, state == CoverageState.COVERED ? 0 : 2,
                state == CoverageState.UNCOVERED ? 0 : 1, state == CoverageState.COVERED ? 0 : 1,
                "JaCoCo", "unit", "run", "process", "hash", "class", "");
    }
    private static MouseEvent press(EditorComponent editor, int x, int y) {
        return new MouseEvent(editor, MouseEvent.MOUSE_PRESSED, 1, 0, x, y, 1, false, MouseEvent.BUTTON1);
    }
    private static BufferedImage paint(EditorComponent editor) {
        BufferedImage image = new BufferedImage(editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); editor.paint(graphics); graphics.dispose(); return image;
    }
    private static void assertInk(BufferedImage image, Rectangle bounds, Color expected) {
        for (int y = Math.max(0, bounds.y); y < Math.min(image.getHeight(), bounds.y + bounds.height); y++)
            for (int x = Math.max(0, bounds.x); x < Math.min(image.getWidth(), bounds.x + bounds.width); x++)
                if (image.getRGB(x, y) == expected.getRGB()) return;
        fail("Missing marker color " + expected + " in " + bounds);
    }
}
