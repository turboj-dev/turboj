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

    @Test void unknownCoveragePlaceholderIsHiddenOnlyAtVerifiedTestDeclarations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor("class Demo {\n void verifies() {}\n int production;\n int stale;\n}\n");
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            TestResult declaration = new TestResult("test","run","suite","verifies","demo","module","","unit","JUnit","1",
                    TestStatus.PASSED,Freshness.CURRENT,1,"",List.of(new Association(Path.of("Demo.java"),2,Attribution.TEST_DECLARATION,"resolved JUnit method")));
            Coverage unknown = Coverage.unknown(Availability.MISSING,"No coverage collected");
            editor.setLineIntelligence(snapshot(editor,Map.of(
                    2,new Line(false,List.of(declaration),unknown),
                    3,new Line(false,List.of(declaration),unknown),
                    4,new Line(false,List.of(),unknown.withFreshness(Freshness.STALE))),List.of(),false,true));
            BufferedImage image = paint(editor);
            Rectangle lane = editor.lineIntelligenceBounds(2,Lane.COVERAGE);
            for (int y = lane.y; y < lane.y + lane.height; y++)
                for (int x = lane.x; x < lane.x + lane.width; x++)
                    assertEquals(theme.background().getRGB(),image.getRGB(x,y));
            assertInk(image,editor.lineIntelligenceBounds(2,Lane.TEST),theme.color("test","success"));
            assertInk(image,editor.lineIntelligenceBounds(3,Lane.COVERAGE),theme.color("status","unknown"));
            assertInk(image,editor.lineIntelligenceBounds(4,Lane.COVERAGE),theme.color("status","stale"));
            assertTrue(editor.lineIntelligenceDescription(2,Lane.COVERAGE).contains("Test declaration"));
            editor.setLineIntelligence(snapshot(editor,Map.of(2,new Line(false,List.of(declaration),
                    coverage(CoverageState.COVERED,Freshness.CURRENT))),List.of(),false,true));
            assertInk(paint(editor),editor.lineIntelligenceBounds(2,Lane.COVERAGE),theme.color("coverage","covered"));
        });
    }

    @Test void notApplicableCoverageIsBlankWhileTestOutcomeUnknownAndStaleRemainVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor("class DemoTest {\n void verifiesPrice() {}\n int unknown;\n int stale;\n}\n");
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            editor.setLineIntelligence(snapshot(editor,Map.of(
                    2,new Line(false,List.of(test(TestStatus.PASSED,Freshness.CURRENT)),Coverage.unknown(Availability.EXCLUDED,"Test source excluded")),
                    3,new Line(false,List.of(),Coverage.unknown(Availability.MISSING,"No report")),
                    4,new Line(false,List.of(),coverage(CoverageState.COVERED,Freshness.STALE))),List.of(),false,true));
            BufferedImage image = paint(editor);
            Rectangle lane = editor.lineIntelligenceBounds(2,Lane.COVERAGE);
            for (int y = lane.y; y < lane.y + lane.height; y++)
                for (int x = lane.x; x < lane.x + lane.width; x++)
                    assertEquals(theme.background().getRGB(),image.getRGB(x,y));
            assertInk(image,editor.lineIntelligenceBounds(2,Lane.TEST),theme.color("test","success"));
            assertInk(image,editor.lineIntelligenceBounds(3,Lane.COVERAGE),theme.color("status","unknown"));
            assertInk(image,editor.lineIntelligenceBounds(4,Lane.COVERAGE),theme.color("status","stale"));
            assertTrue(editor.getToolTipText(press(editor,lane.x + lane.width / 2,lane.y + lane.height / 2)).contains("NOT_APPLICABLE"));
        });
    }

    @Test void braceOnlyCoverageIsHiddenWithoutDiscardingCountersOrOtherDimensions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String source = "class Demo {\r\n    void main()\r\n    {\r\n        System.out.println(42);\r\n"
                    + "    } // end method\r\n    /* note */ }\r\n";
            EditorComponent editor = editor(source);
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            Map<Integer,Line> lines = new java.util.TreeMap<>();
            for (int line : new int[]{3,4,5,6}) lines.put(line,new Line(true,
                    List.of(test(TestStatus.FAILED,Freshness.CURRENT)),
                    coverage(line == 3 ? CoverageState.COVERED : CoverageState.UNCOVERED,Freshness.CURRENT)));
            Snapshot evidence = snapshot(editor,lines,List.of(),true,true);
            editor.setLineIntelligence(evidence);
            BufferedImage image = paint(editor);
            for (int line : new int[]{3,5,6}) {
                Rectangle lane = editor.lineIntelligenceBounds(line,Lane.COVERAGE);
                assertEquals(theme.background().getRGB(),image.getRGB(900,lane.y + lane.height / 2));
                for (int y = lane.y; y < lane.y + lane.height; y++)
                    for (int x = lane.x; x < lane.x + lane.width; x++)
                        assertEquals(theme.background().getRGB(),image.getRGB(x,y),"No brace coverage symbol");
                assertInk(image,editor.lineIntelligenceBounds(line,Lane.GIT),theme.color("git","added"));
                assertInk(image,editor.lineIntelligenceBounds(line,Lane.TEST),theme.color("test","failure"));
                assertTrue(editor.lineIntelligenceDescription(line,Lane.COVERAGE).contains("decoration hidden"));
                assertTrue(editor.lineIntelligenceDescription(line,Lane.COVERAGE).contains("Instructions"));
            }
            assertSame(evidence,editor.lineIntelligence());
            assertInk(image,editor.lineIntelligenceBounds(4,Lane.COVERAGE),theme.color("coverage","uncovered"));
            editor.gotoOffset(editor.document().lineStart(4)); editor.replaceSelection("return; ");
            editor.setLineIntelligence(snapshot(editor,lines,List.of(),true,true));
            assertInk(paint(editor),editor.lineIntelligenceBounds(5,Lane.COVERAGE),theme.color("coverage","uncovered"));
            editor.undo(); editor.setLineIntelligence(snapshot(editor,lines,List.of(),true,true));
            assertTrue(editor.lineIntelligenceDescription(5,Lane.COVERAGE).contains("decoration hidden"));
        });
    }

    @Test void braceCharactersInTextBlocksAndLinesContainingStatementsRetainCoverage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor("String text = \"\"\"\n}\n\"\"\";\n} else {\n} while (ready);\nrun(); }\n");
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            Map<Integer,Line> lines = new java.util.TreeMap<>();
            for (int line : new int[]{2,4,5,6}) lines.put(line,new Line(false,List.of(),coverage(CoverageState.COVERED,Freshness.CURRENT)));
            editor.setLineIntelligence(snapshot(editor,lines,List.of(),false,true));
            BufferedImage image = paint(editor);
            for (int line : lines.keySet()) {
                assertInk(image,editor.lineIntelligenceBounds(line,Lane.COVERAGE),theme.color("coverage","covered"));
                assertFalse(editor.lineIntelligenceDescription(line,Lane.COVERAGE).contains("decoration hidden"));
            }
        });
    }

    @Test void hidingBackgroundsPreservesAllLanesDataAndSelectionAcrossThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = editor(SOURCE);
            Snapshot evidence = snapshot(editor,Map.of(2,new Line(true,List.of(test(TestStatus.FAILED,Freshness.CURRENT)),
                    coverage(CoverageState.COVERED,Freshness.CURRENT))),List.of(),true,true);
            editor.setLineIntelligence(evidence);
            editor.setSelection(SOURCE.indexOf("value"),SOURCE.indexOf("value") + 5);
            var location = editor.captureLocation();
            Rectangle coverage = editor.lineIntelligenceBounds(2,Lane.COVERAGE);
            editor.setShowCoverageBackgrounds(false);
            for (String id : List.of("vscode-monokai","light","classic")) {
                Theme theme = Theme.bundled(id); editor.setTheme(theme);
                BufferedImage image = paint(editor);
                assertFalse(editor.isShowCoverageBackgrounds());
                assertEquals(theme.background().getRGB(),image.getRGB(900,coverage.y + coverage.height / 2));
                assertInk(image,coverage,theme.color("coverage","covered"));
                assertInk(image,editor.lineIntelligenceBounds(2,Lane.GIT),theme.color("git","added"));
                assertInk(image,editor.lineIntelligenceBounds(2,Lane.TEST),theme.color("test","failure"));
                assertSame(evidence,editor.lineIntelligence()); assertEquals(location,editor.captureLocation());
                assertEquals(coverage,editor.lineIntelligenceBounds(2,Lane.COVERAGE));
            }
            editor.setShowCoverageBackgrounds(true);
            assertTrue(editor.isShowCoverageBackgrounds());
            assertEquals(dev.turboj.ui.intelligence.CoverageRowPalette.resolve(Theme.bundled("classic")).covered().getRGB(),
                    paint(editor).getRGB(900,coverage.y + coverage.height / 2));
        });
    }

    @Test void coverageRowsRemainIndependentOfTestResultsAndSelectionWinsAtHiDpi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String id : List.of("vscode-monokai","light","classic")) for (int scale : new int[]{1,2}) {
                EditorComponent editor = editor("class Demo {\n    int a;\n    int b;\n    int c;\n    int d;\n    int e;\n}\n");
                Theme theme = Theme.bundled(id); editor.setTheme(theme);
                var palette = dev.turboj.ui.intelligence.CoverageRowPalette.resolve(theme);
                editor.setLineIntelligence(snapshot(editor,Map.of(
                        2,new Line(true,List.of(test(TestStatus.FAILED,Freshness.CURRENT)),coverage(CoverageState.COVERED,Freshness.CURRENT)),
                        3,new Line(false,List.of(),coverage(CoverageState.PARTIAL,Freshness.CURRENT)),
                        4,new Line(false,List.of(),coverage(CoverageState.UNCOVERED,Freshness.CURRENT)),
                        5,new Line(false,List.of(),coverage(CoverageState.COVERED,Freshness.UNKNOWN)),
                        6,new Line(false,List.of(test(TestStatus.FAILED,Freshness.STALE)),coverage(CoverageState.COVERED,Freshness.STALE))),List.of(),true,true));
                int start = editor.document().lineStart(1);
                editor.setSelection(start,start + 4);
                BufferedImage image = paint(editor,scale);
                for (int line = 2; line <= 6; line++) {
                    Rectangle lane = editor.lineIntelligenceBounds(line,Lane.COVERAGE);
                    Color expected = switch (line) {
                        case 2,3 -> palette.covered(); case 4 -> palette.uncovered(); default -> theme.background();
                    };
                    assertEquals(expected.getRGB(),image.getRGB(900 * scale,(lane.y + lane.height / 2) * scale),id + " row " + line);
                }
                Rectangle selected = editor.offsetBounds(start + 1);
                assertEquals(theme.selection().getRGB(),image.getRGB((selected.x + 1) * scale,(selected.y + selected.height / 2) * scale));
                Rectangle partial = editor.lineIntelligenceBounds(3,Lane.COVERAGE);
                int sourceX = editor.offsetBounds(editor.document().lineStart(2)).x;
                for (int x = sourceX * scale; x < partial.x * scale; x++)
                    assertEquals(palette.covered().getRGB(),image.getRGB(x,(partial.y + partial.height - 1) * scale),
                            "Partial coverage has no diagnostic-like underline; its gutter symbol remains distinct");
                assertTrue(editor.lineIntelligenceDescription(3,Lane.TEST).contains("no verified"));
                Rectangle emptyTestLane = editor.lineIntelligenceBounds(3,Lane.TEST);
                assertTrue(emptyTestLane.width > 0,"Keep lane geometry stable even without test attribution");
                for (int y = emptyTestLane.y * scale; y < (emptyTestLane.y + emptyTestLane.height) * scale; y++)
                    for (int x = emptyTestLane.x * scale; x < (emptyTestLane.x + emptyTestLane.width) * scale; x++)
                        assertEquals(theme.background().getRGB(),image.getRGB(x,y),"No placeholder dot beside coverage-only evidence");
                assertEquals(start,editor.selectionStart());
            }
        });
    }

    @Test void coverageStaysAtRightEdgeOnResizeAndScrollWithoutSourceOrSelectionOverlap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String source = "class Demo {\n    String text = \"" + "long text ".repeat(80) + "\";\n}";
            EditorComponent editor = editor(source);
            Theme theme = Theme.bundled("vscode-monokai"); editor.setTheme(theme);
            editor.setLineIntelligence(snapshot(editor, Map.of(2, new Line(true,
                    List.of(test(TestStatus.FAILED, Freshness.CURRENT)), coverage(CoverageState.PARTIAL, Freshness.CURRENT))),
                    List.of(), true, true));
            AtomicReference<EditorComponent.LineInspection> clicked = new AtomicReference<>();
            editor.setOnInspectLine(clicked::set);
            for (int size : new int[]{10,24,48}) for (int width : new int[]{320,768,1200}) {
                editor.setSize(width,600); editor.setFontSize(size);
                int end = editor.document().lineEnd(1);
                editor.gotoOffset(end); // Forces horizontal scrolling with the coverage cell reserved.
                Rectangle right = editor.lineIntelligenceBounds(2,Lane.COVERAGE);
                assertEquals(width,right.x + right.width);
                assertTrue(editor.lineIntelligenceBounds(2,Lane.TEST).x < editor.offsetBounds(end).x);
                assertTrue(editor.offsetBounds(end).x + editor.offsetBounds(end).width <= right.x,
                        "Caret remains in the source viewport");
                editor.setSelection(editor.document().lineStart(1),end);
                var location = editor.captureLocation();
                BufferedImage image = paint(editor);
                assertInk(image,right,theme.color("coverage","covered"));
                assertInk(image,right,theme.color("coverage","uncovered"));
                assertEquals(theme.background().getRGB(),image.getRGB(right.x,right.y + right.height - 1),
                        "Source tint and long selection cannot paint into the right gutter");
                MouseEvent event = press(editor,right.x + right.width / 2,right.y + right.height / 2);
                editor.dispatchEvent(event);
                assertEquals(new EditorComponent.LineInspection(2,Lane.COVERAGE,null),clicked.get());
                assertEquals(location,editor.captureLocation());
                assertTrue(editor.getToolTipText(event).contains("PARTIAL"));
            }
            editor.setLineIntelligence(null);
            assertEquals(0,editor.lineIntelligenceBounds(2,Lane.COVERAGE).width);
        });
    }

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
            assertEquals(theme.background().getRGB(),image.getRGB(900,rowHeight + rowHeight / 2),"AI-only row remains neutral");
            assertEquals(dev.turboj.ui.intelligence.CoverageRowPalette.resolve(theme).covered().getRGB(),
                    image.getRGB(900,actual.y + rowHeight / 2),"Following real source retains its coverage tint");
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
        return paint(editor,1);
    }
    private static BufferedImage paint(EditorComponent editor,int scale) {
        BufferedImage image = new BufferedImage(editor.getWidth() * scale, editor.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(scale,scale); editor.paint(graphics); graphics.dispose(); return image;
    }
    private static void assertInk(BufferedImage image, Rectangle bounds, Color expected) {
        Color background = new Color(image.getRGB(Math.max(0,bounds.x),Math.max(0,bounds.y)));
        for (int y = Math.max(0, bounds.y); y < Math.min(image.getHeight(), bounds.y + bounds.height); y++)
            for (int x = Math.max(0, bounds.x); x < Math.min(image.getWidth(), bounds.x + bounds.width); x++) {
                Color actual = new Color(image.getRGB(x,y));
                if (actual.equals(expected)) return;
                // Text antialiasing blends ink with the lane; require a predominantly ink-colored pixel.
                if (distance(actual,expected) < distance(background,expected) * .45) return;
            }
        fail("Missing marker color " + expected + " in " + bounds);
    }
    private static double distance(Color a,Color b) {
        return Math.sqrt(Math.pow(a.getRed() - b.getRed(),2) + Math.pow(a.getGreen() - b.getGreen(),2)
                + Math.pow(a.getBlue() - b.getBlue(),2));
    }
}
