package dev.turboj.app;

import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.ui.ProjectSearchDialog;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;

/**
 * Steps 1–3 of docs/first-launch-to-first-test-prompt.md, keyboard only, through the real TurboJ.main bootstrap:
 * empty screen without a project, ~/Workspace selection, New Java Project inside the workspace opened on its main.
 */
public final class FirstLaunchStepsSmoke {
    private static final int CMD = KeyEvent.META_DOWN_MASK;
    private static final Path OUTPUT = Path.of("app/target/first-launch-steps").toAbsolutePath();
    private static TurboFrame frame;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        Path session = Files.createTempDirectory("turboj-first-launch-steps-").toRealPath();
        Path home = Files.createDirectories(session.resolve("home"));
        System.setProperty("user.home", home.toString());
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var previousClipboard = clipboard.getContents(null);
        try {
            TurboJ.main(new String[0]);
            frame = await("native frame", () -> {
                for (Window window : Window.getWindows()) if (window instanceof TurboFrame candidate && candidate.isShowing()) return candidate;
                return null;
            });

            // Step 1: no project, no sample buffer.
            check(edt(() -> frame.editor().getText().isEmpty() && frame.currentFile() == null), "step 1: empty editor, no file");
            await("workspace chooser", () -> showing("first-launch-workspace-path"));
            snapshot("1-empty-screen-with-workspace-chooser");
            System.out.println("PASS step 1: launch without a project shows an empty editor, no Hello sample");

            // Step 2: ~/Workspace, typed with ~, remembered.
            Path workspace = home.resolve("Workspace");
            check(edt(() -> ((JTextField) showing("first-launch-workspace-path")).getText()).equals(workspace.toString()),
                    "step 2: default workspace is ~/Workspace");
            focus("first-launch-workspace-path"); replace("~/Workspace");
            focus("first-launch-workspace-remember");
            if (!edt(() -> ((JCheckBox) showing("first-launch-workspace-remember")).isSelected())) key(KeyEvent.VK_SPACE, 0);
            focus("first-launch-workspace-launch"); key(KeyEvent.VK_ENTER, 0);
            await("workspace accepted", () -> showing("first-launch-workspace-path") == null ? true : null);
            await("workspace folder", () -> Files.isDirectory(workspace) ? true : null);
            check(!Files.exists(Path.of("~")), "step 2: ~ is the home folder, not a folder named ~");
            await("remembered workspace", () -> {
                Path config = Settings.directory().resolve("config.json");
                return Files.isRegularFile(config) && Files.readString(config).contains(workspace.toString()) ? true : null;
            });
            dismissWelcome();
            await("editor focus", () -> frame.editor().isFocusOwner() ? true : null);
            System.out.println("PASS step 2: ~/Workspace expanded to " + workspace + ", created and remembered");

            // Step 3: File → New → New Java Project…, HelloWorld → ~/Workspace/hello-world, opened on its empty main.
            menu("New Java Project…");
            await("project form", () -> showing("first-launch-project-name"));
            Path project = workspace.resolve("hello-world");
            check(edt(() -> ((JTextField) showing("first-launch-project-name")).getText()).equals("HelloWorld"), "step 3: name HelloWorld");
            check(edt(() -> ((JTextField) showing("first-launch-project-location")).getText()).equals(project.toString()),
                    "step 3: location is the workspace's hello-world folder");
            check(edt(() -> ((JCheckBox) showing("first-launch-project-main")).isSelected()
                    && ((JCheckBox) showing("first-launch-project-main")).getText().equals("Create HelloWorld.java with main")),
                    "step 3: main class offered");
            snapshot("3-new-java-project-form");
            focus("first-launch-project-create"); key(KeyEvent.VK_ENTER, 0);
            Path main = project.resolve("src/main/java/HelloWorld.java");
            String expected = "public class HelloWorld {\n\n    public static void main(String[] args) {\n        \n    }\n}\n";
            int caret = expected.indexOf("        \n") + 8;
            await("HelloWorld.java opened with the caret in main", () -> main.equals(frame.currentFile()) && frame.editor().isFocusOwner()
                    && frame.editor().caretOffset() == caret ? true : null);
            check(Files.readString(main).equals(expected) && edt(() -> frame.editor().getText()).equals(expected), "step 3: empty main generated");
            check(Files.isRegularFile(project.resolve(".turboj/project.properties")), "step 3: project configuration created");
            paste("System.out.println(\"Hello TurboJ!\");");
            check(edt(() -> frame.editor().getText()).contains("main(String[] args) {\n        System.out.println(\"Hello TurboJ!\");\n    }"),
                    "step 3: typing at the caret writes inside main");
            snapshot("3-hello-world-main");
            check(edt(() -> !explorerShows("HelloWorld.java") && frame.mavenProjects().workspace() == null),
                    "step 3: keyboard first, the Project Explorer stays closed; a plain project has no Maven model");
            System.out.println("PASS step 3: New Java Project created " + project + " and opened HelloWorld.java with the caret inside main");

            // Remembered workspace: the next launch opens straight to the empty screen.
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            frame = edt(() -> {
                TurboFrame next = new TurboFrame(Settings.load(), Settings.keymap());
                next.setVisible(true); next.offerRecovery(); next.startFirstLaunch(); return next;
            });
            Thread.sleep(1500);
            check(edt(() -> showing("first-launch-workspace-path") == null && frame.editor().getText().isEmpty()),
                    "next launch: remembered workspace, no chooser, empty editor");
            snapshot("relaunch-empty-screen");
            System.out.println("PASS relaunch: remembered workspace skips the chooser and the screen stays empty");

            // Open Project… browses the workspace; a folder without pom.xml opens as a plain Java project.
            SwingUtilities.invokeLater(() -> frame.commandRegistry().execute("project.open",
                    dev.turboj.commandj.core.CommandContext.of("workspace", "editor", "editable")));
            JTextField directory = await("Open Project browser", () -> OverlayHost.dialogs(frame).isEmpty() ? null
                    : named(OverlayHost.dialogs(frame).getLast(), JTextField.class, "Directory path"));
            check(edt(directory::getText).equals(workspace.toString()), "Open Project starts in the workspace, was " + edt(directory::getText));
            // ~ works, and Enter on a folder opens it as the project.
            edt(() -> { named(OverlayHost.dialogs(frame).getLast(), JTextField.class, "File name").setText("~/Workspace/hello-world"); return null; });
            pressOpen();
            // With the explorer closed, the project shows itself through its last file and the status line.
            await("hello-world opened on its last file", () -> main.equals(frame.currentFile()) && frame.editor().isFocusOwner()
                    && frame.projectStatus().equals("Java project · hello-world") ? true : null);
            check(edt(() -> frame.mavenProjects().workspace() == null && !frame.console().text().contains("pom.missing")
                    && !explorerShows("HelloWorld.java")), "a plain project opens without Maven and without forcing the explorer open");
            System.out.println("PASS Open Project… starts in " + workspace + "; ~/Workspace/hello-world + Enter opens it as a Java project"
                    + " and shows HelloWorld.java");

            // File › Open takes a project folder too, and shows its main class.
            Path other = workspace.resolve("other-app");
            Path otherMain = Files.createDirectories(other.resolve("src/app")).resolve("Main.java");
            Files.writeString(other.resolve("src/app/Util.java"), "package app;\nclass Util {}\n");
            Files.writeString(otherMain, "package app;\npublic class Main { public static void main(String[] args) {} }\n");
            SwingUtilities.invokeLater(() -> frame.commandRegistry().execute("open",
                    dev.turboj.commandj.core.CommandContext.of("workspace", "editor", "editable")));
            await("Open browser", () -> OverlayHost.dialogs(frame).isEmpty() ? null
                    : named(OverlayHost.dialogs(frame).getLast(), JTextField.class, "File name"));
            edt(() -> { named(OverlayHost.dialogs(frame).getLast(), JTextField.class, "File name").setText("~/Workspace/other-app"); return null; });
            pressOpen();
            await("other-app opened on its main class", () -> otherMain.equals(frame.currentFile())
                    && frame.projectStatus().equals("Java project · other-app") ? true : null);
            System.out.println("PASS File › Open on a project folder opens it and shows its main class");
            edt(() -> { frame.openPath(project); return null; });
            await("back to hello-world", () -> main.equals(frame.currentFile()) ? true : null);

            // Keyboard search (IntelliJ keymap): Cmd+Shift+O files, Cmd+O classes, Tab to Methods.
            await("editor focus", () -> frame.editor().isFocusOwner() ? true : null);
            key(KeyEvent.VK_O, CMD | KeyEvent.SHIFT_DOWN_MASK);
            ProjectSearchDialog search = await("Go to File", FirstLaunchStepsSmoke::searchDialog);
            check(edt(search::tab) == ProjectSearchDialog.Tab.FILES, "Cmd+Shift+O opens the Files category");
            await("query focus", () -> search.queryField().isFocusOwner() ? true : null);
            paste("Hello");
            await("HelloWorld.java found", () -> search.results().getModel().getSize() > 0
                    && search.results().getSelectedValue().name().equals("HelloWorld.java") ? true : null);
            snapshot("search-files");
            key(KeyEvent.VK_ENTER, 0);
            await("HelloWorld.java opened from search", () -> main.equals(frame.currentFile()) && frame.editor().isFocusOwner() ? true : null);
            System.out.println("PASS Cmd+Shift+O: typing Hello and Enter opens HelloWorld.java");

            edt(() -> { frame.editor().gotoOffset(0); return null; });
            key(KeyEvent.VK_O, CMD);
            ProjectSearchDialog classes = await("Go to Class", FirstLaunchStepsSmoke::searchDialog);
            check(edt(classes::tab) == ProjectSearchDialog.Tab.CLASSES, "Cmd+O opens the Classes category");
            await("query focus", () -> classes.queryField().isFocusOwner() ? true : null);
            paste("HW");
            await("CamelHump HW finds HelloWorld", () -> classes.results().getModel().getSize() > 0
                    && classes.results().getSelectedValue().name().equals("HelloWorld") ? true : null);
            replace("main");
            key(KeyEvent.VK_TAB, 0); key(KeyEvent.VK_TAB, 0);
            await("Methods category lists main", () -> classes.tab() == ProjectSearchDialog.Tab.METHODS
                    && classes.results().getModel().getSize() > 0 && classes.results().getSelectedValue().name().equals("main") ? true : null);
            snapshot("search-methods");
            key(KeyEvent.VK_ENTER, 0);
            int mainName = Files.readString(main).indexOf("main(");
            await("caret on main", () -> frame.editor().isFocusOwner() && frame.editor().caretOffset() == mainName ? true : null);
            System.out.println("PASS Cmd+O: HW finds HelloWorld; Tab Tab to Methods, main + Enter puts the caret on main");

            key(KeyEvent.VK_O, CMD);
            await("search again", FirstLaunchStepsSmoke::searchDialog);
            key(KeyEvent.VK_ESCAPE, 0);
            await("Escape returns to the editor", () -> searchDialog() == null && frame.editor().isFocusOwner() ? true : null);
            System.out.println("PASS Escape closes the search and returns to the editor");
            System.out.println("Screenshots: " + OUTPUT);
        } finally {
            if (frame != null) edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (previousClipboard != null) clipboard.setContents(previousClipboard, null);
        }
    }

    private static void menu(String label) throws Exception {
        key(KeyEvent.VK_F10, 0);
        await("File menu", () -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0 ? true : null);
        for (int i = 0; i < 12 && !edt(() -> selectedMenuItem().equals("New")); i++) key(KeyEvent.VK_DOWN, 0);
        key(KeyEvent.VK_RIGHT, 0);
        for (int i = 0; i < 8 && !edt(() -> selectedMenuItem().equals(label)); i++) key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> selectedMenuItem().equals(label)), "F10 → File → New → " + label);
        key(KeyEvent.VK_ENTER, 0);
    }
    private static String selectedMenuItem() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path.length > 0 && path[path.length - 1] instanceof JMenuItem item ? item.getText() : "";
    }
    private static void dismissWelcome() throws Exception {
        if (edt(() -> OverlayHost.dialogs(frame).stream().anyMatch(d -> d.getTitle().toLowerCase().contains("welcome")))) key(KeyEvent.VK_ESCAPE, 0);
    }
    private static void focus(String name) throws Exception {
        edt(() -> { showing(name).requestFocusInWindow(); return null; });
        await("focus " + name, () -> {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return owner != null && name.equals(owner.getName()) ? true : null;
        });
    }
    private static void replace(String value) throws Exception { key(KeyEvent.VK_A, CMD); paste(value); }
    private static void paste(String value) throws Exception {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
        key(KeyEvent.VK_V, CMD);
    }
    // Posted, not dispatched: a key that opens an EmbeddedDialog enters its modal loop, and waiting for
    // dispatchEvent to return would block the test until the dialog closes.
    private static void key(int code, int modifiers) throws Exception {
        Component owner = edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        if (owner == null) throw new AssertionError("No keyboard focus for " + KeyEvent.getKeyText(code));
        long now = System.currentTimeMillis();
        EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        queue.postEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, now, modifiers, code, KeyEvent.CHAR_UNDEFINED));
        queue.postEvent(new KeyEvent(owner, KeyEvent.KEY_RELEASED, now + 1, modifiers, code, KeyEvent.CHAR_UNDEFINED));
        edt(() -> null);
        Thread.sleep(65);
    }
    private static void snapshot(String name) throws Exception {
        edt(() -> {
            var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", OUTPUT.resolve(name + ".png").toFile());
            return null;
        });
    }
    // Open stays disabled while the browser reads a directory.
    private static void pressOpen() throws Exception {
        JButton open = await("enabled Open button", () -> {
            for (Component button : components(OverlayHost.dialogs(frame).getLast()))
                if (button instanceof JButton b && b.getText().equals("Open") && b.isEnabled()) return b;
            return null;
        });
        edt(() -> { open.doClick(); return null; });
        Thread.sleep(200);
    }
    private static ProjectSearchDialog searchDialog() {
        return OverlayHost.dialogs(frame).stream().filter(ProjectSearchDialog.class::isInstance).map(ProjectSearchDialog.class::cast)
                .filter(Component::isShowing).findFirst().orElse(null);
    }
    private static boolean explorerShows(String fileName) {
        for (Component component : components(frame))
            if (component instanceof JTree tree && tree.isShowing())
                for (int row = 0; row < tree.getRowCount(); row++)
                    if (String.valueOf(tree.getPathForRow(row).getLastPathComponent()).equals(fileName)) return true;
        return false;
    }
    private static java.util.List<Component> components(Container root) {
        var all = new java.util.ArrayList<Component>();
        for (Component child : root.getComponents()) { all.add(child); if (child instanceof Container nested) all.addAll(components(nested)); }
        return all;
    }
    private static <T extends Component> T named(Container root, Class<T> type, String accessibleName) {
        for (Component component : components(root))
            if (type.isInstance(component) && component.getAccessibleContext() != null
                    && accessibleName.equals(component.getAccessibleContext().getAccessibleName())) return type.cast(component);
        return null;
    }
    private static Component showing(String name) { return find(frame, name); }
    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child.isShowing() && name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component match = find(nested, name); if (match != null) return match; }
        }
        return null;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static <T> T await(String what, Callable<T> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) { T value = edt(check); if (value != null) return value; Thread.sleep(25); }
        String search = edt(() -> { var dialog = frame == null ? null : searchDialog();
            return dialog == null ? "none" : "tab " + dialog.tab() + ", query '" + dialog.queryField().getText() + "', results "
                    + dialog.results().getModel().getSize() + ", focus " + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); });
        String dialogs = edt(() -> frame == null ? "" : OverlayHost.dialogs(frame).stream().map(d -> d.getTitle() + " "
                + components(d).stream().filter(c -> c instanceof JLabel).map(c -> ((JLabel) c).getText()).filter(t -> t != null && !t.isBlank()).toList()).toList().toString());
        throw new AssertionError("Timed out waiting for " + what + "; dialogs " + dialogs + "; current " + edt(() -> frame == null ? null : frame.currentFile())
                + "; search " + search + "; console:\n" + edt(() -> frame == null ? "" : frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
