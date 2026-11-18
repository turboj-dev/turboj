package dev.turboj.refactorj.transactions;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Metadata preservation shared by source saves and closed-file refactoring replacements. */
public final class FileMetadata {
    private static final int MAX_ATTRIBUTE_BYTES = 1_048_576;
    private FileMetadata() {}

    private static boolean macOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** Bounded metadata identity, independent of the source-byte fingerprint and timestamps. */
    public static String fingerprint(Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(source)) throw new IOException("Cannot inspect metadata through a changed symbolic source");
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        field(digest, Files.getOwner(source).getName());
        var posix = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        if (posix != null) {
            var attributes = posix.readAttributes();
            field(digest, attributes.group().getName());
            attributes.permissions().stream().sorted().forEach(value -> field(digest, value.name()));
        }
        if (macOS()) {
            String listing = run(List.of("/bin/ls", "-lde", source.toAbsolutePath().toString()), true);
            listing.lines().filter(line -> line.matches("\\s*\\d+:.*")).forEach(line -> field(digest, line.strip()));
            var names = run(List.of("/usr/bin/xattr", source.toAbsolutePath().toString()), true).lines().sorted().toList();
            if (names.size() > 256) throw new IOException("Too many native attributes to preserve safely");
            int total = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (String name : names) {
                if (System.nanoTime() >= deadline) throw new IOException("Native attribute inspection exceeded its time limit");
                if (!name.matches("[A-Za-z0-9._:-]+")) throw new IOException("Unsupported native attribute name; source was not replaced");
                String hex = run(List.of("/usr/bin/xattr", "-px", name, source.toAbsolutePath().toString()), 4 * MAX_ATTRIBUTE_BYTES)
                        .replaceAll("\\s+", "");
                if (!hex.matches("(?:[0-9A-Fa-f]{2})*")) throw new IOException("Unrecognized native attribute value; source was not replaced");
                total += hex.length() / 2;
                if (total > MAX_ATTRIBUTE_BYTES) throw new IOException("Native attributes exceed the preservation limit");
                field(digest, name); field(digest, java.util.HexFormat.of().parseHex(hex));
            }
        } else {
            var acl = Files.getFileAttributeView(source, AclFileAttributeView.class);
            if (acl != null) acl.getAcl().forEach(value -> field(digest, value.toString()));
            var attributes = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
            int total = 0;
            if (attributes != null) for (String name : attributes.list().stream().sorted().toList()) {
                int size = attributes.size(name); total += size;
                if (size < 0 || total > MAX_ATTRIBUTE_BYTES) throw new IOException("Source attributes exceed the preservation limit");
                var bytes = java.nio.ByteBuffer.allocate(size); attributes.read(name, bytes); bytes.flip();
                byte[] actual = new byte[bytes.remaining()]; bytes.get(actual);
                field(digest, name); field(digest, actual);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    public static void requireUnchanged(Path source, String expected) throws IOException {
        if (!java.util.Objects.equals(expected, fingerprint(source)))
            throw new IOException("File metadata changed outside TurboJ before replacement: " + source
                    + ". Source and editor buffer were not replaced; retry only after reviewing the external permissions or attributes.");
    }

    private static void field(java.security.MessageDigest digest, String value) {
        field(digest, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void field(java.security.MessageDigest digest, byte[] value) {
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
    }

    /** Preseed a private staging file, then let the caller truncate/write its new source bytes. */
    public static void seedReplacement(Path source, Path staging) throws IOException {
        if (!Files.exists(source)) return;
        // The macOS JDK exposes no AclFileAttributeView. Native cp preserves ACLs, extended
        // attributes and resource forks that COPY_ATTRIBUTES silently omits on this platform.
        if (macOS()) run(List.of("/bin/cp", "-p", source.toAbsolutePath().toString(), staging.toAbsolutePath().toString()), false);
        var owner = Files.getOwner(source);
        if (!owner.equals(Files.getOwner(staging))) Files.setOwner(staging, owner);
        var posix = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        if (posix != null) {
            var attributes = posix.readAttributes();
            var destination = Files.getFileAttributeView(staging, PosixFileAttributeView.class);
            destination.setPermissions(attributes.permissions());
            if (!attributes.group().equals(destination.readAttributes().group())) destination.setGroup(attributes.group());
        }
        var acl = Files.getFileAttributeView(source, AclFileAttributeView.class);
        if (acl != null) Files.getFileAttributeView(staging, AclFileAttributeView.class).setAcl(acl.getAcl());
        if (!macOS()) {
            var attributes = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
            var destination = Files.getFileAttributeView(staging, UserDefinedFileAttributeView.class);
            if (attributes != null) for (String name : attributes.list()) {
                int size = attributes.size(name);
                if (size > MAX_ATTRIBUTE_BYTES) throw new IOException("Source extended attribute exceeds the preservation limit");
                var bytes = java.nio.ByteBuffer.allocate(size);
                attributes.read(name, bytes); bytes.flip(); destination.write(name, bytes);
            }
        }
    }

    /** The text-only transaction journal cannot reconstruct these attributes after deletion. */
    static void requireJournalableDeletion(Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        boolean unsupported;
        if (macOS()) {
            String listing = run(List.of("/bin/ls", "-lde", source.toAbsolutePath().toString()), true);
            unsupported = listing.lines().anyMatch(line -> line.matches("\\s*\\d+:.*"))
                    || run(List.of("/usr/bin/xattr", source.toAbsolutePath().toString()), true).lines()
                            // macOS attaches its protected provenance tag even to newly created
                            // temporary sources. It is OS-managed, not user/restorable metadata.
                            .anyMatch(name -> !name.isBlank() && !name.equals("com.apple.provenance"));
        } else {
            var acl = Files.getFileAttributeView(source, AclFileAttributeView.class);
            var attributes = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
            unsupported = acl != null && !acl.getAcl().isEmpty() || attributes != null && !attributes.list().isEmpty();
        }
        if (unsupported) throw new IOException("Cannot delete or rename a source with ACLs or extended attributes: "
                + source + ". The transaction journal cannot restore this metadata; no destructive change was made.");
    }

    private static String run(List<String> arguments, boolean capture) throws IOException {
        return run(arguments, capture ? 65_536 : 0);
    }

    private static String run(List<String> arguments, int maximumBytes) throws IOException {
        Process process = null;
        try {
            var builder = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(maximumBytes > 0 ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            var captured = new java.util.concurrent.CompletableFuture<byte[]>();
            if (maximumBytes > 0) {
                Process owned = process;
                Thread.startVirtualThread(() -> {
                    try (var input = owned.getInputStream()) {
                        byte[] bytes = input.readNBytes(maximumBytes + 1);
                        if (bytes.length > maximumBytes) {
                            owned.destroyForcibly();
                            throw new IOException("Native file metadata exceeds the inspection limit");
                        }
                        captured.complete(bytes);
                    } catch (IOException failure) { captured.completeExceptionally(failure); }
                });
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out preserving or inspecting native file metadata");
            }
            byte[] bytes = maximumBytes > 0 ? captured.get(1, TimeUnit.SECONDS) : new byte[0];
            if (process.exitValue() != 0) throw new IOException("Cannot preserve or inspect native file metadata ("
                    + arguments.getFirst() + " exited " + process.exitValue() + ")");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException cancelled) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted preserving native file metadata", cancelled);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("Cannot read native file metadata safely", failure);
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
