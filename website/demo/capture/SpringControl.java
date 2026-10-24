import dev.turboj.execution.WorkerClient;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.mavenj.core.MavenImportDefaults;
import dev.turboj.mavenj.workspace.Toolchains;
import dev.turboj.mavenj.workspace.MavenLifecycle;
import dev.turboj.project.MavenCompilationWorkspace;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Headless control only: the final replay must use a separately captured complete UI session. */
public class SpringControl {
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]).toAbsolutePath();
        Path project = Files.createTempDirectory("turboj-spring-control-");
        try (var paths = Files.walk(fixture)) {
            for (Path path : paths.toList()) {
                Path copy = project.resolve(fixture.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else Files.copy(path, copy);
            }
        }
        System.out.println("CONTROL ONLY — " + Instant.now());
        System.out.println("Spring Boot 4.1.1; Java 21 project; pre-existing Maven local repository");
        try (var maven = new DefaultMavenJ(); var compiler = new MavenCompilationWorkspace()) {
            long start = System.nanoTime();
            var request = MavenImportDefaults.withUserRepositories(MavenImportDefaults.online(project));
            var model = Toolchains.select(maven.open(request), Path.of(System.getProperty("user.home"), ".m2", "toolchains.xml")).workspace();
            log("import incl. settings + model + dependency resolution", start);
            System.out.println("resolved=" + model.resolved() + "; cacheHit=" + model.cacheHit());
            for (var diagnostic : model.diagnostics()) System.out.println(clean(diagnostic.message(), project));
            for (var module : model.modules()) {
                System.out.println("module=" + module.coordinates().gav() + "; java=" + module.mainJava().languageLevel()
                        + "; dependencies=" + module.dependencies().size() + "; fastPath=" + module.fastPath());
                for (var diagnostic : module.diagnostics()) System.out.println(clean(diagnostic.message(), project));
            }
            if (!model.resolved()) throw new AssertionError("Import did not resolve");
            start = System.nanoTime();
            var compiled = compiler.compile(model, Map.of(), 1, false);
            log("native compile incl. output publication", start);
            System.out.println("compile=" + compiled.success());
            for (var diagnostic : compiled.diagnostics()) System.out.println(clean(diagnostic.diagnostic().message(), project));
            if (!compiled.success()) throw new AssertionError("Compilation failed");
            var module = model.modules().getFirst();
            if (args.length > 1 && args[1].equals("maven")) {
                long runStart = System.nanoTime();
                var launch = new MavenLifecycle.Request(project, List.of("spring-boot:run"), List.of(),
                        module.mainJava().javaHome(), null, true, false, Duration.ofMinutes(2), Map.of());
                try (var process = MavenLifecycle.start(launch, line -> System.out.println(clean(line, project)))) {
                    boolean ready = false;
                    long deadline = runStart + TimeUnit.SECONDS.toNanos(90);
                    while (System.nanoTime() < deadline && !process.completion().isDone()) {
                        var curl = new ProcessBuilder("curl", "--silent", "--fail", "--max-time", "1", "http://127.0.0.1:18765/hello").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                        String response = new String(curl.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (curl.waitFor() == 0) {
                            log("Maven dispatch until first successful curl (poll every25ms)", runStart);
                            System.out.println("RESPONSE " + response.strip()); ready = true; break;
                        }
                        Thread.sleep(25);
                    }
                    if (!ready) throw new AssertionError("Maven Spring endpoint not available");
                    long curlStart = System.nanoTime();
                    var curl = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", "--max-time", "2", "http://127.0.0.1:18765/hello").redirectErrorStream(true).start();
                    System.out.print(new String(curl.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    System.out.println("curl exit=" + curl.waitFor());
                    log("external curl incl. process creation", curlStart);
                    process.cancel();
                    System.out.println("Maven cancelled=" + process.completion().get(10, TimeUnit.SECONDS).cancelled());
                }
                return;
            }
            try (var pin = compiler.pinExecution(module.id(), false, model.generation(), 1);
                 var worker = new WorkerClient(Duration.ofSeconds(30), 1024 * 1024, 256,
                         module.mainJava().javaHome() == null ? Path.of(System.getProperty("java.home")) : module.mainJava().javaHome())) {
                long runStart = System.nanoTime();
                var execution = worker.run(1, pin.bytecode(), "dev.turboj.demo.HelloService",
                        List.of("--server.address=127.0.0.1", "--server.port=18765", "--spring.output.ansi.enabled=never"),
                        pin.classpath(), args.length > 1 && args[1].equals("isolated"), event -> System.out.print(clean(event.text(), project)));
                String response = "";
                long deadline = runStart + TimeUnit.SECONDS.toNanos(20);
                boolean ready = false;
                while (System.nanoTime() < deadline && !execution.isDone()) {
                    var curl = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", "--max-time", "1", "http://127.0.0.1:18765/hello").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    response = new String(curl.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (curl.waitFor() == 0) { ready = true; log("dispatch until first successful external curl (poll every 25ms)", runStart); break; }
                    Thread.sleep(25);
                }
                System.out.println("\nfirst endpoint ready=" + ready + "; response=" + response.strip());
                Thread.sleep(300);
                long curlStart = System.nanoTime();
                var curl = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", "--max-time", "1", "http://127.0.0.1:18765/hello").redirectErrorStream(true).start();
                System.out.print(new String(curl.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                System.out.println("curl exit=" + curl.waitFor());
                log("external curl after 300ms including process creation", curlStart);
                System.out.println("worker completed=" + execution.isDone());
                if (execution.isDone()) System.out.println("worker result=" + execution.get());
                else worker.stop();
            }
        }
    }
    static String clean(String value, Path project) {
        return value.replace(project.toString(), "<demo-project>")
                .replace(System.getProperty("user.home"), "<home>")
                .replace(System.getProperty("user.dir"), "<working-directory>");
    }
    static void log(String name, long start) {
        System.out.printf(Locale.ROOT, "MEASURE %s: %.3f ms (System.nanoTime)\n", name, (System.nanoTime()-start)/1_000_000.0);
    }
}
