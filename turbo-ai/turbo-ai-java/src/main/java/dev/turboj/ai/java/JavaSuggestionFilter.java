package dev.turboj.ai.java;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Cheap Java checks on a suggestion before it is shown, using the JDT parser without bindings: the file
 * must not get new syntax errors, and the suggestion must not use a variable name that appears nowhere in
 * the file and is not declared by the suggestion itself. This rejects most invented locals and broken
 * fragments; it cannot prove that the code is correct, and members reached through a dot or calls to
 * inherited methods are not judged.
 */
public final class JavaSuggestionFilter {
    public enum Verdict { ACCEPT, SYNTAX_ERROR, UNKNOWN_NAME }

    /** Above this size the parse costs more than the suggestion is worth; only the name check runs. */
    private static final int PARSE_LIMIT = 120_000;
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\p{L}\\p{N}_$.@:])([\\p{Ll}_$][\\p{L}\\p{N}_$]*)(?![\\p{L}\\p{N}_$])");
    private static final Pattern WORD = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern DECLARED = Pattern.compile(
            "(?:\\b(?:var|int|long|short|byte|char|float|double|boolean|final)|[\\p{Lu}][\\p{L}\\p{N}_$]*(?:<[^;(){}]*>)?(?:\\[\\])*)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*(?=[=;:,)])"
                    + "|([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*->|\\(\\s*([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*(?:,\\s*[\\p{L}_$][\\p{L}\\p{N}_$]*\\s*)*\\)\\s*->"
                    + "|catch\\s*\\([^)]*?([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\)");
    private static final Set<String> PACKAGE_ROOTS = Set.of("java", "javax", "jdk", "jakarta", "org", "com", "net", "io", "dev");
    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "var", "yield", "record", "sealed", "permits", "non", "when", "module", "length", "_");

    private JavaSuggestionFilter() {}

    public static Verdict check(String source, int offset, String suggestion, int javaVersion) {
        String completed = source.substring(0, offset) + suggestion + source.substring(offset);
        if (unknownName(source, offset, suggestion)) return Verdict.UNKNOWN_NAME;
        if (source.length() <= PARSE_LIMIT) {
            int[] after = syntaxErrors(completed, javaVersion, offset, offset + suggestion.length());
            // An error inside the inserted text is the suggestion's; so is any error the file did not have.
            if (after[1] > 0 || after[0] > syntaxErrors(source, javaVersion, -1, -1)[0]) return Verdict.SYNTAX_ERROR;
        }
        return Verdict.ACCEPT;
    }

    /**
     * A lower-case name used as a value, not after a dot or {@code ::}, not a call or declaration, unknown
     * to the file. Each name is judged in the completed file, so the text around the caret counts:
     * {@code tasks.|()} completed with {@code isEmpty} is a member call, not a bare name.
     */
    static boolean unknownName(String source, int offset, String suggestion) {
        String code = blank(source.substring(0, offset) + suggestion + source.substring(offset));
        var known = new HashSet<String>();
        Matcher words = WORD.matcher(blank(source));
        while (words.find()) known.add(words.group());
        Matcher declared = DECLARED.matcher(blank(suggestion));
        while (declared.find())
            for (int g = 1; g <= declared.groupCount(); g++) if (declared.group(g) != null) known.add(declared.group(g));
        Matcher names = IDENTIFIER.matcher(code);
        names.useTransparentBounds(true).useAnchoringBounds(false).region(offset, offset + suggestion.length());
        while (names.find()) {
            String name = names.group(1);
            if (KEYWORDS.contains(name) || known.contains(name)) continue;
            int after = names.end();
            while (after < code.length() && Character.isWhitespace(code.charAt(after))) after++;
            // A call may reach an inherited or static-imported method; a label or a lambda parameter is declared here.
            if (after < code.length() && (code.charAt(after) == '(' || code.charAt(after) == ':' && !code.startsWith("::", after))) continue;
            if (code.startsWith("->", after)) continue;
            if (PACKAGE_ROOTS.contains(name) && after < code.length() && code.charAt(after) == '.') continue;
            return true;
        }
        return false;
    }

    /** Syntax errors in the file, and how many of them start inside [from, to). */
    private static int[] syntaxErrors(String source, int javaVersion, int from, int to) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(false);
        parser.setStatementsRecovery(true);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(Integer.toString(Math.clamp(javaVersion, 8, 25)), options);
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.ENABLED);
        options.put(JavaCore.COMPILER_PB_REPORT_PREVIEW_FEATURES, JavaCore.IGNORE);
        parser.setCompilerOptions(options);
        var unit = (CompilationUnit) parser.createAST(null);
        int errors = 0, inside = 0;
        for (IProblem problem : unit.getProblems())
            if (problem.isError() && (problem.getID() & IProblem.Syntax) != 0) {
                errors++;
                if (problem.getSourceStart() >= from && problem.getSourceStart() < to) inside++;
            }
        return new int[] {errors, inside};
    }

    /** Strings, characters and comments become spaces, so their words are neither names nor known names. */
    private static String blank(String text) {
        var out = new StringBuilder(text.length());
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char, 5 text block
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), n = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (state == 0) {
                if (c == '/' && n == '/') state = 1;
                else if (c == '/' && n == '*') state = 2;
                else if (text.startsWith("\"\"\"", i)) { state = 5; out.append("   "); i += 2; continue; }
                else if (c == '"') state = 3;
                else if (c == '\'') state = 4;
                out.append(state == 0 ? c : ' ');
                continue;
            }
            if (state == 1 && c == '\n') { state = 0; out.append(c); continue; }
            if (state == 2 && c == '*' && n == '/') { state = 0; out.append("  "); i++; continue; }
            if (state == 5 && text.startsWith("\"\"\"", i)) { state = 0; out.append("   "); i += 2; continue; }
            if ((state == 3 || state == 4) && c == '\\') { out.append("  "); i++; continue; }
            if (state == 3 && c == '"' || state == 4 && c == '\'') { state = 0; out.append(' '); continue; }
            out.append(c == '\n' ? '\n' : ' ');
        }
        return out.toString();
    }
}
