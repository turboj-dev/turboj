package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.project.FirstLaunchProjects;
import dev.turboj.refactorj.api.JavaIntelligence.Completion;
import dev.turboj.refactorj.api.JavaQuickActions.Proposal;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window: @Test with Ctrl+Space or Cmd+1 adds JUnit Jupiter to a plain project and to a Maven POM, then compiles. */
public final class JUnitLibrarySmoke {
    private static TurboFrame frame;
    private static final String IMPORT = "import org.junit.jupiter.api.Test;";
    private static final String JUNIT4_SOURCE = """
            import org.junit.Test;

            public class CalculatorTest {
                @Test
                public void test() {
                    Calculator calculator = new Calculator();
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-junit-library-").toRealPath(); // Commits refuse symlinked roots (/var).
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        String source = "public class CalculatorTest {\n    @Test\n    void adds() {}\n}\n";
        try {
            frame = edt(() -> {
                Settings settings = new Settings(); settings.keymapProfile = "turboj-macos";
                TurboFrame result = new TurboFrame(settings, Map.of()); result.setVisible(true); result.toFront(); return result;
            });

            Path plain = Files.createDirectories(session.resolve("plain-completion"));
            Path completed = plain.resolve("CalculatorTest.java"); Files.writeString(completed, source);
            open(completed, source.indexOf("@Test") + 5);
            key(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK);
            await("Ctrl+Space proposals", () -> named("completion-choices") instanceof JList<?> list && list.getModel().getSize() > 0);
            edt(() -> {
                var first = (Completion) ((JList<?>) named("completion-choices")).getModel().getElementAt(0);
                if (!first.detail().equals("org.junit.jupiter.api.Test") || !first.library().equals(Completion.JUNIT_JUPITER))
                    throw new AssertionError("First @Test proposal must be Jupiter with its library: " + first);
                return null;
            });
            key(KeyEvent.VK_ENTER, 0);
            await("bundled JUnit marker", () -> Files.isRegularFile(plain.resolve(FirstLaunchProjects.JUNIT_MARKER)));
            check(edt(() -> frame.editor().getText()).startsWith(IMPORT + "\n"), "Ctrl+Space inserted the import");
            compiles(completed);
            System.out.println("PASS plain Java: @Test + Ctrl+Space imports Jupiter, adds the bundled library and compiles");

            Path quick = Files.createDirectories(session.resolve("plain-quick-fix"));
            Path fixed = quick.resolve("CalculatorTest.java"); Files.writeString(fixed, source);
            open(fixed, source.indexOf("@Test") + 3);
            addLibraryWithQuickFix();
            await("bundled JUnit marker", () -> Files.isRegularFile(quick.resolve(FirstLaunchProjects.JUNIT_MARKER)));
            await("import", () -> frame.editor().getText().startsWith(IMPORT));
            compiles(fixed);
            System.out.println("PASS plain Java: Cmd+1 'Add JUnit 5 library to the build path' imports, adds the library and compiles");

            // The user's JUnit 4 style source, as typed: the unresolved org.junit.Test import becomes Jupiter's.
            Path legacy = Files.createDirectories(session.resolve("plain-junit4"));
            Path legacyTest = legacy.resolve("CalculatorTest.java"); Files.writeString(legacyTest, JUNIT4_SOURCE);
            Files.writeString(legacy.resolve("Calculator.java"), "public class Calculator {}\n");
            open(legacyTest, JUNIT4_SOURCE.indexOf("@Test") + 5);
            key(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK);
            await("Ctrl+Space proposals", () -> named("completion-choices") instanceof JList<?> list && list.getModel().getSize() > 0);
            key(KeyEvent.VK_ENTER, 0);
            await("bundled JUnit marker", () -> Files.isRegularFile(legacy.resolve(FirstLaunchProjects.JUNIT_MARKER)));
            check(edt(() -> frame.editor().getText()).equals(JUNIT4_SOURCE.replace("org.junit.Test", "org.junit.jupiter.api.Test")),
                    "Ctrl+Space replaced import org.junit.Test with Jupiter's, without a second import");
            compiles(legacyTest);
            System.out.println("PASS plain Java: import org.junit.Test + @Test + Ctrl+Space moves to Jupiter, adds the library and compiles");

            // Cmd+Enter: the static import for assertEquals, then completion (twice: list, accept), then Run.
            Path smart = Files.createDirectories(session.resolve("plain-cmd-enter"));
            Files.createDirectories(smart.resolve(".turboj"));
            Files.writeString(smart.resolve(FirstLaunchProjects.JUNIT_MARKER), "runtime=bundled-jupiter-" + FirstLaunchProjects.JUNIT_VERSION + "\n");
            Path smartTest = smart.resolve("CalculatorTest.java");
            String smartSource = "import org.junit.jupiter.api.Test;\n\npublic class CalculatorTest {\n    @Test\n    public void test() {\n"
                    + "        Calculator calculator = new Calculator();\n        assertEquals();\n    }\n}\n";
            Files.writeString(smartTest, smartSource);
            Files.writeString(smart.resolve("Calculator.java"), "public class Calculator {}\n");
            open(smartTest, smartSource.indexOf("assertEquals(") + "assertEquals(".length());
            key(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK);
            await("static import", () -> frame.editor().getText().contains("import static org.junit.jupiter.api.Assertions.assertEquals;"));
            System.out.println("PASS Cmd+Enter in assertEquals() adds import static org.junit.jupiter.api.Assertions.assertEquals");

            edt(() -> {
                String text = frame.editor().getText();
                int at = text.indexOf("        assertEquals();");
                frame.editor().setSelection(at, at + "        assertEquals();".length());
                frame.editor().replaceSelection("        assertTr;");
                frame.editor().gotoOffset(frame.editor().getText().indexOf("assertTr;") + "assertTr".length());
                return null;
            });
            key(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK);
            await("completion list with assertTrue", () -> named("completion-choices") instanceof JList<?> list && list.getModel().getSize() > 0
                    && ((Completion) list.getSelectedValue()).text().equals("assertTrue()"));
            key(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK);
            await("assertTrue inserted with its import", () -> frame.editor().getText().contains("        assertTrue();")
                    && frame.editor().getText().contains("import static org.junit.jupiter.api.Assertions.assertTrue;"));
            System.out.println("PASS assertTr + Cmd+Enter lists assertTrue; Cmd+Enter again inserts it with its static import");

            edt(() -> {
                String text = frame.editor().getText();
                int at = text.indexOf("        assertTrue();");
                frame.editor().setSelection(at, at + "        assertTrue();".length());
                frame.editor().replaceSelection("        assertTrue(calculator != null);\n        assertEquals(4, 2 + 2);");
                frame.editor().gotoOffset(frame.editor().getText().indexOf("2 + 2);") + "2 + 2);".length());
                return null;
            });
            await("compiles before Run", () -> frame.currentResult() != null && frame.currentResult().success());
            key(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK);
            for (int i = 0; i < 40 && frame.tests().lastReport() == null; i++) {
                if (edt(() -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream().anyMatch(d -> d.getTitle().equals("Save before Run"))))
                    key(KeyEvent.VK_ENTER, 0); // Yes: save, then run.
                Thread.sleep(250);
            }
            await("the test ran and passed", () -> frame.tests().lastReport() != null && frame.tests().lastReport().failed() == 0
                    && frame.tests().lastReport().passed() == 1);
            System.out.println("PASS Cmd+Enter with nothing to complete or import runs the test: 1 passed");

            // Running tests that fail to compile only for lack of JUnit offers to add it; the build output says how too.
            Path run = Files.createDirectories(session.resolve("plain-run-tests"));
            Path runTest = run.resolve("CalculatorTest.java");
            String userSource = "public class CalculatorTest {\n    @Test\n    public void test() {\n        Calculator calculator = new Calculator();\n    }\n}\n";
            Files.writeString(runTest, userSource);
            Files.writeString(run.resolve("Calculator.java"), "public class Calculator {}\n");
            open(runTest, 0);
            await("build hint", () -> frame.console().text().contains("JUnit is not on this project's build path"));
            SwingUtilities.invokeLater(() -> frame.commandRegistry().execute("tests.class",
                    dev.turboj.commandj.core.CommandContext.of("workspace", "editor", "editable")));
            await("Add JUnit 5 offer", () -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream()
                    .anyMatch(dialog -> dialog.getTitle().equals("Add JUnit 5")));
            key(KeyEvent.VK_ENTER, 0);
            await("bundled JUnit marker", () -> Files.isRegularFile(run.resolve(FirstLaunchProjects.JUNIT_MARKER)));
            await("import added", () -> frame.editor().getText().startsWith(IMPORT + "\n"));
            compiles(runTest);
            System.out.println("PASS plain Java: the build output explains how to add JUnit; Run Test Class offers Add JUnit 5, Enter adds it and the test compiles");

            Path maven = session.resolve("maven");
            Path test = maven.resolve("src/test/java/demo/CalculatorTest.java");
            Files.createDirectories(test.getParent()); Files.createDirectories(maven.resolve("src/main/java/demo"));
            Files.writeString(maven.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
                    + "<artifactId>calculator</artifactId><version>1.0</version><properties><maven.compiler.release>25</maven.compiler.release>"
                    + "</properties></project>\n");
            Files.writeString(maven.resolve("src/main/java/demo/Calculator.java"), "package demo;\npublic class Calculator {}\n");
            String mavenSource = "package demo;\n\n" + JUNIT4_SOURCE;
            Files.writeString(test, mavenSource);

            // A separate project: its deliberately misplaced test would otherwise keep the module from compiling.
            Path mainOnly = session.resolve("maven-main-only");
            Path misplaced = mainOnly.resolve("src/main/java/demo/MisplacedTest.java");
            Files.createDirectories(misplaced.getParent()); Files.createDirectories(mainOnly.resolve("src/test/java"));
            Files.copy(maven.resolve("pom.xml"), mainOnly.resolve("pom.xml"));
            String misplacedSource = "package demo;\n\n" + source.replace("CalculatorTest", "MisplacedTest");
            Files.writeString(misplaced, misplacedSource);
            edt(() -> { frame.mavenProjects().open(mainOnly); return null; });
            await("Maven import", () -> frame.mavenProjects().workspace() != null && !frame.mavenProjects().busy()
                    && frame.mavenProjects().workspace().root().equals(mainOnly));
            String originalPom = Files.readString(mainOnly.resolve("pom.xml"));
            open(misplaced, misplacedSource.indexOf("@Test") + 3);
            addLibraryWithQuickFix();
            await("main-source refusal", () -> frame.statusMessages().contains("move MisplacedTest.java to src/test/java"));
            check(Files.readString(mainOnly.resolve("pom.xml")).equals(originalPom), "a test in main sources leaves the POM untouched");
            System.out.println("PASS Maven: a test in src/main/java is told to move to src/test/java; the POM is not changed");

            edt(() -> { frame.mavenProjects().open(maven); return null; });
            await("Maven import", () -> frame.mavenProjects().workspace() != null && !frame.mavenProjects().busy()
                    && frame.mavenProjects().workspace().root().equals(maven));
            open(test, mavenSource.indexOf("org.junit.Test") + 4);
            addLibraryWithQuickFix();
            await("POM dependency", () -> Files.readString(maven.resolve("pom.xml")).contains("<artifactId>junit-jupiter</artifactId>"));
            String pom = Files.readString(maven.resolve("pom.xml"));
            check(pom.contains("<version>" + FirstLaunchProjects.JUNIT_VERSION + "</version>") && pom.contains("<scope>test</scope>"),
                    "POM declares the Jupiter test dependency");
            await("Maven reload with JUnit", () -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace() != null
                    && frame.mavenProjects().workspace().modules().getFirst().classpaths()
                    .forScope(dev.turboj.mavenj.api.MavenModels.ClasspathScope.TEST_COMPILE).stream()
                    .anyMatch(path -> path.getFileName().toString().startsWith("junit-jupiter-api-")));
            await("import", () -> frame.editor().getText().contains(IMPORT) && !frame.editor().getText().contains("import org.junit.Test;"));
            compiles(test);
            System.out.println("PASS Maven: Cmd+1 on import org.junit.Test adds the POM test dependency, moves to Jupiter, reloads and compiles");
        } finally { if (frame != null) edt(() -> { frame.shutdown(); frame.dispose(); return null; }); }
    }

    private static void addLibraryWithQuickFix() throws Exception {
        key(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK);
        await("Cmd+1 proposals", () -> named("quick-actions-proposals") instanceof JList<?> list && list.getModel().getSize() > 0);
        edt(() -> {
            JList<?> list = (JList<?>) named("quick-actions-proposals");
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (((Proposal) list.getModel().getElementAt(i)).title().equals("Add JUnit 5 library to the build path")) {
                    list.setSelectedIndex(i); return null;
                }
            throw new AssertionError("Missing 'Add JUnit 5 library to the build path'");
        });
        key(KeyEvent.VK_ENTER, 0);
    }

    private static void open(Path file, int caret) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await("open " + file.getFileName(), () -> file.equals(frame.currentFile()));
        // The first check must settle first: its diagnostics would otherwise race the explicit request.
        await("first check of " + file.getFileName(), () -> !frame.buildStatus().contains("CHECKING")
                && !frame.buildStatus().contains("COMPILING") && frame.currentResult() != null);
        edt(() -> { frame.editor().gotoOffset(caret); frame.editor().requestFocusInWindow(); return null; });
        await("editor focus", () -> frame.editor().isFocusOwner());
    }

    private static void compiles(Path file) throws Exception {
        await(file.getFileName() + " compiles", () -> file.equals(frame.currentFile()) && frame.currentResult() != null
                && frame.currentResult().success());
    }

    private static Component named(String name) { return named(frame, name); }
    private static Component named(Component c, String name) {
        if (name.equals(c.getName()) && c.isShowing()) return c;
        if (c instanceof Container container) for (Component child : container.getComponents()) {
            Component match = named(child, name); if (match != null) return match;
        }
        if (c instanceof Window window) for (Window owned : window.getOwnedWindows()) {
            Component match = named(owned, name); if (match != null) return match;
        }
        return null;
    }
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager(); Component focus = manager.getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                manager.dispatchEvent(new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; status " + edt(frame::buildStatus) + "; focus "
                + edt(() -> String.valueOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()).replaceAll(",.*", ""))
                + "; editor:\n" + edt(() -> frame.editor().getText()) + "; console:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
