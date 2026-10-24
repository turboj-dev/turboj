package dev.turboj.refactorj.transactions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Closed-file workspace with per-file atomic replacement and a recoverable multi-file journal.
 * It does not claim filesystem-wide atomicity. Open editor buffers require a host WorkspaceStore.
 */
public final class DiskWorkspace implements WorkspaceStore {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JOURNAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILES = 10_000;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public interface FaultInjector {
        void beforeFile(int index, Path path) throws IOException;

        FaultInjector NONE = (index, path) -> {};
    }

    private record Fingerprint(String key, long modified, long size) {}

    private record Image(String text, long version, List<String> permissions) {}

    private record Entry(String path, Image before, Image after, String beforeKey) {}

    private record Journal(int schemaVersion, String root, long order, Metadata metadata,
            String status, List<Entry> entries) {}

    private final Path root, journals;
    private final FaultInjector fault;
    private WorkspaceSnapshot current;
    private final Map<Path, Fingerprint> fingerprints = new HashMap<> ();
    private long journalOrder;

    public DiskWorkspace(WorkspaceSnapshot initial) {
        this(initial, FaultInjector.NONE, false);
    }

    public DiskWorkspace(WorkspaceSnapshot initial, FaultInjector fault) {
        this(initial, fault, false);
    }

    private DiskWorkspace(WorkspaceSnapshot initial, FaultInjector fault, boolean recoverOnOpen) {
        this.root = initial.root();
        this.journals = root.resolve(".refactorj/transactions");
        this.fault = Objects.requireNonNull(fault);
        this.current = initial;
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath()
                    .equals(root))
                throw new IOException("Workspace root must be a real directory, not a symbolic alias");
            for (var source : initial.sources()) {
                if (source.open())
                    throw new TransactionException("OPEN_BUFFER",
                            "DiskWorkspace never saves unsaved editor buffers; use a host WorkspaceStore");
                guard(source.file());
            }
            if (recoverOnOpen)
                recover();
            else
                rejectPending();
            // A recovered transaction can legitimately restore a different source image.
            var entries = new ArrayList<SourceSnapshot> ();

            for (var source : initial.sources()) {
                String text = read(source.file());

                if (text != null)
                    entries.add(new SourceSnapshot(source.file(), text, source.text()
                            .equals(text) ? source.version() : source.version() + 1, false));
            }
            current = new WorkspaceSnapshot(root, initial.version(), entries, initial.sourceRoots(),
                    initial.classpath());
            captureFingerprints();
            refresh();
        } catch (IOException error) {
            throw failure("WORKSPACE_IO", "Cannot open disk workspace", error);
        }
    }

    public static DiskWorkspace open(Path root, List<Path> sourceRoots, List<Path> classpath) {
        return open(root, sourceRoots, classpath, false);
    }

    /** Explicit recovery entry point; unlike ordinary open/preview, it may restore journaled sources. */
    public static DiskWorkspace openRecovering(Path root, List<Path> sourceRoots,
            List<Path> classpath) {
        return open(root, sourceRoots, classpath, true);
    }

    private static DiskWorkspace open(Path root, List<Path> sourceRoots, List<Path> classpath,
            boolean recover) {
        try {
            Path real = root.toRealPath();
            List<Path> roots = new ArrayList<> ();

            for (Path sourceRoot : sourceRoots)
                roots.add(sourceRoot.toRealPath());
            var workspace = new WorkspaceSnapshot(real, 0, List.of(), roots.isEmpty() ? List
                    .of(real) : roots, classpath);
            return new DiskWorkspace(workspace, FaultInjector.NONE, recover);
        } catch (IOException error) {
            throw failure("WORKSPACE_IO", "Cannot locate workspace", error);
        }
    }

    @Override
    public synchronized WorkspaceSnapshot snapshot() {
        try {
            refresh();
            return current;
        } catch (IOException error) {
            throw failure("WORKSPACE_IO", "Cannot refresh workspace", error);
        }
    }

    @Override
    public synchronized WorkspaceSnapshot commit(WorkspaceSnapshot expected, ChangeSet changes,
            Metadata metadata, Cancellation cancellation) {
        validateMetadata(metadata);
        cancellation.check();
        try {
            ensureJournalDirectory();
            try (FileChannel channel = FileChannel.open(journals.resolve("lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel
                    .tryLock()) {
                if (lock == null)
                    throw new TransactionException("WORKSPACE_BUSY",
                            "Another RefactorJ transaction owns this workspace");
                rejectPending();
                refresh();
                ChangeSet.expect(current, expected);
                WorkspaceSnapshot next = ChangeSet.apply(current, changes);
                List<Entry> entries = new ArrayList<> ();

                for (var t : changes.transitions()) {
                    guard(t.path());
                    if (t.after() != null && t.after().open())
                        throw new TransactionException("OPEN_BUFFER",
                                "Cannot write an unsaved buffer through DiskWorkspace");
                    String disk = read(t.path());

                    if (!Objects.equals(disk, t.before() == null ? null : t.before().text()))
                        throw new TransactionException("EXTERNAL_CHANGE",
                                "Source differs from the prepared snapshot");
                    List<String> permissions = permissions(t.path());
                    Image before = t.before() == null ? null : new Image(t.before().text(), t
                            .before().version(), permissions);
                    List<String> afterPermissions = before == null && Files.getFileStore(root)
                            .supportsFileAttributeView("posix") ? List.of("OWNER_READ",
                            "OWNER_WRITE") : permissions;
                    Image after = t.after() == null ? null : new Image(t.after().text(), next
                            .source(t.path()).version(), afterPermissions);
                    entries.add(new Entry(root.relativize(t.path()).toString(), before, after,
                            identity(t.path())));
                }
                cancellation.check();
                long order = journalOrder = Math.max(System.currentTimeMillis(), journalOrder + 1);
                Path journalFile = journals.resolve(String.format(Locale.ROOT, "%020d-%s.json",
                        order, metadata.id()));
                Journal journal = new Journal(1, root.toString(), order, metadata, "PREPARED", List
                        .copyOf(entries));
                writeJournal(journalFile, journal);
                try {
                    for (int i = 0; i < entries.size(); i++) {
                        Entry entry = entries.get(i);
                        Path path = entryPath(entry);
                        cancellation.check();
                        fault.beforeFile(i, path);
                        if (!matches(path, entry.before()) || !Objects.equals(identity(path), entry
                                .beforeKey()))
                            throw new TransactionException("EXTERNAL_CHANGE",
                                    "A file changed immediately before replacement");
                        restore(path, entry.after());
                    }
                    cancellation.check();
                    writeJournal(journalFile, withStatus(journal, "COMMITTED"));
                    current = next;
                    captureFingerprints();
                    return current;
                } catch (RuntimeException | IOException error) {
                    try {
                        rollback(journal);
                        writeJournal(journalFile, withStatus(journal, "ROLLED_BACK"));
                        captureFingerprints();
                    } catch (RuntimeException | IOException recovery) {
                        error.addSuppressed(recovery);
                        throw failure("RECOVERY_REQUIRED",
                                "Transaction interrupted; journal retained and external changes were not overwritten",
                                error);
                    }
                    if (error instanceof RuntimeException runtime)
                        throw runtime;
                    throw failure("COMMIT_IO", "Transaction failed and was rolled back", error);
                }
            }
        } catch (java.nio.channels.OverlappingFileLockException error) {
            throw failure("WORKSPACE_BUSY", "Another RefactorJ transaction owns this workspace",
                    error);
        } catch (IOException error) {
            throw failure("COMMIT_IO", "Cannot commit workspace transaction", error);
        }
    }

    @Override
    public synchronized List<History> history() {
        var result = new LinkedHashMap<String, History> ();

        try {
            for (var journal : readJournals())
                if (journal.status().equals("COMMITTED")) {
                    List<ChangeSet.Transition> transitions = new ArrayList<> ();

                    for (var entry : journal.entries()) {
                        Path path = entryPath(entry);
                        transitions.add(new ChangeSet.Transition(path, source(path, entry.before()),
                                source(path, entry.after())));
                    }
                    InMemoryWorkspace.remember(result, new ChangeSet(transitions), journal
                            .metadata());
                }
            return List.copyOf(result.values());
        } catch (IOException error) {
            throw failure("JOURNAL_IO", "Cannot read transaction history", error);
        }
    }

    /** Roll back incomplete commits only when every affected file still matches a known journal image. */
    public synchronized void recover() {
        try {
            if (!Files.exists(journals))
                return;
            ensureJournalDirectory();
            try (FileChannel channel = FileChannel.open(journals.resolve("lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel
                    .tryLock()) {
                if (lock == null)
                    throw new TransactionException("WORKSPACE_BUSY",
                            "Cannot recover an active transaction");
                for (var path : journalPaths()) {
                    Journal journal = readJournal(path);

                    if (journal.status().equals("PREPARED")) {
                        rollback(journal);
                        writeJournal(path, withStatus(journal, "ROLLED_BACK"));
                    }
                }
            }
        } catch (java.nio.channels.OverlappingFileLockException error) {
            throw failure("WORKSPACE_BUSY", "Cannot recover an active transaction", error);
        } catch (IOException error) {
            throw failure("RECOVERY_REQUIRED", "Cannot safely recover transaction journal", error);
        }
    }

    private void rollback(Journal journal) throws IOException {
        for (var entry : journal.entries()) {
            Path path = entryPath(entry);

            if (!matches(path, entry.before()) && !matches(path, entry.after()))
                throw new TransactionException("RECOVERY_CONFLICT",
                        "External changes conflict with recovery: " + entry.path());
        }
        for (var entry : journal.entries()) {
            Path path = entryPath(entry);

            if (!matches(path, entry.before()))
                restore(path, entry.before());
        }
    }

    private void rejectPending() throws IOException {
        for (var journal : readJournals())
            if (journal.status().equals("PREPARED"))
                throw new TransactionException("RECOVERY_REQUIRED",
                        "Recover the interrupted transaction before applying another plan");
    }

    private void refresh() throws IOException {
        NavigableMap<Path, SourceSnapshot> old = ChangeSet.map(current);
        Set<Path> paths = new TreeSet<> (old.keySet());

        paths.addAll(dev.turboj.refactorj.api.SourceInventory.scan(root, current.sourceRoots(), MAX_FILES));
        List<SourceSnapshot> sources = new ArrayList<> ();
        Map<Path, Fingerprint> observed = new HashMap<> ();
        boolean changed = false;

        for (Path path : paths) {
            guard(path);
            String text = read(path);
            var previous = old.get(path);

            if (text == null) {
                if (previous != null)
                    changed = true;
                continue;
            }
            Fingerprint fp = fingerprint(path);
            observed.put(path, fp);
            boolean fileChanged = previous == null || !previous.text().equals(text) || !Objects
                    .equals(fp, fingerprints.get(path));
            changed |= fileChanged;
            sources.add(new SourceSnapshot(path, text, fileChanged ? Math.max(current.version() + 1,
                    previous == null ? 1 : previous.version() + 1) : previous.version(), false));
        }
        if (changed)
            current = new WorkspaceSnapshot(root, current.version() + 1, sources, current
                    .sourceRoots(), current.classpath());
        fingerprints.clear();
        fingerprints.putAll(observed);
    }

    private void captureFingerprints() throws IOException {
        fingerprints.clear();
        for (var source : current.sources())
            if (Files.exists(source.file(), LinkOption.NOFOLLOW_LINKS))
                fingerprints.put(source.file(), fingerprint(source.file()));
    }

    private void guard(Path path) throws IOException {
        ChangeSet.safe(root, path);
        if (path.startsWith(root.resolve(".refactorj")))
            throw new TransactionException("RESERVED_PATH",
                    "Transaction metadata cannot be a source target");
        for (Path cursor = path; cursor != null && !cursor.equals(root); cursor = cursor
                .getParent())
            if (Files.isSymbolicLink(cursor))
                throw new TransactionException("SYMLINK", "Symbolic source paths are not written");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS))
            throw new TransactionException("NOT_FILE", "Source target is not a regular file");
    }

    private void ensureJournalDirectory() throws IOException {
        guardJournalDirectory();
        Files.createDirectories(journals);
    }

    private void guardJournalDirectory() {
        Path metadata = root.resolve(".refactorj");

        if (Files.isSymbolicLink(metadata) || Files.isSymbolicLink(journals) || Files
                .isSymbolicLink(journals.resolve("lock")))
            throw new TransactionException("SYMLINK",
                    "Transaction metadata must not be a symbolic link");
    }

    private String read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return null;
        guard(path);
        if (Files.size(path) > MAX_SOURCE_BYTES)
            throw new IOException("Source exceeds 16 MiB");
        byte[] bytes;

        try (var stream = Files.newInputStream(path)) {
            bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
        }
        if (bytes.length > MAX_SOURCE_BYTES)
            throw new IOException("Source exceeds 16 MiB");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private boolean matches(Path path, Image image) throws IOException {
        return Objects.equals(read(path), image == null ? null : image.text()) && (image == null
                || image.permissions().isEmpty() || new HashSet<> (permissions(path))
                .equals(new HashSet<> (image.permissions())));
    }

    private void restore(Path path, Image image) throws IOException {
        guard(path);
        if (image == null) {
            Files.deleteIfExists(path);
            return;
        }
        Files.createDirectories(path.getParent());
        guard(path);
        atomicWrite(path, image.text().getBytes(StandardCharsets.UTF_8), image.permissions());
    }

    private static void atomicWrite(Path path, byte[] bytes,
            List<String> permissions) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".refactorj-", ".tmp");

        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            if (!permissions.isEmpty() && Files.getFileStore(temporary)
                    .supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> values = new HashSet<> ();
                permissions.forEach(p -> values.add(PosixFilePermission.valueOf(p)));
                Files.setPosixFilePermissions(temporary, values);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeJournal(Path path, Journal journal) throws IOException {
        byte[] bytes = JSON.toJson(journal).getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_JOURNAL_BYTES)
            throw new TransactionException("JOURNAL_LIMIT",
                    "Transaction exceeds 64 MiB journal limit");
        atomicWrite(path, bytes, List.of("OWNER_READ", "OWNER_WRITE"));
    }

    private List<Path> journalPaths() throws IOException {
        guardJournalDirectory();
        if (!Files.exists(journals))
            return List.of();
        try (var files = Files.list(journals)) {
            var result = files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
                    .limit(10_001).toList();

            if (result.size() > 10_000)
                throw new TransactionException("HISTORY_LIMIT",
                        "Transaction journal requires archival before continuing");
            return result;
        }
    }

    private List<Journal> readJournals() throws IOException {
        List<Journal> result = new ArrayList<> ();

        for (Path file : journalPaths())
            result.add(readJournal(file));
        return result;
    }

    private Journal readJournal(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_JOURNAL_BYTES)
            throw new IOException("Invalid transaction journal");
        String text;

        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_JOURNAL_BYTES + 1);

            if (bytes.length > MAX_JOURNAL_BYTES)
                throw new IOException("Oversized journal");
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        Journal journal;

        try {
            journal = JSON.fromJson(text, Journal.class);
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed transaction journal", malformed);
        }
        if (journal == null || journal.schemaVersion() != 1 || !root.toString().equals(journal
                .root()) || journal.entries() == null || journal.entries().size() > MAX_FILES
                || journal.status() == null || !Set.of("PREPARED", "COMMITTED", "ROLLED_BACK")
                .contains(journal.status()))
            throw new IOException("Invalid transaction journal schema");
        validateMetadata(journal.metadata());
        var seen = new HashSet<Path> ();

        for (var entry : journal.entries()) {
            if (entry == null || entry.path() == null || !seen.add(entryPath(entry)) || entry
                    .before() == null && entry.after() == null)
                throw new IOException("Invalid transaction entries");
            for (Image image : new Image[] {entry.before(), entry.after()})
                if (image != null) {
                    if (image.text() == null || image.permissions() == null)
                        throw new IOException("Invalid transaction image");
                    ChangeSet.validateText(image.text());
                    for (String permission : image.permissions())
                        try {
                            PosixFilePermission.valueOf(permission);
                        } catch (RuntimeException invalid) {
                            throw new IOException("Invalid transaction permissions", invalid);
                        }
                }
        }
        journalOrder = Math.max(journalOrder, journal.order());
        return journal;
    }

    private Path entryPath(Entry entry) throws IOException {
        Path relative = Path.of(entry.path());

        if (relative.isAbsolute() || !relative.normalize().equals(relative))
            throw new IOException("Unsafe journal path");
        Path path = root.resolve(relative);
        guard(path);
        return path;
    }

    private static void validateMetadata(Metadata metadata) {
        if (metadata == null || metadata.direction() == null)
            throw new TransactionException("INVALID_METADATA", "Missing transaction metadata");
        try {
            UUID.fromString(metadata.id());
            UUID.fromString(metadata.historyId());
        } catch (RuntimeException invalid) {
            throw new TransactionException("INVALID_METADATA",
                    "Transaction identifiers must be UUIDs");
        }
    }

    private static Fingerprint fingerprint(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return new Fingerprint(String.valueOf(attrs.fileKey()), attrs.lastModifiedTime().toMillis(),
                attrs.size());
    }

    private static String identity(Path path) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? fingerprint(path).key() : null;
    }

    private static List<String> permissions(Path path) throws IOException {
        if (!Files.exists(path))
            return List.of();
        if (!Files.getFileStore(path).supportsFileAttributeView("posix"))
            return List.of();
        return Files.getPosixFilePermissions(path).stream().map(Enum::name).sorted().toList();
    }

    private static SourceSnapshot source(Path path, Image image) {
        return image == null ? null : new SourceSnapshot(path, image.text(), image.version(),
                false);
    }

    private static Journal withStatus(Journal journal, String status) {
        return new Journal(journal.schemaVersion(), journal.root(), journal.order(), journal
                .metadata(), status, journal.entries());
    }

    private static TransactionException failure(String code, String text, Throwable cause) {
        return new TransactionException(code, text, cause);
    }
}
