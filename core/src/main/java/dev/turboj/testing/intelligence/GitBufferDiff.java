package dev.turboj.testing.intelligence;

import java.awt.EventQueue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A read-only Git baseline-to-editor-buffer diff. Call only from a background worker.
 * The index and working file are never rewritten: Git compares two owned snapshots.
 * Logical line endings are normalized (CRLF/CR to LF), but final-newline changes remain
 * changes. The caller owns document-generation checks before publishing this result.
 */
public final class GitBufferDiff {
    private static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 12 * 1024 * 1024;
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(15).toNanos();
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** boundary 0 means before the first line; otherwise after that current logical line. */
    public record Deletion(int boundary, String text) {
        public Deletion {
            if (boundary < 0) throw new IllegalArgumentException("Negative deletion boundary");
            if (text == null) throw new IllegalArgumentException("Missing deleted text");
        }
    }

    public record Result(Path repository, String baseline, String baselineBlob,
                         Map<Integer, Boolean> added, List<Deletion> deleted, String diagnostic) {
        public Result {
            added = Collections.unmodifiableMap(new LinkedHashMap<>(added));
            deleted = List.copyOf(deleted);
        }
        public boolean available() { return diagnostic.isEmpty(); }
    }

    private GitBufferDiff() {}

    public static Result compute(Path file, String buffer, String baseline) throws InterruptedException {
        if (EventQueue.isDispatchThread()) throw new IllegalStateException("Git diff must run off the EDT");
        interrupted();
        String requested = baseline == null || baseline.isBlank() ? "HEAD" : baseline;
        Path repository = null;
        Path temporary = null;
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        try {
            if (file == null || buffer == null) return unavailable(null, requested, "No source file is selected.");
            if (buffer.length() > MAX_SOURCE_BYTES || buffer.indexOf('\0') >= 0)
                return unavailable(null, requested, "Source is binary or exceeds the 4 MiB diff limit.");
            Path absolute = file.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute))
                return unavailable(null, requested, "Symbolic-link sources are not compared as ordinary Git files.");
            Path directory = absolute.getParent();
            while (directory != null && !Files.isDirectory(directory)) directory = directory.getParent();
            if (directory == null) return unavailable(null, requested, "No existing source directory.");
            Path canonicalDirectory = directory.toRealPath();
            absolute = canonicalDirectory.resolve(directory.relativize(absolute)).normalize();
            var worktree = git(canonicalDirectory, deadline, "rev-parse", "--show-toplevel");
            if (worktree.exit != 0) return unavailable(null, requested, "Source is outside an available Git worktree.");
            String root = worktree.text();
            repository = Path.of(root.endsWith("\n") ? root.substring(0, root.length() - 1) : root).toRealPath();
            if (!absolute.startsWith(repository)) return unavailable(repository, requested, "Source is outside this Git worktree.");
            String path = repository.relativize(absolute).toString().replace(java.io.File.separatorChar, '/');
            if (path.isEmpty()) return unavailable(repository, requested, "A source file is required.");
            var tree = git(repository, deadline, "rev-parse", "--verify", "--end-of-options", requested + "^{tree}");
            String treeId = tree.exit == 0 ? tree.text().strip() : "";
            if (treeId.isEmpty() && !unbornHead(repository, requested, deadline))
                return unavailable(repository, requested, "Git baseline cannot be resolved: " + requested);

            String blob = "";
            if (!treeId.isEmpty()) {
                blob = blob(repository, treeId, path, deadline);
                if (blob.isEmpty()) {
                    String original = renamedFrom(repository, treeId, path, deadline);
                    if (original != null) blob = blob(repository, treeId, original, deadline);
                }
            }
            if (blob.isEmpty()) {
                int ignored = git(repository, deadline, "check-ignore", "-q", "--", path).exit;
                if (ignored == 0) return unavailable(repository, requested, "Source is ignored by Git.");
                if (ignored != 1) throw new IOException("Git ignore status is unavailable.");
            }
            byte[] original = new byte[0];
            if (!blob.isEmpty()) {
                var contents = git(repository, deadline, "cat-file", "blob", blob);
                if (contents.exit != 0) return unavailable(repository, requested, "Git baseline blob is unavailable.");
                original = contents.bytes;
            }
            if (original.length > MAX_SOURCE_BYTES)
                return unavailable(repository, requested, "Baseline exceeds the 4 MiB diff limit.");
            String before = normalize(decode(original));
            String after = normalize(buffer);
            if (before.indexOf('\0') >= 0) return unavailable(repository, requested, "Binary Git baseline has no source-line mapping.");
            byte[] current = encode(after);
            if (current.length > MAX_SOURCE_BYTES) return unavailable(repository, requested, "Source exceeds the 4 MiB diff limit.");
            if (before.equals(after)) return new Result(repository, requested, blob, Map.of(), List.of(), "");
            temporary = Files.createTempDirectory("turboj-git-buffer-");
            Path oldFile = temporary.resolve("baseline"), newFile = temporary.resolve("buffer");
            Files.write(oldFile, encode(before));
            Files.write(newFile, current);
            var diff = git(repository, deadline, "diff", "--no-index", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--text", "--unified=0", "--diff-algorithm=myers", "--",
                    oldFile.toString(), newFile.toString());
            if (diff.exit != 0 && diff.exit != 1)
                return unavailable(repository, requested, "Git could not compare the source snapshots.");
            return parse(repository, requested, blob, diff.text());
        } catch (CharacterCodingException failure) {
            return unavailable(repository, requested, "Source or Git baseline is not valid UTF-8 text.");
        } catch (IOException | IllegalArgumentException failure) {
            return unavailable(repository, requested, "Git diff unavailable: " + failure.getMessage());
        } finally {
            if (temporary != null) {
                // Only these three owned paths are removed; never walk a user directory.
                try { Files.deleteIfExists(temporary.resolve("baseline")); } catch (IOException ignored) {}
                try { Files.deleteIfExists(temporary.resolve("buffer")); } catch (IOException ignored) {}
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean unbornHead(Path repository, String baseline, long deadline) throws IOException, InterruptedException {
        if (!baseline.equals("HEAD")) return false;
        var symbolic = git(repository, deadline, "symbolic-ref", "--quiet", "HEAD");
        return symbolic.exit == 0 && git(repository, deadline, "show-ref", "--verify", "--quiet", symbolic.text().strip()).exit == 1;
    }

    private static String blob(Path repository, String tree, String path, long deadline) throws IOException, InterruptedException {
        // ls-tree also rejects submodules/trees and preserves filenames with tabs/newlines via NUL output.
        var entry = git(repository, deadline, "ls-tree", "-z", tree, "--", path);
        if (entry.exit != 0) throw new IOException("Git tree lookup failed.");
        if (entry.bytes.length == 0) return "";
        String value = entry.text();
        int tab = value.indexOf('\t');
        if (tab < 0) throw new IOException("Malformed Git tree entry.");
        String[] header = value.substring(0, tab).split(" ");
        if (header.length != 3 || !header[1].equals("blob") || header[0].equals("120000"))
            throw new IOException("Baseline is not an ordinary source file.");
        return header[2];
    }

    private static String renamedFrom(Path repository, String tree, String path, long deadline) throws IOException, InterruptedException {
        // Examine the complete index diff: limiting the pathspec to the destination hides rename sources.
        var changed = git(repository, deadline, "diff", "--cached", "--name-status", "-z", "--find-renames",
                "--no-ext-diff", "--no-textconv", "--no-color", tree, "--");
        if (changed.exit != 0) throw new IOException("Git index rename lookup failed.");
        String[] fields = changed.text().split("\0", -1);
        for (int i = 0; i < fields.length && !fields[i].isEmpty();) {
            String status = fields[i++];
            if (i >= fields.length) break;
            String first = fields[i++];
            if (status.startsWith("R") || status.startsWith("C")) {
                if (i >= fields.length) break;
                String second = fields[i++];
                if (status.startsWith("R") && second.equals(path)) return first;
            }
        }
        return null;
    }

    static Result parse(Path repository, String baseline, String blob, String patch) throws IOException {
        var added = new LinkedHashMap<Integer, Boolean>();
        var deleted = new ArrayList<Deletion>();
        int next = -1, removedBoundary = -1;
        StringBuilder removed = null;
        for (String line : patch.split("\n", -1)) {
            var hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                if (removed != null) { deleted.add(new Deletion(removedBoundary, removed.toString())); removed = null; }
                int count = hunk.group(4) == null ? 1 : Integer.parseInt(hunk.group(4));
                next = Integer.parseInt(hunk.group(3)) + (count == 0 ? 1 : 0);
            } else if (next >= 0 && line.startsWith("-")) {
                if (removed == null) { removed = new StringBuilder(); removedBoundary = next - 1; }
                removed.append(line.substring(1)).append('\n');
            } else if (next >= 0 && line.startsWith("+")) {
                if (removed != null) { deleted.add(new Deletion(removedBoundary, removed.toString())); removed = null; }
                added.put(next++, Boolean.TRUE);
            } else if (next >= 0 && line.startsWith(" ")) {
                if (removed != null) { deleted.add(new Deletion(removedBoundary, removed.toString())); removed = null; }
                next++;
            } else if (removed != null && line.equals("\\ No newline at end of file")) {
                removed.setLength(removed.length() - 1);
            }
        }
        if (removed != null) deleted.add(new Deletion(removedBoundary, removed.toString()));
        return new Result(repository, baseline, blob, added, deleted, "");
    }

    private static Result unavailable(Path repository, String baseline, String diagnostic) {
        return new Result(repository, baseline, "", Map.of(), List.of(), diagnostic);
    }
    private static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static byte[] encode(String text) throws CharacterCodingException {
        var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
        byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Git diff cancelled");
    }
    private record Output(int exit, byte[] bytes) { String text() throws CharacterCodingException { return decode(bytes); } }

    private static Output git(Path directory, long deadline, String... arguments) throws IOException, InterruptedException {
        interrupted();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Git diff exceeded its 15 second limit.");
        var command = new ArrayList<>(List.of("git", "--no-pager", "-c", "color.ui=false",
                "-c", "core.quotepath=false", "-c", "core.autocrlf=false", "-c", "core.fsmonitor=false",
                "-C", directory.toString()));
        // check-ignore rejects literal pathspec magic; ls-tree must not expand metacharacters.
        if (arguments[0].equals("ls-tree")) command.add("--literal-pathspecs");
        Collections.addAll(command, arguments);
        var builder = new ProcessBuilder(command);
        for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE",
                "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_LITERAL_PATHSPECS"))
            builder.environment().remove(key);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        process.getOutputStream().close();
        var stdout = new Collector(process, process.getInputStream(), MAX_OUTPUT_BYTES);
        var stderr = new Collector(process, process.getErrorStream(), 64 * 1024);
        Thread out = Thread.ofVirtual().start(stdout), err = Thread.ofVirtual().start(stderr);
        try {
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new IOException("Git diff exceeded its 15 second limit.");
            if (!out.join(Duration.ofSeconds(1)) || !err.join(Duration.ofSeconds(1))) throw new IOException("Git output did not finish.");
            if (stdout.failure != null) throw stdout.failure;
            if (stderr.failure != null) throw stderr.failure;
            return new Output(process.exitValue(), stdout.buffer.toByteArray());
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            out.interrupt(); err.interrupt();
        }
    }
    private static final class Collector implements Runnable {
        final Process process;
        final InputStream input;
        final int limit;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        volatile IOException failure;
        Collector(Process process, InputStream input, int limit) { this.process = process; this.input = input; this.limit = limit; }
        @Override public void run() {
            try {
                byte[] chunk = new byte[8192];
                for (int count; (count = input.read(chunk)) >= 0;) {
                    if (buffer.size() + count > limit) throw new IOException("Git output exceeds the bounded diff limit.");
                    buffer.write(chunk, 0, count);
                }
            } catch (IOException error) { failure = error; process.destroyForcibly(); }
        }
    }
}
