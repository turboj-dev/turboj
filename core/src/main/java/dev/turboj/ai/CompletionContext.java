package dev.turboj.ai;

import dev.turboj.editor.document.PieceTable;

import org.eclipse.jdt.core.dom.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bounded current-buffer context only. No adjacent files, repository documents or binaries are
 * read.
 */
public final class CompletionContext {
    private CompletionContext() {}

    private static final Pattern SECRET =
            Pattern.compile(
                    "-----BEGIN (?:[A-Z ]*PRIVATE KEY|PGP PRIVATE"
                        + " KEY)|\\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{24,}|AKIA[A-Z0-9]{16})\\b|(?i:(?:api[_-]?key|password|secret|access[_-]?token)\\s*[=:]\\s*[\\\"'][^\\\"'\\r"
                        + "\\n"
                        + "]{8,}[\\\"'])");

    public static AICompletionRequest build(
            Path file,
            PieceTable.Snapshot snapshot,
            int offset,
            AIConfig config,
            boolean larger,
            Cancellation cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        validatePath(file, config.exclusions());
        checkIgnored(file, cancellation);
        Path resolved = resolveBackingPath(file);
        validatePath(resolved, config.exclusions());
        if (!resolved.equals(file.toAbsolutePath().normalize()))
            checkIgnored(resolved, cancellation);
        Objects.checkIndex(offset, snapshot.length() + 1);
        if (offset > 0
                && offset < snapshot.length()
                && Character.isHighSurrogate(snapshot.charAt(offset - 1))
                && Character.isLowSurrogate(snapshot.charAt(offset)))
            throw new IllegalArgumentException("AI caret must be on a Unicode character boundary.");
        String context =
                snapshot.length() <= 262_144 ? signatures(snapshot.toString(), offset) : "";
        context = context.substring(0, Math.min(context.length(), config.contextChars() / 4));
        if (!context.isEmpty() && Character.isHighSurrogate(context.charAt(context.length() - 1)))
            context = context.substring(0, context.length() - 1);
        int budget = config.contextChars() - context.length();
        int before = Math.min(offset, budget * 3 / 4);
        int after = Math.min(snapshot.length() - offset, budget - before);
        before = Math.min(offset, budget - after);
        int start = offset - before, end = offset + after;
        if (start > 0 && Character.isLowSurrogate(snapshot.charAt(start))) start++;
        if (end < snapshot.length()
                && end > offset
                && Character.isHighSurrogate(snapshot.charAt(end - 1))) end--;
        String prefix = snapshot.subSequence(start, offset).toString();
        String suffix = snapshot.subSequence(offset, end).toString();
        if (SECRET.matcher(prefix + suffix + context).find())
            throw new IllegalArgumentException(
                    "AI request blocked: possible credential in the selected context.");
        cancellation.throwIfCancelled();
        return new AICompletionRequest(
                file, snapshot.version(), offset, prefix, suffix, context, 25, larger);
    }

    static void validatePath(Path file, String exclusions) {
        Path absolute = file.toAbsolutePath().normalize();
        String name = absolute.getFileName().toString();
        if (!name.endsWith(".java")
                || name.toLowerCase(Locale.ROOT)
                        .matches(".*(?:credential|secret|private[-_]?key).*"))
            throw new IllegalArgumentException(
                    "AI context is limited to non-secret Java source files.");
        for (String line : exclusions.split("[\\r\\n,]+")) {
            String rule = line.trim();
            if (rule.isEmpty()) continue;
            final PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + rule);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid AI exclusion pattern.");
            }
            boolean excluded = matcher.matches(absolute) || matcher.matches(absolute.getFileName());
            for (int i = 0; !excluded && i < absolute.getNameCount(); i++)
                excluded = matcher.matches(absolute.subpath(i, absolute.getNameCount()));
            if (excluded)
                throw new IllegalArgumentException("AI completion is excluded for this file.");
        }
    }

    /**
     * Follow existing aliases before applying privacy rules. For an unsaved buffer, resolve the
     * nearest existing ancestor, then append only the not-yet-created path components. Broken or
     * inaccessible symbolic links fail closed instead of being treated as an ordinary new file.
     */
    private static Path resolveBackingPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path existing = absolute;
        try {
            while (existing != null) {
                if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    Path resolved = existing.toRealPath();
                    if (!Files.isReadable(resolved)
                            || (!existing.equals(absolute) && !Files.isDirectory(resolved)))
                        throw new IOException("Backing path is not accessible.");
                    return resolved.resolve(existing.relativize(absolute)).normalize();
                }
                if (!Files.notExists(existing, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Backing path cannot be inspected.");
                existing = existing.getParent();
            }
        } catch (IOException | SecurityException error) {
            throw new IllegalArgumentException(
                    "AI request blocked: source aliases could not be checked.");
        }
        throw new IllegalArgumentException(
                "AI request blocked: source aliases could not be checked.");
    }

    /**
     * Git's own ignore semantics, including local/global excludes. Never invoked on the UI thread.
     */
    private static void checkIgnored(Path file, Cancellation cancellation) throws Exception {
        Path absolute = file.toAbsolutePath().normalize(), directory = absolute.getParent();
        Path root = directory;
        while (root != null && !Files.exists(root.resolve(".git"))) root = root.getParent();
        if (root == null) return;
        Process process =
                new ProcessBuilder(
                                "git",
                                "-C",
                                root.toString(),
                                "check-ignore",
                                "-q",
                                "--",
                                absolute.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try (var registration = cancellation.register(() -> process.destroyForcibly())) {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException(
                        "AI request blocked: ignore rules could not be checked.");
            }
            cancellation.throwIfCancelled();
            if (process.exitValue() == 0)
                throw new IllegalArgumentException(
                        "AI completion is disabled for Git-ignored files.");
            if (process.exitValue() != 1)
                throw new IllegalArgumentException(
                        "AI request blocked: ignore rules could not be checked.");
        }
    }

    private static String signatures(String source, int offset) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);
        parser.setStatementsRecovery(true);
        var options = new HashMap<String, String>();
        org.eclipse.jdt.core.JavaCore.setComplianceOptions("25", options);
        parser.setCompilerOptions(options);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        StringBuilder result = new StringBuilder("Current-file declarations (context data):\n");
        for (Object item : unit.imports()) {
            if (result.length() > 1500) break;
            result.append(item);
        }
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration method) {
                        if (result.length() > 3500) return false;
                        if (method.getStartPosition() <= offset
                                && offset <= method.getStartPosition() + method.getLength())
                            result.append("Current method: ");
                        result.append(
                                        method.getReturnType2() == null
                                                ? "constructor"
                                                : method.getReturnType2())
                                .append(' ')
                                .append(method.getName())
                                .append('(');
                        for (Object parameter : method.parameters())
                            result.append(parameter).append(", ");
                        result.append(")\n");
                        return false;
                    }

                    @Override
                    public boolean visit(TypeDeclaration type) {
                        if (result.length() > 3500) return false;
                        result.append(type.isInterface() ? "interface " : "class ")
                                .append(type.getName())
                                .append('\n');
                        return true;
                    }
                });
        return result.substring(0, Math.min(4096, result.length()));
    }
}
