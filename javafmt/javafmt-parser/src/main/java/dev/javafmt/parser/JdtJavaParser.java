package dev.javafmt.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.RecordPattern;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypePattern;

/** Java 25 syntax and exact token spans, without bindings, workspace state or formatter reuse. */
public final class JdtJavaParser implements JavaParser {
    private static final Map<String, String> OPTIONS = options();

    private static Map<String, String> options() {
        Map<String, String> result = new HashMap<> ();
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, result);
        result.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
        result.put(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.IGNORE);
        result.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        return Map.copyOf(result);
    }

    @Override
    public ParsedSource parse(String source) {
        Objects.requireNonNull(source, "source");
        List<JavaToken> tokens = new ArrayList<> ();
        List<ParseDiagnostic> diagnostics = new ArrayList<> ();
        IScanner scanner = ToolFactory.createScanner(true, false, false, "25", "25", false);
        scanner.setSource(source.toCharArray());
        try {
            int kind;

            while ((kind = nextToken(scanner, tokens)) != ITerminalSymbols.TokenNameEOF) {
                int start = scanner.getCurrentTokenStartPosition();
                int end = scanner.getCurrentTokenEndPosition() + 1;
                boolean line = kind == ITerminalSymbols.TokenNameCOMMENT_LINE || kind
                        == ITerminalSymbols.TokenNameCOMMENT_MARKDOWN;
                boolean comment = line || kind == ITerminalSymbols.TokenNameCOMMENT_BLOCK || kind
                        == ITerminalSymbols.TokenNameCOMMENT_JAVADOC || kind
                        == ITerminalSymbols.TokenNameCOMMENT_MARKDOWN;
                String text = new String(scanner.getCurrentTokenSource());

                // JLS line terminators are not part of a line comment. JDT includes physical
                // terminators in scanner spans; leave those characters available to layout.
                if (line) {
                    while (end > start && (source.charAt(end - 1) == '\n' || source.charAt(end - 1)
                            == '\r'))
                        end--;
                    text = stripPhysicalTerminators(text);
                }
                tokens.add(new JavaToken(kind, start, end, source.substring(start, end), text,
                        comment, line, literal(kind)));
            }
        } catch (InvalidInputException error) {
            int start = Math.clamp(scanner.getCurrentTokenStartPosition(), 0, source.length());
            int end = Math.clamp(scanner.getCurrentTokenEndPosition() + 1, start, source.length());
            diagnostics.add(new ParseDiagnostic(start, end, "Invalid Java token: " + error
                    .getMessage()));
            return new ParsedSource(source, null, tokens, diagnostics);
        } catch (RuntimeException error) {
            diagnostics.add(new ParseDiagnostic(0, source.length(),
                    "Java scanner could not safely read this source."));
            return new ParsedSource(source, null, tokens, diagnostics);
        }
        try {
            CompilationUnit unit = parseUnit(source, "Source.java");
            collectErrors(source, unit, diagnostics);
            // IScanner cannot classify module restricted keywords by itself. Parse module
            // declarations with the compiler's required unit kind; never infer layout from
            // those identifier spellings alone. Selection is independent of an external path.
            if (!diagnostics.isEmpty() && tokens.stream().anyMatch(token -> token.text()
                    .equals("module"))) {
                CompilationUnit module = parseUnit(source, "module-info.java");
                List<ParseDiagnostic> moduleErrors = new ArrayList<> ();
                collectErrors(source, module, moduleErrors);
                if (module.getModule() != null && moduleErrors.isEmpty()) {
                    unit = module;
                    diagnostics.clear();
                }
            }
            if (diagnostics.isEmpty()) {
                unit.accept(new ASTVisitor() {
                    @Override
                    public boolean preVisit2(ASTNode node) {
                        if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
                            int start = Math.clamp(node.getStartPosition(), 0, source.length());
                            int end = Math.clamp(start + node.getLength(), start, source.length());
                            diagnostics.add(new ParseDiagnostic(start, end,
                                    "Recovered or unsupported Java syntax cannot be formatted safely."));
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(TypePattern node) {
                        if (!(node.getParent() instanceof RecordPattern) && node
                                .getPatternVariable2() instanceof SingleVariableDeclaration variable
                                && variable.getType().isPrimitiveType()) {
                            diagnostics.add(new ParseDiagnostic(node.getStartPosition(), node
                                    .getStartPosition() + node.getLength(),
                                    "Primitive type patterns require Java 25 preview features, which are disabled."));
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(InstanceofExpression node) {
                        if (node.getRightOperand().isPrimitiveType()) {
                            diagnostics.add(new ParseDiagnostic(node.getStartPosition(), node
                                    .getStartPosition() + node.getLength(),
                                    "Primitive instanceof requires Java 25 preview features, which are disabled."));
                        }
                        return true;
                    }
                });
            }
            return new ParsedSource(source, unit, tokens, diagnostics);
        } catch (RuntimeException | StackOverflowError error) {
            diagnostics.add(new ParseDiagnostic(0, source.length(),
                    "Java parser could not safely build this syntax tree."));
            return new ParsedSource(source, null, tokens, diagnostics);
        }
    }

    private static int nextToken(IScanner scanner,
            List<JavaToken> previous) throws InvalidInputException {
        try {
            return scanner.getNextToken();
        } catch (InvalidInputException error) {
            // JDT 3.44 understands Java 25 module imports internally, but its public scanner
            // cannot map TokenNamemodule into ITerminalSymbols (a documented contextual-token
            // limitation). Retain this exact lexical identifier only after `import`; the full
            // AST subsequently validates its role. Other scanner errors remain fatal.
            if (error.getMessage() != null && error.getMessage().endsWith(": TokenNamemodule")
                    && new String(scanner.getCurrentTokenSource()).equals("module")) {
                for (int i = previous.size() - 1; i >= 0; i--) {
                    JavaToken token = previous.get(i);

                    if (token.comment())
                        continue;
                    if (token.text().equals("import"))
                        return ITerminalSymbols.TokenNameIdentifier;
                    break;
                }
            }
            throw error;
        }
    }

    private static CompilationUnit parseUnit(String source, String name) {
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(name);
        parser.setCompilerOptions(OPTIONS);
        parser.setResolveBindings(false);
        parser.setBindingsRecovery(false);
        parser.setStatementsRecovery(false);
        parser.setIgnoreMethodBodies(false);
        return (CompilationUnit) parser.createAST(null);
    }

    private static void collectErrors(String source, CompilationUnit unit,
            List<ParseDiagnostic> diagnostics) {
        for (var problem : unit.getProblems()) {
            if (!problem.isError())
                continue;
            int start = Math.clamp(problem.getSourceStart(), 0, source.length());
            int end = Math.clamp(problem.getSourceEnd() + 1, start, source.length());
            diagnostics.add(new ParseDiagnostic(start, end, problem.getMessage()));
        }
    }

    private static String stripPhysicalTerminators(String text) {
        int end = text.length();

        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n'))
            end--;
        return text.substring(0, end);
    }

    private static boolean literal(int kind) {
        return switch (kind) {
            case ITerminalSymbols.TokenNameIntegerLiteral, ITerminalSymbols.TokenNameLongLiteral,
                    ITerminalSymbols.TokenNameFloatingPointLiteral,
                    ITerminalSymbols.TokenNameDoubleLiteral,
                    ITerminalSymbols.TokenNameCharacterLiteral,
                    ITerminalSymbols.TokenNameStringLiteral, ITerminalSymbols.TokenNameTextBlock,
                    ITerminalSymbols.TokenNametrue, ITerminalSymbols.TokenNamefalse,
                    ITerminalSymbols.TokenNamenull -> true;
            default -> false;
        };
    }
}
