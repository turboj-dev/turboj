package dev.turboj.refactorj.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared, offline semantic operations. Call only from a serial background executor. All source
 * ranges are UTF-16 offsets; the caller must reject superseded contexts.
 */
public interface JavaIntelligence extends AutoCloseable {
    /** Empty sourceRoots means an in-memory context: never scan the source file's parent directory. */
    record Context(
            Path file,
            String source,
            long version,
            Map<Path, String> openSources,
            List<Path> sourceRoots,
            List<Path> classpath,
            long projectVersion,
            List<Path> sourceArchives) {
        public Context(
                Path file,
                String source,
                long version,
                Map<Path, String> openSources,
                List<Path> sourceRoots,
                List<Path> classpath,
                long projectVersion) {
            this(
                    file,
                    source,
                    version,
                    openSources,
                    sourceRoots,
                    classpath,
                    projectVersion,
                    List.of());
        }

        public Context {
            file = file.toAbsolutePath().normalize();
            openSources = Map.copyOf(openSources);
            sourceRoots = List.copyOf(sourceRoots);
            classpath = List.copyOf(classpath);
            sourceArchives = List.copyOf(sourceArchives);
        }
    }

    record TextEdit(int start, int end, String text) {}

    /** caret is relative to the replacement text. Additional edits use the original snapshot. */
    record Completion(
            int start,
            int end,
            String text,
            int caret,
            String display,
            String detail,
            List<TextEdit> additionalEdits) {
        public Completion {
            additionalEdits = List.copyOf(additionalEdits);
        }
    }

    record CompletionResult(List<Completion> proposals, long durationNanos) {
        public CompletionResult {
            proposals = List.copyOf(proposals);
        }
    }

    record Symbol(
            String key,
            String name,
            String owner,
            String signature,
            String kind,
            int start,
            int end) {}

    /** Archive sources have a private cached path and are read-only. Missing source is null. */
    record Declaration(
            Path file, String source, int offset, int length, boolean readOnly, String origin) {}

    /** Plain text deliberately cannot execute markup or fetch documentation resources. */
    record Documentation(
            String owner, String signature, String text, String origin, boolean sourceAvailable) {}

    record SignatureHelp(
            List<String> signatures, int activeParameter, int invocationStart, long durationNanos) {
        public SignatureHelp {
            signatures = List.copyOf(signatures);
        }
    }

    record TestTarget(String className, String methodName, String parameterTypes) {}

    default TestTarget testTarget(Context context, int offset) { return null; }

    CompletionResult complete(Context context, int offset);

    Symbol resolveSymbol(Context context, int offset);

    Declaration findDeclaration(Context context, Symbol symbol);

    Documentation documentation(Context context, Symbol symbol);

    SignatureHelp signatureHelp(Context context, int offset);

    @Override
    void close();
}
