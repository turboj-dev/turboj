package dev.turboj.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-instance durable snapshots. File locks distinguish abandoned sessions from running editors. */
public final class SessionRecovery implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int LIMIT = 128 * 1024 * 1024;
    public record Entry(String key, String file, String text, String hash, String fingerprint,
                        boolean dirty, int caret, int anchor, int topLine, int leftColumn,
                        String encoding, String bom) {
        public Entry {
            Objects.requireNonNull(key); Objects.requireNonNull(text);
            if (!Path.of(key).isAbsolute() || file != null && !Path.of(file).isAbsolute())
                throw new IllegalArgumentException("Recovery paths must be absolute");
            if (!HexFormat.of().formatHex(SafeFiles.utf8Fingerprint(text)).equals(hash))
                throw new IllegalArgumentException("Recovery content checksum mismatch");
            if (text.length() > 32 * 1024 * 1024 || caret < 0 || anchor < 0 || caret > text.length()
                    || anchor > text.length() || topLine < 0 || leftColumn < 0)
                throw new IllegalArgumentException("Invalid recovery source or location");
            if (fingerprint != null && !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid recovery disk fingerprint");
            Charset.forName(encoding); HexFormat.of().parseHex(bom);
        }
    }
    public record Snapshot(int schemaVersion, String activeKey, List<Entry> entries) {
        public Snapshot {
            entries = List.copyOf(entries);
            if (schemaVersion != 2 || entries.size() > 256 || entries.isEmpty()
                    || entries.stream().noneMatch(e -> e.key().equals(activeKey))
                    || entries.stream().map(Entry::key).distinct().count() != entries.size())
                throw new IllegalArgumentException("Invalid recovery session");
        }
    }
    private final Path directory, manifest, lockPath;
    private FileChannel channel;
    private FileLock lock;
    private boolean closed;
    private final List<String> warnings = new ArrayList<>();

    public SessionRecovery(Path sessions) {
        directory = sessions.resolve("v2").toAbsolutePath().normalize();
        String id = UUID.randomUUID().toString();
        manifest = directory.resolve(id + ".json"); lockPath = directory.resolve(id + ".lock");
    }
    public synchronized void write(Snapshot snapshot) throws IOException {
        write(snapshot,LIMIT);
    }
    synchronized void write(Snapshot snapshot,int maximumBytes) throws IOException {
        if (closed) return;
        if (maximumBytes < 1 || maximumBytes > LIMIT) throw new IllegalArgumentException("Invalid recovery size limit");
        acquire();
        if (Files.isSymbolicLink(manifest)) throw new IOException("Recovery manifest is a symbolic link");
        Path temporary = Files.createTempFile(directory,".recovery-",".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                OutputStream bytes = java.nio.channels.Channels.newOutputStream(output);
                OutputStream bounded = new FilterOutputStream(bytes) {
                    private long written;
                    private void reserve(int count) throws IOException {
                        if (written + count > maximumBytes) throw new IOException("Recovery session exceeds its " + maximumBytes + " byte limit");
                        written += count;
                    }
                    @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                    @Override public void write(byte[] value,int offset,int count) throws IOException { reserve(count); out.write(value,offset,count); }
                };
                Writer writer = new BufferedWriter(new OutputStreamWriter(bounded,StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)));
                try { JSON.toJson(snapshot,writer); writer.flush(); }
                catch (com.google.gson.JsonIOException failure) { throw new IOException("Cannot serialize recovery session",failure); }
                output.force(true);
            }
            if (Files.isSymbolicLink(manifest)) throw new IOException("Recovery manifest is a symbolic link");
            Files.move(temporary,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void acquire() throws IOException {
        if (lock != null) return;
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Recovery directory is a symbolic link");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        lock = channel.lock();
    }
    public synchronized List<Claim> claimPrevious() throws IOException {
        warnings.clear();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Recovery directory is a symbolic link");
        List<Claim> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path candidate : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                if (candidate.equals(manifest)) continue;
                try {
                    Claim claim = claim(candidate, candidate.resolveSibling(candidate.getFileName().toString().replace(".json", ".lock")), false);
                    if (claim != null) result.add(claim);
                } catch (IOException invalid) { warnings.add(invalid.getMessage()); }
            }
            Path legacy = directory.getParent().resolve("buffer.java");
            if (Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Claim claim = claim(legacy, directory.resolve("legacy.lock"), true);
                    if (claim != null) result.add(claim);
                } catch (IOException invalid) { warnings.add(invalid.getMessage()); }
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            for (Claim claim : result) claim.close();
            if (failure instanceof IOException io) throw io;
            throw new IOException("Cannot read recovery session; originals retained", failure);
        }
    }
    public synchronized List<String> warnings() { return List.copyOf(warnings); }
    private static Claim claim(Path path, Path lockPath, boolean legacy) throws IOException {
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(lockPath)) throw new IOException("Recovery metadata is a symbolic link");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException busy) { channel.close(); return null; }
        if (lock == null) { channel.close(); return null; }
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { lock.release(); channel.close(); return null; }
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(LIMIT + 1); }
            if (bytes.length > LIMIT) throw new IOException("Recovery session exceeds 128 MiB");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            Snapshot snapshot;
            if (legacy) {
                String key = directoryKey(path);
                snapshot = new Snapshot(2, key, List.of(new Entry(key, null, text,
                        HexFormat.of().formatHex(SafeFiles.utf8Fingerprint(text)), null, true, 0, 0, 0, 0, "UTF-8", "")));
            } else snapshot = JSON.fromJson(text, Snapshot.class);
            if (snapshot == null) throw new IOException("Empty recovery session");
            return new Claim(path, channel, lock, snapshot);
        } catch (Exception failure) {
            lock.release(); channel.close();
            throw new IOException("Invalid recovery session " + path + "; original retained", failure);
        }
    }
    private static String directoryKey(Path path) { return path.toAbsolutePath().resolveSibling(".recovered-" + UUID.randomUUID() + ".java").toString(); }
    public static final class Claim implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private final Snapshot snapshot;
        private Claim(Path path, FileChannel channel, FileLock lock, Snapshot snapshot) {
            this.path = path; this.channel = channel; this.lock = lock; this.snapshot = snapshot;
        }
        public Snapshot snapshot() { return snapshot; }
        /** Call only after the receiving instance durably stores the snapshot. */
        public void discard() throws IOException { Files.deleteIfExists(path); }
        @Override public void close() throws IOException { if (lock.isValid()) lock.release(); channel.close(); }
    }
    /** A normal confirmed application exit discards only this instance's recovery. */
    public synchronized void finish() throws IOException {
        Files.deleteIfExists(manifest);
        close();
        Files.deleteIfExists(lockPath);
    }
    /** Releases ownership but retains the snapshot, as after a crash. */
    @Override public synchronized void close() throws IOException {
        closed = true;
        if (lock != null && lock.isValid()) lock.release();
        if (channel != null) channel.close();
    }
}
