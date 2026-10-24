package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.swing.OverlayHost;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Chapter E uses only existing TurboJ commands. Long-running Boot uses explicit Maven. */
public final class SpringCapture {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:18765/hello");

    public static void run(SessionCapture capture) throws Exception {
        TurboFrame frame = capture.frame();
        Path fixture = Path.of("website/demo/projects/spring-hello").toAbsolutePath();
        Path project = capture.workspace().resolve("spring-hello");
        try (var files = Files.walk(fixture)) {
            for (Path file : files.filter(file -> !file.startsWith(fixture.resolve("target"))
                    && !file.startsWith(fixture.resolve(".cache"))).toList()) {
                Path destination = project.resolve(fixture.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        project.resolve("mvnw").toFile().setExecutable(true);
        capture.chapter("spring-boot");
        capture.closeDialogs();
        capture.clear();
        capture.metadata().put("springBoot", Map.of(
                "version", "4.1.1", "javaRelease", 21, "mavenWrapper", "3.9.11",
                "launch", "Existing Build → Run Maven Goal → spring-boot:run. Real Maven lifecycle, not Fast Run.",
                "nativeLimitation", "Control runs confirmed Fast Run and Isolated Run retire the worker when main returns with Spring server threads still active. This session uses the supported explicit Maven lifecycle instead.",
                "logging", "spring.main.log-startup-info=false omits the local username and working directory from Spring startup identity logs.",
                "cache", "Dependencies and wrapper distribution were already downloaded during control runs. No MavenJ model existed for this fresh workspace."));
        capture.operation("maven-import", "Open Maven project; resolve effective model and dependencies",
                () -> { capture.open(project.resolve("pom.xml")); capture.checkpoint("maven-importing", "Maven project import requested"); },
                () -> settled(frame, project));
        var model = SessionCapture.edt(() -> frame.mavenProjects().workspace());
        SessionCapture.require(model.resolved(), "Spring Maven dependencies did not resolve");
        capture.metadata().put("springImport", Map.of("moduleCount", model.modules().size(),
                "dependencyCount", model.modules().getFirst().dependencies().size(), "cacheHit", model.cacheHit(),
                "mavenJDurationNs", model.durationNanos(), "offline", model.offline(),
                "fastPath", model.modules().getFirst().fastPath().name()));
        capture.open(project.resolve("src/main/java/dev/turboj/demo/HelloService.java"));
        capture.state("spring-source", "A real Spring Boot controller; Maven build required for the server");
        capture.showMenu("Build");
        capture.state("spring-build-menu", "Choose Run Maven Goal");
        capture.closeMenu();
        CompletableFuture<Void> dispatched = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                frame.commandRegistry().execute("maven.build.custom", CommandContext.of("editor", "editable"))
                        .stage().whenComplete((result, failure) -> {
                            if (failure == null) dispatched.complete(null); else dispatched.completeExceptionally(failure);
                        });
            } catch (Throwable failure) { dispatched.completeExceptionally(failure); }
        });
        capture.waitFor(() -> capture.dialog("Input") != null);
        SessionCapture.edt(() -> { input(capture.dialog("Input")).setText("spring-boot:run"); return null; });
        capture.state("spring-goal", "Explicit Maven goal: spring-boot:run");
        clickLater(capture, "Input", "OK");
        capture.waitFor(() -> capture.dialog("Project Trust") != null || capture.dialog("Run Maven Build") != null);
        if (SessionCapture.edt(() -> capture.dialog("Project Trust") != null)) {
            capture.state("spring-trust", "Project trust is required before executing Maven");
            clickLater(capture, "Project Trust", "OK");
        }
        capture.waitFor(() -> capture.dialog("Run Maven Build") != null);
        capture.state("spring-confirm", "Review the real Maven command and Java toolchain");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build()) {
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofMillis(500)).GET().build();
            CompletableFuture<Long> available = new CompletableFuture<>();
            capture.operation("spring-startup", "Run Maven goal → first successful HTTP response → UI paint",
                    () -> {
                        capture.click("Run Maven Build", "OK");
                        Thread.ofVirtual().name("demo-endpoint-probe").start(() -> {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                            while (System.nanoTime() < deadline && !available.isDone()) {
                                try {
                                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                                    if (response.statusCode() == 200 && response.body().equals("Hello from TurboJ!\n")) {
                                        available.complete(System.nanoTime()); return;
                                    }
                                } catch (Exception ignored) { }
                                try { Thread.sleep(25); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
                            }
                            available.completeExceptionally(new AssertionError("Spring endpoint did not become ready"));
                        });
                        capture.checkpoint("spring-starting", "Maven is compiling and starting Spring Boot");
                    }, () -> available.isDone() || dispatched.isCompletedExceptionally());
            available.get(1, TimeUnit.SECONDS);
            capture.metadata().put("springReadiness", "Background Java HttpClient GET /hello until HTTP 200 with exact expected body. 25 ms between completed probes; timeout500ms. Measurement also includes final EDT paint checkpoint. This is not Spring's internal startup log duration.");
            String displayedCommand = "curl --silent --show-error --fail http://127.0.0.1:18765/hello";
            capture.externalTerminal("$ " + displayedCommand + "\n");
            capture.state("curl-command", "External terminal: request the running endpoint");
            capture.operation("curl", "External curl process → response and exit → capture checkpoint", () -> {
                Instant started = Instant.now();
                long begin = System.nanoTime();
                var curl = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", ENDPOINT.toString()).redirectErrorStream(true).start();
                String output = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!curl.waitFor(10, TimeUnit.SECONDS)) { curl.destroyForcibly(); throw new AssertionError("curl timed out"); }
                long elapsed = System.nanoTime() - begin;
                SessionCapture.require(curl.exitValue() == 0 && output.equals("Hello from TurboJ!\n"), "Unexpected curl result: " + output);
                capture.metadata().put("curl", Map.of("startedAt", started.toString(), "durationNs", elapsed,
                        "boundary", "System.nanoTime immediately before ProcessBuilder.start through stdout EOF and waitFor return; includes curl process startup.",
                        "exitCode", curl.exitValue(), "stdout", output, "command", displayedCommand));
                capture.externalTerminal("$ " + displayedCommand + "\n" + output + "[exit 0]\n");
                Files.writeString(capture.output().resolve("curl.txt"), "$ " + displayedCommand + "\n" + output + "[exit 0]\n");
            }, () -> true);
        } finally {
            capture.command("maven.stop");
            capture.waitFor(() -> !frame.mavenProjects().lifecycleRunning());
        }
        capture.metadata().put("springStopped", true);
        capture.state("spring-stopped", "Stop Maven terminates the demonstration service");
    }

    private static boolean settled(TurboFrame frame, Path project) throws Exception {
        var controller = frame.mavenProjects();
        var pending = (Future<?>) SessionCapture.field(controller, "pending");
        return !controller.busy() && controller.workspace() != null && controller.workspace().root().equals(project)
                && (pending == null || pending.isDone());
    }
    private static JTextField input(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JTextField value) return value;
            if (child instanceof Container nested) { var value = input(nested); if (value != null) return value; }
        }
        return null;
    }
    private static void clickLater(SessionCapture capture, String title, String label) {
        SwingUtilities.invokeLater(() -> SessionCapture.button(capture.dialog(title), label).doClick(0));
    }
}
