package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaStructure.*;
import org.eclipse.jdt.core.dom.*;
import java.nio.file.Path;
import java.util.*;
import static dev.turboj.refactorj.jdt.JdtKeyboardEditing.*;

/** Static source relationships only: neither a test result nor dynamic coverage. */
final class JdtTestNavigation {
    private static final Set<String> ANNOTATIONS = Set.of("org.junit.Test", "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate", "org.junit.jupiter.params.ParameterizedTest");
    private final JdtKeyboardEditing editing;
    JdtTestNavigation(JdtKeyboardEditing editing) { this.editing = editing; }

    static boolean testMethod(MethodDeclaration method) {
        IMethodBinding binding = method == null ? null : method.resolveBinding();
        if (binding == null || binding.isRecovered()) return false;
        for (IAnnotationBinding annotation : binding.getAnnotations())
            if (testAnnotation(annotation.getAnnotationType(), new HashSet<>())) return true;
        if (!Modifier.isPublic(binding.getModifiers()) || Modifier.isStatic(binding.getModifiers())
                || !binding.getName().startsWith("test") || binding.getParameterTypes().length != 0
                || !binding.getReturnType().getName().equals("void")) return false;
        Set<String> parents = new HashSet<>();
        for (ITypeBinding type = binding.getDeclaringClass(); type != null && parents.size() < 64 && parents.add(type.getKey()); type = type.getSuperclass())
            if (!type.isRecovered() && type.getQualifiedName().equals("junit.framework.TestCase")) return true;
        return false;
    }
    private static boolean testAnnotation(ITypeBinding type, Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey()) || seen.size() > 64) return false;
        if (ANNOTATIONS.contains(type.getQualifiedName())) return true;
        for (IAnnotationBinding annotation : type.getAnnotations())
            if (testAnnotation(annotation.getAnnotationType(), seen)) return true;
        return false;
    }
    static String ownerLabel(ASTNode node) {
        for (ASTNode parent = node; parent != null && !(parent instanceof MethodDeclaration); parent = parent.getParent()) {
            if (parent instanceof Javadoc) return "Documentation reference";
            if (parent instanceof AbstractTypeDeclaration || parent instanceof AnonymousClassDeclaration) return "Initializer / type use";
        }
        MethodDeclaration method = ancestor(node, MethodDeclaration.class);
        if (method == null) return "Initializer / type use";
        IMethodBinding binding = method.resolveBinding();
        String owner = binding == null ? "" : binding.getDeclaringClass().getQualifiedName() + ".";
        return (testMethod(method) ? "Test " : "") + owner + method.getName() + "()";
    }
    private static MethodDeclaration callingTest(ASTNode node) {
        for (ASTNode parent = node; parent != null; parent = parent.getParent()) {
            if (parent instanceof Javadoc || parent instanceof AbstractTypeDeclaration || parent instanceof AnonymousClassDeclaration) return null;
            if (parent instanceof MethodDeclaration method) {
                Block body = method.getBody();
                return body != null && node.getStartPosition() >= body.getStartPosition() && end(node) <= end(body)
                        && testMethod(method) ? method : null;
            }
        }
        return null;
    }
    List<Target> related(int offset) {
        var workspace = editing.workspace;
        List<Target> result = new ArrayList<>();
        String filename = editing.context.file().getFileName().toString();
        String counterpart = filename.endsWith("Test.java") ? filename.substring(0, filename.length()-9)+".java"
                : filename.substring(0, filename.length()-5)+"Test.java";
        for (var source : workspace.snapshot().sources()) {
            if (!source.file().getFileName().toString().equals(counterpart)) continue;
            CompilationUnit tree = workspace.ast(source.file());
            boolean preferred = packageName(tree).equals(packageName(editing.unit)) && relatedProjects(source.file());
            result.add(new Target(source.file(), 0, 0,
                    preferred ? (filename.endsWith("Test.java") ? "Default source" : "Default test") + " (name/package match)"
                            : "Matching filename — relationship unverified",
                    preferred ? TargetKind.DEFAULT_COUNTERPART : TargetKind.SOURCE));
        }
        ASTNode at = editing.at(offset, offset);
        MethodDeclaration enclosing = ancestor(at, MethodDeclaration.class);
        if (testMethod(enclosing)) {
            // An inherited test is represented at its actual declaration, not duplicated per runtime subclass.
            enclosing.accept(new ASTVisitor() {
                @Override public boolean visit(SimpleName name) {
                    if (callingTest(name) != enclosing || name.isDeclaration()) return true;
                    if (!(name.resolveBinding() instanceof IMethodBinding called) || called.isRecovered()) return true;
                    var symbol = workspace.symbol(called.getMethodDeclaration().getJavaElement());
                    if (symbol == null || symbol.declaration() == null) return true;
                    var location = symbol.declaration();
                    var tree = workspace.ast(location.file());
                    var target = ancestor(NodeFinder.perform(tree, location.start(), 0), MethodDeclaration.class);
                    if (!testMethod(target)) result.add(new Target(location.file(), location.start(), location.end(),
                            "Used by this test → " + called.getDeclaringClass().getQualifiedName() + "." + symbol.signature(), TargetKind.PRODUCTION_USAGE));
                    return true;
                }
            });
        } else {
            IMethodBinding selected = selectedMethod(at, enclosing);
            if (selected != null && !selected.isRecovered() && selected.getJavaElement() != null) {
                var symbol = workspace.symbol(selected.getMethodDeclaration().getJavaElement());
                if (symbol != null) for (var location : workspace.references(symbol.id(), Cancellation.NONE)) {
                    CompilationUnit tree = workspace.ast(location.file());
                    ASTNode node = NodeFinder.perform(tree, location.start(), location.end()-location.start());
                    if (callingTest(node) == null) continue;
                    result.add(new Target(location.file(), location.start(), location.end(),
                            "Verified usage — " + ownerLabel(node), TargetKind.TEST_USAGE));
                }
            }
        }
        return result.stream().distinct().sorted(Comparator.comparingInt((Target t) -> t.kind() == TargetKind.DEFAULT_COUNTERPART ? 0 : 1)
                .thenComparing(t -> t.file().toString()).thenComparingInt(Target::start)).toList();
    }
    private static IMethodBinding selectedMethod(ASTNode node, MethodDeclaration enclosing) {
        if (node instanceof SimpleName name && name.resolveBinding() instanceof IMethodBinding method) return method;
        if (node instanceof MethodInvocation call) return call.resolveMethodBinding();
        return enclosing == null ? null : enclosing.resolveBinding();
    }
    private static String packageName(CompilationUnit tree) {
        return tree.getPackage() == null ? "" : tree.getPackage().getName().getFullyQualifiedName();
    }
    private boolean relatedProjects(Path other) {
        var snapshot = editing.workspace.snapshot();
        if (snapshot.projects().isEmpty()) return true;
        var current = owner(editing.context.file()); var candidate = owner(other);
        if (current == null || candidate == null) return false;
        if (current.id().equals(candidate.id())) return true;
        // MavenSemanticAdapter names the two source sets <module>#main / <module>#test.
        String family = current.id().replaceFirst("#(?:main|test)$", "");
        if (!family.equals(current.id()) && family.equals(candidate.id().replaceFirst("#(?:main|test)$", ""))) return true;
        // A dependency edge alone does not make another module's test the default counterpart.
        for (Path left : current.sourceRoots()) for (Path right : candidate.sourceRoots()) {
            Path module = conventionalModule(left);
            if (module != null && module.equals(conventionalModule(right))) return true;
        }
        return false;
    }
    private static Path conventionalModule(Path root) {
        if (!root.endsWith(Path.of("src/main/java")) && !root.endsWith(Path.of("src/test/java"))) return null;
        return root.getParent().getParent().getParent();
    }
    private Model.JavaProjectConfiguration owner(Path file) {
        return editing.workspace.snapshot().projects().stream().filter(p -> p.sourceRoots().stream().anyMatch(file::startsWith))
                .max(Comparator.comparingInt(p -> p.sourceRoots().stream().filter(file::startsWith).mapToInt(Path::getNameCount).max().orElse(0))).orElse(null);
    }
}
