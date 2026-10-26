package dev.turboj.app;

import dev.turboj.commandj.swing.CommandPaletteDialog;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaQuickActions;
import dev.turboj.turbotest.TestEngine;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Real visible Swing first-launch workflow. Project/source creation and application mutations
 * occur only through keyboard events. Filesystem setup is limited to an empty isolated home,
 * preferences and Maven-cache configuration. Read-only inspection records/asserts real effects.
 * This automated acceptance is not a human walkthrough or physical-layout certification. */
public final class FirstLaunchKeyboardSmoke {
    private static final int CMD = KeyEvent.META_DOWN_MASK;
    private static final int SHIFT = KeyEvent.SHIFT_DOWN_MASK;
    private static final StringBuilder report = new StringBuilder(), events = new StringBuilder();
    private static TurboFrame frame;
    private static Path output, session, workspace, project;
    private static Robot robot;
    private static long started;
    private static int eventNumber;

    public static void main(String[] args) throws Exception {
        output = Path.of(System.getProperty("firstlaunch.output", "app/target/first-launch-keyboard-smoke")).toAbsolutePath();
        Files.createDirectories(output);
        session = Files.createTempDirectory("turboj-first-launch-").toRealPath();
        Path home = Files.createDirectories(session.resolve("home/.m2")).getParent();
        Path repository = Path.of(".cache/m2").toAbsolutePath();
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><offline>true</offline><localRepository>"
                + repository + "</localRepository></settings>");
        System.setProperty("user.home", home.toString());
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        workspace = session.resolve("workspace");
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previousClipboard = clipboard.getContents(null);
        if (List.of(args).contains("--robot")) { robot = new Robot(); robot.setAutoDelay(35); }
        started = System.nanoTime();
        report.append("Started: ").append(Instant.now()).append("\nMode: ")
                .append(robot == null ? "Swing KeyboardFocusManager key events" : "AWT Robot OS key events")
                .append("; no mouse events; no direct command callbacks; no programmatic project fixture.\n")
                .append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(' ').append(System.getProperty("os.arch")).append("; Java ").append(Runtime.version()).append('\n')
                .append("Isolated session: ").append(session).append("\nMaven cache: ").append(repository)
                .append(" (offline; warm dependencies)\n");
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(artifact)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(artifact)) {
                byte[] chunk = new byte[65536];
                for (int count; (count = stream.read(chunk)) >= 0;) digest.update(chunk, 0, count);
            }
            report.append("Application artifact: ").append(artifact.getFileName()).append("\nArtifact SHA-256: ")
                    .append(HexFormat.of().formatHex(digest.digest())).append('\n');
        }
        events.append("event\telapsed_ms\taction\tfocus_before\tfocus_after\n");
        try {
            // Actual application bootstrap is deliberate: startup launcher must not be bypassed.
            TurboJ.main(new String[0]);
            waitFor("native frame", () -> {
                for (Window window : Window.getWindows()) if (window instanceof TurboFrame candidate && candidate.isShowing()) {
                    frame = candidate; return true;
                }
                return false;
            });
            workspace();
            action("keymap.profile.eclipse-macos");
            check(edt(() -> frame.commandKeymaps().selectedId().equals("eclipse-macos")), "Eclipse profile selected through CommandJ palette");
            flow(false);
            flow(true);
            snapshot("complete");
            report.append("PASS Java and Maven first-launch-to-green workflows.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append('\n');
            if (frame != null) {
                report.append("Focus: ").append(edt(() -> describe(focus())))
                        .append("\nDialogs: ").append(edt(() -> OverlayHost.dialogs(frame).stream().map(EmbeddedDialog::getTitle).toList()))
                        .append("\nConsole:\n").append(edt(() -> frame.console().text())).append('\n');
                snapshot("failure");
            }
            throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            Files.writeString(output.resolve("keyboard-events.tsv"), events.toString());
            if (frame != null) edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (previousClipboard != null) clipboard.setContents(previousClipboard, null);
        }
    }

    private static void workspace() throws Exception {
        waitFor("first launch workspace chooser", () -> showing("first-launch-workspace-path") != null);
        check(!Files.exists(Settings.directory().resolve("config.json")), "clean profile starts at workspace selection");
        check(edt(() -> ((JTextField)showing("first-launch-workspace-path")).getText()
                .equals(Path.of(System.getProperty("user.home"), "Workspace").toString())), "workspace default is ~/Workspace");
        field("first-launch-workspace-path", workspace.toString());
        focusNamed("first-launch-workspace-remember"); key(KeyEvent.VK_SPACE, 0);
        pressNamed("first-launch-workspace-launch");
        waitFor("workspace accepted", () -> showing("first-launch-workspace-path") == null);
        dismissWelcome();
        waitFor("source focus after launch", () -> frame.editor().isFocusOwner());
        snapshot("workspace");
    }

    private static void flow(boolean maven) throws Exception {
        String chapter = maven ? "maven" : "java";
        report.append("\nWORKFLOW ").append(chapter.toUpperCase(Locale.ROOT)).append('\n');
        String projectName = maven ? "first-launch-maven" : "first-launch-java";
        project = workspace.resolve(projectName);
        check(!Files.exists(project), chapter + ": project does not exist before keyboard wizard");
        newProjectFromMenu(maven);
        waitFor("project wizard", () -> showing("first-launch-project-name") != null);
        field("first-launch-project-name", projectName);
        check(edt(() -> ((JTextField)showing("first-launch-project-location")).getText().equals(project.toString())),
                chapter + ": location follows chosen workspace/project name");
        snapshot(chapter + "-project-form");
        pressNamed("first-launch-project-create");
        waitFor("project creation", () -> Files.isDirectory(project.resolve("src/main/java")) && showing("first-launch-project-name") == null);
        if (maven) waitMaven();
        if (edt(() -> showing("first-launch-main-name") == null)) action("file.newJavaClass");
        waitFor("main class wizard", () -> showing("first-launch-main-name") != null);
        field("first-launch-main-package", "tutorial");
        field("first-launch-main-name", "HelloWorld");
        check(edt(() -> ((JCheckBox)showing("first-launch-main-method")).isSelected()), chapter + ": conventional Java main stub selected");
        pressNamed("first-launch-main-create");
        Path hello = project.resolve("src/main/java/tutorial/HelloWorld.java");
        waitFor("main source opened", () -> hello.equals(frame.currentFile()) && frame.editor().isFocusOwner());
        check(Files.isRegularFile(hello), chapter + ": real HelloWorld.java created by keyboard wizard");
        check(edt(() -> frame.editor().getText().contains("public static void main(String[] args)")), chapter + ": generated main signature is valid");
        action("compile");
        waitFor("HelloWorld compilation", () -> frame.currentResult() != null && frame.currentResult().success());
        eclipseRun();
        handleLaunchDialogs();
        waitFor("HelloWorld actual console output", () -> !frame.isRunning()
                && frame.console().text().contains("\nHello World!\n") && frame.console().text().contains("SUCCESS · exit 0"));
        check(true, chapter + ": generated main compiled and ran through native application");
        snapshot(chapter + "-hello");

        // Unsaved text is entered through Paste; no test helper writes source files.
        replace("""
                package tutorial;
                public class HelloWorld {
                    public static void main(String[] args) {
                        Calculator calculator = new Calculator();
                        System.out.println("Hello World!");
                    }
                }
                """);
        find("Calculator");
        var originalSelection = edt(() -> frame.editor().captureLocation());
        String beforeInspection = edt(() -> frame.editor().getText());
        key(KeyEvent.VK_1, CMD);
        boolean inspectionPending = edt(() -> quickList() == null);
        report.append("Inspection state at Escape: ").append(inspectionPending ? "pending" : "popup already visible").append('\n');
        key(KeyEvent.VK_ESCAPE, 0);
        Thread.sleep(2000);
        check(edt(() -> quickList() == null && frame.editor().isFocusOwner()
                && frame.editor().getText().equals(beforeInspection)
                && frame.editor().captureLocation().caret() == originalSelection.caret()
                && frame.editor().captureLocation().anchor() == originalSelection.anchor()),
                chapter + ": Escape cancels " + (inspectionPending ? "pending Cmd+1" : "visible Cmd+1 popup")
                        + "; no popup or source change during subsequent two-second observation");
        quick();
        waitFor("unresolved-type proposal", () -> proposalIndex("Create class", "Calculator") >= 0);
        check(edt(() -> proposals().stream().anyMatch(p -> p.action() == JavaQuickActions.Action.CREATE_CLASS
                && p.kind() == JavaQuickActions.Kind.FIX)), chapter + ": unresolved-type fix inferred from current unsaved source");
        String sourceBeforePopupKeys = edt(() -> frame.editor().getText());
        focusNamed("quick-actions-filter");
        String filterBefore = edt(() -> ((JTextField)showing("quick-actions-filter")).getText());
        key(KeyEvent.VK_D, CMD);
        check(edt(() -> frame.editor().getText().equals(sourceBeforePopupKeys)
                && ((JTextField)showing("quick-actions-filter")).getText().equals(filterBefore)),
                chapter + ": popup owns Cmd+D; hidden source and filter remain unchanged");
        focusNamed("quick-actions-proposals");
        focusNamed("quick-actions-explanation");
        key(KeyEvent.VK_TAB, SHIFT);
        check(edt(() -> focus() == quickList()), chapter + ": popup Shift+Tab returns from explanation to proposals");
        focusNamed("quick-actions-filter");
        check(edt(() -> frame.editor().getText().equals(sourceBeforePopupKeys)),
                chapter + ": Tab traverses popup controls without indenting hidden source");
        snapshot(chapter + "-class-proposals");
        key(KeyEvent.VK_3, CMD);
        waitFor("global palette supersedes quick fix", () -> dialog("Find Action") != null && quickList() == null);
        key(KeyEvent.VK_ESCAPE, 0);
        waitFor("palette dismiss restores editor", () -> dialog("Find Action") == null && frame.editor().isFocusOwner());
        check(edt(() -> quickList() == null && frame.editor().getText().equals(sourceBeforePopupKeys)),
                chapter + ": global palette supersedes contextual popup without stale focus or source changes");
        quick();
        key(KeyEvent.VK_1, CMD);
        waitFor("second invocation assists", () -> quickList() != null && proposals().stream().noneMatch(p -> p.kind() == JavaQuickActions.Kind.FIX));
        check(true, chapter + ": second Cmd+1 exposes assist-only proposals without applying a fix");
        key(KeyEvent.VK_ESCAPE, 0);
        waitFor("quick fix dismissal", () -> quickList() == null && frame.editor().isFocusOwner());
        check(edt(() -> frame.editor().captureLocation().caret() == originalSelection.caret()
                && frame.editor().captureLocation().anchor() == originalSelection.anchor()), chapter + ": Escape preserves editor selection");
        quick(); chooseProposal("Create class", "Calculator");
        Path calculator = project.resolve("src/main/java/tutorial/Calculator.java");
        waitFor("class created or class form", () -> calculator.equals(frame.currentFile()) || showing("first-launch-quick-class-name") != null);
        if (edt(() -> showing("first-launch-quick-class-name") != null)) {
            check(edt(() -> ((JTextField)showing("first-launch-quick-class-name")).getText().equals("Calculator")), chapter + ": missing class name inferred");
            check(edt(() -> ((JTextField)showing("first-launch-quick-class-package")).getText().equals("tutorial")), chapter + ": missing class package inferred");
            pressNamed("first-launch-quick-class-finish");
        }
        waitFor("generated class source focus", () -> calculator.equals(frame.currentFile()) && frame.editor().isFocusOwner());
        check(Files.isRegularFile(calculator) && Files.readString(calculator).contains("package tutorial;"), chapter + ": class generated in correct source root/package");
        snapshot(chapter + "-class-created");

        find("Calculator"); quick();
        waitFor("JUnit assist", () -> proposals().stream().anyMatch(p -> p.action() == JavaQuickActions.Action.CREATE_JUNIT_TEST));
        check(edt(() -> proposals().stream().noneMatch(p -> p.action() == JavaQuickActions.Action.CREATE_CLASS)), chapter + ": resolved declaration does not offer missing-class fix");
        chooseProposal("JUnit", "test");
        waitFor("JUnit wizard", () -> showing("first-launch-test-name") != null);
        check(edt(() -> ((JTextField)showing("first-launch-test-name")).getText().equals("CalculatorTest")), chapter + ": test name inferred from class declaration");
        check(edt(() -> ((JTextField)showing("first-launch-test-package")).getText().equals("tutorial")), chapter + ": test package inferred");
        check(edt(() -> ((JTextField)showing("first-launch-test-root")).getText().equals(project.resolve("src/test/java").toString())), chapter + ": test uses project test root");
        pressNamed("first-launch-test-create");
        waitFor("explicit JUnit dependency decision", () -> showing("first-launch-junit-add") != null);
        snapshot(chapter + "-junit-dependency");
        pressNamed("first-launch-junit-add");
        Path test = project.resolve("src/test/java/tutorial/CalculatorTest.java");
        waitFor("test source focus", () -> test.equals(frame.currentFile()) && frame.editor().isFocusOwner());
        if (maven) waitMaven();
        check(edt(() -> frame.editor().getText().contains("fail(\"Not yet implemented\")")), chapter + ": generated test intentionally fails, with compilable Jupiter source");
        if (maven) check(Files.readString(project.resolve("pom.xml")).contains("junit-jupiter"), chapter + ": explicit dependency confirmation updates actual pom.xml");
        TestEngine.Report beforeFailure = edt(() -> frame.tests().lastReport());
        eclipseRun(); handleLaunchDialogs();
        waitFor("initial failing JUnit result", () -> frame.tests().lastReport() != beforeFailure && !frame.tests().busy());
        TestEngine.Report failed = edt(() -> frame.tests().lastReport());
        check(failed.failed() == 1 && failed.passed() == 0, chapter + ": one real generated assertion fails after compilation");
        snapshot(chapter + "-assertion-failure");
        closeResults();

        replace("""
                package tutorial;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CalculatorTest {
                    @Test void testCalculatorAddition() {
                        assertEquals(42, new Calculator().add(20, 22));
                    }
                }
                """);
        key(KeyEvent.VK_S, CMD);
        waitFor("modified test saved", () -> Files.readString(test).contains("assertEquals(42, new Calculator().add(20, 22))"));
        if (maven) waitMaven();
        eclipseRun(); handleLaunchDialogs();
        waitFor("unresolved method rejects compilation", () -> !frame.tests().busy()
                && frame.currentResult() != null && !frame.currentResult().success()
                && frame.currentResult().version() == frame.editor().version()
                && frame.currentResult().diagnostics().stream().anyMatch(d -> d.message().contains("add")));
        check(edt(() -> frame.tests().lastReport() == failed), chapter + ": missing method is compilation failure, not a new failed assertion report");
        snapshot(chapter + "-compile-failure");
        closeResults();
        find("add"); quick();
        waitFor("missing-method proposal", () -> proposalIndex("Create method", "add") >= 0);
        check(edt(() -> proposals().stream().filter(p -> p.action() == JavaQuickActions.Action.CREATE_METHOD)
                .anyMatch(p -> p.title().contains("int") && p.title().contains("Calculator"))), chapter + ": method target and int argument types inferred semantically");
        snapshot(chapter + "-method-proposals");
        chooseProposal("Create method", "add");
        waitFor("generated method linked mode", () -> calculator.equals(frame.currentFile())
                && frame.editor().isFocusOwner() && frame.editor().templateActive());
        snapshot(chapter + "-method-template");
        editTemplate(chapter);
        key(KeyEvent.VK_Z, CMD);
        waitFor("linked rename undo", () -> !frame.refactoring().busy()
                && frame.editor().getText().contains("add(int left, int right)")
                && bufferText(test).contains(".add(20, 22)"));
        check(true, chapter + ": one Undo restores declaration and caller before linked method rename");
        snapshot(chapter + "-linked-rename-undo");
        key(KeyEvent.VK_Z, CMD | SHIFT);
        waitFor("linked rename redo", () -> !frame.refactoring().busy()
                && frame.editor().getText().contains("addition(int left, int right)")
                && bufferText(test).contains(".addition(20, 22)"));
        check(true, chapter + ": one Redo restores both files after linked method rename");
        key(KeyEvent.VK_S, CMD | SHIFT);
        check(edt(() -> !OverlayHost.active(frame)), chapter + ": Eclipse Cmd+Shift+S invokes Save All without a Save As dialog");
        waitFor("generated method saved", () -> Files.readString(calculator).contains("return left + right;"));
        check(Files.readString(calculator).contains("addition(int left, int right)")
                && Files.readString(test).contains(".addition(20, 22)"),
                chapter + ": linked method-name edit updates declaration and original caller across files");
        snapshot(chapter + "-method-implemented");
        // Return through native navigation history; no filesystem opener or callback is used.
        key(KeyEvent.VK_LEFT, CMD | KeyEvent.ALT_DOWN_MASK);
        waitFor("return to calling test", () -> test.equals(frame.currentFile()) && frame.editor().isFocusOwner());
        check(true, chapter + ": one Navigate Back reaches original calling test after linked edits");
        key(KeyEvent.VK_RIGHT, CMD | KeyEvent.ALT_DOWN_MASK);
        waitFor("return to implementation", () -> calculator.equals(frame.currentFile()) && frame.editor().isFocusOwner());
        final TestEngine.Report previous = edt(() -> frame.tests().lastReport());
        eclipseRun(); handleLaunchDialogs();
        waitFor("green JUnit result", () -> frame.tests().lastReport() != previous && !frame.tests().busy());
        check(edt(() -> frame.tests().lastReport().successful() && frame.tests().lastReport().passed() == 1), chapter + ": keyboard generated/implemented method passes the real 42 assertion");
        check(edt(() -> calculator.equals(frame.currentFile())), chapter + ": Eclipse Run reruns the prior JUnit launch while Calculator implementation stays active");
        snapshot(chapter + "-green");
        closeResults();
        check(Files.isRegularFile(hello) && Files.isRegularFile(calculator) && Files.isRegularFile(test), chapter + ": all three Java files exist after UI-only creation");
    }

    private static void editTemplate(String chapter) throws Exception {
        // Providers supply actual linked ranges; inspect selected text only to identify the stop.
        String first = edt(() -> frame.editor().getSelectedText());
        key(KeyEvent.VK_TAB, 0);
        String second = edt(() -> frame.editor().getSelectedText());
        key(KeyEvent.VK_TAB, SHIFT);
        check(edt(() -> frame.editor().templateActive() && frame.editor().getSelectedText().equals(first)), chapter + ": Shift+Tab returns to previous linked stop");
        report.append("Template first stops: ").append(first).append(" → ").append(second).append('\n');
        if (first.contains("return") || first.contains("TODO")) {
            key(KeyEvent.VK_TAB, 0);
            check(edt(() -> frame.editor().getSelectedText().matches("[A-Za-z_$][A-Za-z0-9_$]*")), chapter + ": Tab selects first generated parameter");
            String firstParameter = edt(() -> frame.editor().getSelectedText());
            key(KeyEvent.VK_TAB, 0);
            check(edt(() -> frame.editor().getSelectedText().matches("[A-Za-z_$][A-Za-z0-9_$]*")), chapter + ": Tab selects second generated parameter");
            String secondParameter = edt(() -> frame.editor().getSelectedText());
            key(KeyEvent.VK_TAB, SHIFT); key(KeyEvent.VK_TAB, SHIFT);
            paste("return " + firstParameter + " + " + secondParameter + ";");
            key(KeyEvent.VK_TAB, 0);
            paste("left"); key(KeyEvent.VK_TAB, 0);
            waitFor("first linked parameter renamed", () -> frame.editor().templateActive()
                    && frame.editor().getSelectedText().equals(secondParameter)
                    && frame.editor().getText().contains("return left + " + secondParameter + ";"));
            paste("right"); key(KeyEvent.VK_TAB, SHIFT);
            waitFor("second linked parameter renamed", () -> frame.editor().templateActive()
                    && frame.editor().getSelectedText().equals("left")
                    && frame.editor().getText().contains("return left + right;"));
            key(KeyEvent.VK_TAB, SHIFT);
            check(edt(() -> frame.editor().getSelectedText().contains("return")), chapter + ": Shift+Tab returns to generated body after parameter edits");
            check(edt(() -> frame.editor().getText().contains("int left") && frame.editor().getText().contains("int right")
                    && frame.editor().getText().contains("return left + right;")), chapter + ": linked parameter edits update declarations and real body references");
            key(KeyEvent.VK_TAB, SHIFT);
            check(edt(() -> frame.editor().getSelectedText().equals("add")), chapter + ": Shift+Tab reaches generated method-name placeholder");
            paste("addition"); key(KeyEvent.VK_TAB, 0);
            waitFor("linked semantic rename applies", () -> !frame.editor().templateActive()
                    && frame.editor().getText().contains("addition(int left, int right)"));
            check(true, chapter + ": method-name placeholder commits a semantic cross-file rename");
            return;
        }
        boolean renamedLeft = false, renamedRight = false, body = false;
        for (int i = 0; i < 14 && edt(() -> frame.editor().templateActive()); i++) {
            String selected = edt(() -> frame.editor().getSelectedText());
            report.append("Template selected: ").append(selected.replace('\n', ' ')).append('\n');
            if (selected.contains("return") || selected.contains("TODO") || selected.equals("0") || selected.equals("null")) {
                paste(selected.equals("0") || selected.equals("null") ? "left + right" : "return left + right;"); body = true;
            } else if (selected.matches("(?:arg|param)0|(?:arg|param)1|i|a|value|int1" ) && !renamedLeft) {
                paste("left"); renamedLeft = true;
            } else if (selected.matches("(?:arg|param)[12]|j|b|value2|int2") && !renamedRight) {
                paste("right"); renamedRight = true;
            }
            key(KeyEvent.VK_TAB, 0);
            if (body) break;
        }
        check(renamedLeft && renamedRight && body, chapter + ": linked parameters and body are editable using Tab without a method dialog");
        key(KeyEvent.VK_ESCAPE, 0);
        check(edt(() -> frame.editor().getText().contains("int left") && frame.editor().getText().contains("int right")
                && frame.editor().getText().contains("return left + right;")), chapter + ": linked edits changed actual method source");
    }

    private static void waitMaven() throws Exception {
        waitFor("Maven model ready", () -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace() != null
                && frame.mavenProjects().workspace().resolved());
    }
    private static void newProjectFromMenu(boolean maven) throws Exception {
        key(KeyEvent.VK_F10, 0);
        waitFor("File menu", () -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0);
        for (int i = 0; i < 12 && !edt(() -> selectedMenuItem().equals("New")); i++) key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> selectedMenuItem().equals("New")), "F10 → File → New is keyboard accessible");
        key(KeyEvent.VK_RIGHT, 0);
        String label = maven ? "New Maven Project…" : "New Java Project…";
        for (int i = 0; i < 8 && !edt(() -> selectedMenuItem().equals(label)); i++) key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> selectedMenuItem().equals(label)), "File → New → " + label + " selected with arrow keys");
        key(KeyEvent.VK_ENTER, 0);
    }
    private static String selectedMenuItem() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        if (path.length == 0) return "";
        return path[path.length - 1] instanceof JMenuItem item ? item.getText() : "";
    }
    private static void eclipseRun() throws Exception { key(KeyEvent.VK_F11, CMD | SHIFT); }
    private static void handleLaunchDialogs() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            String title = edt(() -> OverlayHost.dialogs(frame).stream().map(EmbeddedDialog::getTitle)
                    .filter(t -> t.contains("Save") || t.equals("Project Trust")).findFirst().orElse(null));
            if (title != null) {
                report.append("Launch confirmation: ").append(title).append('\n');
                button(title.contains("Save") ? "Yes" : "OK", "Save", "OK", "Save and Run", "Save All");
            }
            Thread.sleep(60);
        }
    }
    private static void dismissWelcome() throws Exception {
        if (edt(() -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.getTitle().toLowerCase(Locale.ROOT).contains("welcome")))) key(KeyEvent.VK_ESCAPE, 0);
    }
    private static void closeResults() throws Exception {
        if (edt(() -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.getTitle().startsWith("TurboTest")))) key(KeyEvent.VK_ESCAPE, 0);
        waitFor("editor focus after results", () -> !OverlayHost.active(frame) && frame.editor().isFocusOwner());
    }
    private static void quick() throws Exception {
        key(KeyEvent.VK_1, CMD);
        waitFor("contextual popup", () -> quickList() != null);
    }
    private static void chooseProposal(String... parts) throws Exception {
        waitFor("proposal " + Arrays.toString(parts), () -> proposalIndex(parts) >= 0);
        int target = edt(() -> proposalIndex(parts)), current = edt(() -> quickList().getSelectedIndex());
        for (int i = current; i < target; i++) key(KeyEvent.VK_DOWN, 0);
        for (int i = current; i > target; i--) key(KeyEvent.VK_UP, 0);
        report.append("Selected proposal: ").append(edt(() -> ((JavaQuickActions.Proposal)quickList().getSelectedValue()).title())).append('\n');
        key(KeyEvent.VK_ENTER, 0);
        waitFor("proposal popup closes", () -> quickList() == null);
    }
    private static JList<?> quickList() { Component found = showing("quick-actions-proposals"); return found instanceof JList<?> list ? list : null; }
    private static List<JavaQuickActions.Proposal> proposals() {
        JList<?> list = quickList(); if (list == null) return List.of();
        List<JavaQuickActions.Proposal> result = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i) instanceof JavaQuickActions.Proposal p) result.add(p);
        return result;
    }
    private static int proposalIndex(String... parts) {
        var proposals = proposals();
        for (int i = 0; i < proposals.size(); i++) {
            String title = proposals.get(i).title().toLowerCase(Locale.ROOT);
            if (Arrays.stream(parts).allMatch(p -> title.contains(p.toLowerCase(Locale.ROOT)))) return i;
        }
        return -1;
    }
    private static void action(String id) throws Exception {
        boolean eclipse = edt(() -> frame.commandKeymaps().selectedId().equals("eclipse-macos"));
        key(eclipse ? KeyEvent.VK_3 : KeyEvent.VK_A, eclipse ? CMD : CMD | SHIFT);
        waitFor("palette " + id, () -> dialog("Find Action") instanceof CommandPaletteDialog);
        focusWhere(c -> accessibleName(c).equals("Search commands"), "palette search"); replace(id);
        waitFor("palette match " + id, () -> paletteIndex(id) >= 0);
        int selected = edt(() -> ((CommandPaletteDialog)dialog("Find Action")).results().getSelectedIndex());
        int target = edt(() -> paletteIndex(id));
        for (int i = selected; i < target; i++) key(KeyEvent.VK_DOWN, 0);
        for (int i = selected; i > target; i--) key(KeyEvent.VK_UP, 0);
        check(edt(() -> ((CommandPaletteDialog)dialog("Find Action")).results().getSelectedValue().availability().enabled()), "Command available: " + id);
        key(KeyEvent.VK_ENTER, 0);
        waitFor("palette closes " + id, () -> dialog("Find Action") == null);
    }
    private static int paletteIndex(String id) {
        if (!(dialog("Find Action") instanceof CommandPaletteDialog palette)) return -1;
        for (int i = 0; i < palette.results().getModel().getSize(); i++)
            if (palette.results().getModel().getElementAt(i).command().id().equals(frame.commandRegistry().canonicalId(id))) return i;
        return -1;
    }
    private static void find(String text) throws Exception {
        key(KeyEvent.VK_F, CMD); waitFor("Find", () -> dialog("Input") != null);
        focusWhere(c -> c instanceof JTextField, "find field"); replace(text); key(KeyEvent.VK_ENTER, 0);
        waitFor("Find selects source", () -> dialog("Input") == null && frame.editor().isFocusOwner());
        check(edt(() -> frame.editor().getSelectedText().equals(text)), "Find selects exact source: " + text);
    }
    private static void field(String name, String value) throws Exception { focusNamed(name); replace(value); }
    private static void focusNamed(String name) throws Exception { focusWhere(c -> c != null && name.equals(c.getName()), name); }
    private static void pressNamed(String name) throws Exception { focusNamed(name); key(KeyEvent.VK_ENTER, 0); }
    private static void button(String... labels) throws Exception {
        focusWhere(c -> c instanceof JButton b && b.isEnabled() && List.of(labels).contains(b.getText()), Arrays.toString(labels));
        key(KeyEvent.VK_ENTER, 0);
    }
    private static void focusWhere(Predicate<Component> match, String reason) throws Exception {
        for (int i = 0; i < 65; i++) {
            if (edt(() -> match.test(focus()))) return;
            if (reason.startsWith("quick-actions-") && edt(() -> frame.editor().isFocusOwner()))
                throw new AssertionError("Popup focus escaped to source while traversing to " + reason);
            key(KeyEvent.VK_TAB, 0);
        }
        throw new AssertionError("Tab cannot reach " + reason + "; focus=" + edt(() -> describe(focus())));
    }
    private static void replace(String value) throws Exception { key(KeyEvent.VK_A, CMD); paste(value); }
    private static void paste(String value) throws Exception {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
        report.append("Keyboard paste: ").append(value.replace('\n', ' ')).append('\n');
        key(KeyEvent.VK_V, CMD);
    }
    private static void key(int code, int modifiers) throws Exception {
        String before = edt(() -> describe(focus()));
        if (!edt(() -> frame.isActive() && focus() != null && SwingUtilities.isDescendingFrom(focus(), frame)))
            throw new AssertionError("Native application lost keyboard focus; no key sent: " + before);
        if (robot != null) {
            if (!edt(() -> frame.isActive() && focus() != null && SwingUtilities.isDescendingFrom(focus(), frame)))
                throw new AssertionError("Native application is not foreground; refusing to send OS keys elsewhere");
            int[] masks = {CMD, KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, SHIFT};
            int[] codes = {KeyEvent.VK_META, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT};
            for (int i = 0; i < masks.length; i++) if ((modifiers & masks[i]) != 0) robot.keyPress(codes[i]);
            robot.keyPress(code); robot.keyRelease(code);
            for (int i = masks.length - 1; i >= 0; i--) if ((modifiers & masks[i]) != 0) robot.keyRelease(codes[i]);
            robot.waitForIdle();
        } else {
            // A real key can enter an EmbeddedDialog secondary event loop. Posting the
            // press and release separately lets that modal loop process subsequent keys;
            // waiting for dispatchEvent to return would deadlock the test at Find.
            Component owner = edt(FirstLaunchKeyboardSmoke::focus);
            if (owner == null) throw new AssertionError("No focus owner for " + KeyEvent.getKeyText(code));
            long now = System.currentTimeMillis();
            EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
            queue.postEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, now, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            queue.postEvent(new KeyEvent(owner, KeyEvent.KEY_RELEASED, now + 1, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            edt(() -> null);
        }
        Thread.sleep(65);
        events.append(++eventNumber).append('\t').append(String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0))
                .append('\t').append(KeyEvent.getModifiersExText(modifiers)).append('+').append(KeyEvent.getKeyText(code))
                .append('\t').append(before).append('\t').append(edt(() -> describe(focus()))).append('\n');
    }
    private static void snapshot(String name) throws Exception {
        Path directory = Files.createDirectories(output.resolve(name));
        edt(() -> {
            var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", directory.resolve("frame.png").toFile());
            Files.writeString(directory.resolve("editor.java.txt"), frame.editor().getText());
            Files.writeString(directory.resolve("console.txt"), frame.console().text());
            StringBuilder state = new StringBuilder("Current file: ").append(frame.currentFile()).append("\nFocus: ")
                    .append(describe(focus())).append("\nLocation: ").append(frame.editor().captureLocation())
                    .append("\nCompilation: ").append(frame.currentResult()).append("\nTest report: ").append(frame.tests().lastReport())
                    .append("\nProposals: ").append(proposals()).append("\nBuild status: ").append(frame.buildStatus()).append('\n');
            var maven = frame.mavenProjects().workspace();
            if (maven != null) {
                state.append("Maven root: ").append(maven.root()).append("\nMaven generation: ").append(maven.generation())
                        .append("\nMaven resolved: ").append(maven.resolved()).append("\nMaven offline: ").append(maven.offline()).append('\n');
                for (var module : maven.modules()) state.append("Maven module: ").append(module.id())
                        .append("\nMain roots: ").append(module.sourceRoots()).append("\nTest roots: ").append(module.testRoots())
                        .append("\nDependencies: ").append(module.dependencies().stream().map(d -> d.coordinates() + " [" + d.scope() + "]").toList()).append('\n');
            }
            if (project != null) {
                var buffers = TurboFrame.class.getDeclaredField("buffers"); buffers.setAccessible(true);
                var paths = ((Map<?, ?>)buffers.get(frame)).keySet().stream()
                        .filter(Path.class::isInstance).map(Path.class::cast).filter(p -> p.startsWith(project)).toList();
                for (Path path : paths) {
                    Path copy = directory.resolve("buffers").resolve(project.relativize(path));
                    Files.createDirectories(copy.getParent()); Files.writeString(copy, bufferText(path));
                }
            }
            Files.writeString(directory.resolve("state.txt"), state.toString()); return null;
        });
        if (project != null && Files.exists(project)) {
            StringBuilder hashes = new StringBuilder();
            try (var paths = Files.walk(project)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (path.toString().contains("/target/")) continue;
                    Path relative = project.relativize(path), copy = directory.resolve("project").resolve(relative);
                    Files.createDirectories(copy.getParent()); byte[] bytes = Files.readAllBytes(path); Files.write(copy, bytes);
                    hashes.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                            .append("  ").append(relative).append('\n');
                }
            }
            Files.writeString(directory.resolve("source-sha256.txt"), hashes.toString());
        }
        Files.writeString(output.resolve("report.txt"), report.toString());
        Files.writeString(output.resolve("keyboard-events.tsv"), events.toString());
    }
    private static Component focus() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }
    /** Read-only evidence inspection; source mutations must always come from keys above. */
    private static String bufferText(Path file) throws Exception {
        if (file.equals(frame.currentFile())) return frame.editor().getText();
        var field = TurboFrame.class.getDeclaredField("buffers"); field.setAccessible(true);
        Object buffer = ((Map<?, ?>)field.get(frame)).get(file);
        if (buffer == null) return "";
        var state = buffer.getClass().getDeclaredMethod("state"); state.setAccessible(true);
        return ((dev.turboj.ui.EditorComponent.EditorState)state.invoke(buffer)).document().toString();
    }
    private static String accessibleName(Component c) { return c instanceof JComponent j && j.getAccessibleContext() != null ? Objects.toString(j.getAccessibleContext().getAccessibleName(), "") : ""; }
    private static String describe(Component c) { return c == null ? "none" : c.getClass().getSimpleName() + '[' + Objects.toString(c.getName(), "") + ";" + accessibleName(c) + ']'; }
    private static EmbeddedDialog dialog(String title) { return OverlayHost.dialogs(frame).stream().filter(d -> d.getTitle().equals(title)).findFirst().orElse(null); }
    private static Component showing(String name) { return frame == null ? null : findComponent(frame, c -> c.isShowing() && name.equals(c.getName())); }
    private static Component findComponent(Container root, Predicate<Component> match) {
        for (Component child : root.getComponents()) { if (match.test(child)) return child; if (child instanceof Container nested) { Component result = findComponent(nested, match); if (result != null) return result; } }
        return null;
    }
    private static void check(boolean passed, String label) { if (!passed) throw new AssertionError(label); report.append("PASS ").append(label).append('\n'); System.out.println("PASS " + label); }
    private static void waitFor(String description, Callable<Boolean> ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < until) { if (edt(ready)) return; Thread.sleep(50); }
        throw new AssertionError("Timed out: " + description);
    }
    private static <T> T edt(Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        var task = new FutureTask<>(call); SwingUtilities.invokeLater(task); return task.get(100, TimeUnit.SECONDS);
    }
}
