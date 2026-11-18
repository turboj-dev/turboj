package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;

/** Conservative checked-exception proposals. No inferred catch-and-ignore behavior or caller rewrites. */
final class ExceptionQuickFixes {
    record Candidate(String title, String explanation, List<FileEdits> edits, Location focus) {}
    private record ProblemAt(Path file, int id, int offset, String message) {}

    /** Removing one unhandled-exception diagnostic must not hide the same error newly introduced at a caller. */
    static boolean preservesExistingErrors(LtkWorkspace workspace, Plan plan, Cancellation cancellation) {
        WorkspaceSnapshot original = workspace.snapshot();
        var edits = new HashMap<Path, List<TextEdit>>();
        plan.edits().forEach(file -> edits.put(file.file(), file.edits()));
        var remaining = new HashMap<ProblemAt, Integer>();
        var sources = new ArrayList<SourceSnapshot>();
        for (var source : original.sources()) {
            cancellation.check();
            var changes = edits.getOrDefault(source.file(), List.of());
            for (var problem : workspace.ast(source.file()).getProblems()) if (problem.isError()) {
                int offset = problem.getSourceStart(), delta = 0;
                boolean overlaps = false;
                for (var edit : changes) {
                    if (edit.start() <= offset && edit.end() > offset) { overlaps = true; break; }
                    if (edit.end() <= offset) delta += edit.text().length() - (edit.end() - edit.start());
                }
                if (!overlaps) remaining.merge(new ProblemAt(source.file(), problem.getID(), offset + delta, problem.getMessage()), 1, Integer::sum);
            }
            sources.add(new SourceSnapshot(source.file(), TextEdit.apply(source.text(), changes), source.version() + 1, source.open()));
        }
        try {
            workspace.synchronize(new WorkspaceSnapshot(original.root(), original.version() + 1, sources,
                    original.sourceRoots(), original.classpath(), original.projects()), cancellation);
            for (var source : sources) {
                cancellation.check();
                for (var problem : workspace.ast(source.file()).getProblems()) if (problem.isError()) {
                    var key = new ProblemAt(source.file(), problem.getID(), problem.getSourceStart(), problem.getMessage());
                    Integer count = remaining.get(key);
                    if (count == null || count == 0) return false;
                    remaining.put(key, count - 1);
                }
            }
            return true;
        } finally { workspace.synchronize(original, Cancellation.NONE); }
    }

    @SuppressWarnings("unchecked")
    static List<Candidate> candidates(LtkWorkspace workspace, Location at, CompilationUnit tree,
                                      List<IProblem> problems, Cancellation cancellation) {
        var result = new ArrayList<Candidate>();
        var exceptions = new TreeMap<String, ITypeBinding>();
        Statement statement = null;
        MethodDeclaration method = null;
        for (IProblem problem : problems) {
            cancellation.check();
            if (problem.getID() != IProblem.UnhandledException) continue;
            ASTNode node = NodeFinder.perform(tree, problem.getSourceStart(), problem.getSourceEnd() - problem.getSourceStart() + 1);
            ASTNode call = node;
            while (call != null && !(call instanceof MethodInvocation || call instanceof SuperMethodInvocation
                    || call instanceof ClassInstanceCreation || call instanceof ThrowStatement)) call = call.getParent();
            ITypeBinding[] thrown;
            if (call instanceof ThrowStatement throwing) {
                ITypeBinding type = throwing.getExpression().resolveTypeBinding();
                if (type == null) continue;
                thrown = new ITypeBinding[]{type};
            } else {
                IMethodBinding binding = call instanceof MethodInvocation invocation ? invocation.resolveMethodBinding()
                        : call instanceof SuperMethodInvocation invocation ? invocation.resolveMethodBinding()
                        : call instanceof ClassInstanceCreation creation ? creation.resolveConstructorBinding() : null;
                if (binding == null || binding.isRecovered()) continue;
                thrown = binding.getExceptionTypes();
            }
            // A lambda, anonymous type or initializer establishes a different exception boundary.
            MethodDeclaration enclosing = null;
            Statement enclosingStatement = null;
            for (ASTNode parent = call; parent != null; parent = parent.getParent()) {
                if (parent instanceof MethodDeclaration declaration) { enclosing = declaration; break; }
                if (parent instanceof LambdaExpression || parent instanceof AnonymousClassDeclaration
                        || parent instanceof AbstractTypeDeclaration || parent instanceof Initializer) break;
                if (enclosingStatement == null && parent instanceof Statement s) enclosingStatement = s;
            }
            if (enclosing == null || enclosing.resolveBinding() == null || enclosing.resolveBinding().isRecovered()) continue;
            if (method != null && method != enclosing || statement != null && statement != enclosingStatement) return List.of();
            method = enclosing; statement = enclosingStatement;
            for (ITypeBinding type : thrown) {
                if (type == null || type.isRecovered() || type.isTypeVariable() || type.isCapture() || type.isAnonymous()) continue;
                String qualified = type.getQualifiedName();
                if (qualified.isBlank() || Arrays.stream(problem.getArguments())
                        .noneMatch(argument -> argument.equals(qualified) || argument.equals(type.getName()))) continue;
                exceptions.put(qualified, type);
            }
        }
        if (exceptions.isEmpty() || method == null) return List.of();
        // A broader thrown type covers its subclasses; don't generate illegal multi-catch alternatives.
        var types = exceptions.values().stream().filter(type -> exceptions.values().stream()
                .noneMatch(other -> !other.isEqualTo(type) && type.isSubTypeCompatible(other))).toList();
        String names = String.join(", ", types.stream().map(ITypeBinding::getName).toList());
        AST ast = tree.getAST();
        var throwsRewrite = ASTRewrite.create(ast);
        var thrownTypes = throwsRewrite.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
        for (var type : types) thrownTypes.insertLast(ast.newSimpleType(ast.newName(type.getQualifiedName())), null);
        Candidate declaration = candidate(workspace, at, throwsRewrite, "Add throws declaration for " + names,
                "Declare the checked exception on this method. Existing workspace callers and overriding rules must still compile; external callers may need changes.", cancellation);
        if (declaration != null) result.add(declaration);

        // Restrict wrapping to statements with no declaration escaping its new block. JDT validation remains authoritative.
        if (statement != null && statement.getParent() instanceof Block
                && (statement instanceof ExpressionStatement || statement instanceof ReturnStatement || statement instanceof ThrowStatement)) {
            ASTRewrite rewrite = ASTRewrite.create(ast);
            TryStatement wrapping = ast.newTryStatement();
            wrapping.getBody().statements().add(rewrite.createMoveTarget(statement));
            String source = workspace.snapshot().source(at.file()).text();
            String exceptionName = "exception";
            for (int suffix = 2; source.contains(exceptionName); suffix++) exceptionName = "exception" + suffix;
            CatchClause catchClause = ast.newCatchClause();
            SingleVariableDeclaration variable = ast.newSingleVariableDeclaration();
            variable.setName(ast.newSimpleName(exceptionName));
            if (types.size() == 1) variable.setType(ast.newSimpleType(ast.newName(types.getFirst().getQualifiedName())));
            else {
                UnionType union = ast.newUnionType();
                types.forEach(type -> union.types().add(ast.newSimpleType(ast.newName(type.getQualifiedName()))));
                variable.setType(union);
            }
            catchClause.setException(variable);
            // Preserve thread cancellation instead of swallowing InterruptedException's cleared flag.
            if (types.stream().anyMatch(type -> Set.of("java.lang.InterruptedException", "java.lang.Exception", "java.lang.Throwable").contains(type.getQualifiedName()))) {
                MethodInvocation current = ast.newMethodInvocation();
                current.setExpression(ast.newName("java.lang.Thread")); current.setName(ast.newSimpleName("currentThread"));
                MethodInvocation interrupt = ast.newMethodInvocation(); interrupt.setExpression(current); interrupt.setName(ast.newSimpleName("interrupt"));
                if (types.size() == 1 && types.getFirst().getQualifiedName().equals("java.lang.InterruptedException"))
                    catchClause.getBody().statements().add(ast.newExpressionStatement(interrupt));
                else {
                    IfStatement interrupted = ast.newIfStatement();
                    InstanceofExpression test = ast.newInstanceofExpression(); test.setLeftOperand(ast.newSimpleName(exceptionName));
                    test.setRightOperand(ast.newSimpleType(ast.newName("java.lang.InterruptedException"))); interrupted.setExpression(test);
                    interrupted.setThenStatement(ast.newExpressionStatement(interrupt)); catchClause.getBody().statements().add(interrupted);
                }
            }
            ClassInstanceCreation wrapped = ast.newClassInstanceCreation(); wrapped.setType(ast.newSimpleType(ast.newName("java.lang.RuntimeException")));
            wrapped.arguments().add(ast.newSimpleName(exceptionName));
            ThrowStatement rethrow = ast.newThrowStatement(); rethrow.setExpression(wrapped); catchClause.getBody().statements().add(rethrow);
            wrapping.catchClauses().add(catchClause); rewrite.replace(statement, wrapping, null);
            Candidate catching = candidate(workspace, at, rewrite, "Surround with try/catch for " + names,
                    "Catch the checked exception and rethrow RuntimeException with its cause; no exception is ignored. InterruptedException restores the interrupt flag.", cancellation);
            if (catching != null) result.add(catching);
        }
        return List.copyOf(result);
    }

    private static Candidate candidate(LtkWorkspace workspace, Location at, ASTRewrite rewrite,
                                       String title, String explanation, Cancellation cancellation) {
        cancellation.check();
        String source = workspace.snapshot().source(at.file()).text();
        var document = new Document(source);
        try {
            rewrite.rewriteAST(document, workspace.javaProject(at.file()).getOptions(true)).apply(document);
            var edits = ChangePlans.difference(source, document.get());
            return edits.isEmpty() ? null : new Candidate(title, explanation, List.of(new FileEdits(at.file(), edits)), null);
        } catch (org.eclipse.jface.text.BadLocationException | IllegalArgumentException invalid) { return null; }
    }
}
