package dev.turboj.language;

import dev.turboj.compiler.CompilerDiagnostic;
import dev.turboj.compiler.SourceSnapshot;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Tolerant Java 25 AST parsing. This is an asynchronous full parse, not an incremental parser. */
public final class JdtSyntaxAnalyzer {
    /** Top-level public type takes precedence; compact source receives a stable synthetic name. */
    public static String sourceFileName(String source) {
        var unit = parse(new SourceSnapshot("Untitled.java", source, 0));
        String first = null;
        for (Object item : unit.types()) {
            if (!(item instanceof AbstractTypeDeclaration declaration)
                    || declaration instanceof ImplicitTypeDeclaration) continue;
            String name = declaration.getName().getIdentifier();
            if (name.isEmpty() || declaration.getName().getStartPosition() < 0) continue;
            if (Modifier.isPublic(declaration.getModifiers())) return name + ".java";
            if (first == null) first = name;
        }
        return first == null ? "Untitled.java" : first + ".java";
    }

    public record SourceRange(int start, int end) {
        public boolean contains(int offset) {
            return start <= offset && offset < end;
        }
    }

    public record SyntaxResult(
            long version,
            boolean syntacticallyValid,
            List<CompilerDiagnostic> diagnostics,
            List<SourceRange> statements,
            int completedUnitEnd,
            long durationNanos) {
        public SyntaxResult {
            diagnostics = List.copyOf(diagnostics);
            statements = List.copyOf(statements);
        }

        /** Smallest enclosing statement; method/class context may still make it invalid JShell input. */
        public SourceRange statementAt(int offset) {
            return statements.stream()
                    .filter(r -> r.contains(offset))
                    .min(java.util.Comparator.comparingInt(r -> r.end() - r.start()))
                    .orElse(null);
        }
    }

    public SyntaxResult analyze(SourceSnapshot source) {
        long started = System.nanoTime();
        var unit = parse(source);
        var diagnostics = new ArrayList<CompilerDiagnostic>();
        for (var problem : unit.getProblems()) {
            int start = problem.getSourceStart();
            diagnostics.add(
                    new CompilerDiagnostic(
                            problem.isError() ? "ERROR" : "WARNING",
                            problem.getMessage(),
                            problem.getSourceLineNumber(),
                            start < 0 ? -1 : unit.getColumnNumber(start) + 1,
                            start,
                            problem.getSourceEnd() < 0 ? -1 : problem.getSourceEnd() + 1));
        }
        var statements = new ArrayList<SourceRange>();
        int[] completedEnd = {0};
        unit.accept(
                new ASTVisitor() {
                    @Override
                    public void preVisit(ASTNode node) {
                        if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0)
                            return;
                        if (!(node instanceof Statement)
                                && !(node instanceof AbstractTypeDeclaration)) return;
                        if (node instanceof Block) return;
                        int start = node.getStartPosition(), end = start + node.getLength();
                        if (start < 0 || end > source.source().length() || start == end) return;
                        if (diagnostics.stream()
                                .anyMatch(
                                        d -> d.isError() && d.start() >= start && d.start() < end))
                            return;
                        completedEnd[0] = Math.max(completedEnd[0], end);
                        if (node instanceof Statement) statements.add(new SourceRange(start, end));
                    }
                });
        return new SyntaxResult(
                source.version(),
                diagnostics.stream().noneMatch(CompilerDiagnostic::isError),
                diagnostics,
                statements,
                completedEnd[0],
                System.nanoTime() - started);
    }

    private static CompilationUnit parse(SourceSnapshot source) {
        var parser = ASTParser.newParser(AST.JLS25);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.source().toCharArray());
        parser.setUnitName(source.fileName());
        parser.setStatementsRecovery(true);
        parser.setResolveBindings(false);
        return (CompilationUnit) parser.createAST(null);
    }
}
