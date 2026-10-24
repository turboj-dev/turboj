package dev.javafmt.parser;

import java.util.List;
import java.util.Objects;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Immutable source/token/diagnostic snapshot. The AST is fresh for this call and must be treated
 * as read-only by consumers; it is never shared by the parser between calls. It can be null when
 * lexing or parser initialization fails. JDT types remain confined to this internal parser boundary.
 */
public record ParsedSource(String source, CompilationUnit unit, List<JavaToken> tokens,
        List<ParseDiagnostic> diagnostics) {
    public ParsedSource {
        Objects.requireNonNull(source);
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean valid() {
        return unit != null && diagnostics.isEmpty();
    }
}
