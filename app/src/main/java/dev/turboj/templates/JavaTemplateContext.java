package dev.turboj.templates;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.util.*;
import javax.tools.*;

/** Parse-only Java context, computed on the template worker, never during ordinary typing or painting. */
public record JavaTemplateContext(Set<String> contexts, String className, String methodName,
                                  Map<String, String> variables, List<String> parameters, String fileName) {
    public boolean accepts(LiveTemplateCatalog.Template template) {
        return !template.disabled() && template.contexts().stream().anyMatch(contexts::contains);
    }
    public static JavaTemplateContext inspect(String source, int offset, String fileName) throws Exception {
        if (!codeAt(source, offset)) return new JavaTemplateContext(Set.of(), "", "", Map.of(), List.of(), fileName);
        var input = new SimpleJavaFileObject(URI.create("string:///Template.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, diagnostic -> {}, List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next(); var positions = Trees.instance(task).getSourcePositions();
            class Scan extends TreePathScanner<Void, Void> {
                ClassTree type; MethodTree method; int typeStart = -1, methodStart = -1;
                boolean elsePlace;
                final Map<String, String> variables = new LinkedHashMap<>();
                boolean contains(Tree tree) {
                    long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
                    return start <= offset && (end < 0 || offset < end);
                }
                @Override public Void visitClass(ClassTree tree, Void unused) {
                    if (!contains(tree)) return null;
                    type = tree; typeStart = (int) positions.getStartPosition(unit, tree);
                    return super.visitClass(tree, unused);
                }
                @Override public Void visitMethod(MethodTree tree, Void unused) {
                    if (!contains(tree)) return null;
                    method = tree; methodStart = (int) positions.getStartPosition(unit, tree);
                    return super.visitMethod(tree, unused);
                }
                @Override public Void visitBlock(BlockTree tree, Void unused) { return contains(tree) ? super.visitBlock(tree, unused) : null; }
                @Override public Void visitIf(IfTree tree, Void unused) {
                    long end = positions.getEndPosition(unit, tree.getThenStatement());
                    if (tree.getElseStatement() == null && end >= 0 && end <= offset
                            && lastCodeCharacter(source.substring((int) end, offset), offset - (int) end) == '\0')
                        elsePlace = true;
                    return super.visitIf(tree, unused);
                }
                @Override public Void visitVariable(VariableTree tree, Void unused) {
                    if (positions.getStartPosition(unit, tree) < offset && tree.getType() != null) {
                        boolean scope = true;
                        for (var path = getCurrentPath().getParentPath(); path != null; path = path.getParentPath()) {
                            Tree parent = path.getLeaf();
                            if ((parent instanceof ForLoopTree || parent instanceof EnhancedForLoopTree || parent instanceof CatchTree
                                    || parent instanceof LambdaExpressionTree) && !contains(parent)) scope = false;
                        }
                        if (scope) variables.put(tree.getName().toString(), tree.getType().toString());
                    }
                    return null;
                }
            }
            var scan = new Scan(); scan.scan(unit, null);
            var contexts = new HashSet<String>();
            boolean method = scan.method != null && scan.method.getBody() != null && scan.contains(scan.method.getBody())
                    && scan.methodStart > scan.typeStart;
            // Ignore a whitespace-only prefix, but never expand a statement after a dot or inside an expression.
            char previous = lastCodeCharacter(source, offset);
            boolean boundary = previous == '\0' || previous == '{' || previous == '}' || previous == ';';
            if (method) {
                contexts.add("JAVA_CODE"); contexts.add("JAVA_EXPRESSION");
                if (boundary) contexts.add("JAVA_STATEMENT");
                if (scan.elsePlace) contexts.add("JAVA_ELSE_PLACE");
                // Consumer applicability needs target-type resolution; keep those entries selectable explicitly only
                // after a future semantic provider can prove that context. Never guess from punctuation.
            } else if (boundary) {
                contexts.add("JAVA_DECLARATION"); contexts.add("JAVA_CODE");
                boolean implicit = scan.type == null || scan.type.getSimpleName().isEmpty();
                boolean instanceMain = scan.type != null && scan.type.getMembers().stream().anyMatch(member -> member instanceof MethodTree m
                        && m.getName().contentEquals("main") && !m.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC));
                contexts.add(implicit ? "JAVA_IMPLICIT_CLASS_DECLARATION" : instanceMain
                        ? "JAVA_NORMAL_CLASS_DECLARATION_WITH_INSTANCE_MAIN" : "JAVA_NORMAL_CLASS_DECLARATION_WITHOUT_INSTANCE_MAIN");
            }
            return new JavaTemplateContext(Set.copyOf(contexts), scan.type == null ? "" : scan.type.getSimpleName().toString(),
                    method ? scan.method.getName().toString() : "", Collections.unmodifiableMap(scan.variables),
                    method ? scan.method.getParameters().stream().map(v -> v.getName().toString()).toList() : List.of(), fileName);
        }
    }
    /** Lexical guard for comments, literals, character literals and text blocks, including incomplete code. */
    static boolean codeAt(String text, int end) {
        return lexical(text, end)[0] == 0;
    }
    private static char lastCodeCharacter(String text, int end) { return (char) lexical(text, end)[1]; }
    private static int[] lexical(String text, int end) {
        int mode = 0;
        char last = '\0';
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i), next = i + 1 < end ? text.charAt(i + 1) : '\0';
            if (mode == 1) { if (c == '\n' || c == '\r') mode = 0; }
            else if (mode == 2) { if (c == '*' && next == '/') { mode = 0; i++; } }
            else if (mode == 3 || mode == 4) {
                if (c == '\\') i++; else if (c == (mode == 3 ? '"' : '\'')) mode = 0;
            } else if (mode == 5) {
                if (c == '\\') i++;
                else if (c == '"' && next == '"' && i + 2 < end && text.charAt(i + 2) == '"') { mode = 0; i += 2; }
            } else if (c == '/' && next == '/') { mode = 1; i++; }
            else if (c == '/' && next == '*') { mode = 2; i++; }
            else if (c == '\'') { mode = 4; last = c; }
            else if (c == '"') {
                last = c;
                if (next == '"' && i + 2 < end && text.charAt(i + 2) == '"') { mode = 5; i += 2; }
                else mode = 3;
            } else if (!Character.isWhitespace(c)) last = c;
        }
        return new int[]{mode, last};
    }
}
