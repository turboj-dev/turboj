package dev.turboj.app;

import dev.turboj.commandj.keymap.KeyStrokeSpec;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real CLI/JUnit/JaCoCo execution with real-window Swing keyboard dispatch. */
public final class CherryNativeSmoke {
    private static TurboFrame frame;
    private static final StringBuilder report = new StringBuilder();
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the Cherry schema-v1 Java CLI jar");
        Path output = Path.of("app/target/cherry-native-smoke").toAbsolutePath(); Files.createDirectories(output);
        Path project = Files.createTempDirectory(output,"project-");
        Path fixture = Path.of("integration-tests/fixtures/cherry-native").toAbsolutePath();
        try (var files = Files.walk(fixture)) {
            for (Path path : files.toList()) {
                Path target = project.resolve(fixture.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path,target);
            }
        }
        git(project,"init","-q"); git(project,"add",".");
        git(project,"-c","user.name=TurboJ fixture","-c","user.email=fixture@example.test","commit","-qm","baseline");
        Path source = project.resolve("src/main/java/demo/Price.java"); String original = Files.readString(source);
        Path config = Files.createTempDirectory(output,"settings-");
        System.setProperty("turboj.config",config.toString()); System.setProperty("apple.laf.useScreenMenuBar","false");
        report.append("Started: ").append(java.time.Instant.now()).append("\nJDK: ").append(System.getProperty("java.version"))
                .append("\nOS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append("\nProject: ").append(project).append("\nCLI: ").append(args[0]).append('\n');
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Settings settings = new Settings(); settings.keymapProfile = "turboj-classic"; settings.cherryTestExecutable = args[0];
            var app = new TurboFrame(settings,Map.of()); app.setVisible(true); app.toFront(); app.openPath(source); return app;
        });
        try {
            waitFor(() -> source.equals(frame.currentFile()) && frame.editor().lineIntelligence() != null
                    && frame.editor().lineIntelligence().testingActive(),"Cherry configuration and source detected");
            edt(() -> { frame.editor().requestFocusInWindow(); return null; });
            waitFor(() -> frame.editor().isFocusOwner(),"editor focus");
            palette("run");
            waitFor(() -> completed(),"normal Run starts and completes real Cherry workflow");
            Run passing = edt(() -> frame.tests().cherry().last());
            check(passing.state() == RunState.COMPLETED && passing.tests().size() == 2
                    && passing.tests().stream().allMatch(t -> t.status() == TestStatus.PASSED),"two genuine JUnit tests pass");
            waitFor(() -> frame.editor().lineIntelligence().lines().values().stream().anyMatch(l -> l.coverage().state() == CoverageState.PARTIAL),"actual partial counters reach editor snapshot");
            check(edt(() -> frame.editor().lineIntelligence().lines().values().stream().allMatch(l -> l.tests().isEmpty())),"aggregate coverage invents no production-line test association");
            check(edt(() -> frame.editor().lineIntelligence().lines().values().stream().filter(l -> l.coverage().availability() == Availability.AVAILABLE)
                    .allMatch(l -> l.coverage().freshness() == Freshness.UNKNOWN)),"unverified executed-bytecode identity remains explicitly unknown");
            check(passing.tests().stream().allMatch(t -> t.associations().stream().anyMatch(a -> a.kind() == Attribution.TEST_DECLARATION)),"real JUnit identities resolve to exact hashed test declarations");
            inspectKeyboard(); screenshot(output.resolve("coverage-inspection.png")); key(KeyEvent.VK_ESCAPE,0);
            waitFor(() -> frame.editor().isFocusOwner(),"Escape restores source focus");
            int change = original.indexOf(" - ") + 1;
            edt(() -> { frame.editor().setSelection(change,change + 1); return null; }); type("+");
            waitFor(() -> frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().lines().values().stream()
                    .noneMatch(l -> l.coverage().availability() == Availability.AVAILABLE),"unsaved change withholds old line coverage");
            check(Files.readString(source).equals(original),"unsaved edit does not rewrite disk");
            palette("save"); waitFor(() -> Files.readString(source).contains("cents + cents"),"save modified source");
            String oldRun = passing.id(); palette("run");
            waitFor(() -> completed() && !frame.tests().cherry().last().id().equals(oldRun),"failing real run completes");
            Run failed = edt(() -> frame.tests().cherry().last());
            check(failed.state() == RunState.FAILED && failed.tests().stream().filter(t -> t.status() == TestStatus.FAILED).count() == 1,
                    "changed calculation yields exactly one real assertion failure");
            check(edt(() -> frame.editor().lineIntelligence().lines().values().stream().noneMatch(l -> l.testStatus() == TestStatus.FAILED)),
                    "failed suite does not mark uncovered production code red");
            palette("cherry.results"); waitFor(() -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.isVisible() && d.getTitle().startsWith("Cherry Test")),"native results visible");
            screenshot(output.resolve("test-failure.png")); key(KeyEvent.VK_ESCAPE,0);
            waitFor(() -> frame.editor().isFocusOwner(),"native results Escape restores focus");
            palette("undo");
            waitFor(() -> frame.editor().getText().equals(original),"deferred palette Undo applies");
            check(edt(() -> frame.editor().getText().equals(original)),"keyboard Undo restores the original source after a failed run");
            palette("save"); waitFor(() -> Files.readString(source).equals(original),"restore source");
            palette("cherry.run"); waitFor(() -> completed() && !frame.tests().cherry().last().id().equals(failed.id()),"explicit Cherry rerun completes");
            check(edt(() -> frame.tests().cherry().last().state() == RunState.COMPLETED),"corrected source returns to two passing tests");
            waitFor(() -> {
                Snapshot visible = frame.editor().lineIntelligence();
                return visible != null && visible.source().sourceHash().equals(dev.turboj.testing.intelligence.LineIntelligence.sha256(original))
                        && visible.lines().values().stream().anyMatch(l -> l.coverage().state() == CoverageState.PARTIAL
                        && l.coverage().runId().equals(frame.tests().cherry().last().id()));
            },"final source and measured counters are installed in the native gutter");
            check(edt(() -> frame.editor().lineIntelligence().lines().values().stream().noneMatch(Line::added)),"Git HEAD-to-buffer returns unchanged after correction");
            mouseLanes(); screenshot(output.resolve("editor.png"));
            report.append("Final source SHA256: ").append(dev.turboj.testing.intelligence.LineIntelligence.sha256(Files.readString(source))).append('\n');
            report.append("PASS. Actual Swing events, fixture setup/caret placement programmatic; no physical keyboard/layout claim.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append('\n');
            report.append("Console:\n").append(edt(() -> frame.console().text())); throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"),report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }
    private static boolean completed() {
        Run run = frame.tests().cherry().last();
        return !frame.tests().cherry().busy() && run != null && (run.state() == RunState.COMPLETED || run.state() == RunState.FAILED || run.state() == RunState.INCOMPLETE);
    }
    private static void inspectKeyboard() throws Exception {
        edt(() -> { frame.editor().gotoLine(7); return null; }); int caret = edt(() -> frame.editor().caretOffset());
        palette("editor.inspectLineIntelligence"); waitFor(() -> focus() instanceof JList<?>,"inspection sections focus");
        key(KeyEvent.VK_DOWN,0); key(KeyEvent.VK_DOWN,0); key(KeyEvent.VK_ENTER,0);
        waitFor(() -> focus() instanceof JTextArea,"Enter focuses independently selected coverage details");
        check(caret == edt(() -> frame.editor().caretOffset()),"keyboard inspection preserves caret");
    }
    private static void mouseLanes() throws Exception {
        for (Lane lane : Lane.values()) {
            int caret = edt(() -> frame.editor().caretOffset());
            edt(() -> {
                Rectangle box = frame.editor().lineIntelligenceBounds(7,lane);
                frame.editor().dispatchEvent(new MouseEvent(frame.editor(),MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                        box.x + box.width / 2,box.y + box.height / 2,1,false,MouseEvent.BUTTON1)); return null;
            });
            waitFor(() -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.isVisible() && d.getTitle().startsWith("Line Intelligence")),"mouse " + lane + " details");
            check(caret == edt(() -> frame.editor().caretOffset()),lane + " hit preserves caret"); key(KeyEvent.VK_ESCAPE,0);
            waitFor(() -> frame.editor().isFocusOwner(),"lane inspection closed");
        }
    }
    private static void screenshot(Path path) throws Exception {
        BufferedImage image = edt(() -> { var buffer = new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffer.createGraphics(); frame.printAll(graphics); graphics.dispose(); return buffer; });
        ImageIO.write(image,"png",path.toFile());
    }
    private static void palette(String id) throws Exception {
        if (edt(() -> focus() == null || !frame.isActive())) {
            report.append("Driver reactivated native window before ").append(id).append(" (host focus was outside TurboJ).\n");
            edt(() -> {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))
                    Desktop.getDesktop().requestForeground(true);
                frame.toFront(); frame.requestFocus(); frame.editor().requestFocusInWindow(); return null;
            });
            waitFor(() -> frame.editor().isFocusOwner(),"native window reactivation");
        }
        for (KeyStrokeSpec stroke : edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(),"palette").getFirst().sequence().strokes())) {
            int modifiers = 0; for (var modifier : stroke.modifiers()) modifiers |= switch (modifier) {
                case META -> KeyEvent.META_DOWN_MASK; case ALT -> KeyEvent.ALT_DOWN_MASK; case CTRL -> KeyEvent.CTRL_DOWN_MASK; case SHIFT -> KeyEvent.SHIFT_DOWN_MASK;
            };
            key(KeyEvent.class.getField("VK_" + stroke.key()).getInt(null),modifiers);
        }
        waitFor(() -> palette() != null && palette().searchField().isFocusOwner(),"palette focus");
        type(edt(() -> frame.commandRegistry().find(id).orElseThrow().title()));
        waitFor(() -> palette().results().getModel().getSize() > 0,"palette results");
        int index = edt(() -> {
            var values = palette().results().getModel();
            for (int i = 0; i < values.getSize(); i++) if (values.getElementAt(i).command().id().equals(id)) return i;
            throw new AssertionError("Command not found " + id);
        });
        int selected = edt(() -> palette().results().getSelectedIndex());
        for (int i = selected; i < index; i++) key(KeyEvent.VK_DOWN,0);
        for (int i = selected; i > index; i--) key(KeyEvent.VK_UP,0);
        check(edt(() -> palette().results().getSelectedValue().command().id().equals(id)),"palette selected " + id);
        key(KeyEvent.VK_ENTER,0); waitFor(() -> palette() == null,"palette closes");
    }
    private static CommandPaletteDialog palette() { return OverlayHost.dialogs(frame).stream().filter(d -> d.isVisible() && d instanceof CommandPaletteDialog)
            .map(d -> (CommandPaletteDialog)d).findFirst().orElse(null); }
    private static void type(String text) throws Exception {
        for (char c : text.toCharArray()) edt(() -> {
            int code = KeyEvent.getExtendedKeyCodeForChar(c); int modifiers = Character.isUpperCase(c) ? KeyEvent.SHIFT_DOWN_MASK : 0;
            dispatch(KeyEvent.KEY_PRESSED,code,c,modifiers); dispatch(KeyEvent.KEY_TYPED,KeyEvent.VK_UNDEFINED,c,modifiers);
            dispatch(KeyEvent.KEY_RELEASED,code,c,modifiers); return null;
        });
    }
    private static void key(int code,int modifiers) throws Exception {
        edt(() -> { dispatch(KeyEvent.KEY_PRESSED,code,KeyEvent.CHAR_UNDEFINED,modifiers); dispatch(KeyEvent.KEY_RELEASED,code,KeyEvent.CHAR_UNDEFINED,modifiers); return null; });
    }
    private static void dispatch(int kind,int code,char c,int modifiers) {
        if (focus() == null) throw new AssertionError("Focus lost");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(focus(),kind,System.currentTimeMillis(),modifiers,code,c));
    }
    private static Component focus() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }
    private static void check(boolean condition,String label) { if (!condition) throw new AssertionError(label); report.append("PASS ").append(label).append('\n'); System.out.println("PASS " + label); }
    private static void waitFor(Callable<Boolean> condition,String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(25); }
        throw new AssertionError("Timeout: " + label + "; console: " + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> action) throws Exception { var future = new FutureTask<T>(action); SwingUtilities.invokeAndWait(future); return future.get(); }
    private static void git(Path root,String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git","-C",root.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String log = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(log);
    }
}
