package dev.turboj.ai;

import com.sun.net.httpserver.HttpServer;
import dev.turboj.ui.themes.Theme;
import dev.turboj.ui.MenuStyler;
import dev.turboj.commandj.swing.OverlayHost;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Real settings window with an isolated protocol fixture and memory-only test credentials. */
public final class AISettingsSmoke {
    public static void main(String[] args) throws Exception {
        var reads = new AtomicInteger();
        var writes = new AtomicInteger();
        var deletes = new AtomicInteger();
        var values = new ConcurrentHashMap<String, String>();
        var requestBody = new AtomicReference<String>();
        CredentialStore credentials =
                new CredentialStore() {
                    private void background() {
                        if (SwingUtilities.isEventDispatchThread())
                            throw new AssertionError("Credential access on EDT");
                    }

                    public String read(String endpoint) {
                        background();
                        reads.incrementAndGet();
                        return values.getOrDefault(endpoint, "");
                    }

                    public void write(String endpoint, char[] value) {
                        background();
                        writes.incrementAndGet();
                        values.put(endpoint, new String(value));
                    }

                    public void delete(String endpoint) {
                        background();
                        deletes.incrementAndGet();
                        values.remove(endpoint);
                    }
                };
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/show",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body =
                            "{\"template\":\"{{ .Prompt }}{{ .Suffix }}\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        var applied = new AtomicReference<AIConfig>();
        JFrame owner =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            MenuStyler.install(Theme.bundled("classic"));
                            JFrame frame = new JFrame("AI settings acceptance");
                            frame.setSize(900, 650);
                            frame.setVisible(true);
                            return frame;
                        });
        try {
            AISettingsDialog dialog = open(owner, AIConfig.defaults(), credentials, applied);
            check(edt(() -> SwingUtilities.getWindowAncestor(dialog) == owner), "AI settings stay inside the owner frame");
            check(edt(() -> Arrays.stream(Window.getWindows()).filter(Window::isShowing).count()) == 1, "AI settings create no secondary native window");
            check(
                    reads.get() == 0 && writes.get() == 0,
                    "Opening settings does not access credentials");
            edt(
                    () -> {
                        named(dialog, "ai-mode", JComboBox.class)
                                .setSelectedItem(AIConfig.Mode.LOCAL);
                        named(dialog, "ai-local-endpoint", JTextField.class)
                                .setText("http://127.0.0.1:" + server.getAddress().getPort());
                        named(dialog, "ai-test", JButton.class).doClick(0);
                        return null;
                    });
            long start = System.nanoTime();
            edt(() -> null);
            double pingMillis = (System.nanoTime() - start) / 1e6;
            waitFor(() -> named(dialog, "ai-test", JButton.class).isEnabled());
            check(
                    edt(
                            () ->
                                    named(dialog, "ai-settings-status", JLabel.class)
                                            .getText()
                                            .contains("suffix-aware")),
                    "Availability result shown");
            check(
                    requestBody.get() != null
                            && !requestBody.get().contains("prompt")
                            && !requestBody.get().contains("suffix"),
                    "Connection test sent model metadata only");
            check(reads.get() == 0, "Local connection test never reads cloud credentials");
            Path target = Path.of("app/target/ai-settings");
            Files.createDirectories(target);
            for (String theme : new String[] {"classic", "dark", "light"}) {
                edt(
                        () -> {
                            Theme palette = Theme.bundled(theme);
                            MenuStyler.install(palette);
                            OverlayHost.forOwner(owner).setTheme(palette.color("ui", "surface"),
                                    palette.color("ui", "foreground"), palette.color("ui", "border"));
                            dialog.setTheme(palette);
                            dialog.validate();
                            checkExplanationContrast(dialog, palette.color("ui", "surface"));
                            check(MenuStyler.contrast(named(dialog, "ai-settings-status", JLabel.class)
                                    .getForeground(), palette.color("ui", "surface")) >= 4.5,
                                    "AI status text remains readable in " + theme);
                            check(MenuStyler.contrast(UIManager.getColor("TabbedPane.selectionForeground"),
                                    UIManager.getColor("TabbedPane.selected")) >= 4.5,
                                    "Selected AI tab remains readable in " + theme);
                            BufferedImage capture =
                                    new BufferedImage(
                                            dialog.getWidth(),
                                            dialog.getHeight(),
                                            BufferedImage.TYPE_INT_RGB);
                            Graphics2D graphics = capture.createGraphics();
                            dialog.paint(graphics);
                            graphics.dispose();
                            ImageIO.write(capture, "png", target.resolve(theme + ".png").toFile());
                            return null;
                        });
            }
            edt(
                    () -> {
                        named(dialog, "ai-apply", JButton.class).doClick(0);
                        return null;
                    });
            waitFor(() -> dialog.isClosed());
            check(
                    applied.get().mode() == AIConfig.Mode.LOCAL && reads.get() == 0,
                    "Local apply persists no secret");

            AISettingsDialog cloud = open(owner, applied.get(), credentials, applied);
            edt(
                    () -> {
                        named(cloud, "ai-mode", JComboBox.class)
                                .setSelectedItem(AIConfig.Mode.CLOUD);
                        named(cloud, "ai-api-key", JPasswordField.class)
                                .setText("not-a-live-api-key-test-only");
                        named(cloud, "ai-cloud-consent", JCheckBox.class).setSelected(true);
                        javax.swing.Timer answer =
                                new javax.swing.Timer(
                                        50,
                                        e -> {
                                            check(Arrays.stream(Window.getWindows()).filter(Window::isShowing).count() == 1, "Cloud confirmation stays in the main frame");
                                            JOptionPane pane = namedType(owner, JOptionPane.class);
                                            if (pane != null) pane.setValue(JOptionPane.OK_OPTION);
                                        });
                        answer.start();
                        try {
                            named(cloud, "ai-apply", JButton.class).doClick(0);
                        } finally {
                            answer.stop();
                        }
                        return null;
                    });
            waitFor(() -> cloud.isClosed());
            check(
                    applied.get().mode() == AIConfig.Mode.CLOUD && applied.get().cloudConsent(),
                    "Cloud requires and retains explicit consent");
            check(
                    writes.get() == 1 && reads.get() == 1,
                    "Credential stored and read only by background apply");
            int beforeRead = reads.get();
            AISettingsDialog reopened = open(owner, applied.get(), credentials, applied);
            check(
                    reads.get() == beforeRead,
                    "Reopening cloud settings does not load or display existing key");
            edt(
                    () -> {
                        check(
                                named(reopened, "ai-api-key", JPasswordField.class)
                                                .getPassword()
                                                .length
                                        == 0,
                                "Existing key remains hidden");
                        named(reopened, "ai-cloud-automatic", JCheckBox.class).setSelected(true);
                        named(reopened, "ai-cloud-endpoint", JTextField.class)
                                .setText("https://changed.example/v1");
                        check(
                                !named(reopened, "ai-cloud-consent", JCheckBox.class).isSelected(),
                                "Endpoint change revokes source-sharing consent");
                        check(
                                !named(reopened, "ai-cloud-automatic", JCheckBox.class)
                                        .isSelected(),
                                "Endpoint change disables automatic cloud requests");
                        named(reopened, "ai-cancel", JButton.class).doClick(0);
                        return null;
                    });
            check(
                    reads.get() == beforeRead && writes.get() == 1 && deletes.get() == 0,
                    "Cancel has no credential side effect");
            System.out.printf(
                    Locale.ROOT,
                    "PASS AI settings embedded GUI: no source connection test; background Keychain contract;"
                        + " local/cloud apply; explicit consent; endpoint invalidation; Cancel;3"
                        + " theme captures. EDT ping during delayed provider: %.2f ms.%n",
                    pingMillis);
        } finally {
            server.stop(0);
            edt(
                    () -> {
                        for (Window window : Window.getWindows()) window.dispose();
                        return null;
                    });
            values.clear();
        }
    }

    private static AISettingsDialog open(
            JFrame owner,
            AIConfig initial,
            CredentialStore credentials,
            AtomicReference<AIConfig> applied)
            throws Exception {
        AISettingsDialog dialog =
                edt(
                        () ->
                                new AISettingsDialog(
                                        owner, initial, credentials, applied::set, ignored -> {}));
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        waitFor(dialog::isShowing);
        return dialog;
    }

    private static void checkExplanationContrast(Container parent, Color background) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTextArea area && !area.isEditable())
                check(MenuStyler.contrast(area.getForeground(), background) >= 4.5,
                        "AI explanatory text remains readable on the dialog surface");
            if (component instanceof Container child) checkExplanationContrast(child, background);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Component> T named(Container parent, String name, Class<T> type) {
        for (Component c : parent.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c);
            if (c instanceof Container child) {
                T result = named(child, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static <T extends Component> T namedType(Container parent, Class<T> type) {
        if (type.isInstance(parent)) return type.cast(parent);
        for (Component c : parent.getComponents())
            if (c instanceof Container child) {
                T found = namedType(child, type);
                if (found != null) return found;
            }
        return null;
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for settings UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
