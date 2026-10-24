package dev.javafmt.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Read/validate/write boundaries are separate from formatter policy. */
final class SafeFiles {
    static final int MAX_BYTES = 16 * 1024 * 1024;

    record Snapshot(
            Path requested,
            Path real,
            byte[] bytes,
            String source,
            Object fileKey,
            Set<PosixFilePermission> permissions) {}

    private SafeFiles() {}

    static Snapshot read(Path path) throws IOException {
        Path requested = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) throw new IOException("Refusing a symbolic link.");
        Path real = requested.toRealPath();
        BasicFileAttributes before =
                Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_BYTES)
            throw new IOException("Input must be a regular file of at most 16 MiB.");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(real)) {
            bytes = bounded(in);
        }
        BasicFileAttributes after =
                Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("File changed while being read; no output written.");
        Set<PosixFilePermission> permissions = null;
        if (Files.getFileStore(real).supportsFileAttributeView("posix"))
            permissions = Files.getPosixFilePermissions(real);
        return new Snapshot(requested, real, bytes, decode(bytes), before.fileKey(), permissions);
    }

    static String readUtf8(InputStream in) throws IOException {
        return decode(bounded(in));
    }

    private static byte[] bounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Input exceeds 16 MiB.");
        return bytes;
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    static void write(Snapshot snapshot, String formatted) throws IOException {
        Objects.requireNonNull(snapshot);
        Path path = snapshot.real();
        Path temporary = Files.createTempFile(path.getParent(), ".javafmt-", ".tmp");
        try {
            byte[] output = formatted.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(output);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (snapshot.permissions() != null)
                Files.setPosixFilePermissions(temporary, snapshot.permissions());
            if (Files.isSymbolicLink(snapshot.requested())
                    || !snapshot.requested().toRealPath().equals(path)
                    || Files.isSymbolicLink(path))
                throw new IOException("File identity changed; no output written.");
            BasicFileAttributes current =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile()
                    || !Objects.equals(snapshot.fileKey(), current.fileKey())
                    || current.size() != snapshot.bytes().length
                    || !matchesOriginal(path, snapshot.bytes()))
                throw new IOException("File changed externally; no output written.");
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                throw new IOException(
                        "Filesystem does not support atomic replacement; original left unchanged.",
                        unavailable);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matchesOriginal(Path path, byte[] original) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return Arrays.equals(bounded(in), original);
        }
    }
}
