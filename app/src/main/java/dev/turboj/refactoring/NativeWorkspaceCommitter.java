package dev.turboj.refactoring;

import com.google.gson.Gson;
import dev.turboj.config.SafeFiles;
import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optimistic disk/buffer transaction bridge. Disk work and rollback run outside the EDT.
 * The host publishes all buffer changes in one EDT operation only after rechecking versions.
 * Per-file atomic disk writes and a recovery journal provide recoverability, not filesystem-wide atomicity.
 */
public final class NativeWorkspaceCommitter implements AutoCloseable {
    public record BufferSnapshot(Path path, String text, long version, Object identity, String savedSha256) {
        public BufferSnapshot { path = path.toAbsolutePath().normalize(); Objects.requireNonNull(text); Objects.requireNonNull(identity); }
    }
    public record Capture(long revision, Map<Path, BufferSnapshot> buffers) {
        public Capture { buffers = Map.copyOf(buffers); }
    }
    public enum Direction { APPLY, UNDO, REDO }
    public record Publication(Capture capture, Object historyToken) {}
    public record Result(Plan plan, Capture after, String warning) {
        public Result(Plan plan, Capture after) { this(plan, after, ""); }
    }
    public interface Host {
        Capture capture();
        Publication publish(Capture expected, Plan plan, Direction direction, Object historyToken);
        boolean historyReady(Object token, Direction direction);
    }
    private record DiskHistory(String id, Path root, List<Path> roots, List<Path> classpath, List<Path> tracked) {}
    private record Entry(Plan plan, Object token, DiskHistory disk) {}
    private record Marker(int schema, String root, String historyId, boolean beforeApplied, List<String> tracked) {}
    private static final Gson JSON = new Gson();
    private final Host host;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("turboj-refactor-commit").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Deque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private volatile boolean closed;

    public NativeWorkspaceCommitter(Host host) { this.host = Objects.requireNonNull(host); }
    public boolean busy() { return busy.get(); }
    public boolean canUndo() { edtRequired(); return !busy() && !undo.isEmpty() && host.historyReady(undo.peek().token, Direction.UNDO); }
    public boolean canRedo() { edtRequired(); return !busy() && !redo.isEmpty() && host.historyReady(redo.peek().token, Direction.REDO); }
    public CompletableFuture<Result> apply(Plan plan, Capture capture) {
        edtRequired(); Objects.requireNonNull(plan); Objects.requireNonNull(capture);
        if (!plan.valid()) return CompletableFuture.failedFuture(new IllegalArgumentException("The refactoring plan is not valid"));
        return execute(Direction.APPLY, plan, capture, null);
    }
    public CompletableFuture<Result> undo() {
        edtRequired();
        if (!canUndo()) return CompletableFuture.failedFuture(new IllegalStateException("Workspace undo is unavailable"));
        return execute(Direction.UNDO, undo.peek().plan, host.capture(), undo.peek());
    }
    public CompletableFuture<Result> redo() {
        edtRequired();
        if (!canRedo()) return CompletableFuture.failedFuture(new IllegalStateException("Workspace redo is unavailable"));
        return execute(Direction.REDO, redo.peek().plan, host.capture(), redo.peek());
    }

    private CompletableFuture<Result> execute(Direction direction, Plan plan, Capture expected, Entry entry) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        if (closed || !busy.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("A workspace transaction is already running or closed"));
        if (!same(expected, host.capture())) { busy.set(false); return CompletableFuture.failedFuture(stale()); }
        executor.execute(() -> {
            DiskHistory disk = null;
            Path marker = null;
            boolean diskChanged = false;
            Publication published = null;
            try {
                Cancellation cancellation = () -> closed || result.isCancelled();
                cancellation.check();
                if (direction == Direction.APPLY) {
                    PreparedDisk prepared = prepareDisk(plan, expected);
                    if (!prepared.changes.transitions().isEmpty()) {
                        String id = UUID.randomUUID().toString();
                        var tracked = new LinkedHashSet<Path>();
                        prepared.before.sources().stream().map(SourceSnapshot::file)
                                .filter(path -> !path.toString().endsWith(".java")).forEach(tracked::add);
                        prepared.changes.transitions().stream().map(ChangeSet.Transition::path)
                                .filter(path -> !path.toString().endsWith(".java")).forEach(tracked::add);
                        disk = new DiskHistory(id, prepared.before.root(), prepared.before.sourceRoots(), prepared.before.classpath(), List.copyOf(tracked));
                        marker = writeMarker(disk, false);
                        prepared.store.commit(prepared.before, prepared.changes,
                                new WorkspaceStore.Metadata(id, id, WorkspaceStore.Direction.APPLY), cancellation);
                        diskChanged = true;
                    }
                } else if (entry.disk != null) {
                    disk = entry.disk;
                    marker = writeMarker(disk, direction == Direction.UNDO);
                    WorkspaceTransactions transactions = transactions(disk);
                    if (direction == Direction.UNDO) transactions.undo(disk.id, cancellation);
                    else transactions.redo(disk.id, cancellation);
                    diskChanged = true;
                }
                cancellation.check();
                DiskHistory completedDisk = disk;
                Publication publication = onEdt(() -> {
                    if (closed || !same(expected, host.capture())) throw stale();
                    if (entry != null && !host.historyReady(entry.token, direction)) throw stale();
                    Publication value = host.publish(expected, plan, direction, entry == null ? null : entry.token);
                    if (direction == Direction.APPLY) { undo.push(new Entry(plan, value.historyToken, completedDisk)); redo.clear(); }
                    else if (direction == Direction.UNDO) { undo.pop(); redo.push(entry); }
                    else { redo.pop(); undo.push(entry); }
                    return value;
                }).get();
                published = publication;
                // A marker left by a crash before buffer publication can be recovered explicitly.
                // If deletion fails, keep the committed state and report the recovery metadata problem.
                if (marker != null) Files.delete(marker);
                busy.set(false);
                result.complete(new Result(plan, publication.capture));
            } catch (Throwable error) {
                Throwable failure = unwrap(error);
                if (published != null) {
                    // Never roll disk back after buffers have already been published. The user can
                    // continue editing; the retained marker requires explicit recovery on restart.
                    busy.set(false);
                    result.complete(new Result(plan, published.capture,
                            "Refactoring applied, but its recovery marker could not be finalized: " + failure.getMessage()));
                } else if (diskChanged && disk != null) {
                    try {
                        WorkspaceTransactions transactions = transactions(disk);
                        if (direction == Direction.UNDO) transactions.redo(disk.id, Cancellation.NONE);
                        else transactions.undo(disk.id, Cancellation.NONE);
                        if (marker != null) Files.deleteIfExists(marker);
                    } catch (Throwable rollback) {
                        failure.addSuppressed(rollback);
                        failure = new IllegalStateException("Refactoring could not be published and disk rollback needs recovery; external changes were preserved", failure);
                    }
                } else if (marker != null) {
                    // DiskWorkspace keeps its own PREPARED journal if an interrupted rollback needs recovery.
                    try { Files.deleteIfExists(marker); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                if (published == null) { busy.set(false); result.completeExceptionally(failure); }
            } finally { busy.set(false); }
        });
        return result;
    }

    private record PreparedDisk(DiskWorkspace store, WorkspaceSnapshot before, ChangeSet changes) {}

    private PreparedDisk prepareDisk(Plan plan, Capture capture) throws IOException {
        WorkspaceSnapshot semantic = plan.before();
        rejectPending(semantic.root());
        DiskWorkspace store = openTracked(semantic.root(), semantic.sourceRoots(), semantic.classpath(),
                semantic.sources().stream().map(SourceSnapshot::file).toList());
        WorkspaceSnapshot disk = store.snapshot();
        Map<Path, SourceSnapshot> diskSources = new HashMap<>();
        disk.sources().forEach(source -> diskSources.put(source.file(), source));
        // Closed source changes invalidate semantic analysis even if that particular file has no edit.
        for (SourceSnapshot source : semantic.sources()) {
            if (capture.buffers.containsKey(source.file()) && !savesConfiguration(plan, capture.buffers.get(source.file()))) continue;
            SourceSnapshot current = diskSources.get(source.file());
            if (current == null || !current.text().equals(source.text())) throw stale();
        }
        List<FileEdits> edits = plan.edits().stream().filter(edit -> !capture.buffers.containsKey(edit.file())
                || savesConfiguration(plan, capture.buffers.get(edit.file()))).toList();
        List<FileOperation> operations = new ArrayList<>();
        for (FileOperation operation : plan.fileOperations()) {
            BufferSnapshot buffer = capture.buffers.get(operation.path());
            if (buffer != null && operation.kind() != FileOperationKind.CREATE) {
                byte[] actual = SafeFiles.fingerprint(operation.path());
                String fingerprint = actual == null ? null : HexFormat.of().formatHex(actual);
                if (!Objects.equals(buffer.savedSha256, fingerprint))
                    throw new IOException("The saved file changed outside TurboJ: " + operation.path().getFileName());
                // Moving/deleting a never-saved document affects only its editor identity.
                if (actual == null) {
                    if (operation.destination() != null && Files.exists(operation.destination(), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Move destination already exists");
                    continue;
                }
            }
            operations.add(operation);
        }
        Plan diskPlan = new Plan(plan.id(), plan.operation(), disk, plan.affectedSymbols(), edits,
                operations, plan.diagnostics(), plan.status(), plan.description());
        return new PreparedDisk(store, disk, ChangeSet.prepare(diskPlan));
    }

    /** The explicit Add JUnit decision may persist an unmodified POM, never pre-existing unsaved edits. */
    public static boolean savesConfiguration(Plan plan, BufferSnapshot buffer) {
        return buffer != null && plan.operation() == dev.turboj.refactorj.api.Operation.CREATE_CLASS
                && buffer.path().getFileName().toString().equals("pom.xml")
                && Objects.equals(buffer.savedSha256(), HexFormat.of().formatHex(SafeFiles.utf8Fingerprint(buffer.text())));
    }

    private static WorkspaceTransactions transactions(DiskHistory history) {
        return new WorkspaceTransactions(openTracked(history.root, history.roots, history.classpath, history.tracked));
    }

    private static DiskWorkspace openTracked(Path root, List<Path> roots, List<Path> classpath, List<Path> paths) {
        // Configuration edits (POM/JUnit marker) belong to the same transaction as generated Java.
        // DiskWorkspace re-reads these explicit paths and validates them under its normal path guards.
        return new DiskWorkspace(new WorkspaceSnapshot(root, 0, paths.stream().distinct()
                .map(path -> new SourceSnapshot(path, "", 0, false)).toList(), roots, classpath));
    }

    /** Explicit crash-recovery action. Must run off the EDT and before preparing fresh semantic plans. */
    public static void recover(Path root) throws IOException {
        Path real = root.toRealPath();
        Path directory = markerDirectory(real);
        if (!Files.exists(directory)) return;
        DiskWorkspace disk = DiskWorkspace.openRecovering(real, List.of(real), List.of());
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (Files.isSymbolicLink(path) || Files.size(path) > 4096) throw new IOException("Invalid native recovery marker");
                Marker marker = JSON.fromJson(Files.readString(path), Marker.class);
                if (marker == null || marker.schema != 1 || !real.toString().equals(marker.root)
                        || marker.historyId == null || !marker.historyId.matches("[a-f0-9-]{36}")) throw new IOException("Invalid native recovery identity");
                var tracked = marker.tracked == null ? List.<Path>of() : marker.tracked.stream().map(real::resolve).toList();
                WorkspaceTransactions transactions = new WorkspaceTransactions(tracked.isEmpty() ? disk
                        : openTracked(real, List.of(real), List.of(), tracked));
                var history = transactions.history().stream().filter(entry -> entry.id().equals(marker.historyId)).findFirst();
                if (history.isPresent() && history.get().applied() != marker.beforeApplied) {
                    if (marker.beforeApplied) transactions.redo(marker.historyId, Cancellation.NONE);
                    else transactions.undo(marker.historyId, Cancellation.NONE);
                }
                Files.delete(path);
            }
        }
    }

    private static Path writeMarker(DiskHistory history, boolean beforeApplied) throws IOException {
        Path directory = markerDirectory(history.root);
        Files.createDirectories(directory);
        Path file = directory.resolve(history.id + ".json");
        Path temporary = Files.createTempFile(directory, ".native-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(new Marker(1, history.root.toString(), history.id, beforeApplied,
                    history.tracked.stream().map(path -> history.root.relativize(path).toString()).toList())));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return file;
    }

    private static void rejectPending(Path root) throws IOException {
        Path directory = markerDirectory(root);
        if (!Files.exists(directory)) return;
        try (var paths = Files.list(directory)) {
            if (paths.anyMatch(path -> path.getFileName().toString().endsWith(".json")))
                throw new IOException("An interrupted native refactoring needs recovery before applying another plan");
        }
    }
    private static Path markerDirectory(Path root) throws IOException {
        Path parent = root.resolve(".refactorj"), directory = parent.resolve("native-transactions");
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(directory)) throw new IOException("Symbolic recovery directories are not supported");
        return directory;
    }
    private static boolean same(Capture expected, Capture actual) {
        if (expected.revision != actual.revision || !expected.buffers.keySet().equals(actual.buffers.keySet())) return false;
        for (var entry : expected.buffers.entrySet()) {
            BufferSnapshot a = entry.getValue(), b = actual.buffers.get(entry.getKey());
            if (a.identity != b.identity || a.version != b.version || !Objects.equals(a.savedSha256, b.savedSha256)) return false;
        }
        return true;
    }
    private static IllegalStateException stale() { return new IllegalStateException("The workspace changed; prepare the refactoring again"); }
    private static Throwable unwrap(Throwable error) { return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error; }
    private static <T> CompletableFuture<T> onEdt(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> { try { result.complete(task.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result;
    }
    private static void edtRequired() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Editor transaction entry points require the EDT"); }
    @Override public void close() { closed = true; executor.shutdown(); }
}
