package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.Operation;
import dev.turboj.refactorj.api.Requests;
import dev.turboj.refactorj.api.Requests.Request;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.rewrite.*;
import org.eclipse.jdt.core.manipulation.*;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.fix.*;
import org.eclipse.jdt.internal.ui.text.correction.ProblemLocation;
import org.eclipse.jdt.internal.ui.text.correction.QuickAssistProcessorUtil;
import org.eclipse.jdt.internal.ui.text.correction.proposals.*;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.jdt.ui.text.java.correction.ASTRewriteCorrectionProposalCore;
import org.eclipse.ltk.core.refactoring.*;
import java.util.*;

/**
 * Adapts the pinned, headless JDT correction implementation to preview-only LTK changes.
 * Eclipse internal APIs are deliberately confined to this versioned implementation bundle.
 * No fix is applied to a buffer or resource during preparation.
 */
public final class ModernizationOperations {
    private ModernizationOperations() {}

    public static Refactoring create(Request request, LtkWorkspace workspace, ICompilationUnit unit, CompilationUnit ast) {
        if (request.operation().ordinal() < Operation.ANONYMOUS_TO_LAMBDA.ordinal()) return null;
        return new PreparedRefactoring(request.operation().id(), monitor -> prepare(request, workspace, unit, ast, monitor));
    }

    private static Change prepare(Request request, LtkWorkspace workspace, ICompilationUnit unit, CompilationUnit ast,
                                  IProgressMonitor monitor) throws CoreException {
        ASTNode selected = NodeFinder.perform(ast, request.location().start(),
                request.location().end() - request.location().start());
        require(selected != null, "Select a Java syntax element.");
        return switch (request.operation()) {
            case ANONYMOUS_TO_LAMBDA -> {
                ClassInstanceCreation creation = ancestor(selected, ClassInstanceCreation.class);
                require(creation != null && creation.getAnonymousClassDeclaration() != null,
                        "Select an anonymous implementation of a functional interface.");
                require(!serializable(creation.getType().resolveBinding(), new HashSet<>()),
                        "A serializable anonymous implementation cannot become a lambda without changing its serialized form.");
                yield fix(LambdaExpressionsFixCore.createConvertToLambdaFix(creation), monitor);
            }
            case LAMBDA_TO_METHOD_REFERENCE -> {
                LambdaExpression lambda = ancestor(selected, LambdaExpression.class);
                require(lambda != null, "Select a lambda expression.");
                requireSafeReferenceReceiver(lambda);
                yield fix(ConvertLambdaToMethodReferenceFixCore.createConvertLambdaToMethodReferenceFix(ast, lambda), monitor);
            }
            case METHOD_REFERENCE_TO_LAMBDA -> methodReference(unit, ast, selected);
            case SWITCH_TO_EXPRESSION -> {
                SwitchStatement statement = ancestor(selected, SwitchStatement.class);
                require(statement != null, "Select a traditional switch statement.");
                yield fix(SwitchExpressionsFixCore.createConvertToSwitchExpressionFix(statement), monitor);
            }
            case TYPE_TO_VAR, VAR_TO_TYPE -> variableType(unit, ast, selected, request.operation());
            case ORGANIZE_IMPORTS -> organize(unit, ast, monitor);
            case ADD_IMPORT -> addImport(unit, ast, (Requests.AddImport) request, monitor);
            case REMOVE_IMPORT -> removeImport(unit, ast, selected, monitor);
            case IMPLEMENT_METHODS -> implement(workspace, ast, selected, (Requests.ImplementMethods) request, monitor);
            case CREATE_METHOD, CREATE_CONSTRUCTOR -> createMethod(unit, ast, selected, request.operation());
            case CREATE_FIELD, CREATE_LOCAL -> createVariable(unit, ast, selected, request.operation());
            case FIX_TYPE_MISMATCH -> mismatch(unit, ast, selected);
            case REMOVE_UNREACHABLE -> unreachable(unit, ast, selected);
            default -> throw failure("Operation is not handled by the modernization adapter.");
        };
    }

    private static Change fix(ICleanUpFix fix, IProgressMonitor monitor) throws CoreException {
        require(fix != null, "JDT cannot establish the preconditions for this transformation at the selection.");
        return fix.createChange(monitor);
    }

    private static boolean serializable(ITypeBinding type, Set<String> visited) throws CoreException {
        require(type != null && !type.isRecovered(), "The functional interface hierarchy must resolve.");
        if ("java.io.Serializable".equals(type.getErasure().getQualifiedName())) return true;
        if (!visited.add(type.getKey())) return false;
        for (ITypeBinding parent : type.getInterfaces()) if (serializable(parent, visited)) return true;
        return type.getSuperclass() != null && serializable(type.getSuperclass(), visited);
    }

    private static void requireSafeReferenceReceiver(LambdaExpression lambda) throws CoreException {
        ASTNode body = lambda.getBody();
        if (body instanceof Block block && block.statements().size() == 1) {
            Object statement = block.statements().getFirst();
            if (statement instanceof ReturnStatement returning) body = returning.getExpression();
            else if (statement instanceof ExpressionStatement expression) body = expression.getExpression();
        }
        while (body instanceof ParenthesizedExpression parentheses) body = parentheses.getExpression();
        if (body instanceof SuperMethodInvocation) return;
        if (body instanceof ClassInstanceCreation creation) {
            require(creation.getExpression() == null,
                    "A qualified constructor receiver requires evaluation-order analysis before conversion.");
            return;
        }
        if (body instanceof ArrayCreation) return;
        require(body instanceof MethodInvocation, "Select a forwarding invocation eligible for a method reference.");
        MethodInvocation invocation = (MethodInvocation) body;
        IMethodBinding target = invocation.resolveMethodBinding();
        require(target != null && !target.isRecovered(), "The invoked method must resolve.");
        Expression receiver = invocation.getExpression();
        while (receiver instanceof ParenthesizedExpression parentheses) receiver = parentheses.getExpression();
        // this/super and implicit this are non-null and invariant. A type qualifier
        // is not evaluated. An unbound lambda parameter is still checked on invocation.
        if (receiver == null || receiver instanceof ThisExpression) return;
        if (receiver instanceof Name name && name.resolveBinding() instanceof ITypeBinding) return;
        if (!Modifier.isStatic(target.getModifiers()) && receiver instanceof SimpleName name
                && name.resolveBinding() instanceof IVariableBinding binding) {
            for (Object parameter : lambda.parameters()) {
                IVariableBinding candidate = ((VariableDeclaration) parameter).resolveBinding();
                if (candidate != null && candidate.isEqualTo(binding)) return;
            }
        }
        throw failure("A bound receiver would be evaluated and null-checked when the reference is created instead of when the lambda runs; retain the lambda.");
    }

    private static Change methodReference(ICompilationUnit unit, CompilationUnit ast, ASTNode selected) throws CoreException {
        MethodReference reference = ancestor(selected, MethodReference.class);
        require(reference != null, "Select a method reference.");
        // A bound receiver is evaluated immediately, whereas a lambda body is evaluated later.
        // Only receivers whose evaluation and null behavior are invariant are accepted here.
        if (reference instanceof ExpressionMethodReference bound) {
            Expression receiver = bound.getExpression();
            require(receiver instanceof ThisExpression || receiver instanceof Name name && name.resolveBinding() instanceof ITypeBinding,
                    "This bound receiver would need a captured temporary to preserve evaluation and null-check timing.");
        }
        IMethodBinding functional = QuickAssistProcessorUtil.getFunctionalMethodForMethodReference(reference);
        require(functional != null && !functional.isRecovered(), "The functional target type must resolve.");
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        QuickAssistProcessorUtil.convertMethodRefernceToLambda(reference, functional, ast, rewrite,
                new LinkedProposalModelCore(), false);
        return rewrite(unit, rewrite, "Convert method reference to lambda");
    }

    private static Change variableType(ICompilationUnit unit, CompilationUnit ast, ASTNode selected,
                                       Operation operation) throws CoreException {
        VariableDeclarationStatement statement = ancestor(selected, VariableDeclarationStatement.class);
        VariableDeclarationExpression expression = ancestor(selected, VariableDeclarationExpression.class);
        Type type;
        List<?> fragments;
        if (statement != null) { type = statement.getType(); fragments = statement.fragments(); }
        else if (expression != null) { type = expression.getType(); fragments = expression.fragments(); }
        else throw failure("Select a local declaration with one initialized variable.");
        require(fragments.size() == 1, "A declaration with multiple variables cannot use var.");
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragments.getFirst();
        IVariableBinding variable = fragment.resolveBinding();
        Expression initializer = fragment.getInitializer();
        require(variable != null && !variable.isRecovered() && initializer != null && fragment.extraDimensions().isEmpty(),
                "The initialized local variable and its type must resolve.");
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ASTRewriteCorrectionProposalCore proposal = new ASTRewriteCorrectionProposalCore("Change local variable type", unit, rewrite, 0);
        if (operation == Operation.TYPE_TO_VAR) {
            require(!type.isVar(), "This declaration already uses var.");
            ITypeBinding inferred = initializer.resolveTypeBinding();
            require(inferred != null && inferred.isEqualTo(variable.getType()) && standaloneType(initializer),
                    "Removing the declared target type could alter type inference or overload resolution.");
            boolean[] annotations = {false};
            type.accept(new ASTVisitor() { @Override public void preVisit(ASTNode n) { if (n instanceof Annotation) annotations[0] = true; } });
            require(!annotations[0], "Type annotations cannot be discarded when introducing var.");
            rewrite.replace(type, ast.getAST().newSimpleType(ast.getAST().newSimpleName("var")), null);
        } else {
            require(type.isVar(), "This declaration does not use var.");
            require(denotable(variable.getType(), new HashSet<>()), "The inferred anonymous, intersection or capture type has no equivalent explicit spelling.");
            ImportRewrite imports = proposal.createImportRewrite(ast);
            rewrite.replace(type, imports.addImport(variable.getType(), ast.getAST()), null);
        }
        return proposal.getTextChange();
    }

    private static boolean standaloneType(Expression expression) {
        if (expression instanceof ParenthesizedExpression p) return standaloneType(p.getExpression());
        if (expression instanceof LambdaExpression || expression instanceof MethodReference || expression instanceof NullLiteral
                || expression instanceof ArrayInitializer || expression instanceof ConditionalExpression || expression instanceof SwitchExpression) return false;
        if (expression instanceof ClassInstanceCreation creation) {
            return creation.getAnonymousClassDeclaration() == null
                    && (!(creation.getType() instanceof ParameterizedType p) || !p.typeArguments().isEmpty());
        }
        if (expression instanceof MethodInvocation invocation) {
            IMethodBinding binding = invocation.resolveMethodBinding();
            return binding != null && binding.getMethodDeclaration().getTypeParameters().length == 0;
        }
        if (expression instanceof SuperMethodInvocation invocation) {
            IMethodBinding binding = invocation.resolveMethodBinding();
            return binding != null && binding.getMethodDeclaration().getTypeParameters().length == 0;
        }
        return true;
    }

    private static boolean denotable(ITypeBinding type, Set<String> visited) {
        if (type == null || type.isRecovered() || type.isAnonymous() || type.isCapture() || type.isIntersectionType() || type.isNullType()) return false;
        if (!visited.add(type.getKey())) return true;
        if (type.isArray()) return denotable(type.getElementType(), visited);
        for (ITypeBinding argument : type.getTypeArguments()) if (!denotable(argument, visited)) return false;
        return true;
    }

    private static Change organize(ICompilationUnit unit, CompilationUnit ast, IProgressMonitor monitor) throws CoreException {
        boolean[] ambiguous = {false};
        OrganizeImportsOperation operation = new OrganizeImportsOperation(unit, ast, true, false, true,
                (choices, ranges) -> { ambiguous[0] = true; return new org.eclipse.jdt.core.search.TypeNameMatch[0]; });
        var edits = operation.createTextEdit(monitor);
        require(!ambiguous[0], "Several types match a missing import. Add an explicit import before organizing.");
        require(operation.getParseError() == null, "Imports cannot be organized while this file has syntax errors.");
        return textChange(unit, "Organize imports", edits);
    }

    private static Change addImport(ICompilationUnit unit, CompilationUnit ast, Requests.AddImport request,
                                    IProgressMonitor monitor) throws CoreException {
        String name = request.qualifiedName();
        require(name != null && name.contains(".") && !name.endsWith(".*"), "Specify a resolvable qualified type or static member.");
        ImportRewrite imports = ImportRewrite.create(ast, true);
        if (request.isStatic()) {
            int separator = name.lastIndexOf('.');
            IType owner = unit.getJavaProject().findType(name.substring(0, separator));
            require(owner != null && owner.exists(), "The declaring type is not on the project classpath.");
            String member = name.substring(separator + 1);
            IField field = owner.getField(member);
            boolean isField = field.exists() && Flags.isStatic(field.getFlags());
            boolean isMethod = false;
            for (IMethod method : owner.getMethods()) if (method.getElementName().equals(member) && Flags.isStatic(method.getFlags())) isMethod = true;
            require(isField || isMethod, "No static member with that name exists on the declaring type.");
            imports.addStaticImport(owner.getFullyQualifiedName(), member, isField);
        } else {
            IType type = unit.getJavaProject().findType(name);
            require(type != null && type.exists(), "The requested type is not on the project classpath.");
            String imported = imports.addImport(name);
            require(imported.equals(type.getElementName()), "This import conflicts with an existing simple name.");
        }
        return textChange(unit, "Add import", imports.rewriteImports(monitor));
    }

    private static Change removeImport(ICompilationUnit unit, CompilationUnit ast, ASTNode selected,
                                       IProgressMonitor monitor) throws CoreException {
        ImportDeclaration declaration = ancestor(selected, ImportDeclaration.class);
        require(declaration != null, "Select an unused import declaration.");
        for (IProblem problem : ast.getProblems()) {
            if (problem.getID() == IProblem.UnusedImport && intersects(declaration, problem))
                return fix(UnusedCodeFixCore.createRemoveUnusedImportFix(ast, new ProblemLocation(problem)), monitor);
        }
        throw failure("JDT has not established that this import is unused.");
    }

    private static Change implement(LtkWorkspace workspace, CompilationUnit ast, ASTNode selected, Requests.ImplementMethods request,
                                     IProgressMonitor monitor) throws CoreException {
        ASTNode type = enclosingType(selected);
        require(type != null, "Select a concrete class, record, enum or anonymous class.");
        Set<IJavaElement> selectedMethods = new HashSet<>();
        for (String id : request.methodIds()) {
            IJavaElement method = workspace.element(id);
            require(method instanceof IMethod && method.exists(), "Select resolved method identities from this workspace.");
            selectedMethods.add(method);
        }
        AddUnimplementedMethodsOperation operation = new AddUnimplementedMethodsOperation(type,
                method -> !selectedMethods.isEmpty() && !selectedMethods.contains(method.getMethodDeclaration().getJavaElement()));
        require(operation.getMethodsToImplement().length > 0, "No unimplemented methods match the selection.");
        Set<IJavaElement> matchedMethods = new HashSet<>();
        for (IMethodBinding method : operation.getMethodsToImplement()) matchedMethods.add(method.getMethodDeclaration().getJavaElement());
        require(matchedMethods.containsAll(selectedMethods), "Some selected methods are not unimplemented members of this type.");
        return fix(new CompilationUnitRewriteOperationsFixCore("Implement methods", ast, operation), monitor);
    }

    @SuppressWarnings("unchecked")
    private static Change createMethod(ICompilationUnit unit, CompilationUnit ast, ASTNode selected,
                                       Operation operation) throws CoreException {
        ASTNode invocation;
        List<Expression> arguments;
        ITypeBinding owner;
        if (operation == Operation.CREATE_CONSTRUCTOR) {
            ClassInstanceCreation creation = ancestor(selected, ClassInstanceCreation.class);
            require(creation != null, "Select a constructor invocation with a missing signature.");
            IMethodBinding existing = creation.resolveConstructorBinding();
            require(existing == null || existing.isRecovered() || hasError(ast, creation, IProblem.UndefinedConstructor), "This constructor already resolves.");
            invocation = creation;
            arguments = creation.arguments();
            owner = creation.getType().resolveBinding();
        } else {
            MethodInvocation call = ancestor(selected, MethodInvocation.class);
            require(call != null, "Select an unresolved method invocation.");
            IMethodBinding existing = call.resolveMethodBinding();
            require(existing == null || existing.isRecovered() || hasError(ast, call, IProblem.UndefinedMethod), "This method already resolves.");
            invocation = call;
            arguments = call.arguments();
            owner = call.getExpression() == null ? typeBinding(enclosingType(call)) : call.getExpression().resolveTypeBinding();
        }
        require(owner != null && !owner.isRecovered() && owner.getJavaElement() instanceof IType,
                "The target type must resolve to editable project source.");
        ICompilationUnit destination = ((IType) owner.getJavaElement()).getCompilationUnit();
        require(destination != null && destination.exists(), "A binary dependency cannot be modified.");
        for (Expression argument : arguments) require(argument.resolveTypeBinding() != null, "Every argument type must resolve.");
        return new NewMethodCorrectionProposalCore(operation.id(), destination, invocation, arguments, owner, 0).getTextChange();
    }

    private static Change createVariable(ICompilationUnit unit, CompilationUnit ast, ASTNode selected,
                                         Operation operation) throws CoreException {
        SimpleName name = selected instanceof SimpleName n ? n : ancestor(selected, SimpleName.class);
        require(name != null && (name.resolveBinding() == null || name.resolveBinding().isRecovered()), "Select an unresolved variable name.");
        require(hasError(ast, name, IProblem.UndefinedName) || hasError(ast, name, IProblem.UnresolvedVariable)
                || hasError(ast, name, IProblem.UndefinedField), "The selection must have an unresolved variable diagnostic.");
        ITypeBinding owner = typeBinding(enclosingType(name));
        if (operation == Operation.CREATE_FIELD && name.getParent() instanceof QualifiedName qualified && qualified.getName() == name)
            owner = qualified.getQualifier().resolveTypeBinding();
        if (operation == Operation.CREATE_FIELD && name.getParent() instanceof FieldAccess access)
            owner = access.getExpression().resolveTypeBinding();
        require(owner != null && owner.getJavaElement() instanceof IType, "The declaring source type must resolve.");
        ICompilationUnit destination = operation == Operation.CREATE_LOCAL ? unit : ((IType) owner.getJavaElement()).getCompilationUnit();
        require(destination != null, "A binary dependency cannot be modified.");
        return new NewVariableCorrectionProposalCore(operation.id(), destination,
                operation == Operation.CREATE_LOCAL ? NewVariableCorrectionProposalCore.LOCAL : NewVariableCorrectionProposalCore.FIELD,
                name, owner, 0, false).getTextChange();
    }

    private static Change mismatch(ICompilationUnit unit, CompilationUnit ast, ASTNode selected) throws CoreException {
        VariableDeclarationFragment variable = ancestor(selected, VariableDeclarationFragment.class);
        if (variable == null && selected instanceof VariableDeclarationStatement statement && statement.fragments().size() == 1)
            variable = (VariableDeclarationFragment) statement.fragments().getFirst();
        require(variable != null && variable.getInitializer() != null && variable.resolveBinding() != null,
                "Select a local variable initializer whose type does not match its declaration.");
        require(variable.getParent() instanceof VariableDeclarationStatement && hasError(ast, variable, IProblem.TypeMismatch),
                "This quick fix requires a compiler-confirmed local variable type mismatch.");
        ITypeBinding actual = variable.getInitializer().resolveTypeBinding();
        require(denotable(actual, new HashSet<>()), "The initializer type must resolve to an explicit Java type.");
        return new TypeChangeCorrectionProposalCore(unit, variable.resolveBinding(), ast, actual, false, false, 0).getTextChange();
    }

    private static Change unreachable(ICompilationUnit unit, CompilationUnit ast, ASTNode selected) throws CoreException {
        Statement statement = ancestor(selected, Statement.class);
        require(statement != null && statement.getParent() instanceof Block, "Select an unreachable statement in a block.");
        require(hasError(ast, statement, IProblem.CodeCannotBeReached), "The compiler has not proved this statement unreachable.");
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        rewrite.remove(statement, null);
        return rewrite(unit, rewrite, "Remove unreachable statement");
    }

    private static ASTNode enclosingType(ASTNode node) {
        for (ASTNode cursor = node; cursor != null; cursor = cursor.getParent())
            if (cursor instanceof AbstractTypeDeclaration || cursor instanceof AnonymousClassDeclaration) return cursor;
        return null;
    }

    private static ITypeBinding typeBinding(ASTNode node) {
        return switch (node) {
            case AbstractTypeDeclaration type -> type.resolveBinding();
            case AnonymousClassDeclaration type -> type.resolveBinding();
            case null, default -> null;
        };
    }

    private static <T> T ancestor(ASTNode node, Class<T> type) {
        for (ASTNode cursor = node; cursor != null; cursor = cursor.getParent()) if (type.isInstance(cursor)) return type.cast(cursor);
        return null;
    }

    private static boolean intersects(ASTNode node, IProblem problem) {
        return problem.getSourceStart() < node.getStartPosition() + node.getLength()
                && problem.getSourceEnd() >= node.getStartPosition();
    }

    private static boolean hasError(CompilationUnit ast, ASTNode node, int id) {
        for (IProblem problem : ast.getProblems()) if (problem.getID() == id && intersects(node, problem)) return true;
        return false;
    }

    private static Change rewrite(ICompilationUnit unit, ASTRewrite rewrite, String title) throws CoreException {
        return new ASTRewriteCorrectionProposalCore(title, unit, rewrite, 0).getTextChange();
    }

    private static Change textChange(ICompilationUnit unit, String title, org.eclipse.text.edits.TextEdit edits) {
        CompilationUnitChange change = new CompilationUnitChange(title, unit);
        change.setEdit(edits);
        return change;
    }

    private static void require(boolean condition, String message) throws CoreException {
        if (!condition) throw failure(message);
    }

    private static CoreException failure(String message) {
        return new CoreException(new Status(IStatus.ERROR, "dev.turboj.refactorj.jdt", message));
    }

    @FunctionalInterface private interface Preparation { Change create(IProgressMonitor monitor) throws CoreException; }

    private static final class PreparedRefactoring extends Refactoring {
        private final String name;
        private final Preparation preparation;
        private Change prepared;
        private RefactoringStatus status;
        PreparedRefactoring(String name, Preparation preparation) { this.name = name; this.preparation = preparation; }
        @Override public String getName() { return name; }
        @Override public RefactoringStatus checkInitialConditions(IProgressMonitor monitor) {
            if (status != null) return status;
            status = new RefactoringStatus();
            if (monitor == null) monitor = new NullProgressMonitor();
            if (monitor.isCanceled()) throw new OperationCanceledException();
            try {
                prepared = preparation.create(monitor);
                if (prepared == null) status.addFatalError("No applicable change was produced.");
            } catch (CoreException error) { status.addFatalError(error.getStatus().getMessage()); }
            return status;
        }
        @Override public RefactoringStatus checkFinalConditions(IProgressMonitor monitor) { return checkInitialConditions(monitor); }
        @Override public Change createChange(IProgressMonitor monitor) throws CoreException {
            require(!checkInitialConditions(monitor).hasFatalError(), "This transformation is not applicable.");
            return prepared;
        }
    }
}
