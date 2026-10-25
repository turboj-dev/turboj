package dev.turboj.testing;

import dev.turboj.testing.intelligence.*;
import dev.turboj.ui.EditorComponent;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Projects immutable Git and Cherry evidence onto an editor revision outside the EDT. */
public final class LineIntelligenceController implements AutoCloseable {
    private final EditorComponent editor;
    private final Supplier<Path> file;
    private final TurboTestController tests;
    private final Path cache;
    private final Consumer<Run> results;
    private final Consumer<String> output;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("turboj-line-evidence").factory());
    private Future<?> pending;
    private volatile long generation;
    private volatile boolean closed;
    private Path project;
    private Path detectedFile;
    private Run run;

    public LineIntelligenceController(EditorComponent editor,Supplier<Path> file,TurboTestController tests,
            Path cache,Consumer<Run> results,Consumer<String> output) {
        this.editor = editor; this.file = file; this.tests = tests; this.cache = cache;
        this.results = results; this.output = output;
    }
    public Path project() { return Objects.equals(detectedFile,file.get()) ? project : null; }
    public Run run() { return run; }

    public void changed() {
        requireEdt();
        long ticket = ++generation;
        if (pending != null) pending.cancel(true);
        Path sourceFile = file.get();
        if (!Objects.equals(sourceFile,detectedFile)) {
            detectedFile = null;
            if (project != null && (sourceFile == null || !sourceFile.startsWith(project))) changeProject(null);
        }
        if (sourceFile == null) { changeProject(null); editor.setLineIntelligence(null); return; }
        var source = editor.snapshot();
        Object document = editor.document();
        Run evidence = run;
        pending = worker.schedule(() -> {
            try {
                Path detected = detect(sourceFile);
                SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != generation || editor.document() != document) return;
                    changeProject(detected);
                    detectedFile = sourceFile;
                });
                String text = source.toString();
                var git = GitBufferDiff.compute(sourceFile,text,"HEAD");
                Run applicable = evidence != null && Objects.equals(evidence.root(),detected) ? evidence : null;
                if (applicable == null && detected != null) applicable = history(detected);
                Snapshot decoration = snapshot(sourceFile,text,source.version(),document,detected,git,applicable);
                Run loaded = applicable;
                SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != generation || editor.document() != document || editor.version() != source.version()) return;
                    changeProject(detected);
                    detectedFile = sourceFile;
                    if (run == null && loaded != null) { run = loaded; results.accept(loaded); }
                    editor.setLineIntelligence(decoration);
                });
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException | RuntimeException failure) {
                SwingUtilities.invokeLater(() -> { if (!closed && ticket == generation) output.accept("Line intelligence: " + failure.getMessage() + "\n"); });
            }
        },160,TimeUnit.MILLISECONDS);
    }

    private void changeProject(Path next) {
        if (Objects.equals(project,next)) return;
        tests.cherry().detach(); run = null; project = next;
    }

    public void start(String executable,List<String> selectors) {
        requireEdt();
        if (project() == null) { output.accept("Cherry Test project detection has not resolved this source, or no configuration exists.\n"); return; }
        if (tests.cherry().busy()) { output.accept("Cherry Test is already running.\n"); return; }
        Path root = project;
        Path eventFile = historyDirectory(root).resolve("run-" + UUID.randomUUID() + ".jsonl");
        tests.cherry().run(new CherryTestProcess.Request(root,executable,eventFile,selectors),value -> {
            if (!Objects.equals(root,project)) return;
            run = value; results.accept(value);
            if (Objects.equals(detectedFile,file.get())) changed();
        },output);
    }

    public static Path detect(Path file) {
        for (Path parent = file.toAbsolutePath().normalize().getParent(); parent != null; parent = parent.getParent()) {
            for (String name : List.of("cherry-test.yaml","cherry-test.yml","cherry-test.toml"))
                if (Files.isRegularFile(parent.resolve(name))) return parent;
        }
        return null;
    }

    private Run history(Path root) throws IOException {
        Path directory = historyDirectory(root);
        if (!Files.isDirectory(directory)) return null;
        Path latest;
        try (var files = Files.list(directory)) {
            latest = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).max(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException ignored) { return 0L; }
            })).orElse(null);
        }
        if (latest == null || Files.size(latest) > 64L * 1024 * 1024) return null;
        CherryEvents reader = new CherryEvents(root);
        try (BufferedReader lines = Files.newBufferedReader(latest)) {
            for (String line; (line = lines.readLine()) != null;) if (!line.isBlank()) reader.accept(line);
        } catch (IOException | RuntimeException incomplete) { return reader.interrupted(false,"Historical Cherry report is incomplete: " + incomplete.getMessage()); }
        Run reported = reader.terminal() ? reader.snapshot() : reader.interrupted(false,"Historical Cherry execution did not complete");
        return TestSourceResolver.resolve(CherryExecutionReceipt.restore(latest,reported));
    }
    private Path historyDirectory(Path root) { return cache.resolve(sha256(root.toString()).substring(0,24)); }

    public static Snapshot snapshot(Path file,String text,long version,Object document,Path project,
            GitBufferDiff.Result git,Run run) {
        String hash = sha256(text);
        int lineCount = text.split("\n",-1).length;
        Map<Integer,Line> lines = new TreeMap<>();
        Availability availability = run == null ? Availability.UNKNOWN : run.coverageAvailability();
        Coverage empty = Coverage.unknown(availability == Availability.AVAILABLE ? Availability.UNKNOWN : availability,
                "No verified coverage for this line; run collection: " + availability);
        git.added().keySet().stream().filter(line -> line <= lineCount).forEach(line -> lines.put(line,new Line(true,List.of(),empty)));
        boolean sameSource = run != null && hash.equals(run.sourceHashes().get(file.toAbsolutePath().normalize()));
        if (run != null && sameSource) {
            run.coverage().getOrDefault(file,Map.of()).forEach((line,value) -> {
                if (line <= lineCount) lines.put(line,new Line(git.added().containsKey(line),List.of(),value));
            });
            for (TestResult test : run.tests()) for (Association association : test.associations()) {
                if (!association.verified() || !file.equals(association.file()) || association.line() > lineCount) continue;
                Line existing = lines.getOrDefault(association.line(),new Line(false,List.of(),empty));
                List<TestResult> associated = new ArrayList<>(existing.tests()); associated.add(test);
                lines.put(association.line(),new Line(existing.added(),associated,existing.coverage()));
            }
        }
        String message = run == null ? project == null ? git.diagnostic() : "Cherry configuration detected; no execution evidence"
                : run.message() + "\nCoverage collection: " + run.coverageAvailability()
                + (sameSource ? "" : "\nSource differs or has no recorded hash: historical line associations are withheld.");
        var identity = new SourceIdentity(project,git.repository(),project == null ? "" : project.toString(),"",file,
                file + "@" + Integer.toHexString(System.identityHashCode(document)),version,hash,"");
        return new Snapshot(identity,lines,git.deleted().stream().map(d -> new Deletion(d.boundary(),d.text())).toList(),
                git.available(),project != null,"HEAD" + (git.baselineBlob().isBlank() ? "" : " · " + git.baselineBlob()),message);
    }

    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Editor lifecycle requires EDT"); }
    @Override public void close() { closed = true; generation++; if (pending != null) pending.cancel(true); worker.shutdownNow(); }
}
