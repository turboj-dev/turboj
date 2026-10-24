package dev.turboj.mavenj.workspace;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;

/** Encoding-preserving POM file commits, guarded against stale previews and root escapes. */
public final class PomFiles {
    private PomFiles() {}

    public record Document(Path path, String text, Charset encoding, byte[] bom) {
        public Document {
            bom = bom.clone();
        }

        @Override
        public byte[] bom() {
            return bom.clone();
        }
    }

    public static Path allowed(Path root, Path path) throws IOException {
        Path canonicalRoot = root.toRealPath(), absolute = path.isAbsolute() ? path
                .normalize() : canonicalRoot.resolve(path).normalize();
        Path real = absolute.toRealPath();

        if (!real.startsWith(canonicalRoot))
            throw new SecurityException("Symlink escapes the allowed workspace root");
        if (!real.getFileName().toString().equals("pom.xml"))
            throw new SecurityException("Only pom.xml may be edited");
        return real;
    }

    public static Document read(Path root, Path path) throws IOException {
        Path allowed = allowed(root, path);

        if (Files.size(allowed) > 4_000_000)
            throw new IOException("POM exceeds 4 MB");
        byte[] bytes = Files.readAllBytes(allowed);
        int skip = 0;
        Charset charset = StandardCharsets.UTF_8;

        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2]
                == (byte) 0xbf)
            skip = 3;
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<' && bytes[2] == 0
                && bytes[3] == '?')
            charset = StandardCharsets.UTF_16BE;
        else if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0 && bytes[2] == '?'
                && bytes[3] == 0)
            charset = StandardCharsets.UTF_16LE;
        else {
            String prolog = new String(bytes, 0, Math.min(bytes.length, 256),
                    StandardCharsets.ISO_8859_1);
            var encoding = Pattern.compile("^\\s*<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]")
                    .matcher(prolog);

            if (encoding.find())
                try {
                    charset = Charset.forName(encoding.group(1));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Unsupported POM encoding", e);
                }
        }
        String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip,
                bytes.length - skip)).toString();
        PomEdits.validate(text);
        return new Document(allowed, text, charset, Arrays.copyOf(bytes, skip));
    }

    /** Serializes MavenJ writers; rejects external content changes before committing a single-file atomic replacement. */
    public static synchronized void apply(Path root, Document original,
            PomEdits.Plan plan) throws IOException {
        PomEdits.validate(plan.result());
        Path destination = allowed(root, original.path);
        Document current = read(root, destination);
        plan.applyTo(current.text);
        if (!current.encoding.equals(original.encoding) || !Arrays.equals(current.bom,
                original.bom))
            throw new IOException("POM encoding changed after preview");
        ByteBuffer encoded = original.encoding.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer
                .wrap(plan.result()));
        byte[] bytes = new byte[original.bom.length + encoded.remaining()];
        System.arraycopy(original.bom, 0, bytes, 0, original.bom.length);
        encoded.get(bytes, original.bom.length, encoded.remaining());
        Path temporary = Files.createTempFile(destination.getParent(), ".mavenj-pom-", ".tmp");

        try {
            Set<PosixFilePermission> permissions = null;

            try {
                permissions = Files.getPosixFilePermissions(destination);
            } catch (UnsupportedOperationException ignored) {}
            Files.write(temporary, bytes);
            if (permissions != null)
                Files.setPosixFilePermissions(temporary, permissions);
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Document rechecked = read(root, destination);
            plan.applyTo(rechecked.text);
            if (!rechecked.encoding.equals(original.encoding) || !Arrays.equals(rechecked.bom,
                    original.bom))
                throw new IOException("POM encoding changed after preview");
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Filesystem does not support an atomic POM replacement; use the editor save action",
                        unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
