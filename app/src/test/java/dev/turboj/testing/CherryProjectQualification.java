package dev.turboj.testing;

import dev.turboj.testing.intelligence.CherryEvents;
import dev.turboj.testing.intelligence.GitBufferDiff;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.nio.file.*;
import java.util.*;

/** Offline acceptance against real, retained CLI events and their original artifacts. */
public final class CherryProjectQualification {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("root events verified|unverified");
        Path root = Path.of(args[0]).toRealPath();
        CherryEvents reader = new CherryEvents(root);
        for (String event : Files.readAllLines(Path.of(args[1]))) reader.accept(event);
        require(reader.terminal(), "real execution has terminal event");
        Run run = reader.snapshot();
        System.out.println("Run " + run.id() + " " + run.state() + " collection=" + run.coverageAvailability());
        Map<String, Long> tests = new TreeMap<>();
        run.tests().forEach(t -> tests.merge(t.project() + "/" + t.module() + "/" + t.phase() + "/" + t.runtime() + "/" + t.status(), 1L, Long::sum));
        tests.forEach((scope, count) -> System.out.println("Tests " + scope + " = " + count));
        require(run.tests().stream().map(TestResult::id).distinct().count() == run.tests().size(), "test identities are unique across scopes");
        long current = 0;
        for (Path source : run.coverage().keySet().stream().sorted().toList()) {
            String original = Files.readString(source);
            Snapshot snapshot = snapshot(root, source, original, run);
            Map<String, Long> states = new TreeMap<>();
            snapshot.lines().values().forEach(l -> states.merge(l.coverage().state() + "/" + l.coverage().freshness() + "/" + l.coverage().precision(), 1L, Long::sum));
            System.out.println("Source " + root.relativize(source) + " " + states);
            List<Coverage> certified = snapshot.lines().values().stream().map(Line::coverage)
                    .filter(c -> c.freshness() == Freshness.CURRENT).toList();
            current += certified.size();
            require(certified.stream().allMatch(c -> c.proof() != null), "current coverage has proof");
            require(snapshot(root, source, original + "\n// qualification edit\n", run).lines().values().stream()
                    .noneMatch(l -> l.coverage().availability() == Availability.AVAILABLE), "unsaved edit withholds historical positions");
            for (var proof : certified.stream().map(Coverage::proof).distinct().toList()) {
                System.out.println("Proof compiler=" + proof.compilerIdentity() + " compilation=" + proof.compilationId()
                        + " process=" + proof.processId() + " session=" + proof.sessionId());
                Path classFile = root.resolve(proof.classFiles().keySet().iterator().next()).normalize();
                require(classFile.toRealPath().startsWith(root) && root.startsWith(Path.of("/private/tmp")),
                        "bytecode tampering is restricted to disposable projects under /private/tmp");
                byte[] bytes = Files.readAllBytes(classFile);
                try {
                    byte[] altered = bytes.clone(); altered[altered.length - 1] ^= 1;
                    Files.write(classFile, altered);
                    require(snapshot(root, source, original, run).lines().values().stream()
                            .anyMatch(l -> l.coverage().availability() == Availability.BYTECODE_MISMATCH), "changed class bytes reject certification");
                } finally { Files.write(classFile, bytes); }
                require(snapshot(root, source, original, run).lines().values().stream()
                        .anyMatch(l -> l.coverage().freshness() == Freshness.CURRENT), "exact source and bytecode restoration recovers coverage");
            }
            require(snapshot.lines().values().stream().flatMap(l -> l.tests().stream()).flatMap(t -> t.associations().stream())
                    .noneMatch(a -> a.kind() == Attribution.DYNAMIC_EXECUTION), "aggregate coverage fabricates no dynamic test attribution");
        }
        require(args[2].equals("verified") ? current > 0 : current == 0, "expected provenance qualification: " + args[2]);
        System.out.println("PASS currentLines=" + current + " tests=" + run.tests().size() + " files=" + run.coverage().size());
    }

    private static Snapshot snapshot(Path root, Path source, String text, Run run) {
        return LineIntelligenceController.snapshot(source, text, 1, source, root,
                new GitBufferDiff.Result(root, "HEAD", "", Map.of(), List.of(), "Git is outside this evidence control"), run);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
