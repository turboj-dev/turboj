package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaStructure.*;
import org.eclipse.jdt.core.dom.*;
import java.nio.file.Path;
import java.util.*;
import static dev.turboj.refactorj.jdt.JdtKeyboardEditing.*;

/** Queries the same source units and binding identities used by refactoring. */
final class JdtKeyboardNavigation {
    private final JdtKeyboardEditing editing;
    JdtKeyboardNavigation(JdtKeyboardEditing editing) { this.editing = editing; }
    List<Target> navigate(int offset, Navigation kind, String query) {
        var workspace = editing.workspace; var context = editing.context;
        var result = new ArrayList<Target>();
        if (kind == Navigation.SYMBOLS || kind == Navigation.TYPES) {
            for (var symbol : workspace.symbols(Objects.requireNonNullElse(query, ""), Cancellation.NONE)) {
                if (kind == Navigation.TYPES && !Set.of("class", "interface", "enum", "annotation", "record", "type").contains(symbol.kind().toLowerCase(Locale.ROOT))) continue;
                var loc = symbol.declaration(); if (loc != null) result.add(new Target(loc.file(), loc.start(), loc.end(), symbol.signature()));
            }
        } else if (kind == Navigation.NEXT_METHOD || kind == Navigation.PREVIOUS_METHOD) {
            editing.unit.accept(new ASTVisitor() {
                @Override public boolean visit(MethodDeclaration method) { result.add(new Target(context.file(), method.getName().getStartPosition(), end(method.getName()), method.getName().getIdentifier())); return true; }
            });
            if (kind == Navigation.NEXT_METHOD) return result.stream().filter(t -> t.start() > offset).min(Comparator.comparingInt(Target::start)).map(List::of).orElse(List.of());
            return result.stream().filter(t -> t.start() < offset).max(Comparator.comparingInt(Target::start)).map(List::of).orElse(List.of());
        } else if (kind == Navigation.MATCHING_BRACE) {
            Range range = editing.select(offset, offset, Selection.BRACKETS);
            if (range != null) {
                int start = range.start(), end = range.end() - 1;
                if (start >= 0) { int target = Math.abs(offset - start) <= Math.abs(offset - end) ? end : start; result.add(new Target(context.file(), target, target, "Matching bracket")); }
            }
        } else if (kind == Navigation.REFERENCES || kind == Navigation.TYPE_HIERARCHY) {
            var symbol = workspace.resolve(new Model.Location(context.file(), offset), Cancellation.NONE);
            if (symbol == null && offset > 0) symbol = workspace.resolve(new Model.Location(context.file(), offset - 1), Cancellation.NONE);
            if (symbol == null) return List.of();
            if (kind == Navigation.REFERENCES) for (var loc : workspace.references(symbol.id(), Cancellation.NONE)) result.add(new Target(loc.file(), loc.start(), loc.end(), symbol.name() + " — " + loc.file().getFileName()));
            else {
                var hierarchy = workspace.hierarchy(symbol.id(), Cancellation.NONE);
                if (hierarchy != null) {
                    var symbols = new ArrayList<Model.Symbol>(); symbols.add(hierarchy.type()); symbols.addAll(hierarchy.supertypes()); symbols.addAll(hierarchy.subtypes());
                    for (var item : symbols) { var loc = item.declaration(); if (loc != null) result.add(new Target(loc.file(), loc.start(), loc.end(), item.signature())); }
                }
            }
        } else if (kind == Navigation.IMPLEMENTATIONS) {
            IBinding binding = binding(editing.at(offset, offset));
            if (binding == null) return List.of();
            for (var source : workspace.snapshot().sources()) workspace.ast(source.file()).accept(new ASTVisitor() {
                @Override public boolean visit(TypeDeclaration n) { addType(n, n.resolveBinding()); return true; }
                @Override public boolean visit(EnumDeclaration n) { addType(n, n.resolveBinding()); return true; }
                @Override public boolean visit(RecordDeclaration n) { addType(n, n.resolveBinding()); return true; }
                private void addType(AbstractTypeDeclaration n, ITypeBinding type) {
                    if (binding instanceof ITypeBinding target && type != null && !type.isInterface() && !Modifier.isAbstract(type.getModifiers()) && !type.isEqualTo(target) && type.isSubTypeCompatible(target)) result.add(target(source.file(), n.getName(), n.getName().getIdentifier()));
                }
                @Override public boolean visit(MethodDeclaration method) {
                    IMethodBinding resolved = method.resolveBinding();
                    if (binding instanceof IMethodBinding target && resolved != null && method.getBody() != null && (resolved.overrides(target) || resolved.isEqualTo(target))) result.add(target(source.file(), method.getName(), resolved.getDeclaringClass().getName() + "." + method.getName()));
                    return true;
                }
            });
        } else if (kind == Navigation.CALLERS || kind == Navigation.CALLEES) {
            IBinding binding = binding(editing.at(offset, offset));
            MethodDeclaration enclosing = ancestor(editing.at(offset, offset), MethodDeclaration.class);
            IMethodBinding target = binding instanceof IMethodBinding method ? method.getMethodDeclaration() : enclosing == null ? null : enclosing.resolveBinding();
            if (target == null) return List.of();
            final IMethodBinding selected = target.getMethodDeclaration();
            for (var source : workspace.snapshot().sources()) workspace.ast(source.file()).accept(new ASTVisitor() {
                @Override public boolean visit(MethodInvocation invocation) { consider(invocation, invocation.resolveMethodBinding()); return true; }
                @Override public boolean visit(SuperMethodInvocation invocation) { consider(invocation, invocation.resolveMethodBinding()); return true; }
                @Override public boolean visit(ClassInstanceCreation invocation) { consider(invocation, invocation.resolveConstructorBinding()); return true; }
                @Override public boolean visit(ConstructorInvocation invocation) { consider(invocation, invocation.resolveConstructorBinding()); return true; }
                @Override public boolean visit(SuperConstructorInvocation invocation) { consider(invocation, invocation.resolveConstructorBinding()); return true; }
                void consider(ASTNode call, IMethodBinding called) {
                    if (called == null || called.isRecovered()) return;
                    MethodDeclaration owner = ancestor(call, MethodDeclaration.class);
                    IMethodBinding ownerBinding = owner == null ? null : owner.resolveBinding();
                    if (kind == Navigation.CALLERS && called.getMethodDeclaration().isEqualTo(selected)) result.add(target(source.file(), call, (owner == null ? "Initializer" : owner.getName().getIdentifier()) + " → " + called.getName()));
                    if (kind == Navigation.CALLEES && ownerBinding != null && ownerBinding.getMethodDeclaration().isEqualTo(selected)) result.add(target(source.file(), call, called.getDeclaringClass().getName() + "." + called.getName()));
                }
            });
        }
        return result.stream().distinct().sorted(Comparator.comparing((Target t) -> t.file().toString()).thenComparingInt(Target::start)).toList();
    }
    private static Target target(Path file, ASTNode node, String label) { return new Target(file, node.getStartPosition(), end(node), label); }
    private IBinding binding(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof SimpleName name && name.resolveBinding() != null) return name.resolveBinding();
            if (n instanceof MethodDeclaration method) return method.resolveBinding();
            if (n instanceof MethodInvocation method) return method.resolveMethodBinding();
            if (n instanceof AbstractTypeDeclaration type) return type.resolveBinding();
        }
        return null;
    }
}
