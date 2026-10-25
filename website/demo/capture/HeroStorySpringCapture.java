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
public final class HeroStorySpringCapture {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:18765/hello");

    public static void run(HeroStoryCapture capture) throws Exception {
        TurboFrame frame = capture.frame();
        Path fixture = Path.of("website/demo/projects/hero-story/spring-hello").toAbsolutePath();
        Path project = capture.workspace().resolve("spring-hello");
        // Refuse an occupied port; never stop or query a pre-existing server.
        try(var reservation=new java.net.ServerSocket()) {
            reservation.bind(new java.net.InetSocketAddress("127.0.0.1",18765));
        }
        Path preferences=dev.turboj.config.Settings.directory().resolve("maven-projects");
        Files.createDirectories(preferences);
        String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(project.toString().getBytes(StandardCharsets.UTF_8)));
        String repository=Path.of(System.getProperty("user.home"),".m2/repository").toString();
        String settings=Path.of(System.getProperty("user.home"),".m2/settings.xml").toString();
        Files.writeString(preferences.resolve(key+".json"),new com.google.gson.Gson().toJson(Map.of(
            "userSettings",settings,"globalSettings","","localRepository",repository,
            "javaHome","/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home",
            "offline",true,"repositories",List.of("https://repo.maven.apache.org/maven2"),
            "activeProfiles",List.of(),"inactiveProfiles",List.of(),"mavenExecutable","","defaultRepositoryPolicy",false)));
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
                "nativeLimitation", "Prior feasibility controls documented Fast Run and Isolated Run ending worker server threads after main returns. This new session verifies the explicit Maven lifecycle only.",
                "logging", "spring.main.log-startup-info=false omits the local username and working directory from Spring startup identity logs.",
                "cache", "Dependencies and wrapper distribution were already downloaded during control runs. No MavenJ model existed for this fresh workspace."));
        capture.operation("maven-import", "Open Maven project; resolve effective model and dependencies",
                () -> { capture.open(project.resolve("pom.xml")); capture.checkpoint("maven-importing", "Maven project import requested"); },
                () -> settled(frame, project));
        var model = HeroStoryCapture.edt(() -> frame.mavenProjects().workspace());
        HeroStoryCapture.require(model.resolved(), "Spring Maven dependencies did not resolve");
        capture.metadata().put("springImport", Map.of("moduleCount", model.modules().size(),
                "dependencyCount", model.modules().getFirst().dependencies().size(), "cacheHit", model.cacheHit(),
                "mavenJDurationNs", model.durationNanos(), "offline", model.offline(),
                "fastPath", model.modules().getFirst().fastPath().name()));
        Files.writeString(capture.output().resolve("spring-effective-pom.xml"),model.modules().getFirst().effectivePom());
        var module=model.modules().getFirst();
        var imported=new LinkedHashMap<String,Object>();
        imported.put("generation",model.generation()); imported.put("resolved",model.resolved());
        imported.put("module",module.coordinates().toString()); imported.put("java",module.mainJava().toString());
        imported.put("dependencies",module.dependencies().stream().map(Object::toString).toList());
        imported.put("classpaths",module.classpaths().toString()); imported.put("diagnostics",model.diagnostics().stream().map(Object::toString).toList());
        Files.writeString(capture.output().resolve("spring-import.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(imported));
        capture.checked("mavenImported");
        if (!Boolean.TRUE.equals(capture.metadata().get("selectedRepresentativeRun"))) validateLifecycle(capture);
        var version=new ProcessBuilder(project.resolve("mvnw").toString(),"--version").directory(project.toFile()).redirectErrorStream(true);
        version.environment().put("JAVA_HOME","/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home");
        var versionProcess=version.start();
        HeroStoryCapture.require(versionProcess.waitFor(10,TimeUnit.SECONDS),"Maven version probe timed out");
        String versionText=new String(versionProcess.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        capture.metadata().put("mavenVersionObserved",versionText.lines().filter(line->line.startsWith("Apache Maven")).findFirst().orElseThrow());
        capture.metadata().put("projectJdkObserved",versionText.lines().filter(line->line.startsWith("Java version:")).findFirst().orElseThrow());
        HeroStoryCapture.require(versionText.contains("Apache Maven 3.9.11") && versionText.contains("Java version: 21."),"Pinned Maven/JDK version not observed");
        capture.open(project.resolve("src/main/java/dev/turboj/demo/HelloService.java"));
        capture.state("spring-source", "A real Spring Boot controller; Maven build required for the server");
        capture.showMenu("Build");
        capture.state("spring-build-menu", "Choose Run Maven Goal");
        capture.closeMenu();
        capture.recordCommand("maven.build.custom", "Registered CommandJ command opens Maven goal dialog");
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
        HeroStoryCapture.edt(() -> { input(capture.dialog("Input")).setText("spring-boot:run"); return null; });
        capture.state("spring-goal", "Explicit Maven goal: spring-boot:run");
        clickLater(capture, "Input", "OK");
        capture.waitFor(() -> capture.dialog("Project Trust") != null || capture.dialog("Run Maven Build") != null);
        if (HeroStoryCapture.edt(() -> capture.dialog("Project Trust") != null)) {
            capture.state("spring-trust", "Project trust is required before executing Maven");
            clickLater(capture, "Project Trust", "OK");
        }
        capture.waitFor(() -> capture.dialog("Run Maven Build") != null);
        capture.state("spring-confirm", "Review the real Maven command and Java toolchain");
        List<ProcessHandle> owned = new ArrayList<>();
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
            Process process=HeroStoryCapture.edt(() -> {
                Object execution=HeroStoryCapture.field(frame.mavenProjects(),"execution");
                return (Process)HeroStoryCapture.field(execution,"process");
            });
            HeroStoryCapture.require(process.isAlive(),"Owned Maven process must remain alive");
            owned.add(process.toHandle()); owned.addAll(process.descendants().toList());
            var lookup=new ProcessBuilder("/usr/sbin/lsof","-nP","-t","-iTCP:18765","-sTCP:LISTEN").start();
            HeroStoryCapture.require(lookup.waitFor(5,TimeUnit.SECONDS),"Owned server lookup timed out");
            String listeners=new String(lookup.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
            HeroStoryCapture.require(!listeners.isBlank(),"Expected listening Spring process");
            for(String listener:listeners.split("\\s+")) HeroStoryCapture.require(owned.stream().anyMatch(h->Long.toString(h.pid()).equals(listener)),"Endpoint belongs to the owned Maven process tree");
            capture.metadata().put("springOwnership",Map.of("mavenPid",process.pid(),"ownedPids",owned.stream().map(ProcessHandle::pid).toList(),"listenerPids",listeners));
            capture.metadata().put("httpReadiness",Map.of("status",200,"body","Hello from TurboJ!\n","endpoint",ENDPOINT.toString(),"observedAtNs",available.get()-System.nanoTime()+capture.relativeNanoTime()));
            capture.metadata().put("springReadiness", "Background Java HttpClient GET /hello until HTTP 200 with exact expected body. 25 ms between completed probes; timeout500ms. Measurement also includes final EDT paint checkpoint. This is not Spring's internal startup log duration.");
            String displayedCommand = "curl --silent --show-error --fail --connect-timeout 2 --max-time 10 http://127.0.0.1:18765/hello";
            capture.externalTerminal("$ " + displayedCommand + "\n");
            capture.state("curl-command", "External terminal: request the running endpoint");
            capture.operation("curl", "External curl process → response and exit → capture checkpoint", () -> {
                Instant started = Instant.now();
                long begin = System.nanoTime();
                var curl = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", "--connect-timeout", "2", "--max-time", "10", ENDPOINT.toString()).redirectErrorStream(true).start();
                if (!curl.waitFor(12, TimeUnit.SECONDS)) { curl.destroyForcibly(); throw new AssertionError("curl timed out"); }
                String output = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                long elapsed = System.nanoTime() - begin;
                HeroStoryCapture.require(curl.exitValue() == 0 && output.equals("Hello from TurboJ!\n"), "Unexpected curl result: " + output);
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
        capture.waitFor(() -> owned.stream().noneMatch(ProcessHandle::isAlive));
        try(var reservation=new java.net.ServerSocket()) { reservation.bind(new java.net.InetSocketAddress("127.0.0.1",18765)); }
        capture.checked("ownedServerStopped");
        capture.metadata().put("springStopped", true);
        capture.state("spring-stopped", "Stop Maven terminates the demonstration service");
    }

    private static void validateLifecycle(HeroStoryCapture capture) throws Exception {
        capture.recordCommand("maven.build.custom","Control: actual registered Maven goal action");
        SwingUtilities.invokeLater(() -> capture.frame().commandRegistry().execute("maven.build.custom",CommandContext.of("editor","editable")));
        capture.waitFor(() -> capture.dialog("Input")!=null);
        HeroStoryCapture.edt(() -> { input(capture.dialog("Input")).setText("validate"); return null; });
        clickLater(capture,"Input","OK");
        capture.waitFor(() -> capture.dialog("Project Trust")!=null || capture.dialog("Run Maven Build")!=null);
        if(HeroStoryCapture.edt(() -> capture.dialog("Project Trust")!=null)) clickLater(capture,"Project Trust","OK");
        capture.waitFor(() -> capture.dialog("Run Maven Build")!=null);
        capture.clear();
        capture.operation("maven-validate-control","Control: native explicit Maven validate lifecycle goal",()->capture.click("Run Maven Build","OK"),
            ()->!capture.frame().mavenProjects().lifecycleRunning() && capture.frame().console().text().contains("BUILD SUCCESS"));
        capture.checked("mavenValidateLifecycle");
    }

    private static boolean settled(TurboFrame frame, Path project) throws Exception {
        var controller = frame.mavenProjects();
        var pending = (Future<?>) HeroStoryCapture.field(controller, "pending");
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
    private static void clickLater(HeroStoryCapture capture, String title, String label) {
        try { capture.recordCommand(title+" → "+label,"Actual dialog button"); } catch(Exception error) { throw new RuntimeException(error); }
        SwingUtilities.invokeLater(() -> HeroStoryCapture.button(capture.dialog(title), label).doClick(0));
    }
}
