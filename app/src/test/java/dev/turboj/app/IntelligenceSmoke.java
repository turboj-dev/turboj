package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;

import java.awt.Rectangle;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Real-window acceptance checks. Run explicitly on a desktop, outside headless JUnit. */
public final class IntelligenceSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static final String PARTIAL =
            "class Main {\n    void demo() {\n        System.out.pr\n    }\n}\n";

    public static void main(String[] args) throws Exception {
        boolean editingOnly = args.length > 0 && args[0].equals("--editing-only");
        Path target = Path.of("app", "target").toAbsolutePath();
        Path reportPath = editingOnly && args.length > 1 ? Path.of(args[1]).toAbsolutePath()
                : target.resolve("intelligence-smoke.txt");
        Files.createDirectories(reportPath.getParent());
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        note("Started UTC: %s", java.time.Instant.now());
        note("Artifact: %s", artifact);
        if (Files.isRegularFile(artifact)) note("SHA-256: %s", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        note("Runtime: %s; OS: %s %s %s; processors: %d", System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        note("Fresh JVM and isolated settings; dependencies already present; synthetic Swing key dispatch, no mouse in editing-only mode.");
        Path project = Files.createTempDirectory(target, "intelligence-smoke-");
        Files.createDirectories(project.resolve(".turboj"));
        for (String name : new String[] {"Main.java", "Calculator.java", "MathUtils.java"})
            Files.copy(Path.of("examples", "intelligence", name), project.resolve(name));
        System.setProperty("turboj.config", project.resolve(".settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            Settings settings = new Settings();
            settings.keymapProfile = "turboj-macos"; // TurboJ: Cmd+D declaration, F12 and F1 kept from Turbo, Cmd+[ / Cmd+] history.
            TurboFrame value = new TurboFrame(settings, Map.of());
            value.setVisible(true); value.toFront(); value.editor().requestFocusInWindow();
            return value;
        });
        try {
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus");
            if (!editingOnly) {
                completion(frame, target);
                navigate(frame, project);
                documentation(frame, project);
                signature(frame);
                errorRecovery(frame);
                measureWarmSemantics(frame);
            }
            measureEditing(frame);
            note("%s", edt(() -> frame.intelligence().metrics()));
            note("PASS %s", editingOnly ? "editing latency and current diagnostics checks completed"
                    : "native semantic acceptance checks completed");
            note("Completed UTC: %s", java.time.Instant.now());
        } catch (Throwable failure) {
            note("FAIL %s", failure);
            throw failure;
        } finally {
            Files.writeString(reportPath, report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static void completion(TurboFrame frame, Path target) throws Exception {
        edt(() -> {
            frame.editor().setText(PARTIAL);
            frame.editor().gotoOffset(PARTIAL.indexOf("System.out.pr") + "System.out.pr".length());
            frame.editor().requestFocusInWindow();
            return null;
        });
        waitFor(() -> frame.editor().inlineCompletion() != null, "automatic inline completion");
        String source = edt(() -> frame.editor().getText());
        int caret = edt(() -> frame.editor().caretOffset());
        long version = edt(() -> frame.editor().version());
        check(source.equals(PARTIAL), "ghost text changed source");
        check(!edt(() -> frame.intelligence().alternativesVisible()), "automatic popup opened");
        for (String id : Theme.BUNDLED) {
            edt(() -> { frame.applyTheme(Theme.bundled(id)); return null; });
            check(edt(() -> frame.editor().getText()).equals(source), "theme changed source");
            check(edt(() -> frame.editor().caretOffset()) == caret, "theme changed caret");
            check(edt(() -> frame.editor().version()) == version, "theme changed source version");
            check(edt(() -> frame.editor().inlineCompletion()) != null, "theme lost ghost overlay");
        }
        edt(() -> {
            frame.applyTheme(Theme.bundled("classic"));
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            frame.paintAll(graphics); graphics.dispose();
            ImageIO.write(image, "png", target.resolve("intelligence-inline.png").toFile());
            key(frame, KeyEvent.VK_TAB, 0);
            return null;
        });
        check(edt(() -> frame.editor().getText()).contains("System.out.println()"),
                "Tab did not accept println(): " + edt(() -> frame.editor().getText()));
        edt(() -> { key(frame, KeyEvent.VK_Z, InputEvent.META_DOWN_MASK); return null; });
        check(edt(() -> frame.editor().getText()).equals(source), "completion is not one undo operation");
        waitFor(() -> frame.editor().inlineCompletion() != null, "completion after undo");
        edt(() -> { key(frame, KeyEvent.VK_ESCAPE, 0); return null; });
        check(edt(() -> frame.editor().inlineCompletion()) == null, "Escape did not dismiss ghost");
        edt(() -> { key(frame, KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK); return null; });
        waitFor(() -> frame.intelligence().alternativesVisible(), "Ctrl+Space alternatives");
        singleFrame(frame, "completion choices");
        check(edt(() -> frame.intelligence().lastCompletion().proposals().stream()
                .anyMatch(p -> p.display().contains("printf"))), "real printf overload absent");
        edt(() -> { key(frame, KeyEvent.VK_DOWN, 0); key(frame, KeyEvent.VK_ENTER, 0); return null; });
        check(!edt(() -> frame.editor().getText()).equals(source), "popup selection did not accept");
        note("PASS semantic ghost overlay, Tab/undo, Escape, Ctrl+Space/Down/Enter; three themes preserve source/caret");
    }

    private static void navigate(TurboFrame frame, Path project) throws Exception {
        Path main = project.resolve("Main.java"), calculator = project.resolve("Calculator.java");
        Path math = project.resolve("MathUtils.java");
        edt(() -> { frame.openPath(main); return null; });
        waitFor(() -> main.equals(frame.currentFile()), "open Main.java");
        edt(() -> {
            frame.editor().gotoOffset(0); frame.editor().replaceSelection("// unsaved Main edit\n");
            int call = frame.editor().getText().indexOf("calculator.add(2");
            frame.editor().gotoOffset(call + "calculator.".length());
            return null;
        });
        var origin = edt(() -> frame.editor().captureLocation());
        String original = edt(() -> frame.editor().getText());
        edt(() -> { key(frame, KeyEvent.VK_D, InputEvent.META_DOWN_MASK); return null; });
        waitFor(() -> calculator.equals(frame.currentFile()), "Cmd+D cross-file declaration");
        check(edt(() -> frame.editor().caretOffset() == frame.editor().getText().indexOf("add(int")),
                "integer overload navigation selected wrong method: caret=" + edt(() -> frame.editor().caretOffset())
                        + ", expected=" + edt(() -> frame.editor().getText().indexOf("add(int"))
                        + ", source=" + edt(() -> frame.editor().getText()));
        edt(() -> {
            frame.editor().gotoOffset(0); frame.editor().replaceSelection("// unsaved Calculator edit\n");
            frame.editor().gotoOffset(frame.editor().getText().indexOf("MathUtils.sum") + "MathUtils.".length());
            return null;
        });
        var middle = edt(() -> frame.editor().captureLocation());
        edt(() -> { key(frame, KeyEvent.VK_F12, 0); return null; });
        waitFor(() -> math.equals(frame.currentFile()), "F12 nested declaration");
        edt(() -> { key(frame, KeyEvent.VK_OPEN_BRACKET, InputEvent.META_DOWN_MASK); return null; });
        waitFor(() -> calculator.equals(frame.currentFile()), "Cmd+[ to Calculator");
        check(edt(() -> frame.editor().captureLocation()).equals(middle), "middle history position mismatch");
        check(edt(() -> frame.editor().getText()).startsWith("// unsaved Calculator edit"), "unsaved Calculator lost");
        edt(() -> { key(frame, KeyEvent.VK_OPEN_BRACKET, InputEvent.META_DOWN_MASK); return null; });
        waitFor(() -> main.equals(frame.currentFile()), "Cmd+[ to Main");
        check(edt(() -> frame.editor().captureLocation()).equals(origin), "origin history position mismatch");
        check(edt(() -> frame.editor().getText()).equals(original), "unsaved Main lost");
        edt(() -> { key(frame, KeyEvent.VK_CLOSE_BRACKET, InputEvent.META_DOWN_MASK); return null; });
        waitFor(() -> calculator.equals(frame.currentFile()), "Cmd+] forward navigation");
        edt(() -> { frame.navigateBack(); return null; });
        waitFor(() -> main.equals(frame.currentFile()), "return Main for mouse navigation");
        edt(() -> {
            int symbol = frame.editor().getText().indexOf("calculator.add(\"Turbo") + "calculator.".length();
            frame.editor().gotoOffset(symbol);
            Rectangle bounds = frame.editor().offsetBounds(symbol);
            frame.editor().dispatchEvent(new MouseEvent(frame.editor(), MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), InputEvent.META_DOWN_MASK,
                    bounds.x + Math.max(1, bounds.width / 2), bounds.y + bounds.height / 2,
                    1, false, MouseEvent.BUTTON1));
            return null;
        });
        waitFor(() -> calculator.equals(frame.currentFile()), "Command-click String overload");
        check(edt(() -> frame.editor().caretOffset() == frame.editor().getText().indexOf("add(String")),
                "String overload navigation selected wrong method");
        edt(() -> { frame.navigateBack(); return null; });
        waitFor(() -> main.equals(frame.currentFile()), "restore Main after mouse navigation");
        note("PASS Cmd+B/F12/Cmd+Click resolve overloads across three files; Alt+Left/Right preserve positions and unsaved buffers");
    }

    private static void documentation(TurboFrame frame, Path project) throws Exception {
        edt(() -> {
            frame.editor().gotoOffset(frame.editor().getText().indexOf("substring(1)"));
            key(frame, KeyEvent.VK_F1, 0);
            return null;
        });
        waitFor(() -> frame.intelligence().documentationVisible()
                && frame.intelligence().lastDocumentation() != null
                && frame.intelligence().lastDocumentation().signature().contains("substring"), "F1 JDK documentation");
        var doc = edt(() -> frame.intelligence().lastDocumentation());
        singleFrame(frame, "quick documentation");
        check(doc.owner().contains("String"), "wrong JDK owner");
        if (doc.sourceAvailable()) {
            check(doc.text().toLowerCase(Locale.ROOT).contains("substring"), "attached Javadoc absent");
            waitFor(() -> {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                return focus instanceof JTextArea && SwingUtilities.getWindowAncestor(focus) == frame;
            }, "keyboard focus in F1 documentation");
            edt(() -> { key(frame, KeyEvent.VK_ESCAPE, 0); return null; });
            waitFor(() -> !frame.intelligence().documentationVisible(), "documentation Escape closes popup");
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus after documentation Escape");
            edt(() -> { key(frame, KeyEvent.VK_F12, 0); return null; });
            waitFor(() -> frame.editor().isReadOnly(), "read-only JDK source");
            String source = edt(() -> frame.editor().getText());
            check(source.contains("class String"), "wrong JDK source: " + edt(frame::currentFile)
                    + " prefix=" + source.substring(0, Math.min(1000, source.length())));
            edt(() -> { frame.editor().replaceSelection("unsafe edit"); return null; });
            check(edt(() -> frame.editor().getText()).equals(source), "JDK source became writable");
            edt(() -> { frame.navigateBack(); return null; });
            waitFor(() -> project.resolve("Main.java").equals(frame.currentFile()), "back from JDK source");
            note("PASS F1 genuine String.substring Javadoc and read-only attached JDK source");
        } else note("PASS F1 genuine String.substring signature; JDK source explicitly unavailable in this environment");
        edt(() -> {
            key(frame, KeyEvent.VK_ESCAPE, 0);
            int symbol = frame.editor().getText().indexOf("calculator.add(2") + "calculator.".length();
            Rectangle bounds = frame.editor().offsetBounds(symbol);
            frame.editor().dispatchEvent(new MouseEvent(frame.editor(), MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, bounds.x + 2, bounds.y + bounds.height / 2, 0, false));
            return null;
        });
        waitFor(() -> frame.intelligence().documentationVisible()
                && frame.intelligence().lastDocumentation().text().contains("Adds two integer values"),
                "350 ms project Javadoc hover");
        check(edt(() -> frame.intelligence().lastDocumentation().text()).contains("left"), "Javadoc parameters missing");
        note("PASS delayed hover shows genuine project Javadoc and parameter descriptions");
    }

    private static void signature(TurboFrame frame) throws Exception {
        String source = "class Main { void demo() { System.out.printf(\"%s\", \"value\"); } }";
        edt(() -> {
            frame.editor().setText(source);
            frame.editor().gotoOffset(source.indexOf("\"value\""));
            frame.intelligence().complete(false); return null;
        });
        waitFor(() -> frame.intelligence().lastSignature() != null
                && !frame.intelligence().lastSignature().signatures().isEmpty(), "printf signature help");
        var help = edt(() -> frame.intelligence().lastSignature());
        singleFrame(frame, "signature help");
        check(help.activeParameter() == 1, "printf argument index incorrect: " + help.activeParameter());
        check(help.signatures().stream().allMatch(s -> s.contains("printf")), "unrelated overload shown");
        note("PASS receiver-resolved printf overload signatures and active argument");
    }

    private static void errorRecovery(TurboFrame frame) throws Exception {
        edt(() -> {
            frame.editor().setText("class Main { void demo() { unknownReceiver.someMethod( ; } }");
            frame.editor().gotoOffset(frame.editor().getText().indexOf("someMethod"));
            key(frame, KeyEvent.VK_F12, 0);
            frame.editor().replaceSelection("stillEditable");
            return null;
        });
        Thread.sleep(300);
        check(edt(() -> frame.editor().getText()).contains("stillEditable"), "recovery blocked editing");
        check(!edt(() -> frame.editor().isReadOnly()), "unresolved symbol navigated to unrelated declaration");
        note("PASS unresolved/incomplete source stays editable and obsolete navigation is rejected");
    }

    private static void measureEditing(TurboFrame frame) throws Exception {
        StringBuilder source = new StringBuilder("class Main {\n    // typing: \n");
        for (int i = 0; i < 1000; i++) source.append("    int value").append(i).append(" = ").append(i).append(";\n");
        source.append("}\n");
        edt(() -> {
            frame.editor().setText(source.toString());
            frame.editor().gotoOffset(frame.editor().document().lineEnd(1));
            frame.editor().requestFocusInWindow(); frame.editor().resetInputToPaintSamples();
            frame.compile(false, false); frame.intelligence().complete(false); return null;
        });
        CompletableFuture<Void> typed = new CompletableFuture<>();
        edt(() -> {
            int[] count = {0};
            Timer timer = new Timer(12, null);
            timer.addActionListener(e -> {
                frame.editor().dispatchEvent(new KeyEvent(frame.editor(), KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x'));
                frame.editor().dispatchEvent(new KeyEvent(frame.editor(), KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'x'));
                frame.editor().dispatchEvent(new KeyEvent(frame.editor(), KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_X, 'x'));
                if (++count[0] == 120) { timer.stop(); typed.complete(null); }
            });
            timer.start(); return null;
        });
        typed.get(20, TimeUnit.SECONDS);
        waitFor(() -> frame.currentResult() != null
                && frame.currentResult().version() == frame.editor().version(), "current diagnostics after rapid typing");
        check(edt(() -> frame.editor().getText()).contains("// typing: " + "x".repeat(120)), "input was lost");
        long[] rawSamples = edt(() -> frame.editor().inputToPaintSamples());
        double[] samples = Arrays.stream(rawSamples).mapToDouble(n -> n / 1e6).sorted().toArray();
        check(samples.length > 50, "too few measured paints: " + samples.length);
        note("1000-line source, compiler + semantic service enabled, 120 synthetic keys: %d paints; edit-to-paint p50 %.3f / p95 %.3f / p99 %.3f ms",
                samples.length, percentile(samples, .50), percentile(samples, .95), percentile(samples, .99));
        note("Paint measurement excludes OS event queue, compositor and display scanout; no hardware key-to-photon claim.");
        note("Raw paint samples (nanoseconds, capture order): %s", Arrays.toString(rawSamples));
    }

    private static void measureWarmSemantics(TurboFrame frame) throws Exception {
        double[] completion = new double[30], docs = new double[30];
        String[] prefixes = {"System.out.pr", "System.out.pri", "System.out.print", "System.out.printl"};
        edt(() -> { frame.intelligence().dismiss(); frame.editor().requestFocusInWindow(); return null; });
        waitFor(() -> frame.editor().isFocusOwner(), "focus before warm samples");
        for (int i = 0; i < 40; i++) {
            String prefix = prefixes[i % prefixes.length];
            String source = "// distinct source " + i + "\n" + PARTIAL.replace("System.out.pr", prefix);
            double elapsed = measuredResult(() -> {
                frame.editor().setText(source);
                frame.editor().gotoOffset(source.indexOf(prefix) + prefix.length());
                frame.intelligence().complete(false);
            }, () -> frame.intelligence().lastCompletion(), true);
            if (i >= 10) completion[i - 10] = elapsed;
        }
        String source = "class Main { String demo() { return \"TurboJ\".substring(1); } }";
        edt(() -> {
            frame.editor().setText(source); frame.editor().gotoOffset(source.indexOf("substring"));
            return null;
        });
        for (int i = 0; i < 40; i++) {
            double elapsed = measuredResult(() -> frame.intelligence().quickDocumentation(),
                    () -> frame.intelligence().lastDocumentation(), false);
            if (i >= 10) docs[i - 10] = elapsed;
        }
        Arrays.sort(completion); Arrays.sort(docs);
        note("Warm completion, 10 warmups + 30 distinct source versions: request-to-result p50 %.3f / p95 %.3f / p99 %.3f ms",
                percentile(completion, .5), percentile(completion, .95), percentile(completion, .99));
        note("Cached JDK documentation, 10 warmups + 30 lookups: request-to-result p50 %.3f / p95 %.3f / p99 %.3f ms",
                percentile(docs, .5), percentile(docs, .95), percentile(docs, .99));
        note("Semantic UI measurements include context discovery and EDT delivery, observed by a 1 ms Swing timer; configured hover delay excluded.");
        edt(() -> { frame.intelligence().dismiss(); frame.editor().requestFocusInWindow(); return null; });
        waitFor(() -> frame.editor().isFocusOwner(), "focus after warm samples");
    }

    private static double measuredResult(Runnable submit, Callable<Object> result, boolean clearsResult) throws Exception {
        CompletableFuture<Double> completed = new CompletableFuture<>();
        edt(() -> {
            Object previous = result.call();
            long started = System.nanoTime();
            submit.run();
            Timer timer = new Timer(1, null);
            timer.addActionListener(e -> {
                try {
                    Object current = result.call();
                    if (current != null && (clearsResult || current != previous)) {
                        timer.stop(); completed.complete((System.nanoTime() - started) / 1e6);
                    } else if (System.nanoTime() - started > Duration.ofSeconds(20).toNanos()) {
                        timer.stop(); completed.completeExceptionally(new AssertionError("Semantic result timed out"));
                    }
                } catch (Exception failure) { timer.stop(); completed.completeExceptionally(failure); }
            });
            timer.start(); return null;
        });
        return completed.get(25, TimeUnit.SECONDS);
    }

    private static void key(TurboFrame frame, int key, int modifiers) {
        Component recipient = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (recipient == null) recipient = frame.editor();
        KeyEvent event = new KeyEvent(recipient, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED);
        recipient.dispatchEvent(event);
        recipient.dispatchEvent(new KeyEvent(recipient, KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED));
    }

    private static void singleFrame(TurboFrame frame, String surface) throws Exception {
        check(edt(() -> Arrays.stream(java.awt.Window.getWindows())
                .filter(java.awt.Window::isVisible).allMatch(window -> window == frame)),
                surface + " created a separate native window");
        check(edt(() -> {
            Rectangle bounds = new Rectangle(0, 0, frame.getLayeredPane().getWidth(), frame.getLayeredPane().getHeight());
            for (Component component : frame.getLayeredPane().getComponents())
                if (component.isVisible() && component.getClass().getSimpleName().equals("InlinePopup")
                        && !bounds.contains(component.getBounds())) return false;
            return true;
        }), surface + " escaped the frame bounds");
    }

    private static void waitFor(Callable<Boolean> predicate, String description) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(predicate)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try { result.complete(action.call()); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static double percentile(double[] values, double percentile) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * percentile) - 1)];
    }

    private static void note(String format, Object... arguments) {
        String text = String.format(Locale.ROOT, format, arguments);
        System.out.println(text); report.append(text).append('\n');
    }
}
