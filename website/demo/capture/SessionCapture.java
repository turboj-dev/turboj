package dev.turboj.app;

import com.google.gson.GsonBuilder;
import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.turbotest.TestEngine;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** External capture instrumentation. Compiled against the unmodified released app JAR. */
public final class SessionCapture {
    private static final String BOUNDARY = "System.nanoTime immediately before registered command/button dispatch, through an EDT paint checkpoint after the completion predicate. Includes scheduling, compilation and UI handling; nominal completion polling interval 10 ms, subject to OS scheduling. It does not measure physical display scanout.";
    private final Path output, workspace;
    private final Settings settings;
    private final TurboFrame frame;
    private final boolean presentation;
    private final long origin = System.nanoTime();
    private final Instant started = Instant.now();
    private final List<Map<String,Object>> events = new ArrayList<>(), measurements = new ArrayList<>();
    private final Map<String,Object> metadata = new LinkedHashMap<>();
    private String chapter = "hello-world";
    private String externalTerminal = "";
    private int sequence;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/hero-session/control-01" : args[0]).toAbsolutePath();
        if (Files.exists(output.resolve("trace.json"))) throw new IllegalArgumentException("Refusing to overwrite an existing session: " + output);
        SessionCapture capture = new SessionCapture(output, Arrays.asList(args).contains("--presentation"));
        try {
            capture.runPlain();
            if (Arrays.asList(args).contains("--spring")) capture.runSpring();
            capture.metadata.put("completed", true);
        } catch (Throwable failure) {
            capture.metadata.put("completed", false);
            capture.metadata.put("failure", failure.toString());
            capture.state("capture-failed", "Capture stopped: " + failure.getClass().getSimpleName());
            throw failure;
        } finally {
            capture.write();
            edt(() -> { capture.frame.shutdown(); capture.frame.dispose(); return null; });
        }
    }

    private SessionCapture(Path output, boolean presentation) throws Exception {
        this.presentation = presentation;
        this.output = Files.createDirectories(output);
        workspace = Files.createDirectories(Path.of("/private/tmp/turboj-demo", output.getFileName().toString(), "workspace"));
        if (Files.exists(workspace.resolve("hello-world/HelloWorld.java"))) throw new IllegalArgumentException("Refusing to reuse a capture workspace: " + workspace);
        Files.createDirectories(output.resolve("screenshots"));
        System.setProperty("turboj.config", output.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        settings = new Settings();
        settings.keymapProfile = "turboj-classic";
        settings.width = 1060; settings.height = 740;
        settings.theme = "classic";
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var value = new TurboFrame(settings, Map.of());
            value.setVisible(true); value.toFront(); return value;
        });
        metadata.put("schemaVersion", 1);
        metadata.put("startedAt", started.toString());
        metadata.put("clock", "System.nanoTime (relative nanoseconds); wall timestamps are Instant.now");
        metadata.put("measurementBoundary", BOUNDARY);
        metadata.put("capture", "Actual Swing frame painted into an image on the EDT; no desktop or other windows captured");
        metadata.put("input", "Deterministic automation enters source into the actual editor and dispatches real registered commands and dialog buttons");
        metadata.put("keymap", "TurboJ Classic (explicitly selected for this session)");
        metadata.put("fontSize", 18);
        metadata.put("editorFont", edt(() -> Map.of("name", frame.editor().getFont().getName(), "family", frame.editor().getFont().getFamily(), "size", frame.editor().getFont().getSize())));
        metadata.put("theme", "classic");
        metadata.put("menus", edt(() -> {
            JMenuBar menus = (JMenuBar) field(frame, "menus");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < menus.getMenuCount(); i++) labels.add(menus.getMenu(i).getText());
            return labels;
        }));
        metadata.put("frameSize", List.of(1060, 740));
        metadata.put("jdk", System.getProperty("java.runtime.version"));
        metadata.put("javaVendor", System.getProperty("java.vendor"));
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        metadata.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("turbojCommit", commandOutput("git", "rev-parse", "HEAD").strip());
        metadata.put("cpu", commandOutput("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string").strip());
        metadata.put("ramBytes", commandOutput("/usr/sbin/sysctl", "-n", "hw.memsize").strip());
        metadata.put("appJar", "target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar");
        metadata.put("appJarSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of("target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar")))));
        metadata.put("conditions", "Fresh app JVM, fresh private project/configuration directories. Bundled JUnit; no network needed for chapters A-D. Semantic/compiler workers warm progressively within this single session. No durations copied from control runs.");
        metadata.put("presentationPauses", presentation ? "Actual pauses in this captured session: 1400 ms after state snapshots (600 ms for the empty file), 1600 ms after operation results, all outside operation measurement boundaries. Source input is automated in complete edits. Live compilation may finish during presentation pauses, so explicit Compile and Run may reuse its cache." : "No presentation pauses; control run only");
    }

    private void runPlain() throws Exception {
        Path hello = workspace.resolve("hello-world/HelloWorld.java");
        Files.createDirectories(hello.getParent().resolve(".turboj"));
        Files.writeString(hello.getParent().resolve(".turboj/source-roots.txt"), ".\n");
        Files.writeString(hello, "");
        open(hello);
        state("hello-empty", "A new Java source file");
        edit(Files.readString(Path.of("website/demo/projects/hello-world/HelloWorld.java")));
        command("save");
        state("hello-source", "Write and save HelloWorld.java");
        clear();
        operation("hello-compile", "Fast Compile: explicit compilation (live compilation may have warmed the cache)", () -> command("compile"),
                () -> frame.console().text().contains("BUILD OK") || frame.console().text().contains("CACHE HIT"));
        clear();
        operation("hello-run", "Fast Run: compile and execute Hello World", () -> command("run"),
                () -> !frame.isRunning() && frame.console().text().contains("Hello world! TurboJ") && frame.console().text().contains("[exit 0"));

        chapter = "tests";
        Path price = workspace.resolve("price-demo/src/Price.java");
        Path test = workspace.resolve("price-demo/test/PriceTest.java");
        Files.createDirectories(price.getParent()); Files.createDirectories(test.getParent());
        Files.createDirectories(price.getParent().getParent().resolve(".turboj"));
        Files.writeString(price.getParent().getParent().resolve(".turboj/source-roots.txt"), "src\ntest\n");
        Files.writeString(price, ""); Files.writeString(test, "");
        open(price); edit(Files.readString(Path.of("website/demo/projects/price-demo/src/Price.java"))); command("save");
        clear(); state("price-source", "A small discount function using integer cents");
        open(test); edit(Files.readString(Path.of("website/demo/projects/price-demo/test/PriceTest.java"))); command("save");
        state("test-source", "Two deterministic tests: 800 cents and 1000 cents");
        tests("tests-before-refactor", "Run All Tests: compile project and execute two JUnit tests", 2, 0, () -> command("tests.all"));
        closeDialogs(); state("tests-output", "Actual TurboTest results in the output pane");

        chapter = "refactoring";
        open(price); clear();
        edt(() -> { frame.editor().gotoOffset(frame.editor().getText().indexOf("discount(")); return null; });
        state("rename-selection", "Select the discount method");
        operation("rename-inspect", "Rename: resolve the selected symbol and show the inline form", () -> command("refactor.rename"),
                () -> dialog("Rename discount") != null);
        edt(() -> { textField(dialog("Rename discount")).setText("discountedPrice"); return null; });
        state("rename-form", "Rename discount to discountedPrice");
        operation("rename-prepare", "Rename: prepare and validate the cross-file preview", () -> click("Rename discount", "Preview"),
                () -> dialog("RefactorJ — Rename") != null);
        require(edt(() -> button(dialog("RefactorJ — Rename"), "Apply changes").isEnabled()), "Rename preview must be valid");
        operation("rename-apply", "Rename: apply the validated workspace changes", () -> click("RefactorJ — Rename", "Apply changes"),
                () -> !frame.refactoring().busy() && dialog("RefactorJ — Rename") == null && frame.editor().getText().contains("int discountedPrice("));
        command("save");
        open(test); require(edt(() -> frame.editor().getText().contains("Price.discountedPrice(")), "Both test references must be renamed semantically");
        command("save"); state("renamed-tests", "The same tests now reference discountedPrice");
        open(price);
        String selected = "int reduction = cents * percent / 100;\n        return cents - reduction;";
        edt(() -> { frame.editor().gotoOffset(0); require(frame.editor().find(selected, true), "Extract statement range exists"); return null; });
        state("extract-selection", "Select the two calculation statements");
        operation("extract-inspect", "Extract Method: resolve the statement selection and show options", () -> command("refactor.extract-method"),
                () -> dialog("Extract Method") != null);
        edt(() -> { textField(dialog("Extract Method")).setText("applyDiscount"); return null; });
        state("extract-form", "Extract the calculation into applyDiscount");
        operation("extract-prepare", "Extract Method: analyze data flow and prepare the preview", () -> click("Extract Method", "OK"),
                () -> dialog("RefactorJ — Extract Method") != null);
        require(edt(() -> button(dialog("RefactorJ — Extract Method"), "Apply changes").isEnabled()), "Extract Method preview must be valid");
        operation("extract-apply", "Extract Method: apply the validated changes", () -> click("RefactorJ — Extract Method", "Apply changes"),
                () -> !frame.refactoring().busy() && dialog("RefactorJ — Extract Method") == null && frame.editor().getText().contains("private static int applyDiscount("));
        command("save");
        tests("tests-after-refactor", "Run the same tests after both refactorings", 2, 0, () -> command("tests.all"));
        closeDialogs(); state("refactoring-preserved", "Both tests still pass after Rename and Extract Method");

        chapter = "automation";
        open(price); command("tests.onSave");
        require(settings.autoTestOnSave && !settings.autoRunOnSave, "Only Auto Test on Save must be active");
        showMenu("Options"); state("autotest-enabled", "Enable Auto Test on Save independently of Auto Run"); closeMenu();
        String correct = edt(() -> frame.editor().getText());
        require(correct.contains("cents * percent / 100"), "The calculation survives extraction");
        edit(correct.replace("cents * percent / 100", "cents * percent / 200"));
        clear(); state("autotest-edited", "Change the divisor; typing alone has not run tests");
        observeNoExecution("autotest-wait", "Unsaved edit: no automatic test execution", 600);
        tests("autotest-fails", "Save triggers compilation and Auto Test: one failure", 1, 1, () -> command("save"));
        require(edt(() -> OverlayHost.dialogs(frame).isEmpty()), "Automatic tests report inline");
        edit(correct); clear(); state("autotest-fixed", "Restore the divisor to 100");
        tests("autotest-passes", "Save the correction: compile and Auto Test return to green", 2, 0, () -> command("save"));
        command("tests.onSave"); command("run.onSave");
        require(settings.autoRunOnSave && !settings.autoTestOnSave, "Only Auto Run on Save must be active");
        showMenu("Options"); state("autorun-enabled", "Enable Auto Run on Save with Auto Test off"); closeMenu();
        edit(correct.replace("Price: ", "Updated price: ")); clear();
        state("autorun-edited", "Edit the displayed message; execution waits for Save");
        observeNoExecution("autorun-wait", "Unsaved output edit: no automatic run", 600);
        operation("autorun-saved", "Save triggers compilation and Auto Run", () -> command("save"),
                () -> !frame.isRunning() && frame.console().text().contains("Updated price: 800 cents") && frame.console().text().contains("[exit 0"));
        command("run.onSave");
        metadata.put("automationTrigger", "Successful Java Save. Unsaved changes were observed for 600 ms without execution. Save-to-result measurements include trigger scheduling and compilation; no inferred debounce duration is published.");
        Files.writeString(output.resolve("Price.after.java"), edt(() -> frame.editor().getText()));
        open(test); Files.writeString(output.resolve("PriceTest.after.java"), edt(() -> frame.editor().getText()));
    }

    /** E is supplied by the Spring verification task; absence must never produce a success event. */
    private void runSpring() throws Exception {
        var helper = Class.forName("dev.turboj.app.SpringCapture");
        helper.getMethod("run", SessionCapture.class).invoke(null, this);
    }

    private void tests(String id, String label, int passed, int failed, ThrowingRunnable action) throws Exception {
        TestEngine.Report previous = edt(() -> frame.tests().lastReport());
        operation(id, label, action, () -> !frame.tests().busy() && frame.tests().lastReport() != null && frame.tests().lastReport() != previous);
        TestEngine.Report report = edt(() -> frame.tests().lastReport());
        require(report.passed() == passed && report.failed() == failed && report.tests().size() == 2,
                id + ": expected " + passed + " passed, " + failed + " failed; actual " + report);
    }

    private void observeNoExecution(String id, String label, long millis) throws Exception {
        TestEngine.Report report = edt(() -> frame.tests().lastReport());
        operation(id, label, () -> Thread.sleep(millis), () -> !frame.tests().busy() && !frame.isRunning());
        require(edt(() -> frame.tests().lastReport() == report && !frame.console().text().contains("FAST RUN")
                && !frame.console().text().contains("TURBOTEST") && !frame.console().text().contains("[exit ")), "Unsaved edit unexpectedly executed");
    }

    public void operation(String id, String label, ThrowingRunnable action, Callable<Boolean> complete) throws Exception {
        Snapshot before = snapshot(id + "-start");
        long start = System.nanoTime() - origin;
        String startWall = Instant.now().toString();
        event(id + "-start", "start", label, id, start, new Snapshot(before.atNs(), startWall, before.state(), before.screenshot()));
        action.run();
        waitFor(complete);
        Snapshot after = snapshot(id + "-result");
        long end = after.atNs();
        event(id + "-result", "result", label, id, end, after);
        var measurement = new LinkedHashMap<String,Object>(Map.of("id", id, "chapter", chapter, "label", label,
                "startNs", start, "endNs", end, "durationNs", end - start, "boundary", BOUNDARY, "success", true));
        measurement.put("startedAt", startWall); measurement.put("endedAt", after.wallTime());
        measurements.add(measurement);
        System.out.printf(Locale.ROOT, "%s %.3f ms%n", id, (end - start) / 1_000_000.0);
        write();
        if (presentation) Thread.sleep(1600);
    }

    public void state(String id, String label) throws Exception {
        checkpoint(id, label);
        if (presentation) Thread.sleep(id.equals("hello-empty") ? 600 : 1400);
    }

    /** Actual intermediate UI evidence, without an intentional presentation pause. */
    public void checkpoint(String id, String label) throws Exception {
        Snapshot value = snapshot(id);
        event(id, "state", label, null, value.atNs(), value);
        write();
    }

    private record Snapshot(long atNs, String wallTime, Map<String,Object> state, String screenshot) {}
    private Snapshot snapshot(String id) throws Exception {
        String name = String.format(Locale.ROOT, "%03d-%s.png", ++sequence, id);
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Snapshot result = edt(() -> {
            Map<String,Object> state = new LinkedHashMap<>();
            state.put("file", frame.currentFile() == null ? "Untitled.java" : relative(frame.currentFile()));
            state.put("source", frame.editor().getText());
            var location = frame.editor().captureLocation();
            state.put("selectionStart", Math.min(location.caret(), location.anchor()));
            state.put("selectionEnd", Math.max(location.caret(), location.anchor()));
            state.put("cursorOffset", location.caret());
            state.put("topLine", location.topLine());
            state.put("console", frame.console().text());
            state.put("status", field(frame, "buildStatus"));
            state.put("autoRunOnSave", settings.autoRunOnSave);
            state.put("autoTestOnSave", settings.autoTestOnSave);
            state.put("externalTerminal", externalTerminal);
            state.put("dialogs", OverlayHost.dialogs(frame).stream().map(this::dialogState).toList());
            var openDialogs = OverlayHost.dialogs(frame);
            if (!openDialogs.isEmpty()) {
                EmbeddedDialog top = openDialogs.getLast();
                var bounds = SwingUtilities.convertRectangle(top.getParent(), top.getBounds(), frame);
                state.put("dialogBounds", Map.of("x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height));
            }
            state.put("menus", Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast).map(JMenuItem::getText).toList());
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose(); Toolkit.getDefaultToolkit().sync();
            return new Snapshot(System.nanoTime() - origin, Instant.now().toString(), state, "screenshots/" + name);
        });
        ImageIO.write(image, "png", output.resolve(result.screenshot()).toFile());
        return result;
    }

    private Map<String,Object> dialogState(EmbeddedDialog dialog) {
        var values = new ArrayList<Map<String,Object>>();
        for (Component item : descendants(dialog)) {
            Map<String,Object> value = new LinkedHashMap<>();
            if (item instanceof JTextComponent text) { value.put("type", item instanceof JTextField ? "input" : "text"); value.put("text", text.getText()); }
            else if (item instanceof AbstractButton button) { value.put("type", "button"); value.put("text", button.getText()); value.put("enabled", button.isEnabled()); value.put("selected", button.isSelected()); }
            else if (item instanceof JLabel label && label.getText() != null) { value.put("type", "label"); value.put("text", label.getText()); }
            else if (item instanceof JComboBox<?> combo) { value.put("type", "select"); value.put("text", String.valueOf(combo.getSelectedItem())); }
            if (!value.isEmpty()) values.add(value);
        }
        return Map.of("title", dialog.getTitle(), "components", values);
    }

    private void event(String id, String kind, String label, String operationId, long at, Snapshot snapshot) {
        var event = new LinkedHashMap<String,Object>();
        event.put("id", id); event.put("chapter", chapter); event.put("kind", kind); event.put("label", label);
        event.put("operationId", operationId); event.put("atNs", at); event.put("wallTime", snapshot.wallTime());
        event.put("state", snapshot.state()); event.put("screenshot", snapshot.screenshot()); events.add(event);
    }

    public void write() throws Exception {
        Files.writeString(output.resolve("trace.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(Map.of("metadata", metadata, "events", events, "measurements", measurements)) + "\n");
    }

    public void open(Path file) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        waitFor(() -> file.equals(frame.currentFile()));
    }
    public void edit(String source) throws Exception { edt(() -> { frame.editor().selectAll(); frame.editor().replaceSelection(source); return null; }); }
    public void clear() throws Exception { edt(() -> { frame.console().clear(); return null; }); }
    public void closeDialogs() throws Exception { edt(() -> { OverlayHost.dialogs(frame).forEach(EmbeddedDialog::dispose); return null; }); }
    public void command(String id) throws Exception {
        var execution = edt(() -> frame.commandRegistry().execute(id, CommandContext.of("editor", "editable")));
        CommandResult result = execution.stage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        require(result.status() == CommandResult.Status.SUCCEEDED, "Command " + id + " failed: " + result);
    }
    public void click(String title, String label) throws Exception { edt(() -> { button(dialog(title), label).doClick(0); return null; }); }
    public void showMenu(String title) throws Exception {
        edt(() -> {
            JMenuBar menus = (JMenuBar) field(frame, "menus");
            for (int i = 0; i < menus.getMenuCount(); i++) if (menus.getMenu(i).getText().equals(title)) { menus.getMenu(i).doClick(); return null; }
            throw new AssertionError("Missing menu " + title);
        });
    }
    public void closeMenu() throws Exception { edt(() -> { MenuSelectionManager.defaultManager().clearSelectedPath(); return null; }); }
    public EmbeddedDialog dialog(String title) { return OverlayHost.dialogs(frame).stream().filter(d -> d.getTitle().equals(title)).findFirst().orElse(null); }
    public static JButton button(Container root, String text) {
        return descendants(root).stream().filter(JButton.class::isInstance).map(JButton.class::cast).filter(b -> text.equals(b.getText())).findFirst().orElseThrow(() -> new AssertionError("Missing button " + text));
    }
    private static JTextField textField(Container root) { return descendants(root).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow(); }
    private static List<Component> descendants(Container root) {
        if (root == null) throw new AssertionError("Missing dialog");
        List<Component> result = new ArrayList<>();
        for (Component child : root.getComponents()) { result.add(child); if (child instanceof Container container) result.addAll(descendants(container)); }
        return result;
    }
    public static Object field(Object instance, String name) throws Exception { var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
    public void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(10); }
        throw new AssertionError("Timed out; console=" + edt(() -> frame.console().text()));
    }
    public static <T> T edt(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    public static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private String relative(Path path) { return path.startsWith(workspace) ? workspace.relativize(path).toString() : path.getFileName().toString(); }
    private static String commandOutput(String... command) throws Exception { var process = new ProcessBuilder(command).redirectErrorStream(true).start(); String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly(); return result; }
    public TurboFrame frame() { return frame; }
    public Path workspace() { return workspace; }
    public Path output() { return output; }
    public Map<String,Object> metadata() { return metadata; }
    public void chapter(String value) { chapter = value; }
    public void externalTerminal(String value) { externalTerminal = value; }
    public long relativeNanoTime() { return System.nanoTime() - origin; }
    @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }
}
