package dev.javafmt.core;

import dev.javafmt.parser.*;
import dev.javafmt.core.internal.Layout;
import java.util.*;
import org.eclipse.jdt.core.dom.*;

/** Stateless, thread-safe entry point. No filesystem, network, workspace or IDE dependency. */
public final class JavaFormatter {
    public static final String VERSION = "0.1.0";
    public static final int STYLE_VERSION = 1;
    private final JavaParser parser;

    public JavaFormatter() {
        this(new JdtJavaParser());
    }

    public JavaFormatter(JavaParser parser) {
        this.parser = Objects.requireNonNull(parser);
    }

    public String format(String source) {
        FormatResult result = format(new FormatRequest(source));

        if (!result.success())
            throw new FormatException(result.diagnostics().getFirst().message());
        return result.formattedText();
    }

    public FormatResult format(FormatRequest request) {
        return run(request, null);
    }

    public FormatResult formatRange(FormatRequest request, TextRange range) {
        Objects.requireNonNull(range);
        if (range.end() > request.source().length())
            return failure(request, "Range exceeds source", range);
        return run(request, range);
    }

    private FormatResult run(FormatRequest request, TextRange requestedRange) {
        Objects.requireNonNull(request);
        if (request.source().length() > 4 * 1024 * 1024)
            return failure(request, "Source exceeds the 4 Mi UTF-16 unit safety limit",
                    requestedRange);
        try {
            ParsedSource before = parser.parse(request.source());

            if (!before.valid())
                return new FormatResult(request.source(), List.of(), request.sourceVersion(),
                        VERSION, STYLE_VERSION, before.diagnostics().stream()
                        .map(d -> new FormatDiagnostic(d.start(), d.end(), d.message())).toList(),
                        false, requestedRange);
            TextRange range = requestedRange == null ? new TextRange(0, request.source()
                    .length()) : enclosing(before, requestedRange);

            if (range == null)
                return failure(request, "No safe enclosing syntax region", requestedRange);
            List<TextEdit> edits = new Layout(before, request.lineEndings()).edits(range,
                    requestedRange == null);
            String output = TextEdit.apply(request.source(), edits);

            if (edits.isEmpty())
                return result(request, output, edits, range);
            ParsedSource after = parser.parse(output);

            if (!after.valid() || !sameTokens(before, after) || !before.unit()
                    .subtreeMatch(new ASTMatcher(true), after.unit()))
                return failure(request, "Layout validation failed; original source retained",
                        range);
            return result(request, output, edits, range);
        } catch (StackOverflowError tooDeep) {
            return failure(request, "Syntax nesting exceeds the parser/layout safety limit",
                    requestedRange);
        } catch (RuntimeException unsupported) {
            return failure(request, "Unsupported syntax/layout: " + unsupported.getClass()
                    .getSimpleName(), requestedRange);
        }
    }

    private static boolean sameTokens(ParsedSource a, ParsedSource b) {
        if (a.tokens().size() != b.tokens().size())
            return false;
        for (int i = 0; i < a.tokens().size(); i++) {
            JavaToken x = a.tokens().get(i), y = b.tokens().get(i);

            if (x.kind() != y.kind() || !x.raw().equals(y.raw()))
                return false;
        }
        return true;
    }

    private static TextRange enclosing(ParsedSource source, TextRange requested) {
        ASTNode[] best = {source.unit()};
        source.unit().accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                int start = node.getStartPosition(), end = start + node.getLength();
                boolean eligible = node instanceof BodyDeclaration || node instanceof Statement
                        || node instanceof ImportDeclaration || node instanceof PackageDeclaration
                        || node instanceof ModuleDirective;

                if (eligible && start <= requested.start() && end >= requested.end() && node
                        .getLength() < best[0].getLength())
                    best[0] = node;
            }
        });
        ASTNode node = best[0];
        int start = node.getStartPosition(), end = start + node.getLength();
        int left = start, right = end;

        for (JavaToken token : source.tokens()) {
            if (token.end() <= start)
                left = token.end();
            if (token.start() >= end) {
                right = token.start();
                break;
            }
        }
        // Include indentation and leading comments attached to this declaration, never neighboring code.
        return new TextRange(Math.max(0, left), Math.min(source.source().length(), right));
    }

    private static FormatResult result(FormatRequest r, String text, List<TextEdit> edits,
            TextRange range) {
        return new FormatResult(text, edits, r.sourceVersion(), VERSION, STYLE_VERSION, List.of(),
                true, range);
    }

    private static FormatResult failure(FormatRequest r, String reason, TextRange range) {
        return new FormatResult(r.source(), List.of(), r.sourceVersion(), VERSION, STYLE_VERSION,
                List.of(new FormatDiagnostic(range == null ? 0 : range.start(), range
                == null ? 0 : range.end(), reason)), false, range);
    }
}
