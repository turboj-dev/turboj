package dev.turboj.config;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;

public final class SafeFiles {
    public record Snapshot(String text, byte[] fingerprint, Charset encoding, byte[] bom) {
        public Snapshot(String text, byte[] fingerprint) { this(text, fingerprint, StandardCharsets.UTF_8, new byte[0]); }
        public Snapshot {
            fingerprint = fingerprint.clone();
            bom = bom.clone();
        }

        @Override public byte[] bom() { return bom.clone(); }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }
    }

    private SafeFiles() {}

    public static String readUtf8(Path path) throws IOException {
        return readSnapshot(path).text();
    }

    public static Snapshot readSnapshot(Path path) throws IOException {
        return readSnapshot(path, StandardCharsets.UTF_8);
    }

    public static Snapshot readSnapshot(Path path, Charset charset) throws IOException {
        byte[] bytes = readBytes(path);
        return decode(bytes, charset, 0);
    }

    /** XML encoding/BOM discovery also works for incomplete POM buffers being repaired. */
    public static Snapshot readXmlSnapshot(Path path) throws IOException {
        byte[] bytes = readBytes(path);
        Charset charset = StandardCharsets.UTF_8; int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) skip = 3;
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) { charset = StandardCharsets.UTF_16LE; skip = 2; }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) { charset = StandardCharsets.UTF_16BE; skip = 2; }
        else {
            String prolog = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1);
            var declaration = java.util.regex.Pattern.compile("^\\s*<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(prolog);
            if (declaration.find()) try { charset = Charset.forName(declaration.group(1)); }
            catch (IllegalArgumentException invalid) { throw new IOException("Unsupported XML source encoding", invalid); }
        }
        return decode(bytes, charset, skip);
    }

    private static byte[] readBytes(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(32 * 1024 * 1024 + 1);
        }
        if (bytes.length > 32 * 1024 * 1024)
            throw new IOException("Files over 32 MiB are not supported in this release");
        return bytes;
    }

    private static Snapshot decode(byte[] bytes, Charset charset, int skip) throws IOException {
        String text =
                charset
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                        .toString();
        return new Snapshot(text, digest(bytes), charset, java.util.Arrays.copyOf(bytes, skip));
    }

    public static byte[] fingerprint(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        return digest(Files.readAllBytes(path));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Fingerprint for a transaction's known UTF-8 bytes, without filesystem I/O on the EDT. */
    public static byte[] utf8Fingerprint(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] atomicWriteIfUnchanged(Path path, String text, byte[] expected)
            throws IOException {
        return atomicWriteIfUnchanged(path, text, expected, StandardCharsets.UTF_8);
    }

    public static byte[] atomicWriteIfUnchanged(Path path, String text, byte[] expected, Charset charset)
            throws IOException {
        return atomicWriteIfUnchanged(path, text, expected, charset, new byte[0]);
    }

    public static byte[] atomicWriteIfUnchanged(Path path, String text, byte[] expected, Charset charset, byte[] bom)
            throws IOException {
        return write(path, text, charset, bom, expected, true, () -> {});
    }

    /** Returns the fingerprint of the exact bytes written, without re-reading a changing file. */
    public static byte[] atomicWrite(Path path, String text) throws IOException {
        return atomicWrite(path, text, StandardCharsets.UTF_8);
    }

    public static byte[] atomicWrite(Path path, String text, Charset charset) throws IOException {
        return atomicWrite(path, text, charset, new byte[0]);
    }

    public static byte[] atomicWrite(Path path, String text, Charset charset, byte[] bom) throws IOException {
        return write(path, text, charset, bom, null, false, () -> {});
    }

    @FunctionalInterface interface Prepared { void run() throws IOException; }

    static byte[] write(Path path, String text, Charset charset, byte[] bom, byte[] expected,
            boolean conditional, Prepared prepared) throws IOException {
        Path requested = path.toAbsolutePath().normalize();
        Path target = Files.isSymbolicLink(requested) ? requested.toRealPath() : requested;
        if (conditional) unchanged(requested, target, expected);
        String metadata = conditional ? dev.turboj.refactorj.transactions.FileMetadata.fingerprint(target) : null;
        java.nio.ByteBuffer encoded =
                charset
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes = new byte[bom.length + encoded.remaining()];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        encoded.get(bytes, bom.length, encoded.remaining());
        Path temp = Files.createTempFile(target.getParent(), ".turboj-", ".tmp");
        try {
            dev.turboj.refactorj.transactions.FileMetadata.seedReplacement(target, temp);
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            prepared.run();
            // Recheck after encoding, writing and metadata copying. An external, non-cooperating
            // writer can still race the final rename: the filesystem offers no portable compare-and-swap.
            if (conditional) {
                unchanged(requested, target, expected);
                dev.turboj.refactorj.transactions.FileMetadata.requireUnchanged(target, metadata);
            }
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                if (conditional) {
                    unchanged(requested, target, expected);
                    dev.turboj.refactorj.transactions.FileMetadata.requireUnchanged(target, metadata);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return digest(bytes);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void unchanged(Path requested, Path target, byte[] expected) throws IOException {
        Path current = Files.isSymbolicLink(requested) ? requested.toRealPath() : requested;
        if (!current.equals(target) || !java.util.Arrays.equals(expected, fingerprint(target)))
            throw new IOException("File changed outside TurboJ before replacement: " + requested
                    + ". Your editor buffer is unchanged. Use Save as to preserve both versions, or reopen it.");
    }
}
