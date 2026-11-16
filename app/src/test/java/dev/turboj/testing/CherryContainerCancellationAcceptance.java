package dev.turboj.testing;

import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Opt-in real Docker cancellation, not part of the default unit test suite. */
public final class CherryContainerCancellationAcceptance {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[1]).toAbsolutePath();
        if (!root.startsWith(Path.of("/private/tmp"))) throw new IllegalArgumentException("Use a disposable workspace");
        Files.createDirectories(root.resolve("fixture")); Files.writeString(root.resolve("fixture/package.json"),"{}");
        Files.writeString(root.resolve("cherry-test.yaml"),"""
                defaults:
                  coverage: false
                  open_report: false
                  container:
                    image: postgres:16-alpine
                    pull: never
                    timeout: 180s
                projects:
                  cancellation:
                    path: .
                    services:
                      db:
                        image: postgres:16-alpine
                        port: 5432
                        kind: postgres
                        env: {POSTGRES_PASSWORD: disposable-cancellation-test}
                        tmpfs: [/var/lib/postgresql/data]
                        health:
                          exec: [pg_isready, -h, 127.0.0.1, -p, "5432"]
                          timeout: 120s
                    packages:
                      waiting:
                        manifest: fixture/package.json
                        kinds: [integration]
                        integration:
                          adapter: command
                          command: sh
                          scope: cancellation
                          args: [-c, 'sleep 120']
                """);
        var updates=new CopyOnWriteArrayList<Run>();
        try(var process=new CherryTestProcess()) {
            SwingUtilities.invokeAndWait(() -> process.run(new CherryTestProcess.Request(root,args[0],root.resolve("events.jsonl"),List.of(),"container"),updates::add,System.out::print));
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(3);
            String runId="";
            while(System.nanoTime()<deadline) {
                if(!updates.isEmpty()) runId=updates.getLast().id();
                if(!runId.isBlank() && containers(runId).lines().count() >= 2) break;
                if(!process.busy()) throw new AssertionError("Runner stopped before cancellation control");
                Thread.sleep(100);
            }
            if(runId.isBlank() || containers(runId).isBlank()) throw new AssertionError("No real owned containers started");
            System.out.println("CANCEL owned run="+runId);
            SwingUtilities.invokeAndWait(process::cancel);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
            while(process.busy() && System.nanoTime()<deadline) Thread.sleep(50);
            if(process.busy() || updates.getLast().state()!=RunState.CANCELLED) throw new AssertionError("Cancellation not delivered");
            if(!containers(runId).isBlank()) throw new AssertionError("Owned containers survived cancellation: "+runId);
            if(updates.getLast().tests().stream().anyMatch(t->t.status()==TestStatus.PASSED)) throw new AssertionError("Cancelled sleep manufactured a pass");
            System.out.println("PASS native cancellation; owned containers removed; no invented success");
        }
    }
    private static String containers(String runId) throws Exception {
        Process p=new ProcessBuilder("docker","ps","-aq","--filter","label=cherry-test.run="+runId).redirectErrorStream(true).start();
        String result=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(p.waitFor()!=0) throw new IllegalStateException(result);return result.strip();
    }
}
