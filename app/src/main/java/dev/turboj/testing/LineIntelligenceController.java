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
    private final Function<Path,String> executionPolicy;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("turboj-line-evidence").factory());
    private Future<?> pending;
    private volatile long generation;
    private volatile boolean closed;
    private Path project;
    private Path detectedFile;
    private Run run;
    private final CherryRunHistory history = new CherryRunHistory();
    private final javax.swing.Timer executionTimer = new javax.swing.Timer(350,event -> dispatchQueued());
    private QueuedExecution queued;
    private long executionRequest;
    private long savedGeneration;
    private record QueuedExecution(Path source,String executable,BooleanSupplier ready,BooleanSupplier allowed) {}

    public LineIntelligenceController(EditorComponent editor,Supplier<Path> file,TurboTestController tests,
            Path cache,Consumer<Run> results,Consumer<String> output) {
        this(editor,file,tests,cache,results,output,path -> "configured");
    }
    public LineIntelligenceController(EditorComponent editor,Supplier<Path> file,TurboTestController tests,
            Path cache,Consumer<Run> results,Consumer<String> output,Function<Path,String> executionPolicy) {
        this.editor = editor; this.file = file; this.tests = tests; this.cache = cache;
        this.results = results; this.output = output;
        this.executionPolicy = executionPolicy;
        executionTimer.setRepeats(false);
    }
    public Path project() { return Objects.equals(detectedFile,file.get()) ? project : null; }
    public Run run() { return run; }
    public List<Run> runs() { return history.snapshot(); }

    public void changed() {
        requireEdt();
        long ticket = ++generation;
        if (pending != null) pending.cancel(true);
        Path sourceFile = file.get();
        if (!Objects.equals(sourceFile,detectedFile)) {
            detectedFile = null;
            if (project != null && (sourceFile == null || !sourceFile.startsWith(project) && !inContext(sourceFile,run))) changeProject(null);
        }
        if (sourceFile == null) { changeProject(null); editor.setLineIntelligence(null); return; }
        var source = editor.snapshot();
        Object document = editor.document();
        Run evidence = run;
        pending = worker.schedule(() -> {
            try {
                Path detected = detect(sourceFile,evidence);
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
                    if (run == null && loaded != null) { run = loaded; history.accept(loaded); results.accept(loaded); }
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
        if (project != null) clearQueued();
        tests.cherry().detach(); run = null; project = next;
    }

    public boolean queued() { return queued != null; }

    /** Called only after a changed disk save succeeds; all configured phases remain selected. */
    public void saved(Path source,String executable,BooleanSupplier ready,BooleanSupplier allowed) {
        requireEdt();
        if (closed) return;
        savedGeneration++;
        if (run != null) { run = stale(run); history.accept(run); results.accept(run); changed(); }
        scheduleAll(source,executable,ready,allowed);
    }

    public void scheduleAll(Path source,String executable,BooleanSupplier ready,BooleanSupplier allowed) {
        requireEdt();
        if (closed || source == null) return;
        queued = new QueuedExecution(source,executable,ready,allowed);
        executionRequest++;
        executionTimer.restart();
    }

    private void dispatchQueued() {
        requireEdt();
        QueuedExecution request = queued;
        if (closed || request == null) return;
        if (tests.cherry().busy() || !request.ready().getAsBoolean()) { executionTimer.restart(); return; }
        long ticket = executionRequest;
        Path active = file.get();
        Run evidence = run;
        worker.execute(() -> {
            Path root = detect(request.source(),evidence);
            Path activeRoot = active == null ? null : detect(active,evidence);
            SwingUtilities.invokeLater(() -> {
                if (closed || ticket != executionRequest) return;
                if (!Objects.equals(active,file.get())) { executionTimer.restart(); return; }
                if (root == null || !root.equals(activeRoot)) { clearQueued(); return; }
                if (!request.allowed().getAsBoolean()) {
                    clearQueued();
                    output.accept("Cherry Test on save paused: resolve and trust this Maven project through Run or Test.\n");
                    return;
                }
                if (tests.cherry().busy() || !request.ready().getAsBoolean()) { executionTimer.restart(); return; }
                clearQueued();
                changeProject(root);
                detectedFile = active;
                start(request.executable(),List.of());
            });
        });
    }

    private void clearQueued() { queued = null; executionRequest++; executionTimer.stop(); }

    public void cancel() { requireEdt(); clearQueued(); tests.cherry().cancel(); }

    private static Run stale(Run value) {
        String notice = "Saved project changes; previous results are stale.\n";
        Map<Path,Map<Integer,Coverage>> coverage = new LinkedHashMap<>();
        value.coverage().forEach((path,lines) -> {
            Map<Integer,Coverage> stale = new LinkedHashMap<>();
            lines.forEach((line,item) -> stale.put(line,item.withFreshness(Freshness.STALE)));
            coverage.put(path,stale);
        });
        return new Run(value.id(),value.root(),value.state(),value.tests().stream()
                .map(test -> test.withFreshness(Freshness.STALE)).toList(),coverage,value.sourceHashes(),
                value.message().startsWith(notice) ? value.message() : notice + value.message(),value.htmlReport(),
                value.sequence(),value.coverageAvailability(),value.collections().stream()
                        .map(collection -> collection.withFreshness(Freshness.STALE)).toList(),value.context());
    }

    public void start(String executable,List<String> selectors) {
        start(executable,selectors,List.of());
    }
    public void rerun(String executable,Run original,List<TestResult> selected) {
        requireEdt();
        if (original == null || !Objects.equals(original.root(),project()) || selected.isEmpty()) return;
        if (selected.size() > 1000 || selected.stream().anyMatch(test -> !test.runId().equals(original.id()) || test.selector() == null || !test.selector().exact())) {
            output.accept("This adapter did not provide an exact test selector. Use Rerun configured suites for the complete safe scope.\n"); return;
        }
        start(executable,List.of(),selected.stream().map(TestResult::selector).distinct().toList());
    }
    private void start(String executable,List<String> selectors,List<TestSelector> selected) {
        requireEdt();
        if (project() == null) { output.accept("Cherry Test project detection has not resolved this source, or no configuration exists.\n"); return; }
        if (tests.cherry().busy()) { output.accept("Cherry Test is already running.\n"); return; }
        clearQueued();
        Path root = project;
        long sourceGeneration = savedGeneration;
        Path eventFile = historyDirectory(root).resolve("run-" + UUID.randomUUID() + ".jsonl");
        tests.cherry().run(new CherryTestProcess.Request(root,executable,eventFile,selectors,executionPolicy.apply(root),selected),value -> {
            if (!Objects.equals(root,project)) return;
            run = sourceGeneration == savedGeneration ? value : stale(value); history.accept(run); results.accept(run);
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
    private static Path detect(Path file,Run context) {
        return inContext(file,context) ? context.root() : detect(file);
    }
    private static boolean inContext(Path file,Run context) {
        return file != null && context != null && context.context().sourceRoots(context.root()).stream()
                .anyMatch(root -> file.toAbsolutePath().normalize().startsWith(root));
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
        CherryEvents reader = new CherryEvents(root,CherryExecutionReceipt.matchesRetainedReceipt(latest));
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
            Map<dev.turboj.testing.intelligence.CoverageProof,Boolean> checked = new HashMap<>();
            run.coverage().getOrDefault(file,Map.of()).forEach((line,value) -> {
                Coverage current = value;
                if (value.freshness() == Freshness.CURRENT) {
                    if (run.state() != RunState.COMPLETED && run.state() != RunState.FAILED)
                        current = value.withFreshness(Freshness.UNKNOWN);
                    else if (value.proof() != null && !checked.computeIfAbsent(value.proof(),proof -> proof.matchesClasses(run.context().sourceRoots(run.root()))))
                        current = Coverage.unknown(Availability.BYTECODE_MISMATCH,"Compiled classes changed or are unavailable; rerun to refresh coverage");
                }
                if (line <= lineCount) lines.put(line,new Line(git.added().containsKey(line),List.of(),current));
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
        String compilation = String.join(",",lines.values().stream().map(Line::coverage)
                .filter(value -> value.freshness() == Freshness.CURRENT && value.proof() != null)
                .map(value -> value.proof().compilationId()).distinct().sorted().toList());
        var identity = new SourceIdentity(project,git.repository(),project == null ? "" : project.toString(),"",file,
                file + "@" + Integer.toHexString(System.identityHashCode(document)),version,hash,compilation);
        return new Snapshot(identity,lines,git.deleted().stream().map(d -> new Deletion(d.boundary(),d.text())).toList(),
                git.available(),project != null,"HEAD" + (git.baselineBlob().isBlank() ? "" : " · " + git.baselineBlob()),message);
    }

    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Editor lifecycle requires EDT"); }
    @Override public void close() { closed = true; generation++; clearQueued(); if (pending != null) pending.cancel(true); worker.shutdownNow(); }
}
