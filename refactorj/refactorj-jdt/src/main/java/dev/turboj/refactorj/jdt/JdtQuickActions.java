package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaQuickActions.*;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import javax.lang.model.SourceVersion;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.search.*;
import org.eclipse.jdt.internal.ui.text.correction.proposals.AssignToVariableAssistProposalCore;
import org.eclipse.ltk.core.refactoring.Change;

/** Diagnostic-driven providers over the shared JDT model. No UI, file writes, or tutorial names.
 * Each provider owns its context guard and preparation; adding a fix does not change dispatch. */
final class JdtQuickActions implements JavaQuickActions {
    private final LtkRefactorJ engine;
    private final LtkWorkspace workspace;
    private record Offered(Proposal proposal, WorkspaceSnapshot snapshot, Function<Cancellation, Prepared> prepare) {}
    private final LinkedHashMap<String, Offered> offered = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Offered> entry) { return size() > 128; }
    };
    @FunctionalInterface private interface Provider { void collect(Context context, List<Proposal> into); }
    private final List<Provider> providers = List.of(this::missingTypes, this::missingMethods,
            this::diagnosticCorrections, this::testGeneration, this::refactoringAssists, this::assignAssists);
    private record Context(Location at, WorkspaceSnapshot snapshot, CompilationUnit tree, ASTNode node,
                           List<IProblem> problems, Cancellation cancel) {}

    JdtQuickActions(LtkRefactorJ engine) { this.engine = engine; this.workspace = engine.workspace(); }

    @Override public Inspection inspect(Location at, Cancellation cancel) {
        cancel.check();
        var before = workspace.snapshot();
        String text = before.source(at.file()).text();
        if (at.end() > text.length()) throw new IllegalArgumentException("Selection is outside the current source");
        offered.entrySet().removeIf(e -> !e.getValue().snapshot().equals(before));
        var tree = workspace.ast(at.file());
        ASTNode node = NodeFinder.perform(tree, at.start(), at.end() - at.start());
        if (node == null && at.start() > 0) node = NodeFinder.perform(tree, at.start() - 1, 0);
        List<IProblem> problems = Arrays.stream(tree.getProblems()).filter(p -> intersects(at, p)).toList();
        var context = new Context(at, before, tree, node, problems, cancel);
        var proposals = new ArrayList<Proposal>();
        for (Provider provider : providers) { cancel.check(); provider.collect(context, proposals); }
        var diagnostics = problems.stream().map(p -> new Diagnostic(p.isError() ? Severity.ERROR : Severity.WARNING,
                "jdt." + p.getID(), p.getMessage(), new Location(at.file(), Math.max(0, p.getSourceStart()),
                Math.min(text.length(), Math.max(p.getSourceStart(), p.getSourceEnd() + 1))))).toList();
        return new Inspection(before, at, diagnostics, proposals);
    }

    @Override public Prepared prepare(Proposal proposal, Cancellation cancel) {
        cancel.check();
        Offered item = offered.get(proposal.id());
        if (item == null || !item.proposal().equals(proposal))
            throw new IllegalArgumentException("Quick action has expired; invoke Quick Fix again");
        if (!workspace.snapshot().equals(item.snapshot()))
            throw new IllegalStateException("Source or project changed; invoke Quick Fix again");
        if (item.prepare() == null) throw new IllegalArgumentException("This action requires its contextual form");
        Prepared result = item.prepare().apply(cancel);
        cancel.check();
        return result;
    }

    @Override public Prepared renameLinked(WorkspaceSnapshot beforeTyping, Location declaration, String newName, Cancellation cancel) {
        cancel.check();
        if (!identifier(newName)) throw new IllegalArgumentException("The declaration name must be a Java identifier");
        WorkspaceSnapshot current = workspace.snapshot();
        if (!beforeTyping.root().equals(current.root()) || !beforeTyping.sourceRoots().equals(current.sourceRoots())
                || !beforeTyping.classpath().equals(current.classpath()) || !beforeTyping.projects().equals(current.projects())
                || beforeTyping.sources().size() != current.sources().size())
            throw new IllegalStateException("Project changed during linked editing; invoke Rename again");
        String original = beforeTyping.source(declaration.file()).text();
        if (declaration.end() > original.length() || declaration.start() == declaration.end()
                || !identifier(original.substring(declaration.start(), declaration.end())))
            throw new IllegalArgumentException("Linked edit must identify the original complete declaration name");
        for (var source : beforeTyping.sources()) {
            String expected = source.file().equals(declaration.file()) ? TextEdit.apply(source.text(),
                    List.of(new TextEdit(declaration.start(), declaration.end(), newName))) : source.text();
            if (!expected.equals(current.source(source.file()).text()))
                throw new IllegalStateException("Another source edit occurred during linked editing; invoke Rename again");
        }
        Plan rename;
        String methodIdentity;
        String focusGroup;
        try {
            workspace.synchronize(beforeTyping, cancel);
            ASTNode node = NodeFinder.perform(workspace.ast(declaration.file()), declaration.start(), declaration.end() - declaration.start());
            if (!(node instanceof SimpleName name) || name.getStartPosition() != declaration.start()
                    || name.getStartPosition() + name.getLength() != declaration.end())
                throw new IllegalArgumentException("Linked edit must select a complete declaration name");
            if (name.getParent() instanceof MethodDeclaration method && method.getName() == name && !method.isConstructor()) {
                methodIdentity = methodIdentity(method, newName);
                focusGroup = "methodName";
            } else if (name.getParent() instanceof SingleVariableDeclaration parameter && parameter.getName() == name
                    && parameter.getParent() instanceof MethodDeclaration method && !method.isConstructor()
                    && method.parameters().contains(parameter)) {
                methodIdentity = methodIdentity(method);
                focusGroup = "parameter." + method.parameters().indexOf(parameter);
            } else throw new IllegalArgumentException("Linked edit must select a method name or a declared method parameter name");
            rename = engine.prepare(new Requests.Rename(declaration, newName), cancel);
        } finally { workspace.synchronize(current, Cancellation.NONE); }
        Location focus = new Location(declaration.file(), declaration.start(), declaration.start() + newName.length());
        if (!rename.valid()) return new Prepared(new Plan(rename.id(), Operation.RENAME, current, rename.affectedSymbols(),
                List.of(), List.of(), rename.diagnostics(), rename.status(), rename.description()), focus, List.of());
        if (!rename.fileOperations().isEmpty()) throw new IllegalStateException("A linked declaration rename cannot alter source file identity");
        var edits = new ArrayList<FileEdits>();
        for (var source : beforeTyping.sources()) {
            String wanted = resultingSource(rename, source.file());
            String now = current.source(source.file()).text();
            if (!now.equals(wanted)) edits.add(new FileEdits(source.file(), ChangePlans.difference(now, wanted)));
        }
        Plan rebased = checkedPlan(Operation.RENAME, current, edits, List.of(), "Rename linked declaration to '" + newName + "'", cancel);
        if (!rename.diagnostics().isEmpty()) {
            var diagnostics = new ArrayList<>(rename.diagnostics()); diagnostics.addAll(rebased.diagnostics());
            rebased = new Plan(rebased.id(), rebased.operation(), current, rename.affectedSymbols(), edits, List.of(), diagnostics, rebased.status(), rebased.description());
        }
        if (!rebased.valid()) return prepared(rebased, focus);
        var methods = new ArrayList<MethodDeclaration>();
        sourceTree(declaration.file(), resultingSource(rebased, declaration.file())).accept(new ASTVisitor() {
            @Override public boolean visit(MethodDeclaration method) {
                if (methodIdentity(method).equals(methodIdentity)) methods.add(method);
                return true;
            }
        });
        if (methods.size() != 1) throw new IllegalStateException("Cannot restore the generated method after linked rename");
        Prepared template = methodTemplate(rebased, declaration.file(), methods.getFirst());
        Location renamed = template.placeholders().stream().filter(p -> p.group().equals(focusGroup))
                .map(Placeholder::location).findFirst().orElseThrow();
        return new Prepared(rebased, renamed, template.placeholders());
    }

    private void offer(Context c, List<Proposal> into, Kind kind, Action action, String title,
                       String explanation, Location at, Operation operation, Map<String, String> parameters,
                       Function<Cancellation, Prepared> prepare) {
        var proposal = new Proposal(UUID.randomUUID().toString(), kind, action, title, explanation, at, operation, parameters);
        offered.put(proposal.id(), new Offered(proposal, c.snapshot(), prepare));
        into.add(proposal);
    }

    private void missingTypes(Context c, List<Proposal> into) {
        if (!problem(c, IProblem.UndefinedType)) return;
        SimpleName name = c.node() instanceof SimpleName n ? n : ancestor(c.node(), SimpleName.class);
        if (name == null || name.resolveBinding() != null && !name.resolveBinding().isRecovered()) return;
        SimpleType type = ancestor(name, SimpleType.class);
        if (type == null || ancestor(name, ImportDeclaration.class) != null) return;
        String identifier = name.getIdentifier();
        String qualified = type.getName().getFullyQualifiedName();
        String pkg = qualified.contains(".") ? qualified.substring(0, qualified.lastIndexOf('.')) : packageName(c.tree());
        if (!identifier(identifier) || !packageNameValid(pkg)) return;

        // Import alternatives come from the actual Java project's visible source/library types.
        if (!qualified.contains(".")) for (String candidate : importCandidates(c, identifier)) {
            var request = new Requests.AddImport(c.at(), candidate, false);
            Plan plan = engine.prepare(request, c.cancel());
            if (!plan.valid()) continue;
            offer(c, into, Kind.FIX, Action.ADD_IMPORT, "Import '" + candidate + "'",
                    "Add the resolved type import to this source file.", c.at(), Operation.ADD_IMPORT,
                    Map.of("qualifiedName", candidate), cancel -> prepared(plan, c.at()));
        }
        // An implemented interface or annotation cannot truthfully be offered as Create class.
        ASTNode topType = type;
        while (topType.getParent() instanceof ParameterizedType || topType.getParent() instanceof ArrayType) topType = topType.getParent();
        if (topType.getLocationInParent() == TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || topType.getLocationInParent() == EnumDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || topType.getLocationInParent() == RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY) return;
        ClassInstanceCreation creation = ancestor(type, ClassInstanceCreation.class);
        if (creation != null && (!creation.arguments().isEmpty() || creation.getAnonymousClassDeclaration() != null)) return;
        Path root = sourceRoot(c.snapshot(), c.at().file());
        Path destination = root.resolve(pkg.replace('.', '/')).resolve(identifier + ".java").normalize();
        if (!destination.startsWith(root) || !destination.startsWith(c.snapshot().root())
                || Files.exists(destination) || c.snapshot().sources().stream().anyMatch(s -> s.file().equals(destination))) return;
        int arity = type.getParent() instanceof ParameterizedType p ? p.typeArguments().size() : 0;
        String typeParameters = arity == 0 ? "" : "<" + java.util.stream.IntStream.range(0, arity)
                .mapToObj(i -> i == 0 ? "T" : "T" + (i + 1)).collect(java.util.stream.Collectors.joining(", ")) + ">";
        String separator = c.snapshot().source(c.at().file()).text().contains("\r\n") ? "\r\n" : "\n";
        String content = (pkg.isEmpty() ? "" : "package " + pkg + ";" + separator + separator)
                + "public class " + identifier + typeParameters + " {" + separator + "    " + separator + "}" + separator;
        var at = new Location(destination, content.indexOf("    ") + 4);
        Plan plan = checkedPlan(Operation.CREATE_CLASS, c.snapshot(), List.of(),
                List.of(new FileOperation(FileOperationKind.CREATE, destination, null, content)),
                "Create class '" + identifier + "'", c.cancel());
        if (!plan.valid()) return;
        offer(c, into, Kind.FIX, Action.CREATE_CLASS, "Create class '" + identifier + "'",
                "Create " + destination.getFileName() + " in " + (pkg.isEmpty() ? "the default package" : pkg)
                        + " under " + c.snapshot().root().relativize(root) + ".",
                c.at(), Operation.CREATE_CLASS, Map.of("className", identifier, "packageName", pkg,
                        "sourceRoot", root.toString(), "targetFile", destination.toString()),
                cancel -> new Prepared(plan, at, List.of()));
    }

    private List<String> importCandidates(Context c, String name) {
        var names = new TreeSet<String>();
        try {
            new SearchEngine(workspace.units().toArray(ICompilationUnit[]::new)).searchAllTypeNames(null,
                    SearchPattern.R_EXACT_MATCH, name.toCharArray(), SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                    IJavaSearchConstants.TYPE, SearchEngine.createJavaSearchScope(new IJavaElement[]{workspace.javaProject(c.at().file())}),
                    new TypeNameMatchRequestor() { @Override public void acceptTypeNameMatch(TypeNameMatch match) {
                        c.cancel().check();
                        IType type = match.getType();
                        if (type != null && (Flags.isPublic(match.getModifiers()) || type.getPackageFragment().getElementName().equals(packageName(c.tree()))))
                            names.add(type.getFullyQualifiedName('.'));
                    } }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, LtkWorkspace.monitor(c.cancel()));
        } catch (CoreException error) { throw new IllegalStateException("Cannot inspect import candidates", error); }
        return names.stream().filter(n -> n.contains(".")).limit(30).toList();
    }

    @SuppressWarnings("unchecked")
    private void missingMethods(Context c, List<Proposal> into) {
        if (!problem(c, IProblem.UndefinedMethod) && !problem(c, IProblem.ParameterMismatch)) return;
        MethodInvocation call = ancestor(c.node(), MethodInvocation.class);
        if (call == null || !contains(call.getName(), c.at())) return;
        ITypeBinding owner = call.getExpression() == null ? enclosingBinding(call) : call.getExpression().resolveTypeBinding();
        if (owner == null || owner.isRecovered() || !(owner.getJavaElement() instanceof IType target)) return;
        ICompilationUnit unit = target.getCompilationUnit();
        if (unit == null || !unit.exists()) return;
        Path targetFile = workspace.original(unit);
        if (c.snapshot().sources().stream().noneMatch(s -> s.file().equals(targetFile))) return;
        List<Expression> arguments = call.arguments();
        if (arguments.stream().anyMatch(a -> a.resolveTypeBinding() == null || a.resolveTypeBinding().isRecovered())) return;
        String signature = call.getName().getIdentifier() + "(" + arguments.stream().map(a -> a.resolveTypeBinding().getName())
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
        String methodName = call.getName().getIdentifier();
        Plan plan = engine.prepare(new Requests.CreateMethod(c.at()), c.cancel());
        if (!plan.valid()) return;
        Prepared prepared = methodPrepared(plan, targetFile, methodName, arguments.size());
        offer(c, into, Kind.FIX, Action.CREATE_METHOD, "Create method '" + signature + "' in " + owner.getName(),
                "Infer the signature from this call, create the method in " + unit.getElementName()
                        + ", and continue in its editable body. Tab visits the generated parameters.",
                c.at(), Operation.CREATE_METHOD, Map.of("className", owner.getName(), "targetFile", targetFile.toString(),
                        "methodName", methodName),
                cancel -> prepared);
    }

    private void diagnosticCorrections(Context c, List<Proposal> into) {
        // Compiler IDs, not the mere presence of an error anywhere in the file, select providers.
        if (problem(c, IProblem.UnresolvedVariable) || problem(c, IProblem.UndefinedName) || problem(c, IProblem.UndefinedField)) {
            SimpleName name = c.node() instanceof SimpleName n ? n : null;
            if (name != null && ancestor(name, Type.class) == null) {
                diagnosticRequest(c, into, "Create local variable '" + name + "'", new Requests.CreateLocal(c.at()));
                diagnosticRequest(c, into, "Create field '" + name + "'", new Requests.CreateField(c.at()));
            }
        }
        if (problem(c, IProblem.TypeMismatch)) diagnosticRequest(c, into, "Change variable type to match its initializer", new Requests.FixTypeMismatch(c.at()));
        if (problem(c, IProblem.CodeCannotBeReached)) diagnosticRequest(c, into, "Remove unreachable statement", new Requests.RemoveUnreachable(c.at()));
        if (problem(c, IProblem.AbstractMethodMustBeImplemented) || problem(c, IProblem.AbstractMethodMustBeImplementedOverConcreteMethod))
            diagnosticRequest(c, into, "Implement inherited abstract methods", new Requests.ImplementMethods(c.at(), List.of()));
    }

    private void diagnosticRequest(Context c, List<Proposal> into, String title, Requests.Request request) {
        // JDT checks (including candidate compilation) prevent coarse AST guards from offering a nonfunctional fix.
        Plan plan = engine.prepare(request, c.cancel());
        if (!plan.valid()) return;
        offer(c, into, Kind.FIX, Action.REFACTOR, title, "Apply the compiler-confirmed source correction as one undoable change.",
                request.location(), request.operation(), Map.of("direct", "true"), cancel -> prepared(plan, request.location()));
    }

    private void testGeneration(Context c, List<Proposal> into) {
        AbstractTypeDeclaration type = ancestor(c.node(), AbstractTypeDeclaration.class);
        if (type == null || !contains(type.getName(), c.at()) || type.resolveBinding() == null || type.resolveBinding().isRecovered()) return;
        ITypeBinding binding = type.resolveBinding();
        if (binding.isAnnotation() || binding.isAnonymous() || binding.isLocal()) return;
        Path root = sourceRoot(c.snapshot(), c.at().file());
        Path testRoot = testRoot(c.snapshot(), root);
        String methods = Arrays.stream(binding.getDeclaredMethods()).filter(m -> !m.isConstructor() && !Modifier.isPrivate(m.getModifiers()))
                .map(m -> m.getName() + "(" + Arrays.stream(m.getParameterTypes()).map(ITypeBinding::getName)
                        .collect(java.util.stream.Collectors.joining(", ")) + ")")
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        var parameters = new HashMap<String, String>();
        parameters.put("className", binding.getName()); parameters.put("qualifiedName", binding.getQualifiedName());
        parameters.put("packageName", packageName(c.tree())); parameters.put("sourceRoot", root.toString());
        parameters.put("testRoot", testRoot.toString()); parameters.put("sourceFile", c.at().file().toString());
        parameters.put("methods", methods);
        if (binding.getJavaElement() != null) parameters.put("symbolId", workspace.symbolId(binding.getJavaElement()));
        offer(c, into, Kind.ASSIST, Action.CREATE_JUNIT_TEST, "Create new JUnit test", "Create a test for " + binding.getQualifiedName()
                        + " using this project's source roots and JUnit dependencies.", c.at(), null, parameters, null);
    }

    private void refactoringAssists(Context c, List<Proposal> into) {
        if (c.node() == null) return;
        SimpleName name = c.node() instanceof SimpleName n ? n : null;
        if (name != null && name.resolveBinding() != null && !name.resolveBinding().isRecovered()) {
            IBinding binding = name.resolveBinding();
            IJavaElement element = binding.getJavaElement();
            if (element != null && element.getAncestor(IJavaElement.COMPILATION_UNIT) != null)
                offer(c, into, Kind.ASSIST, Action.REFACTOR, "Rename '" + name + "'", "Rename the resolved Java symbol and its references with a semantic preview.",
                        c.at(), Operation.RENAME, Map.of(), null);
        }
        Expression expression = ancestor(c.node(), Expression.class);
        if (expression instanceof SimpleName n && n.getParent() instanceof MethodInvocation call && call.getName() == n) expression = call;
        if (expression != null && !(expression instanceof SimpleName n && n.isDeclaration()) && expression.resolveTypeBinding() != null
                && !"void".equals(expression.resolveTypeBinding().getName()) && ancestor(expression, MethodDeclaration.class) != null) {
            Location selection = range(c.at().file(), expression);
            Plan probe = engine.prepare(new Requests.ExtractVariable(selection, freshName(c, "extractedValue"), false, false), c.cancel());
            if (probe.valid()) offer(c, into, Kind.ASSIST, Action.REFACTOR, "Extract local variable", "Introduce a local for the selected expression with RefactorJ's scope and evaluation checks.",
                        selection, Operation.EXTRACT_VARIABLE, Map.of(), null);
        }
        if (c.at().end() > c.at().start() && ancestor(c.node(), MethodDeclaration.class) != null) {
            Plan probe = engine.prepare(new Requests.ExtractMethod(c.at(), freshName(c, "extractedMethod")), c.cancel());
            if (probe.valid()) offer(c, into, Kind.ASSIST, Action.REFACTOR, "Extract method", "Extract the selected statements using RefactorJ's data-flow checks and preview.",
                        c.at(), Operation.EXTRACT_METHOD, Map.of(), null);
        }
        VariableDeclarationStatement variable = ancestor(c.node(), VariableDeclarationStatement.class);
        if (variable != null && variable.fragments().size() == 1) {
            Requests.Request request = variable.getType().isVar() ? new Requests.VarToType(range(c.at().file(), variable)) : new Requests.TypeToVar(range(c.at().file(), variable));
            assistRequest(c, into, variable.getType().isVar() ? "Replace var with explicit type" : "Use var for local variable", request);
        }
        if (ancestor(c.node(), LambdaExpression.class) != null)
            assistRequest(c, into, "Convert lambda to method reference", new Requests.LambdaToMethodReference(c.at()));
        if (ancestor(c.node(), MethodReference.class) != null)
            assistRequest(c, into, "Convert method reference to lambda", new Requests.MethodReferenceToLambda(c.at()));
        if (ancestor(c.node(), SwitchStatement.class) != null)
            assistRequest(c, into, "Convert switch to expression", new Requests.SwitchToExpression(c.at()));
    }

    private void assistRequest(Context c, List<Proposal> into, String title, Requests.Request request) {
        Plan plan = engine.prepare(request, c.cancel());
        if (!plan.valid()) return;
        offer(c, into, Kind.ASSIST, Action.REFACTOR, title, "Apply the validated Java transformation as one undoable change.",
                request.location(), request.operation(), Map.of("direct", "true"), cancel -> prepared(plan, request.location()));
    }

    private void assignAssists(Context c, List<Proposal> into) {
        ExpressionStatement statement = ancestor(c.node(), ExpressionStatement.class);
        if (statement == null || statement.getParent() == null || !(statement.getParent() instanceof Block)
                || ancestor(statement, MethodDeclaration.class) == null) return;
        Expression expression = statement.getExpression();
        if (!(expression instanceof MethodInvocation || expression instanceof ClassInstanceCreation)) return;
        ITypeBinding type = expression.resolveTypeBinding();
        if (type == null || type.isRecovered() || type.isNullType() || "void".equals(type.getName())) return;
        for (int kind : new int[]{AssignToVariableAssistProposalCore.LOCAL, AssignToVariableAssistProposalCore.FIELD}) {
            Change change = null;
            try {
                change = new AssignToVariableAssistProposalCore(workspace.unit(c.at().file()), kind, statement, type, 0, false).getTextChange();
                var converted = new ChangePlans(workspace); converted.collect(change, LtkWorkspace.monitor(c.cancel())); converted.normalize();
                Operation operation = kind == AssignToVariableAssistProposalCore.LOCAL ? Operation.CREATE_LOCAL : Operation.CREATE_FIELD;
                String title = kind == AssignToVariableAssistProposalCore.LOCAL ? "Assign to local variable" : "Assign to field";
                Plan plan = checkedPlan(operation, c.snapshot(), converted.edits, converted.files, title, c.cancel());
                if (plan.valid()) offer(c, into, Kind.ASSIST, Action.REFACTOR, title, "Capture this expression's result without changing evaluation order.",
                        c.at(), operation, Map.of("direct", "true"), cancel -> prepared(plan, c.at()));
            } catch (CoreException | IllegalArgumentException ignored) {
                // A failed semantic precondition is absence of a proposal, not a fake selectable action.
            } finally { if (change != null) change.dispose(); }
        }
    }

    private Plan checkedPlan(Operation operation, WorkspaceSnapshot before, List<FileEdits> edits,
                             List<FileOperation> files, String title, Cancellation cancel) {
        Plan plan = new Plan(UUID.randomUUID().toString(), operation, before, List.of(), edits, files,
                List.of(), ValidationStatus.VALID, title);
        var validation = engine.validate(plan, cancel);
        return new Plan(plan.id(), operation, before, List.of(), edits, files, validation.diagnostics(),
                validation.valid() ? ValidationStatus.VALID : ValidationStatus.CONFLICTED, title);
    }

    private Prepared methodPrepared(Plan plan, Path target, String name, int arity) {
        if (!plan.valid()) return prepared(plan, null);
        String source = resultingSource(plan, target);
        var tree = sourceTree(target, source);
        var oldMethods = new HashSet<String>();
        workspace.ast(target).accept(new ASTVisitor() { @Override public boolean visit(MethodDeclaration method) {
            oldMethods.add(methodIdentity(method)); return true;
        } });
        var methods = new ArrayList<MethodDeclaration>();
        tree.accept(new ASTVisitor() { @Override public boolean visit(MethodDeclaration method) {
            if (method.getName().getIdentifier().equals(name) && method.parameters().size() == arity
                    && !oldMethods.contains(methodIdentity(method)))
                methods.add(method);
            return true;
        } });
        if (methods.size() != 1) return prepared(plan, new Location(target, 0));
        return methodTemplate(plan, target, methods.getFirst());
    }

    private CompilationUnit sourceTree(Path target, String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS25); parser.setSource(source.toCharArray());
        parser.setCompilerOptions(workspace.javaProject(target).getOptions(true));
        return (CompilationUnit) parser.createAST(null);
    }

    private static Prepared methodTemplate(Plan plan, Path target, MethodDeclaration method) {
        var placeholders = new ArrayList<Placeholder>();
        Location focus = range(target, method.getName());
        if (method.getBody() != null) {
            if (!method.getBody().statements().isEmpty()) focus = range(target, (ASTNode) method.getBody().statements().getFirst());
            else focus = new Location(target, method.getBody().getStartPosition() + 1);
            placeholders.add(new Placeholder("body", focus));
        }
        for (int i = 0; i < method.parameters().size(); i++) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) method.parameters().get(i);
            placeholders.add(new Placeholder("parameter." + i, range(target, parameter.getName())));
        }
        if (method.getReturnType2() != null) placeholders.add(new Placeholder("returnType", range(target, method.getReturnType2())));
        placeholders.add(new Placeholder("methodName", range(target, method.getName())));
        return new Prepared(plan, focus, placeholders);
    }

    private static String methodIdentity(MethodDeclaration method) {
        return methodIdentity(method, method.getName().getIdentifier());
    }

    private static String methodIdentity(MethodDeclaration method, String methodName) {
        var owner = new StringBuilder();
        for (ASTNode n = method.getParent(); n != null; n = n.getParent())
            if (n instanceof AbstractTypeDeclaration type) owner.insert(0, type.getName() + ".");
        return owner + methodName + method.parameters().stream()
                .map(p -> ((SingleVariableDeclaration) p).getType().toString() + (((SingleVariableDeclaration) p).isVarargs() ? "..." : ""))
                .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private static Prepared prepared(Plan plan, Location focus) { return new Prepared(plan, focus, List.of()); }
    private static String resultingSource(Plan plan, Path file) {
        for (var created : plan.fileOperations()) if (created.kind() == FileOperationKind.CREATE && created.path().equals(file)) return created.content();
        String source = plan.before().source(file).text();
        for (var edits : plan.edits()) if (edits.file().equals(file)) source = TextEdit.apply(source, edits.edits());
        return source;
    }
    private static boolean intersects(Location at, IProblem problem) {
        return problem.getSourceStart() >= 0 && problem.getSourceStart() <= at.end() && problem.getSourceEnd() >= at.start();
    }
    private static boolean problem(Context c, int id) { return c.problems().stream().anyMatch(p -> p.getID() == id); }
    private static boolean contains(ASTNode node, Location at) { return node.getStartPosition() <= at.end() && node.getStartPosition() + node.getLength() >= at.start(); }
    private static <T> T ancestor(ASTNode node, Class<T> type) { for (ASTNode n = node; n != null; n = n.getParent()) if (type.isInstance(n)) return type.cast(n); return null; }
    private static Location range(Path file, ASTNode node) { return new Location(file, node.getStartPosition(), node.getStartPosition() + node.getLength()); }
    private static ITypeBinding enclosingBinding(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof AbstractTypeDeclaration t) return t.resolveBinding();
            if (n instanceof AnonymousClassDeclaration t) return t.resolveBinding();
        }
        return null;
    }
    private static String packageName(CompilationUnit tree) { return tree.getPackage() == null ? "" : tree.getPackage().getName().getFullyQualifiedName(); }
    private static String freshName(Context context, String base) {
        String text = context.snapshot().source(context.at().file()).text(), name = base;
        for (int suffix = 2; text.contains(name); suffix++) name = base + suffix;
        return name;
    }
    private static boolean identifier(String name) { return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name); }
    private static boolean packageNameValid(String name) { return name.isEmpty() || Arrays.stream(name.split("\\.", -1)).allMatch(JdtQuickActions::identifier); }
    private static Path sourceRoot(WorkspaceSnapshot snapshot, Path file) {
        return java.util.stream.Stream.concat(snapshot.sourceRoots().stream(), snapshot.projects().stream().flatMap(p -> p.sourceRoots().stream()))
                .filter(file::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElse(snapshot.root());
    }
    private static Path testRoot(WorkspaceSnapshot snapshot, Path sourceRoot) {
        String root = sourceRoot.toString().replace('\\', '/');
        if (root.endsWith("/src/main/java")) return sourceRoot.getParent().getParent().resolve("test/java");
        if (root.endsWith("/src/test/java")) return sourceRoot;
        return snapshot.sourceRoots().stream().filter(p -> p.startsWith(snapshot.root()) && p.toString().replace('\\', '/').endsWith("/test"))
                .findFirst().orElse(snapshot.root().resolve("test"));
    }
}
