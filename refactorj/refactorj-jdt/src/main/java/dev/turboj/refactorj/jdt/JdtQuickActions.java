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
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
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
    private final List<Provider> providers = List.of(this::missingJUnit, this::missingTypes, this::missingStaticImports, this::missingMethods,
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
        var inspection = inspect(at, before, text, tree, cancel);
        if (!inspection.fixes().isEmpty() || at.start() != at.end()) return inspection;
        // Like Eclipse, a caret without fixes moves to the closest fixable problem on its line, errors first.
        for (IProblem problem : lineProblems(tree, text, at)) {
            var moved = inspect(new Location(at.file(), problem.getSourceStart()), before, text, tree, cancel);
            if (moved.fixes().isEmpty()) { forget(moved); continue; }
            forget(inspection);
            return moved;
        }
        return inspection;
    }

    private Inspection inspect(Location at, WorkspaceSnapshot before, String text, CompilationUnit tree, Cancellation cancel) {
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
        if (name.getParent() instanceof Annotation annotation && annotation.getTypeName() == name) {
            // An annotation's name is not a SimpleType; it is only ever imported, never created as a class.
            offerImports(c, into, name.getIdentifier(), IJavaSearchConstants.ANNOTATION_TYPE);
            return;
        }
        SimpleType type = ancestor(name, SimpleType.class);
        if (type == null || ancestor(name, ImportDeclaration.class) != null) return;
        String identifier = name.getIdentifier();
        String qualified = type.getName().getFullyQualifiedName();
        String pkg = qualified.contains(".") ? qualified.substring(0, qualified.lastIndexOf('.')) : packageName(c.tree());
        if (!identifier(identifier) || !packageNameValid(pkg)) return;

        if (!qualified.contains(".")) offerImports(c, into, identifier, IJavaSearchConstants.TYPE);
        createClass(c, into, type, identifier, pkg);
        if (!qualified.contains(".")) similarTypes(c, into, name);
    }

    private void createClass(Context c, List<Proposal> into, SimpleType type, String identifier, String pkg) {
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
        // Like Eclipse's new-class wizard, `Base b = new Missing()` creates Missing as a Base, so the assignment compiles.
        ITypeBinding expected = creation == null || creation.getType() != type ? null : supertype(expectedType(creation));
        String imported = expected == null || expected.getPackage().getName().isEmpty()
                || expected.getPackage().getName().equals("java.lang") || expected.getPackage().getName().equals(pkg)
                ? "" : "import " + expected.getQualifiedName() + ";" + separator + separator;
        String header = expected == null ? "" : (expected.isInterface() ? " implements " : " extends ") + expected.getName();
        String content = (pkg.isEmpty() ? "" : "package " + pkg + ";" + separator + separator) + imported
                + "public class " + identifier + typeParameters + header + " {" + separator + "    " + separator + "}" + separator;
        var at = new Location(destination, content.indexOf("    ") + 4);
        Plan plan = checkedPlan(Operation.CREATE_CLASS, c.snapshot(), List.of(),
                List.of(new FileOperation(FileOperationKind.CREATE, destination, null, content)),
                "Create class '" + identifier + "'", c.cancel());
        if (!plan.valid()) return;
        offer(c, into, Kind.FIX, Action.CREATE_CLASS, "Create class '" + identifier + "'",
                "Create " + destination.getFileName() + (header.isEmpty() ? "" : " (" + header.strip() + ")") + " in "
                        + (pkg.isEmpty() ? "the default package" : pkg) + " under " + c.snapshot().root().relativize(root) + ".",
                c.at(), Operation.CREATE_CLASS, Map.of("className", identifier, "packageName", pkg,
                        "sourceRoot", root.toString(), "targetFile", destination.toString(),
                        expected != null && expected.isInterface() ? "interface" : "superclass",
                        expected == null ? "" : expected.getQualifiedName()),
                cancel -> new Prepared(plan, at, List.of()));
    }

    /** The type a `new` expression must produce: its variable's, its assignment target's or its method's return type. */
    private static ITypeBinding expectedType(ClassInstanceCreation creation) {
        ASTNode parent = creation.getParent();
        if (parent instanceof VariableDeclarationFragment fragment && fragment.getInitializer() == creation)
            return fragment.resolveBinding() == null ? null : fragment.resolveBinding().getType();
        if (parent instanceof Assignment assignment && assignment.getRightHandSide() == creation)
            return assignment.getLeftHandSide().resolveTypeBinding();
        if (parent instanceof ReturnStatement) {
            for (ASTNode node = parent; node != null; node = node.getParent()) {
                if (node instanceof LambdaExpression) return null;
                if (node instanceof MethodDeclaration method)
                    return method.resolveBinding() == null ? null : method.resolveBinding().getReturnType();
            }
        }
        return null;
    }

    /** Only a plain, importable, extendable class or interface; validation still rejects one the new class cannot satisfy. */
    private static ITypeBinding supertype(ITypeBinding type) {
        if (type == null || type.isRecovered() || !type.isTopLevel() || !type.isClass() && !type.isInterface()
                || type.isAnnotation() || type.isParameterizedType() || type.isGenericType() || type.isRawType()
                || Modifier.isFinal(type.getModifiers()) || type.getQualifiedName().equals("java.lang.Object")) return null;
        return type;
    }

    /** Like Eclipse's "Change to", a misspelled type offers the visible types with a nearly identical name. */
    private void similarTypes(Context c, List<Proposal> into, SimpleName name) {
        String identifier = name.getIdentifier();
        String pkg = packageName(c.tree());
        var similar = new TreeMap<String, Integer>();
        try {
            new SearchEngine(workspace.units().toArray(ICompilationUnit[]::new)).searchAllTypeNames(null,
                    SearchPattern.R_EXACT_MATCH, identifier.substring(0, 1).toCharArray(),
                    SearchPattern.R_PREFIX_MATCH | SearchPattern.R_CASE_SENSITIVE, IJavaSearchConstants.CLASS_AND_INTERFACE,
                    SearchEngine.createJavaSearchScope(new IJavaElement[]{workspace.javaProject(c.at().file())}),
                    new TypeNameMatchRequestor() { @Override public void acceptTypeNameMatch(TypeNameMatch match) {
                        c.cancel().check();
                        IType type = match.getType();
                        String simple = match.getSimpleTypeName();
                        int distance = distance(identifier, simple);
                        if (type == null || type.getDeclaringType() != null || simple.equals(identifier)
                                || distance > Math.max(1, identifier.length() / 4)
                                || !Flags.isPublic(match.getModifiers()) && !type.getPackageFragment().getElementName().equals(pkg)) return;
                        similar.put(type.getFullyQualifiedName('.'), distance);
                    } }, IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, LtkWorkspace.monitor(c.cancel()));
        } catch (CoreException error) { return; }
        // Project types (the same package first) before library types, then the closest spelling.
        var ranked = similar.keySet().stream().sorted(Comparator.<String>comparingInt(similar::get)
                .thenComparing(qualified -> !samePackage(qualified, pkg)).thenComparing(Comparator.naturalOrder())).limit(3).toList();
        for (String qualified : ranked) {
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            String before = c.snapshot().source(c.at().file()).text(), after;
            try {
                var document = new org.eclipse.jface.text.Document(before);
                ImportRewrite imports = ImportRewrite.create(c.tree(), true);
                if (!samePackage(qualified, pkg) && !imports.addImport(qualified).equals(simple)) continue;
                var edit = new org.eclipse.text.edits.MultiTextEdit();
                edit.addChild(new org.eclipse.text.edits.ReplaceEdit(name.getStartPosition(), name.getLength(), simple));
                edit.addChild(imports.rewriteImports(LtkWorkspace.monitor(c.cancel())));
                edit.apply(document);
                after = document.get();
            } catch (CoreException | org.eclipse.jface.text.BadLocationException | org.eclipse.text.edits.MalformedTreeException error) { continue; }
            String title = "Change to '" + simple + "'" + (samePackage(qualified, pkg) ? "" : " (" + qualified.substring(0, qualified.lastIndexOf('.')) + ")");
            Plan plan = checkedPlan(Operation.FIX_TYPE_MISMATCH, c.snapshot(),
                    List.of(new FileEdits(c.at().file(), ChangePlans.difference(before, after))), List.of(), title, c.cancel());
            if (!plan.valid()) continue;
            offer(c, into, Kind.FIX, Action.REFACTOR, title, "Replace '" + identifier + "' with the existing type " + qualified + ".",
                    c.at(), Operation.FIX_TYPE_MISMATCH, Map.of("direct", "true"), cancel -> prepared(plan, c.at()));
        }
    }

    private static boolean samePackage(String qualified, String pkg) {
        return qualified.lastIndexOf('.') < 0 ? pkg.isEmpty() : qualified.substring(0, qualified.lastIndexOf('.')).equals(pkg);
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }

    /** Like Eclipse, an unresolved Jupiter annotation offers the library itself when it is missing from the build path.
     * Unresolved JUnit 4 imports (org.junit.Test…) move to Jupiter, the JUnit that TurboJ provides. */
    private void missingJUnit(Context c, List<Proposal> into) {
        boolean jupiter = onBuildPath(c, JupiterApi.PROBE);
        var junit4 = onBuildPath(c, JupiterApi.JUNIT4_PROBE) ? List.<ImportDeclaration>of() : junit4Imports(c.tree());
        if (atJUnit4(c, junit4)) { migrateJUnit4(c, into, junit4, jupiter); return; }
        if (jupiter || !problem(c, IProblem.UndefinedType)) return;
        SimpleName name = c.node() instanceof SimpleName n ? n : ancestor(c.node(), SimpleName.class);
        if (name == null || !(name.getParent() instanceof Annotation annotation) || annotation.getTypeName() != name
                || !JupiterApi.ANNOTATIONS.contains(name.getIdentifier())) return;
        String qualified = JupiterApi.PACKAGE + "." + name.getIdentifier();
        ImportRewrite imports = ImportRewrite.create(c.tree(), true);
        if (!imports.addImport(qualified).equals(name.getIdentifier())) return;
        String before = c.snapshot().source(c.at().file()).text(), after;
        try {
            var document = new org.eclipse.jface.text.Document(before);
            imports.rewriteImports(LtkWorkspace.monitor(c.cancel())).apply(document);
            after = document.get();
        } catch (CoreException | org.eclipse.jface.text.BadLocationException error) { return; }
        // The import cannot compile until the host adds the library, so it is not validated against this snapshot.
        Plan plan = new Plan(UUID.randomUUID().toString(), Operation.ADD_IMPORT, c.snapshot(), List.of(),
                List.of(new FileEdits(c.at().file(), ChangePlans.difference(before, after))), List.of(), List.of(),
                ValidationStatus.VALID, "Add JUnit 5 library to the build path");
        offer(c, into, Kind.FIX, Action.ADD_JUNIT_LIBRARY, "Add JUnit 5 library to the build path",
                "Add JUnit Jupiter to this project, then import '" + qualified + "'.", c.at(), Operation.ADD_IMPORT,
                Map.of("qualifiedName", qualified), cancel -> new Prepared(plan, c.at(), List.of()));
    }

    /** An unqualified call such as assertEquals(…) imports its static favorite, as IntelliJ's and Eclipse's fixes do. */
    private void missingStaticImports(Context c, List<Proposal> into) {
        MethodInvocation call = ancestor(c.node(), MethodInvocation.class);
        if (call == null || call.getExpression() != null || !contains(call.getName(), c.at())) return;
        // While an argument is in error, the compiler does not report the call itself: assertEquals(0, missing()).
        if (!problem(c, IProblem.UndefinedMethod) && !unimported(c.tree(), call)) return;
        String name = call.getName().getIdentifier();
        for (String owner : favoriteOwners(c, name)) {
            // The arguments may still be unwritten (assertEquals()), so the import is not compile-validated.
            Plan plan = staticImportPlan(c, owner, name, "Import static " + owner + "." + name);
            if (plan == null) continue;
            String shortName = owner.substring(owner.lastIndexOf('.') + 1) + "." + name;
            offer(c, into, Kind.FIX, Action.ADD_IMPORT, "Import static '" + shortName + "'",
                    "Add 'import static " + owner + "." + name + ";' to this source file.", c.at(), Operation.ADD_IMPORT,
                    Map.of("qualifiedName", owner + "." + name), cancel -> new Prepared(plan, c.at(), List.of()));
        }
        if (!JupiterApi.ASSERTION_METHODS.contains(name) || onBuildPath(c, JupiterApi.PROBE)) return;
        Plan plan = staticImportPlan(c, JupiterApi.ASSERTIONS, name, "Add JUnit 5 library to the build path");
        if (plan == null) return;
        offer(c, into, Kind.FIX, Action.ADD_JUNIT_LIBRARY, "Add JUnit 5 library to the build path",
                "Add JUnit Jupiter to this project, then 'import static " + JupiterApi.ASSERTIONS + "." + name + ";'.", c.at(),
                Operation.ADD_IMPORT, Map.of("qualifiedName", JupiterApi.ASSERTIONS + "." + name), cancel -> new Prepared(plan, c.at(), List.of()));
    }

    /** The favorites on the build path (Assertions, Mockito…) declaring a public static method with this name. */
    private List<String> favoriteOwners(Context c, String name) {
        var owners = new ArrayList<String>();
        for (String owner : JupiterApi.FAVORITES) {
            try {
                IType type = workspace.javaProject(c.at().file()).findType(owner);
                if (type != null && type.exists() && Arrays.stream(type.getMethods())
                        .anyMatch(method -> method.getElementName().equals(name) && hasStatic(method))) owners.add(owner);
            } catch (JavaModelException unavailable) { }
        }
        return owners;
    }

    /** An unqualified call that resolves nowhere: neither statically imported nor declared by an enclosing class. */
    private static boolean unimported(CompilationUnit tree, MethodInvocation call) {
        if (call.getExpression() != null || call.resolveMethodBinding() != null) return false;
        String name = call.getName().getIdentifier();
        for (Object value : tree.imports())
            if (value instanceof ImportDeclaration declaration && declaration.isStatic() && (declaration.isOnDemand()
                    || declaration.getName().getFullyQualifiedName().endsWith("." + name))) return false;
        for (ASTNode node = call; node != null; node = node.getParent())
            if (node instanceof AbstractTypeDeclaration type)
                for (ITypeBinding owner = type.resolveBinding(); owner != null; owner = owner.getSuperclass())
                    if (Arrays.stream(owner.getDeclaredMethods()).anyMatch(method -> method.getName().equals(name))) return false;
        return true;
    }

    /** One plan's edits per file, in order; null when two edits overlap. */
    private static List<FileEdits> merged(List<FileEdits> first, List<FileEdits> second) {
        var files = new LinkedHashMap<Path, List<TextEdit>>();
        for (var plan : List.of(first, second))
            for (FileEdits edits : plan) files.computeIfAbsent(edits.file(), file -> new ArrayList<>()).addAll(edits.edits());
        var out = new ArrayList<FileEdits>();
        for (var entry : files.entrySet()) {
            var edits = entry.getValue().stream().sorted(Comparator.comparingInt(TextEdit::start)).toList();
            for (int i = 1; i < edits.size(); i++) if (edits.get(i).start() < edits.get(i - 1).end()) return null;
            out.add(new FileEdits(entry.getKey(), edits));
        }
        return out;
    }

    /** import static owner.member; null when another static import already claims the name. */
    private Plan staticImportPlan(Context c, String owner, String member, String title) {
        ImportRewrite imports = ImportRewrite.create(c.tree(), true);
        if (!imports.addStaticImport(owner, member, false).equals(member)) return null;
        String before = c.snapshot().source(c.at().file()).text(), after;
        try {
            var document = new org.eclipse.jface.text.Document(before);
            imports.rewriteImports(LtkWorkspace.monitor(c.cancel())).apply(document);
            after = document.get();
        } catch (CoreException | org.eclipse.jface.text.BadLocationException error) { return null; }
        if (before.equals(after)) return null;
        return new Plan(UUID.randomUUID().toString(), Operation.ADD_IMPORT, c.snapshot(), List.of(),
                List.of(new FileEdits(c.at().file(), ChangePlans.difference(before, after))), List.of(), List.of(), ValidationStatus.VALID, title);
    }

    private static boolean hasStatic(IMethod method) {
        try { return Flags.isStatic(method.getFlags()) && Flags.isPublic(method.getFlags()); }
        catch (JavaModelException unavailable) { return false; }
    }

    private boolean onBuildPath(Context c, String type) {
        try {
            IType found = workspace.javaProject(c.at().file()).findType(type);
            return found != null && found.exists();
        } catch (JavaModelException error) { return false; }
    }

    private static List<ImportDeclaration> junit4Imports(CompilationUnit tree) {
        var result = new ArrayList<ImportDeclaration>();
        for (Object object : tree.imports())
            if (object instanceof ImportDeclaration declaration && !declaration.isStatic() && !declaration.isOnDemand()
                    && declaration.getName() instanceof QualifiedName name
                    && name.getQualifier().getFullyQualifiedName().equals(JupiterApi.JUNIT4_PACKAGE)
                    && JupiterApi.JUNIT4.containsKey(name.getName().getIdentifier()))
                result.add(declaration);
        return result;
    }

    /** The caret is on one of the JUnit 4 imports, or on an annotation that one of them names. */
    private static boolean atJUnit4(Context c, List<ImportDeclaration> junit4) {
        if (junit4.isEmpty()) return false;
        ImportDeclaration declaration = ancestor(c.node(), ImportDeclaration.class);
        if (declaration != null) return junit4.contains(declaration);
        SimpleName name = c.node() instanceof SimpleName n ? n : ancestor(c.node(), SimpleName.class);
        return name != null && name.getParent() instanceof Annotation annotation && annotation.getTypeName() == name
                && junit4.stream().anyMatch(d -> ((QualifiedName) d.getName()).getName().getIdentifier().equals(name.getIdentifier()));
    }

    /** Rewrites every JUnit 4 annotation import, and renamed annotations (@Before → @BeforeEach), in one fix. */
    private void migrateJUnit4(Context c, List<Proposal> into, List<ImportDeclaration> junit4, boolean jupiter) {
        var edits = new ArrayList<TextEdit>();
        var renames = new HashMap<String, String>();
        for (ImportDeclaration declaration : junit4) {
            QualifiedName name = (QualifiedName) declaration.getName();
            String replacement = JupiterApi.JUNIT4.get(name.getName().getIdentifier());
            edits.add(new TextEdit(name.getStartPosition(), name.getStartPosition() + name.getLength(), JupiterApi.PACKAGE + "." + replacement));
            if (!replacement.equals(name.getName().getIdentifier())) renames.put(name.getName().getIdentifier(), replacement);
        }
        if (!renames.isEmpty()) c.tree().accept(new ASTVisitor() {
            @Override public void endVisit(MarkerAnnotation annotation) { rename(annotation.getTypeName()); }
            @Override public void endVisit(SingleMemberAnnotation annotation) { rename(annotation.getTypeName()); }
            @Override public void endVisit(NormalAnnotation annotation) { rename(annotation.getTypeName()); }
            private void rename(Name name) {
                if (name instanceof SimpleName simple && renames.containsKey(simple.getIdentifier()))
                    edits.add(new TextEdit(simple.getStartPosition(), simple.getStartPosition() + simple.getLength(), renames.get(simple.getIdentifier())));
            }
        });
        edits.sort(Comparator.comparingInt(TextEdit::start));
        String qualified = JupiterApi.PACKAGE + "." + JupiterApi.JUNIT4.get(((QualifiedName) junit4.getFirst().getName()).getName().getIdentifier());
        String title = jupiter ? "Change JUnit 4 imports to JUnit 5" : "Add JUnit 5 library to the build path";
        // Deterministic import edits; with the library still missing they cannot compile against this snapshot yet.
        Plan plan = new Plan(UUID.randomUUID().toString(), Operation.ADD_IMPORT, c.snapshot(), List.of(),
                List.of(new FileEdits(c.at().file(), edits)), List.of(), List.of(), ValidationStatus.VALID, title);
        offer(c, into, Kind.FIX, jupiter ? Action.ADD_IMPORT : Action.ADD_JUNIT_LIBRARY, title,
                (jupiter ? "Change" : "TurboJ provides JUnit 5 (Jupiter): add it to this project and change")
                        + " the JUnit 4 imports to " + JupiterApi.PACKAGE + ", e.g. '" + qualified + "'.",
                c.at(), Operation.ADD_IMPORT, Map.of("qualifiedName", qualified), cancel -> new Prepared(plan, c.at(), List.of()));
    }

    // Import alternatives come from the actual Java project's visible source/library types.
    private void offerImports(Context c, List<Proposal> into, String identifier, int kind) {
        for (String candidate : importCandidates(c, identifier, kind)) {
            var request = new Requests.AddImport(c.at(), candidate, false);
            Plan plan = engine.prepare(request, c.cancel());
            if (!plan.valid()) continue;
            offer(c, into, Kind.FIX, Action.ADD_IMPORT, "Import '" + candidate + "'",
                    "Add the resolved type import to this source file.", c.at(), Operation.ADD_IMPORT,
                    Map.of("qualifiedName", candidate), cancel -> prepared(plan, c.at()));
        }
    }

    private List<String> importCandidates(Context c, String name, int kind) {
        var names = new TreeSet<String>();
        try {
            new SearchEngine(workspace.units().toArray(ICompilationUnit[]::new)).searchAllTypeNames(null,
                    SearchPattern.R_EXACT_MATCH, name.toCharArray(), SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                    kind, SearchEngine.createJavaSearchScope(new IJavaElement[]{workspace.javaProject(c.at().file())}),
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
        String imported = "";
        if (!plan.valid() && !plan.edits().isEmpty() && call.getParent() instanceof MethodInvocation caller && unimported(c.tree(), caller)) {
            // assertEquals(0, calculator.add(0, 0)) before assertEquals is imported: the compiler checks the caller only
            // once its argument resolves, so the method and the caller's static import are created together.
            String name = caller.getName().getIdentifier();
            for (String favorite : favoriteOwners(c, name)) {
                Plan importing = staticImportPlan(c, favorite, name, plan.description());
                var edits = importing == null ? null : merged(plan.edits(), importing.edits());
                if (edits == null) continue;
                Plan combined = checkedPlan(Operation.CREATE_METHOD, c.snapshot(), edits, plan.fileOperations(), plan.description(), c.cancel());
                if (combined.valid()) { plan = combined; imported = ", importing static " + favorite + "." + name; break; }
            }
        }
        if (!plan.valid()) return;
        Prepared prepared = methodPrepared(plan, targetFile, methodName, arguments.size());
        offer(c, into, Kind.FIX, Action.CREATE_METHOD, "Create method '" + signature + "' in " + owner.getName(),
                "Infer the signature from this call, create the method in " + unit.getElementName() + imported
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

    /**
     * The inline template of a generated method, in Tab order: return type, method name, each parameter name, then the
     * returned expression. "end" is not a stop: it is where the caret goes when the template is confirmed (after the
     * returned expression, or inside a void method's body). The focus is the first stop.
     */
    private static Prepared methodTemplate(Plan plan, Path target, MethodDeclaration method) {
        var placeholders = new ArrayList<Placeholder>();
        if (method.getReturnType2() != null) placeholders.add(new Placeholder("returnType", range(target, method.getReturnType2())));
        placeholders.add(new Placeholder("methodName", range(target, method.getName())));
        for (int i = 0; i < method.parameters().size(); i++) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) method.parameters().get(i);
            placeholders.add(new Placeholder("parameter." + i, range(target, parameter.getName())));
        }
        Location end = null;
        if (method.getBody() != null) {
            var statements = method.getBody().statements();
            if (statements.size() == 1 && statements.getFirst() instanceof ReturnStatement returned && returned.getExpression() != null) {
                Location value = range(target, returned.getExpression());
                placeholders.add(new Placeholder("body", value));
                end = new Location(target, value.end());
            } else end = statements.isEmpty() ? new Location(target, method.getBody().getStartPosition() + 1)
                    : range(target, (ASTNode) statements.getFirst());
        }
        if (end != null) placeholders.add(new Placeholder("end", new Location(target, end.end())));
        return new Prepared(plan, placeholders.getFirst().location(), placeholders);
    }

    @Override public List<Location> localOccurrences(Location location, Cancellation cancel) {
        cancel.check();
        CompilationUnit tree = workspace.ast(location.file());
        ASTNode node = NodeFinder.perform(tree, location.start(), Math.max(0, location.end() - location.start()));
        if (!(node instanceof SimpleName name)) node = NodeFinder.perform(tree, location.start(), 0);
        if (!(node instanceof SimpleName name) || !(name.resolveBinding() instanceof IVariableBinding variable)
                || variable.isField() || variable.isRecordComponent() || variable.isRecovered()) return List.of();
        var occurrences = new ArrayList<Location>();
        tree.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName candidate) {
                if (candidate.resolveBinding() instanceof IVariableBinding other && other.isEqualTo(variable))
                    occurrences.add(range(location.file(), candidate));
                return true;
            }
        });
        return occurrences;
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
    /** A caret also touches a problem when it sits right after the problem's last character. */
    private static boolean intersects(Location at, IProblem problem) {
        int end = at.start() == at.end() ? problem.getSourceEnd() + 1 : problem.getSourceEnd();
        return problem.getSourceStart() >= 0 && problem.getSourceStart() <= at.end() && end >= at.start();
    }
    private static List<IProblem> lineProblems(CompilationUnit tree, String text, Location at) {
        int lineStart = text.lastIndexOf('\n', at.start() - 1) + 1, next = text.indexOf('\n', at.start());
        int lineEnd = next < 0 ? text.length() : next;
        return Arrays.stream(tree.getProblems())
                .filter(p -> p.getSourceStart() >= lineStart && p.getSourceStart() < lineEnd && !intersects(at, p))
                .sorted(Comparator.comparing((IProblem p) -> !p.isError())
                        .thenComparingInt(p -> Math.max(p.getSourceStart() - at.start(), at.start() - p.getSourceEnd() - 1)))
                .toList();
    }
    private void forget(Inspection inspection) { inspection.proposals().forEach(p -> offered.remove(p.id())); }
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
