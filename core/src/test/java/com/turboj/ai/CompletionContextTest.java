package com.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.turboj.editor.document.PieceTable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class CompletionContextTest {
    @TempDir Path directory;

    @Test
    void contextKeepsPrefixSuffixImportsAndSignaturesWithoutReadingOtherFiles() throws Exception {
        String source =
                "import java.util.List;\n"
                        + "class MathOps { int square(int value) {\n"
                        + "    \n"
                        + "} int other(int x) { return x; } }";
        int offset = source.indexOf("    ") + 4;
        Files.writeString(directory.resolve("credentials.env"), "private_token=never_read");
        var request =
                CompletionContext.build(
                        directory.resolve("MathOps.java"),
                        new PieceTable(source).snapshot(),
                        offset,
                        AIConfig.defaults(),
                        false,
                        new Cancellation());
        assertEquals(source.substring(0, offset), request.prefix());
        assertEquals(source.substring(offset), request.suffix());
        assertTrue(request.context().contains("import java.util.List"));
        assertTrue(request.context().contains("Current method: int square"));
        assertTrue(request.context().contains("int other"));
        assertFalse(request.context().contains("never_read"));
        assertEquals(25, request.javaVersion());
    }

    @Test
    void requestsStayBoundedAndOffsetsAreUtf16() throws Exception {
        String source = "class Huge {\n" + "// ordinary source 😀\n".repeat(10000) + "}";
        var request =
                CompletionContext.build(
                        directory.resolve("Huge.java"),
                        new PieceTable(source).snapshot(),
                        source.length() / 2,
                        AIConfig.defaults(),
                        false,
                        new Cancellation());
        assertTrue(
                request.prefix().length() + request.suffix().length()
                        <= AIConfig.defaults().contextChars());
        assertTrue(request.context().length() <= 4096);
    }

    @Test
    void builtInAndUserExclusionsBlockBeforeContextAssembly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompletionContext.validatePath(directory.resolve(".env"), ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompletionContext.validatePath(directory.resolve("Credentials.java"), ""));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.validatePath(
                                directory.resolve("private/Main.java"), "**/private/**"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompletionContext.validatePath(directory.resolve("Main.java"), "["));
    }

    @Test
    void secretsAndCancellationAreFailClosed() {
        var snapshot =
                new PieceTable("class Main { String password = \"top-secret-value\"; }").snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                directory.resolve("Main.java"),
                                snapshot,
                                20,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
        Cancellation cancelled = new Cancellation();
        cancelled.cancel();
        assertThrows(
                Exception.class,
                () ->
                        CompletionContext.build(
                                directory.resolve("Main.java"),
                                snapshot,
                                20,
                                AIConfig.defaults(),
                                false,
                                cancelled));
    }

    @Test
    void actualGitIgnoreRulesExcludeJavaBuffers() throws Exception {
        Process init = new ProcessBuilder("git", "init", "-q", directory.toString()).start();
        assertEquals(0, init.waitFor());
        Files.writeString(directory.resolve(".gitignore"), "Ignored.java\n");
        var snapshot = new PieceTable("class Ignored {} ").snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                directory.resolve("Ignored.java"),
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
    }

    @Test
    void aliasesCannotHideExcludedOrGitIgnoredBackingFiles() throws Exception {
        var snapshot = new PieceTable("class Example {} ").snapshot();
        Path privateFile = Files.writeString(directory.resolve(".env"), "not-read");
        Path alias = directory.resolve("Example.java");
        Files.createSymbolicLink(alias, privateFile);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                alias,
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));

        Path project = Files.createDirectory(directory.resolve("project"));
        Process init = new ProcessBuilder("git", "init", "-q", project.toString()).start();
        assertEquals(0, init.waitFor());
        Files.writeString(project.resolve(".gitignore"), "Ignored.java\n");
        Path ignored = Files.writeString(project.resolve("Ignored.java"), "class Example {} ");
        Path outsideAlias = directory.resolve("Outside.java");
        Files.createSymbolicLink(outsideAlias, ignored);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                outsideAlias,
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
    }

    @Test
    void brokenAliasesFailClosedWhileUnsavedBuffersRemainUsable() throws Exception {
        var snapshot = new PieceTable("class Example {} ").snapshot();
        Path broken = directory.resolve("Broken.java");
        Files.createSymbolicLink(broken, directory.resolve("Missing.java"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                broken,
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
        Path brokenParent = directory.resolve("broken-parent");
        Files.createSymbolicLink(brokenParent, directory.resolve("missing-directory"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                brokenParent.resolve("Example.java"),
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
        assertDoesNotThrow(
                () ->
                        CompletionContext.build(
                                directory.resolve("new/package/Example.java"),
                                snapshot,
                                10,
                                AIConfig.defaults(),
                                false,
                                new Cancellation()));
    }

    @Test
    void directoryAliasesCannotHideExcludedUnsavedFiles() throws Exception {
        Path privateDirectory = Files.createDirectory(directory.resolve("private"));
        Path publicAlias = directory.resolve("public");
        Files.createSymbolicLink(publicAlias, privateDirectory);
        AIConfig defaults = AIConfig.defaults();
        AIConfig config =
                new AIConfig(
                        defaults.mode(),
                        defaults.localBackend(),
                        defaults.localEndpoint(),
                        defaults.localModel(),
                        defaults.cloudEndpoint(),
                        defaults.cloudModel(),
                        defaults.automaticLocal(),
                        defaults.automaticCloud(),
                        defaults.cloudConsent(),
                        defaults.delayMillis(),
                        defaults.timeoutSeconds(),
                        defaults.maxTokens(),
                        defaults.contextChars(),
                        "**/private/**",
                        defaults.diagnostics());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompletionContext.build(
                                publicAlias.resolve("Unsaved.java"),
                                new PieceTable("class Unsaved {} ").snapshot(),
                                10,
                                config,
                                false,
                                new Cancellation()));
    }
}
