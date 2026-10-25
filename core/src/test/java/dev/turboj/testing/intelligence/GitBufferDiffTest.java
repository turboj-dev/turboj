package dev.turboj.testing.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GitBufferDiffTest {
    @TempDir Path temporary;

    Path repository(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        git(root, "init", "-q");
        git(root, "config", "user.name", "TurboJ fixture");
        git(root, "config", "user.email", "fixture@example.test");
        git(root, "config", "core.autocrlf", "false");
        return root;
    }
    Path committed(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, text);
        git(root, "add", "--", name); git(root, "commit", "-qm", "fixture"); return file;
    }
    static String git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), command + "\n" + output); return output;
    }
    static GitBufferDiff.Result diff(Path file, String buffer) throws Exception {
        var result = GitBufferDiff.compute(file, buffer, "HEAD");
        assertTrue(result.available(), result.diagnostic()); return result;
    }

    @Test void comparesHeadToUnsavedBufferAcrossStagedAndUnstagedChangesWithoutWritingEither() throws Exception {
        Path root = repository("staged");
        Path file = committed(root, "Price.java", "first\nsecond\nthird\n");
        Files.writeString(file, "first\nSTAGED\nthird\n"); git(root, "add", "Price.java");
        Files.writeString(file, "first\nDISK\nthird\n");
        String index = git(root, "show", ":Price.java"), status = git(root, "status", "--porcelain=v1");
        var result = diff(file, "first\nBUFFER\nthird\nnew\n");
        assertEquals(Set.of(2, 4), result.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(1, "second\n")), result.deleted());
        assertEquals(git(root, "rev-parse", "HEAD:Price.java").strip(), result.baselineBlob());
        assertEquals("first\nDISK\nthird\n", Files.readString(file));
        assertEquals(index, git(root, "show", ":Price.java"));
        assertEquals(status, git(root, "status", "--porcelain=v1"));
    }

    @Test void deletionAnchorsReferToCurrentBoundariesIncludingBofAndEof() throws Exception {
        Path root = repository("boundaries"), file = committed(root, "A.java", "before\nkeep\nafter\n");
        var result = diff(file, "keep\n");
        assertTrue(result.added().isEmpty());
        assertEquals(List.of(new GitBufferDiff.Deletion(0, "before\n"), new GitBufferDiff.Deletion(1, "after\n")), result.deleted());
        assertEquals(List.of(new GitBufferDiff.Deletion(0, "before\nkeep\nafter\n")), diff(file, "").deleted());
    }

    @Test void multilineReplacementHasSeparateAddedLinesAndDeletedText() throws Exception {
        Path root = repository("replacement"), file = committed(root, "A.java", "a\nb\nc\nd\n");
        var result = diff(file, "a\nX\nY\nZ\nd\n");
        assertEquals(Set.of(2, 3, 4), result.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(1, "b\nc\n")), result.deleted());
    }

    @Test void stagedRenameUsesOriginalBlobEvenWithSpacesTabsAndUnicodeInPath() throws Exception {
        Path root = repository("rename");
        String oldName = "old λ\t name.java", newName = "new 日本\t name.java";
        Path old = committed(root, oldName, "alpha\nbeta\ngamma\n");
        git(root, "mv", "--", oldName, newName);
        var result = diff(root.resolve(newName), "alpha\nchanged\ngamma\n");
        assertEquals(Set.of(2), result.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(1, "beta\n")), result.deleted());
        assertEquals(git(root, "rev-parse", "HEAD:" + oldName).strip(), result.baselineBlob());
        assertFalse(Files.exists(old));
    }

    @Test void newUntrackedAndUnbornFilesHaveEmptyBaselineAndOnlyRealAddedLines() throws Exception {
        Path root = repository("new");
        var unborn = diff(root.resolve("newdir/New.java"), "hello\nworld\n");
        assertEquals(Set.of(1, 2), unborn.added().keySet());
        assertEquals("", unborn.baselineBlob()); assertTrue(unborn.deleted().isEmpty());
        committed(root, "Tracked.java", "tracked\n");
        assertEquals(Set.of(1), diff(root.resolve("New.java"), "\n").added().keySet());
        assertTrue(diff(root.resolve("Empty.java"), "").added().isEmpty());
        assertFalse(Files.exists(root.resolve("newdir")), "No editor source directory should be created by a diff.");
    }

    @Test void ignoredUnresolvedAndNonRepositoryRemainUnknown() throws Exception {
        Path root = repository("unknown"); committed(root, ".gitignore", "Ignored.java\n");
        assertFalse(GitBufferDiff.compute(root.resolve("Ignored.java"), "new\n", "HEAD").available());
        var invalid = GitBufferDiff.compute(root.resolve("New.java"), "new\n", "missing-branch");
        assertFalse(invalid.available()); assertTrue(invalid.added().isEmpty());
        assertFalse(GitBufferDiff.compute(temporary.resolve("Outside.java"), "new\n", "HEAD").available());
        Path unborn = repository("unborn-invalid");
        assertFalse(GitBufferDiff.compute(unborn.resolve("New.java"), "new", "main").available());
    }

    @Test void trackedFilesStayComparableDespiteLaterIgnoreRule() throws Exception {
        Path root = repository("tracked-ignore"), file = committed(root, "A.java", "before\n");
        Files.writeString(root.resolve(".gitignore"), "A.java\n");
        assertEquals(Set.of(1), diff(file, "after\n").added().keySet());
    }

    @Test void deletingFromIndexOrDiskDoesNotReplaceHeadWithAnEmptyBaseline() throws Exception {
        Path root = repository("deleted"), file = committed(root, "A.java", "before\n");
        git(root, "rm", "--", "A.java");
        assertEquals(List.of(new GitBufferDiff.Deletion(0, "before\n")), diff(file, "").deleted());
        Files.writeString(file, "recreated\n");
        var recreated = diff(file, "recreated\n");
        assertEquals(Set.of(1), recreated.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(0, "before\n")), recreated.deleted());
    }

    @Test void crlfAndUnicodeUseLogicalLinesAndRetainUnterminatedDeletedText() throws Exception {
        Path root = repository("unicode"), file = committed(root, "日本.java", "è🙂\r\nlast");
        assertTrue(diff(file, "è🙂\nlast").added().isEmpty());
        var result = diff(file, "è🙂\nchanged");
        assertEquals(Set.of(2), result.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(1, "last")), result.deleted());
    }

    @Test void explicitBaselineUsesThatTreeRatherThanHeadOrIndex() throws Exception {
        Path root = repository("explicit"), file = committed(root, "A.java", "base\n");
        git(root, "tag", "base"); Files.writeString(file, "head\n"); git(root, "commit", "-qam", "change");
        var result = GitBufferDiff.compute(file, "buffer\n", "base");
        assertTrue(result.available(), result.diagnostic());
        assertEquals(List.of(new GitBufferDiff.Deletion(0, "base\n")), result.deleted());
        assertEquals("base", result.baseline());
    }

    @Test void nestedRepositoryAndLinkedWorktreeUseTheirOwnHead() throws Exception {
        Path outer = repository("outer"); committed(outer, "A.java", "outer\n");
        Path nested = repository("outer/nested"), file = committed(nested, "A.java", "nested\n");
        var result = diff(file, "edited\n");
        assertEquals(nested.toRealPath(), result.repository());
        assertEquals("nested\n", result.deleted().getFirst().text());
        Path linked = temporary.resolve("linked");
        git(outer, "worktree", "add", "--detach", linked.toString());
        var worktree = diff(linked.resolve("A.java"), "edited\n");
        assertEquals(linked.toRealPath(), worktree.repository());
        assertEquals("outer\n", worktree.deleted().getFirst().text());
    }

    @Test void binaryOversizedAndUnencodableSourcesAreUnavailableNotUncovered() throws Exception {
        Path root = repository("limits"), file = committed(root, "A.java", "before\n");
        for (String source : List.of("x\0y", "\ud800", "x".repeat(4 * 1024 * 1024 + 1))) {
            var result = GitBufferDiff.compute(file, source, "HEAD");
            assertFalse(result.available()); assertTrue(result.added().isEmpty()); assertTrue(result.deleted().isEmpty());
        }
    }

    @Test void interruptionCancelsBeforeAnyGitWorkAndEdtCallsAreRejected() throws Exception {
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> GitBufferDiff.compute(temporary.resolve("A.java"), "", "HEAD")); }
        finally { Thread.interrupted(); }
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try { GitBufferDiff.compute(temporary.resolve("A.java"), "", "HEAD"); }
            catch (Throwable failure) { error.set(failure); }
        });
        assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test void tenThousandLinesWithSmallEditProducesBoundedSparseMarkers() throws Exception {
        Path root = repository("large");
        String original = java.util.stream.IntStream.rangeClosed(1, 10_000).mapToObj(i -> "value " + i + "\n").collect(java.util.stream.Collectors.joining());
        Path file = committed(root, "A.java", original);
        var result = diff(file, original.replace("value 5000\n", "changed 5000\n"));
        assertEquals(Set.of(5000), result.added().keySet());
        assertEquals(List.of(new GitBufferDiff.Deletion(4999, "value 5000\n")), result.deleted());
    }

    @Test void backgroundDiffDoesNotInvokeRepositoryConfiguredFsmonitorPrograms() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path root = repository("fsmonitor");
        committed(root, "Tracked.java", "tracked\n");
        Path helper = root.resolve("monitor.sh"), marker = root.resolve("helper-ran");
        Files.writeString(helper, "#!/bin/sh\n: > '" + marker.toString().replace("'", "'\\''") + "'\n");
        assertTrue(helper.toFile().setExecutable(true));
        git(root, "config", "core.fsmonitor", helper.toString());
        assertEquals(Set.of(1), diff(root.resolve("New.java"), "new\n").added().keySet());
        assertFalse(Files.exists(marker), "An editor Git lookup must not execute a repository-configured helper.");
    }
}
