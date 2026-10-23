package com.turboj.config;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;

public final class SafeFiles {
    public record Snapshot(String text, byte[] fingerprint) {
        public Snapshot {
            fingerprint = fingerprint.clone();
        }

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
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(32 * 1024 * 1024 + 1);
        }
        if (bytes.length > 32 * 1024 * 1024)
            throw new IOException("Files over 32 MiB are not supported in this release");
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
        return new Snapshot(text, digest(bytes));
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

    public static byte[] atomicWriteIfUnchanged(Path path, String text, byte[] expected)
            throws IOException {
        if (!java.util.Arrays.equals(expected, fingerprint(path)))
            throw new IOException(
                    "File changed outside TurboJ. Use Save as to preserve both versions, or reopen it.");
        return atomicWrite(path, text);
    }

    /** Returns the fingerprint of the exact bytes written, without re-reading a changing file. */
    public static byte[] atomicWrite(Path path, String text) throws IOException {
        java.nio.ByteBuffer encoded =
                StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        Path target = path.toAbsolutePath();
        if (Files.isSymbolicLink(target)) target = target.toRealPath();
        Path temp = Files.createTempFile(target.getParent(), ".turboj-", ".tmp");
        try {
            Files.write(temp, bytes);
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
                } catch (UnsupportedOperationException ignored) {
                }
            }
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return digest(bytes);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
