package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.turbotest.TestEngine;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Visible Swing workflow audit. Fixtures/preferences and assertions are programmatic;
 * all application interaction after initial focus uses keyboard events and system clipboard.
 * Default uses the real Swing KeyboardFocusManager pipeline; --robot uses OS AWT Robot.
 * Neither mode is a human walkthrough or verification of physical keyboard layouts. */
public final class KeyboardWorkflowSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static TurboFrame frame;
    private static Settings settings;
    private static Robot robot;
    private static Path output, project, source;
    private static final int CMD = KeyEvent.META_DOWN_MASK;
    private static final String MAIN = """
            package demo;
            public class KeyboardMain {
                public static int answer() { return 20 + 22; }
                public static void main(String[] args) {
                    System.out.println("KEYBOARD_RESULT=" + answer());
                }
                public static String first() { return "first"; }
                public static String second() { return "second"; }
            }
            """;

    public static void main(String[] args) throws Exception {
        output = Path.of("app/target/keyboard-workflow-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path session = Files.createTempDirectory("turboj-keyboard-workflows-");
        Path home = Files.createDirectories(session.resolve("home/.m2")).getParent();
        Path repository = Path.of(".cache/m2").toAbsolutePath();
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><offline>true</offline><localRepository>"
                + repository + "</localRepository></settings>");
        System.setProperty("user.home", home.toString());
        System.setProperty("turboj.config", session.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        project = fixture(session.resolve("workspace"));
        source = project.resolve("src/main/java/demo/KeyboardMain.java");
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        if (List.of(args).contains("--robot")) { robot = new Robot(); robot.setAutoDelay(35); }
        report.append("Mode: ").append(robot == null ? "Swing KeyboardFocusManager key events" : "OS AWT Robot key events")
                .append("; no mouse events; not a human audit.\nStarted: ").append(Instant.now()).append('\n');
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            settings = new Settings();
            var value = new TurboFrame(settings, Map.of());
            value.setVisible(true); value.toFront(); value.editor().requestFocusInWindow(); return value;
        });
        try {
            waitFor("initial source focus", () -> frame.isActive() && frame.editor().isFocusOwner());
            workflowA();
            workflowC();
            workflowB();
            workflowH();
            workflowD();
            workflowF();
            workflowE();
            screenshot("complete.png");
            report.append("PASS all A–F and H workflow assertions. G is covered by KeyboardEditingSmoke.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append("\nFocus: ").append(edt(() -> describe(focus())))
                    .append("\nDialogs: ").append(edt(() -> OverlayHost.dialogs(frame).stream().map(EmbeddedDialog::getTitle).toList()))
                    .append("\nConsole:\n").append(edt(() -> frame.console().text())).append('\n');
            screenshot("failure.png"); throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (previous != null) clipboard.setContents(previous, null);
        }
    }

    private static void workflowA() throws Exception {
        action("project.open");
        chooseFile(project.resolve("pom.xml"));
        waitFor("Maven import", () -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace() != null
                && frame.mavenProjects().workspace().resolved());
        check(edt(() -> frame.mavenProjects().workspace().offline()), "A: keyboard import resolves cached Maven/JUnit offline");
        open(source);
        find("KEYBOARD_RESULT="); paste("KEYBOARD_RUN="); key(KeyEvent.VK_S, CMD);
        waitFor("saved Java", () -> Files.readString(source).contains("KEYBOARD_RUN="));
        action("run");
        waitFor("project trust", () -> dialog("Project Trust") != null);
        button("OK");
        waitFor("native program result", () -> frame.console().text().contains("KEYBOARD_RUN=42") && !frame.isRunning());
        check(true, "A: project → file → edit → save → trust → native Run uses only keys");
        check(!Files.exists(project.resolve("target")), "A: native Run does not invoke Maven lifecycle");
        screenshot("a-project-run.png");
    }

    private static void workflowC() throws Exception {
        TestEngine.Report before = edt(() -> frame.tests().lastReport());
        action("tests.all");
        waitFor("initial JUnit report", () -> frame.tests().lastReport() != before && !frame.tests().busy());
        check(edt(() -> frame.tests().lastReport().passed() == 2), "C: two real JUnit tests pass");
        closeResults();
        action("tests.onSave");
        check(edt(() -> settings.autoTestOnSave), "C: Auto Test on Save enabled with keyboard");
        find("20 + 22"); paste("20 + 21");
        TestEngine.Report passing = edt(() -> frame.tests().lastReport());
        key(KeyEvent.VK_S, CMD);
        waitFor("Auto Test failure", () -> frame.tests().lastReport() != passing && !frame.tests().busy()
                && frame.tests().lastReport().failed() == 1);
        action("navigation.testFailure");
        waitFor("test failure source", () -> frame.currentFile() != null && frame.currentFile().getFileName().toString().equals("KeyboardMainTest.java"));
        check(true, "C: failing Auto Test result navigates to test source");
        open(source); find("20 + 21"); paste("20 + 22");
        TestEngine.Report failed = edt(() -> frame.tests().lastReport());
        key(KeyEvent.VK_S, CMD);
        waitFor("Auto Test corrected", () -> frame.tests().lastReport() != failed && !frame.tests().busy()
                && frame.tests().lastReport().passed() == 2);
        check(true, "C: edit/save automatically compiles and reruns the latest corrected source");
        screenshot("c-auto-test.png");
        action("tests.onSave");
    }

    private static void workflowB() throws Exception {
        find("20 + 22");
        String before = edt(() -> frame.editor().getText());
        action("refactor.extract-variable");
        waitFor("Extract Variable parameters", () -> dialog("Extract Variable") != null);
        focusWhere(c -> c instanceof JTextField, "variable name"); replace("total"); button("OK");
        waitFor("Extract Variable preview", () -> preview() != null);
        check(edt(() -> contents(preview()).contains("total")), "B: keyboard opens real RefactorJ change preview");
        focusWhere(c -> c instanceof JTextArea, "refactoring preview text");
        key(KeyEvent.VK_HOME, CMD); key(KeyEvent.VK_END, CMD);
        button("Apply changes");
        waitFor("Extract Variable apply", () -> preview() == null && !frame.refactoring().busy() && frame.editor().getText().contains("total"));
        key(KeyEvent.VK_Z, CMD);
        waitFor("workspace refactoring undo", () -> !frame.refactoring().busy() && frame.editor().getText().equals(before));
        check(true, "B: expression → Extract Variable → inspect preview → Apply → one Undo");
        key(KeyEvent.VK_S, CMD);
    }

    private static void workflowH() throws Exception {
        find("public static String second()");
        key(KeyEvent.VK_LEFT,0);
        action("editor.selectCurrentMethod");
        waitFor("complete method selection", () -> frame.editor().getSelectedText().contains("return \"second\";"));
        String before = edt(() -> frame.editor().getText());
        action("editor.moveStatementUp");
        waitFor("method movement", () -> dialog("Java change preview") != null || !frame.editor().getText().equals(before));
        if (edt(() -> dialog("Java change preview") != null)) {
            check(edt(() -> contents(dialog("Java change preview")).contains("second")), "H: semantic movement warning preview is keyboard reachable");
            button("Apply");
        }
        waitFor("method move apply", () -> preview() == null && !frame.refactoring().busy()
                && frame.editor().getText().indexOf("String second") < frame.editor().getText().indexOf("String first"));
        key(KeyEvent.VK_S, CMD);
        TestEngine.Report previous = edt(() -> frame.tests().lastReport());
        action("tests.all");
        waitFor("tests after method move", () -> frame.tests().lastReport() != previous && !frame.tests().busy());
        check(edt(() -> frame.tests().lastReport().passed() == 2 && frame.tests().lastReport().successful()),
                "H: keyboard method move recompiles and preserves both JUnit results");
        closeResults();
        check(!before.equals(edt(() -> frame.editor().getText())), "H: actual method source order changed");
        screenshot("h-method-move.png");
    }

    private static void workflowD() throws Exception {
        // The separate input fixture is created here through New/Paste/Save As, using the keyboard.
        action("view.focusConsole");
        action("new");
        waitFor("New from console focuses source", () -> frame.editor().isFocusOwner());
        check(true, "D: New from console returns typing focus to the source editor");
        String input = """
                public class KeyboardInput {
                    public static void main(String[] args) {
                        System.out.println("ARG=" + args[0]);
                        System.out.println("INPUT>");
                        System.out.println("READ=" + new java.util.Scanner(System.in).nextLine());
                    }
                }
                """;
        replace(input); action("saveAs");
        Path file = project.resolve("src/main/java/KeyboardInput.java"); chooseFile(file);
        waitFor("save input program", () -> file.equals(frame.currentFile()) && Files.exists(file));
        action("run.configuration");
        waitFor("Run Configuration", () -> dialog("Run Configuration") != null);
        focusWhere(c -> name(c).equals("Program arguments, one per line"), "program arguments");
        replace("keyboard-argument");
        key(KeyEvent.VK_TAB, 0);
        check(edt(() -> !name(focus()).equals("Program arguments, one per line")), "D: Tab leaves multiline input instead of inserting a tab");
        focusWhere(c -> name(c).equals("Initial standard input"), "stdin after any intervening scroll controls");
        check(true, "D: Tab reaches the next multiline run configuration field");
        for (int i=0;i<25 && !edt(() -> name(focus()).equals("Program arguments, one per line"));i++)
            key(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
        check(edt(() -> name(focus()).equals("Program arguments, one per line") && ((JTextComponent)focus()).getText().equals("keyboard-argument")),
                "D: Shift+Tab reverses dialog traversal without modifying input");
        button("OK"); action("run");
        waitFor("Scanner waits for input", () -> frame.console().text().contains("INPUT>") && frame.isRunning());
        action("view.focusConsole"); paste("keyboard-input"); key(KeyEvent.VK_ENTER, 0);
        waitFor("Scanner result", () -> frame.console().text().contains("READ=keyboard-input") && !frame.isRunning());
        check(edt(() -> frame.console().text().contains("ARG=keyboard-argument")), "D: run arguments and interactive Scanner input arrive correctly");
        action("view.focusConsole"); key(KeyEvent.VK_A, CMD); key(KeyEvent.VK_C, CMD);
        check(clipboard().contains("READ=keyboard-input"), "D: console output copies via Command+C");
        key(KeyEvent.VK_ESCAPE, 0); waitFor("console returns source focus", () -> frame.editor().isFocusOwner());
        screenshot("d-console-input.png");
    }

    private static void workflowF() throws Exception {
        action("theme.more");
        waitFor("theme browser", () -> dialog("More themes") != null);
        focusWhere(c -> name(c).equals("Search local themes"), "theme search");
        replace("Modern Light");
        waitFor("theme search matches", () -> findComponent(dialog("More themes"), c -> c instanceof JButton b && b.getText().equals("Apply theme") && b.isEnabled()) != null);
        key(KeyEvent.VK_DOWN, 0); button("Apply theme");
        waitFor("theme applied", () -> dialog("More themes") == null && settings.theme.equals("light"));
        waitFor("theme returns editor", () -> frame.editor().isFocusOwner());
        check(true, "F: theme search → preview → apply → source focus entirely by keyboard");
        screenshot("f-theme.png");
    }

    private static void workflowE() throws Exception {
        action("keymap"); waitFor("Keymap Settings", () -> dialog("Keymap") != null);
        focusWhere(c -> name(c).equals("Search commands or shortcuts"), "keymap command search");
        replace("editor.duplicateLine"); key(KeyEvent.VK_DOWN, 0); key(KeyEvent.VK_ENTER, 0);
        waitFor("shortcut recorder", () -> dialog("Record shortcut") != null);
        key(KeyEvent.VK_S, CMD); key(KeyEvent.VK_ENTER, 0);
        waitFor("binding context", () -> dialog("Binding context") != null); button("OK");
        waitFor("conflict explanation", () -> dialog("Resolve shortcut conflict") != null);
        check(edt(() -> contents(dialog("Resolve shortcut conflict")).contains("Save")), "E: conflicting shortcut names the affected Save action");
        button("OK");
        waitFor("binding saved", () -> dialog("Resolve shortcut conflict") == null && dialog("Keymap") != null);
        key(KeyEvent.VK_ESCAPE, 0); waitFor("keymap returns editor", () -> frame.editor().isFocusOwner());
        check(edt(() -> frame.commandKeymaps().bindings("save").isEmpty()), "E: explicit conflict resolution removes only conflicting Save binding");
        String before = edt(() -> frame.editor().getText());
        key(KeyEvent.VK_HOME, CMD); key(KeyEvent.VK_S, CMD);
        waitFor("custom shortcut used", () -> !frame.editor().getText().equals(before));
        check(edt(() -> frame.editor().getText().startsWith("public class KeyboardInput {\npublic class KeyboardInput {")),
                "E: user-defined Command+S performs Duplicate Line");
        key(KeyEvent.VK_Z, CMD);
        waitFor("custom shortcut undo", () -> frame.editor().getText().equals(before));
        waitFor("custom keymap persistence", () -> {
            Path path = Settings.directory().resolve("commandj-keymap.json");
            if (!Files.isRegularFile(path)) return false;
            var restored = new dev.turboj.commandj.keymap.KeymapManager();
            dev.turboj.commandj.keymap.KeymapJson.read(restored,Files.readString(path));
            return restored.bindings("save").isEmpty() && restored.commandBindings(frame.commandRegistry(),"duplicateLine")
                    .stream().anyMatch(b -> b.sequence().external().equals("META+S"));
        });
        check(true,"E: persisted settings reload the custom binding and explicit conflict resolution");
        action("help"); waitFor("generated reference", () -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d instanceof ShortcutReferenceDialog));
        check(true, "E: generated Shortcut Reference is keyboard reachable after customization");
        key(KeyEvent.VK_ESCAPE, 0); screenshot("e-custom-shortcut.png");
    }

    private static Path fixture(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("src/main/java/demo"));
        Files.createDirectories(directory.resolve("src/test/java/demo"));
        Files.writeString(directory.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>dev.turboj.keyboard</groupId><artifactId>keyboard-workflows</artifactId><version>1.0.0</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>
                </project>
                """);
        Files.writeString(directory.resolve("src/main/java/demo/KeyboardMain.java"), MAIN);
        Files.writeString(directory.resolve("src/test/java/demo/KeyboardMainTest.java"), """
                package demo;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class KeyboardMainTest {
                    @Test void answerIs42() { assertEquals(42, KeyboardMain.answer()); }
                    @Test void firstIsStable() { assertEquals("first", KeyboardMain.first()); }
                }
                """);
        return directory;
    }

    private static void action(String id) throws Exception {
        if (edt(() -> dialog("Find Action") != null)) key(KeyEvent.VK_ESCAPE, 0);
        key(KeyEvent.VK_A, CMD | KeyEvent.SHIFT_DOWN_MASK);
        waitFor("Find Action for " + id, () -> dialog("Find Action") instanceof CommandPaletteDialog);
        focusWhere(c -> name(c).equals("Search commands"), "command search"); replace(id);
        waitFor("command match " + id, () -> {
            var palette = (CommandPaletteDialog) dialog("Find Action");
            for (int i=0;i<palette.results().getModel().getSize();i++) if (palette.results().getModel().getElementAt(i).command().id()
                    .equals(frame.commandRegistry().canonicalId(id))) return true;
            return false;
        });
        int target = edt(() -> {
            var list = ((CommandPaletteDialog) dialog("Find Action")).results();
            for (int i=0;i<list.getModel().getSize();i++) if (list.getModel().getElementAt(i).command().id().equals(frame.commandRegistry().canonicalId(id))) return i;
            throw new AssertionError(id);
        });
        int selected = edt(() -> ((CommandPaletteDialog) dialog("Find Action")).results().getSelectedIndex());
        for (int i=selected;i<target;i++) key(KeyEvent.VK_DOWN,0);
        for (int i=selected;i>target;i--) key(KeyEvent.VK_UP,0);
        check(edt(() -> ((CommandPaletteDialog) dialog("Find Action")).results().getSelectedValue().availability().enabled()), "command available: " + id);
        key(KeyEvent.VK_ENTER, 0);
        waitFor("Find Action closed " + id, () -> dialog("Find Action") == null);
    }

    private static void open(Path path) throws Exception { action("open"); chooseFile(path); waitFor("open source", () -> path.equals(frame.currentFile())); }
    private static void chooseFile(Path path) throws Exception {
        waitFor("file chooser", () -> OverlayHost.dialogs(frame).stream().anyMatch(d -> findComponent(d, c -> name(c).equals("File name")) != null));
        focusWhere(c -> name(c).equals("File name"), "file chooser name"); replace(path.toString()); key(KeyEvent.VK_ENTER, 0);
        waitFor("file chooser closed", () -> OverlayHost.dialogs(frame).stream().noneMatch(d -> findComponent(d, c -> name(c).equals("File name")) != null));
    }
    private static void find(String text) throws Exception {
        key(KeyEvent.VK_F, CMD); waitFor("Find text", () -> dialog("Input") != null);
        focusWhere(c -> c instanceof JTextField, "Find field"); replace(text); key(KeyEvent.VK_ENTER, 0);
        waitFor("Find closes", () -> dialog("Input") == null && frame.editor().isFocusOwner());
        check(edt(() -> frame.editor().getSelectedText().equals(text)), "Find selects exact source: " + text);
    }
    private static void closeResults() throws Exception { waitFor("JUnit results", () -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.getTitle().startsWith("TurboTest"))); key(KeyEvent.VK_ESCAPE,0); waitFor("results close", () -> !OverlayHost.active(frame)); }
    private static void button(String label) throws Exception { focusWhere(c -> c instanceof JButton b && b.getText().equals(label), "button " + label); key(KeyEvent.VK_ENTER,0); }
    private static void focusWhere(Predicate<Component> predicate, String reason) throws Exception {
        for (int i=0; i<55; i++) { if (edt(() -> predicate.test(focus()))) return; key(KeyEvent.VK_TAB,0); }
        throw new AssertionError("Cannot reach " + reason + " with Tab; focus=" + edt(() -> describe(focus())));
    }
    private static void replace(String text) throws Exception { key(KeyEvent.VK_A,CMD); paste(text); }
    private static void paste(String text) throws Exception { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null); key(KeyEvent.VK_V,CMD); }
    private static String clipboard() throws Exception { return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); }
    private static void key(int code,int mods) throws Exception {
        if (robot != null) {
            if (!edt(() -> frame.isActive() && focus() != null && SwingUtilities.isDescendingFrom(focus(),frame)))
                throw new AssertionError("TurboJ lost foreground focus; refusing to send OS keys to another application");
            int[] masks={CMD,KeyEvent.CTRL_DOWN_MASK,KeyEvent.ALT_DOWN_MASK,KeyEvent.SHIFT_DOWN_MASK};
            int[] codes={KeyEvent.VK_META,KeyEvent.VK_CONTROL,KeyEvent.VK_ALT,KeyEvent.VK_SHIFT};
            for(int i=0;i<masks.length;i++) if((mods&masks[i])!=0) robot.keyPress(codes[i]);
            robot.keyPress(code); robot.keyRelease(code);
            for(int i=masks.length-1;i>=0;i--) if((mods&masks[i])!=0) robot.keyRelease(codes[i]);
            robot.waitForIdle();
        } else edt(() -> {
            Component owner=focus(); if(owner==null)throw new AssertionError("No focus owner for key " + code);
            var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager(); long now=System.currentTimeMillis();
            manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,now,mods,code,KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,now+1,mods,code,KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(70);
    }
    private static Component focus(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();}
    private static String name(Component c){return c instanceof JComponent j && j.getAccessibleContext()!=null ? java.util.Objects.toString(j.getAccessibleContext().getAccessibleName(),"") : "";}
    private static String describe(Component c){return c==null ? "none" : c.getClass().getSimpleName()+"["+name(c)+"]";}
    private static EmbeddedDialog dialog(String title){return OverlayHost.dialogs(frame).stream().filter(d->d.getTitle().equals(title)).findFirst().orElse(null);}
    private static EmbeddedDialog preview(){return OverlayHost.dialogs(frame).stream().filter(d->d.getTitle().startsWith("RefactorJ —")).findFirst().orElse(null);}
    private static Component findComponent(Container c,Predicate<Component> predicate){for(Component child:c.getComponents()){if(predicate.test(child))return child;if(child instanceof Container nested){Component match=findComponent(nested,predicate);if(match!=null)return match;}}return null;}
    private static String contents(Container c){StringBuilder b=new StringBuilder();for(Component child:c.getComponents()){if(child instanceof JTextComponent text)b.append(text.getText()).append('\n');if(child instanceof JLabel label)b.append(label.getText()).append('\n');if(child instanceof Container nested)b.append(contents(nested));}return b.toString();}
    private static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);report.append("PASS ").append(label).append('\n');System.out.println("PASS "+label);}
    private static void waitFor(String label,Callable<Boolean> ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);while(System.nanoTime()<end){if(edt(ready))return;Thread.sleep(50);}throw new AssertionError("Timed out: "+label);}
    private static <T>T edt(Callable<T> task)throws Exception{if(SwingUtilities.isEventDispatchThread())return task.call();var future=new FutureTask<>(task);SwingUtilities.invokeLater(future);return future.get(100,TimeUnit.SECONDS);}
    private static void screenshot(String file)throws Exception{edt(()->{var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();frame.paint(graphics);graphics.dispose();javax.imageio.ImageIO.write(image,"png",output.resolve(file).toFile());return null;});}
}
