package dev.turboj.app;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.turboj.ai.*;
import dev.turboj.config.Settings;
import dev.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Real Swing/HTTP/compile/run integration using a protocol fixture, not a model-quality benchmark.
 */
public final class AICompletionSmoke {
    private static final String SOURCE =
            "public class AIDemo {\n"
                + "    static int square(int value) {\n"
                + "        \n"
                + "    }\n"
                + "    public static void main(String[] args) { System.out.println(square(3)); }\n"
                + "}\n";
    private static final String GENERATED = "int result = value * value;\n        return result;";
    private static final StringBuilder report = new StringBuilder();

    private static int cursor() {
        return SOURCE.indexOf("        \n") + 8;
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty(
                "turboj.config", Files.createTempDirectory(output, "ai-smoke-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean delayed = new AtomicBoolean();
        AtomicReference<String> protocolError = new AtomicReference<>();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(
                "/api/show",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] json =
                            "{\"template\":\"{{ if .Suffix }}<|fim_prefix|>{{ .Prompt }}<|fim_suffix|>{{ .Suffix }}<|fim_middle|>{{ end }}\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(json);
                    }
                });
        server.createContext(
                "/api/generate",
                exchange -> {
                    try {
                        var body =
                                JsonParser.parseString(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8))
                                        .getAsJsonObject();
                        if (!body.has("suffix")
                                || !body.get("suffix")
                                        .getAsString()
                                        .contains("public static void main"))
                            protocolError.set("FIM suffix missing");
                        requests.incrementAndGet();
                        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                        exchange.sendResponseHeaders(200, 0);
                        try (var out = exchange.getResponseBody()) {
                            out.write(
                                    "{\"response\":\"int result =\",\"done\":false}\n"
                                            .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(delayed.get() ? 1200 : 80);
                            JsonObject event = new JsonObject();
                            event.addProperty(
                                    "response", GENERATED.substring("int result =".length()));
                            event.addProperty("done", true);
                            out.write((event + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (Exception ignored) {
                        exchange.close();
                    }
                });
        server.start();
        AIConfig defaults = AIConfig.defaults();
        AIConfig local =
                new AIConfig(
                        AIConfig.Mode.LOCAL,
                        AIConfig.LocalBackend.OLLAMA,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "fixture-fim",
                        defaults.cloudEndpoint(),
                        defaults.cloudModel(),
                        false,
                        false,
                        false,
                        200,
                        5,
                        256,
                        12288,
                        defaults.exclusions(),
                        true);
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            var f = new TurboFrame(new Settings(), Map.of());
                            f.setVisible(true);
                            f.toFront();
                            f.editor().requestFocusInWindow();
                            return f;
                        });
        try {
            waitFor(() -> frame.editor().isFocusOwner(), "focus");
            check(requests.get() == 0, "Disabled startup sends no model requests");
            edt(
                    () -> {
                        frame.aiCompletion().configure(local);
                        frame.editor().setText(SOURCE);
                        frame.editor().gotoOffset(cursor());
                        frame.editor().requestFocusInWindow();
                        return null;
                    });
            edt(
                    () -> {
                        frame.aiCompletion().request(true, false);
                        return null;
                    });
            waitFor(() -> frame.editor().isAIInlineCompletion(), "AI ghost");
            check(edt(() -> frame.editor().getText()).equals(SOURCE), "Ghost is not source");
            check(!edt(frame::isRunning), "Generated code never auto-executes");
            for (String id : Theme.BUNDLED) {
                edt(
                        () -> {
                            frame.applyTheme(Theme.bundled(id));
                            return null;
                        });
                check(
                        edt(() -> frame.editor().isAIInlineCompletion()),
                        "Theme preserves AI ghost: " + id);
                BufferedImage shot =
                        edt(
                                () -> {
                                    var b =
                                            new BufferedImage(
                                                    frame.getWidth(),
                                                    frame.getHeight(),
                                                    BufferedImage.TYPE_INT_RGB);
                                    var g = b.createGraphics();
                                    frame.paint(g);
                                    g.dispose();
                                    return b;
                                });
                ImageIO.write(shot, "png", output.resolve("ai-native-" + id + ".png").toFile());
            }
            edt(
                    () -> {
                        press(frame, KeyEvent.VK_TAB, 0);
                        return null;
                    });
            String accepted =
                    SOURCE.substring(0, cursor()) + GENERATED + SOURCE.substring(cursor());
            check(
                    edt(() -> frame.editor().getText()).equals(accepted),
                    "Tab accepts multiline insertion and preserves suffix");
            edt(
                    () -> {
                        frame.editor().undo();
                        return null;
                    });
            check(
                    edt(() -> frame.editor().getText()).equals(SOURCE),
                    "One undo restores original source");
            edt(
                    () -> {
                        frame.editor().redo();
                        frame.compile(true, false);
                        return null;
                    });
            waitFor(
                    () -> frame.console().text().contains("9") && !frame.isRunning(),
                    "Explicit compile/run generated method");
            note(
                    "PASS fixture completion → Tab → one undo/redo → explicit Java compile/run,"
                        + " output9");
            int before = requests.get();
            String member = "class Main {\n    void run() {\n        System.out.pr\n    }\n}\n";
            edt(
                    () -> {
                        frame.editor().setText(member);
                        frame.editor().gotoOffset(member.indexOf(".pr") + 3);
                        frame.editor().requestFocusInWindow();
                        return null;
                    });
            waitFor(
                    () ->
                            frame.editor().inlineCompletion() != null
                                    && !frame.editor().isAIInlineCompletion(),
                    "semantic priority");
            edt(
                    () -> {
                        frame.aiCompletion().request(true, false);
                        return null;
                    });
            Thread.sleep(250);
            check(requests.get() == before, "Semantic completion wins without an AI request");
            delayed.set(true);
            edt(
                    () -> {
                        frame.editor().setText(SOURCE);
                        frame.editor().gotoOffset(cursor());
                        frame.aiCompletion().requestAnother();
                        return null;
                    });
            waitFor(() -> requests.get() > before, "delayed request");
            edt(
                    () -> {
                        frame.editor().setText("class Changed {}\n");
                        return null;
                    });
            Thread.sleep(1400);
            check(
                    !edt(() -> frame.editor().isAIInlineCompletion()),
                    "Canceled old response cannot reappear");
            server.stop(0);
            edt(
                    () -> {
                        frame.editor().setText(SOURCE);
                        frame.editor().gotoOffset(cursor());
                        frame.aiCompletion().requestAnother();
                        return null;
                    });
            Thread.sleep(300);
            edt(
                    () -> {
                        frame.editor().setText(member);
                        frame.editor().gotoOffset(member.indexOf(".pr") + 3);
                        return null;
                    });
            waitFor(
                    () ->
                            frame.editor().inlineCompletion() != null
                                    && !frame.editor().isAIInlineCompletion(),
                    "semantic fallback after server loss");
            check(protocolError.get() == null, "Prefix/suffix protocol checks");
            note(
                    "PASS semantic priority, stale cancellation and offline provider failure"
                        + " preserve Java intelligence");
            note(edt(() -> frame.aiCompletion().metrics()));
            note("PASS native AI integration fixture. No live model or paid cloud account used.");
        } finally {
            server.stop(0);
            Files.writeString(output.resolve("ai-smoke.txt"), report);
            edt(
                    () -> {
                        frame.shutdown();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static void press(TurboFrame frame, int code, int modifiers) {
        frame.editor()
                .dispatchEvent(
                        new KeyEvent(
                                frame.editor(),
                                KeyEvent.KEY_PRESSED,
                                System.currentTimeMillis(),
                                modifiers,
                                code,
                                KeyEvent.CHAR_UNDEFINED));
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        note("PASS " + message);
    }

    private static void note(String message) {
        report.append(message).append('\n');
        System.out.println(message);
    }

    private static void waitFor(Callable<Boolean> condition, String label) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Timeout: " + label);
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var value = new FutureTask<T>(task);
        SwingUtilities.invokeAndWait(value);
        return value.get();
    }
}
