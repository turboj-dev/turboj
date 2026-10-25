package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaIntelligence.Context;
import dev.turboj.refactorj.api.JavaIntelligence.TextEdit;
import dev.turboj.refactorj.api.JavaStructure.*;
import org.eclipse.jdt.core.dom.*;
import java.util.*;
import java.util.function.Predicate;

/** Read-only operations over the workspace's cached AST; no independent parser/index per feature. */
final class JdtKeyboardEditing {
    final LtkWorkspace workspace;
    final Context context;
    final CompilationUnit unit;
    final String source;
    JdtKeyboardEditing(LtkWorkspace workspace, Context context) {
        this.workspace = workspace; this.context = context;
        source = context.source();
        require(workspace.snapshot().source(context.file()).text().equals(source), "The source context differs from the shared workspace; synchronize it before structural editing.");
        unit = workspace.ast(context.file());
    }
    ASTNode at(int start, int end) {
        start = Math.clamp(start, 0, source.length()); end = Math.clamp(end, start, source.length());
        ASTNode n = NodeFinder.perform(unit, start, end - start);
        if ((n == null || n == unit) && start == end && start > 0) n = NodeFinder.perform(unit, start - 1, 0);
        return n == null ? unit : n;
    }
    static int end(ASTNode n) { return n.getStartPosition() + n.getLength(); }
    static <T> T ancestor(ASTNode n, Class<T> kind) { for (; n != null; n = n.getParent()) if (kind.isInstance(n)) return kind.cast(n); return null; }
    Range select(int start, int end, Selection selection) {
        if (start > end) { int t = start; start = end; end = t; }
        ASTNode n = at(start, end);
        for (; n != null; n = n.getParent()) {
            if (selection == Selection.BRACKETS) {
                Range brackets = brackets(n);
                if (brackets != null && brackets.start() <= start && brackets.end() >= end) return brackets;
                continue;
            }
            boolean accept = switch (selection) {
                case EXPAND -> n instanceof Name || n instanceof Expression || n instanceof Statement || n instanceof BodyDeclaration || n instanceof CompilationUnit || n instanceof VariableDeclaration;
                case STATEMENT -> n instanceof Statement && !(n instanceof Block);
                case METHOD -> n instanceof MethodDeclaration;
                case BLOCK -> n instanceof Block;
                case TYPE -> n instanceof AbstractTypeDeclaration || n instanceof AnonymousClassDeclaration;
                case BRACKETS -> n instanceof Block || n instanceof ParenthesizedExpression || n instanceof ArrayInitializer || n instanceof AbstractTypeDeclaration;
            };
            if (accept && n.getStartPosition() <= start && end(n) >= end &&
                    (selection != Selection.EXPAND || n.getStartPosition() < start || end(n) > end)) return new Range(n.getStartPosition(), end(n));
        }
        return null;
    }
    Range brackets(ASTNode node) {
        int from, opening, closing;
        if (node instanceof Block || node instanceof ArrayInitializer || node instanceof AnonymousClassDeclaration) {
            return new Range(node.getStartPosition(), end(node));
        } else if (node instanceof ParenthesizedExpression) return new Range(node.getStartPosition(), end(node));
        else if (node instanceof AbstractTypeDeclaration type) { from = end(type.getName()); opening = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLBRACE; closing = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameRBRACE; }
        else if (node instanceof MethodInvocation invocation) { from = end(invocation.getName()); opening = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLPAREN; closing = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameRPAREN; }
        else if (node instanceof ClassInstanceCreation creation) { from = end(creation.getType()); opening = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLPAREN; closing = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameRPAREN; }
        else if (node instanceof MethodDeclaration method) { from = end(method.getName()); opening = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLPAREN; closing = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameRPAREN; }
        else if (node instanceof ArrayAccess access) { from = end(access.getArray()); opening = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameLBRACKET; closing = org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameRBRACKET; }
        else return null;
        var scanner = org.eclipse.jdt.core.ToolFactory.createScanner(false, false, false, workspace.javaProject(context.file()).getOption(org.eclipse.jdt.core.JavaCore.COMPILER_SOURCE, true));
        scanner.setSource(source.toCharArray()); scanner.resetTo(from, Math.max(from, end(node)-1));
        int first = -1, depth = 0;
        try {
            for (int token; (token = scanner.getNextToken()) != org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameEOF;) {
                if (token == opening) { if (first < 0) first = scanner.getCurrentTokenStartPosition(); depth++; }
                if (token == closing && first >= 0 && --depth == 0) return new Range(first, scanner.getCurrentTokenEndPosition()+1);
            }
        } catch (org.eclipse.jdt.core.compiler.InvalidInputException ignored) { /* Incomplete input has no proven bracket pair. */ }
        return null;
    }
    EditResult edit(int caret, int anchor, Edit operation) {
        try {
            return switch (operation) {
                case MOVE_STATEMENT_UP, MOVE_BLOCK_UP -> move(caret, anchor, -1);
                case MOVE_STATEMENT_DOWN, MOVE_BLOCK_DOWN -> move(caret, anchor, 1);
                case MOVE_ELEMENT_LEFT -> moveElement(caret, anchor, -1);
                case MOVE_ELEMENT_RIGHT -> moveElement(caret, anchor, 1);
                case SURROUND_IF, SURROUND_TRY_FINALLY -> surround(caret, anchor, operation);
                case COMPLETE_STATEMENT -> complete(caret);
                default -> new JdtKeyboardGeneration(this).generate(caret, operation);
            };
        } catch (IllegalArgumentException unavailable) { return EditResult.unavailable(unavailable.getMessage()); }
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    @SuppressWarnings("unchecked")
    private List<ASTNode> siblings(ASTNode n) {
        var location = n.getLocationInParent();
        if (location instanceof ChildListPropertyDescriptor list &&
                (n.getParent() instanceof Block || n instanceof BodyDeclaration && !(n instanceof EnumConstantDeclaration)))
            return (List<ASTNode>) n.getParent().getStructuralProperty(list);
        return List.of();
    }
    private EditResult move(int caret, int anchor, int direction) {
        int start = Math.min(caret, anchor), finish = Math.max(caret, anchor);
        ASTNode n = at(start, start);
        if (n instanceof Block block && start != finish) {
            n = null;
            for (Object object : block.statements()) {
                Statement statement = (Statement)object;
                if (statement.getStartPosition() >= start && statement.getStartPosition() < finish) { n = statement; break; }
            }
        }
        while (n != null && siblings(n).isEmpty()) n = n.getParent();
        require(n != null, "Select a statement or member inside one block/type; scope boundaries cannot be crossed.");
        List<ASTNode> list = siblings(n);
        int first = list.indexOf(n), last = first;
        if (start != finish) {
            while (last + 1 < list.size() && list.get(last + 1).getStartPosition() < finish) last++;
            require(source.substring(start, Math.max(start, list.get(first).getStartPosition())).isBlank()
                    && source.substring(Math.min(finish, end(list.get(last))), finish).isBlank(), "Select complete adjacent statements or members.");
            require(start <= list.get(first).getStartPosition() && finish >= end(list.get(last)), "Select complete adjacent statements or members.");
        }
        int other = direction < 0 ? first - 1 : last + 1;
        require(other >= 0 && other < list.size(), "Already at the boundary of this block or type.");
        List<ASTNode> selected = list.subList(first, last + 1);
        ASTNode adjacent = list.get(other);
        var warnings = new ArrayList<String>();
        for (ASTNode moving : selected) analyzeMovement(moving, adjacent, warnings);
        Range a = extended(list.get(direction < 0 ? other : first));
        Range b = extended(list.get(direction < 0 ? last : other));
        int splitA = direction < 0 ? extended(adjacent).end() : extended(list.get(last)).end();
        int splitB = direction < 0 ? extended(list.get(first)).start() : extended(adjacent).start();
        require(splitA <= splitB, "Attached comments overlap; this move is ambiguous.");
        String left = source.substring(a.start(), splitA), gap = source.substring(splitA, splitB), right = source.substring(splitB, b.end());
        require(gap.isBlank(), "The move would cross a comment or declaration outside the selection.");
        String replacement = right + gap + left;
        int delta = direction < 0 ? a.start() - splitB : right.length() + gap.length();
        int afterCaret = Math.clamp(caret + delta, a.start(), a.start() + replacement.length());
        int afterAnchor = Math.clamp(anchor + delta, a.start(), a.start() + replacement.length());
        return checked(List.of(new TextEdit(a.start(), b.end(), replacement)), afterCaret, afterAnchor, warnings, "Move complete Java syntax within its existing scope");
    }
    private Range extended(ASTNode n) {
        int start = unit.getExtendedStartPosition(n), finish = start + unit.getExtendedLength(n);
        if (start < 0) { start = n.getStartPosition(); finish = end(n); }
        return new Range(start, finish);
    }
    private void analyzeMovement(ASTNode a, ASTNode b, List<String> warnings) {
        if (a instanceof MethodDeclaration && b instanceof MethodDeclaration) return;
        if (a instanceof AbstractTypeDeclaration && b instanceof BodyDeclaration || b instanceof AbstractTypeDeclaration && a instanceof BodyDeclaration) {
            require(!(a instanceof FieldDeclaration) && !(b instanceof FieldDeclaration) && !(a instanceof Initializer) && !(b instanceof Initializer), "Cannot reorder type initialization relative to a nested declaration."); return;
        }
        if (a instanceof MethodDeclaration && b instanceof BodyDeclaration || b instanceof MethodDeclaration && a instanceof BodyDeclaration) return;
        require(!(a instanceof Initializer) && !(b instanceof Initializer), "Initialization blocks cannot be reordered safely.");
        if (a instanceof FieldDeclaration || b instanceof FieldDeclaration) {
            require(a instanceof FieldDeclaration && b instanceof FieldDeclaration, "Incompatible member kinds.");
            require(constantFields((FieldDeclaration)a) && constantFields((FieldDeclaration)b), "Field reordering is limited to compile-time constants; initialization order may be observable.");
            return;
        }
        require(a instanceof Statement && b instanceof Statement, "Incompatible syntax elements.");
        var ea = effects(a); var eb = effects(b);
        require(!ea.unresolved && !eb.unresolved, "A binding could not be resolved; movement is unsafe.");
        require(Collections.disjoint(ea.declarations, eb.references) && Collections.disjoint(eb.declarations, ea.references), "The move changes a local declaration's lifetime or dependency.");
        require(!ea.control && !eb.control, "A return, throw, break, continue or yield cannot be reordered.");
        require(Collections.disjoint(ea.writes, eb.references) && Collections.disjoint(eb.writes, ea.references), "The statements read/write the same state; reordering is unsafe.");
        if (ea.effects || eb.effects) warnings.add("Statement order can change side effects, exceptions or loop behavior. Review the complete move before applying.");
    }
    private boolean constantFields(FieldDeclaration field) {
        if (!Modifier.isFinal(field.getModifiers())) return false;
        for (Object item : field.fragments()) {
            VariableDeclarationFragment f = (VariableDeclarationFragment)item;
            if (f.resolveBinding() == null || f.resolveBinding().getConstantValue() == null) return false;
        }
        return true;
    }
    private static final class Effects {
        final Set<String> declarations = new HashSet<>(), references = new HashSet<>(), writes = new HashSet<>();
        boolean effects, control, unresolved;
    }
    private Effects effects(ASTNode n) {
        Effects result = new Effects();
        n.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName name) {
                IBinding binding = name.resolveBinding();
                if (binding == null || binding.isRecovered()) { result.unresolved = true; return true; }
                if (binding instanceof IVariableBinding variable) {
                    String id = variable.getVariableDeclaration().getKey(); result.references.add(id);
                    if (!name.isDeclaration() && (variable.isField() && variable.getConstantValue() == null || !variable.getType().isPrimitive())) result.effects = true;
                    if (name.isDeclaration()) result.declarations.add(id);
                    ASTNode p = name.getParent();
                    if (p instanceof Assignment assignment && assignment.getLeftHandSide() == name || p instanceof PostfixExpression || p instanceof PrefixExpression prefix && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)) result.writes.add(id);
                }
                return true;
            }
            @Override public void preVisit(ASTNode node) {
                if (node instanceof MethodInvocation || node instanceof SuperMethodInvocation || node instanceof ClassInstanceCreation || node instanceof ArrayCreation || node instanceof Assignment || node instanceof PostfixExpression || node instanceof SynchronizedStatement || node instanceof TryStatement || node instanceof ForStatement || node instanceof EnhancedForStatement || node instanceof WhileStatement || node instanceof DoStatement) result.effects = true;
                if (node instanceof PrefixExpression p && (p.getOperator() == PrefixExpression.Operator.INCREMENT || p.getOperator() == PrefixExpression.Operator.DECREMENT)) result.effects = true;
                if (node instanceof ArrayAccess || node instanceof CastExpression || node instanceof FieldAccess || node instanceof InfixExpression infix && (infix.getOperator() == InfixExpression.Operator.DIVIDE || infix.getOperator() == InfixExpression.Operator.REMAINDER)) result.effects = true;
                if (node instanceof ReturnStatement || node instanceof ThrowStatement || node instanceof BreakStatement || node instanceof ContinueStatement || node instanceof YieldStatement) result.control = true;
            }
            @Override public boolean visit(LambdaExpression ignored) { result.effects = true; return false; }
            @Override public boolean visit(AnonymousClassDeclaration ignored) { result.effects = true; return false; }
        });
        return result;
    }
    @SuppressWarnings("unchecked")
    private EditResult moveElement(int caret, int anchor, int direction) {
        ASTNode n = at(Math.min(caret, anchor), Math.max(caret, anchor));
        while (n != null) {
            var loc = n.getLocationInParent();
            if (loc instanceof ChildListPropertyDescriptor && (loc.getId().equals("arguments") || loc.getId().equals("expressions") || loc.getId().equals("enumConstants") || loc.getId().equals("parameters"))) break;
            n = n.getParent();
        }
        require(n != null, "Select a resolved argument, array element, enum constant or parameter.");
        require(!(n instanceof SingleVariableDeclaration), "Reordering method parameters changes callers. Use Refactor → Change Signature to update declarations and call sites together.");
        var nodes = (List<ASTNode>)n.getParent().getStructuralProperty(n.getLocationInParent());
        int current = nodes.indexOf(n), target = current + direction;
        require(target >= 0 && target < nodes.size(), "Already at the boundary of this list.");
        ASTNode other = nodes.get(target);
        require(pureElement(n) && pureElement(other), "Element movement cannot reorder side effects, mutable reads, exceptions or enum constructors.");
        ASTNode a = direction < 0 ? other : n, b = direction < 0 ? n : other;
        int start = a.getStartPosition(), finish = end(b);
        String replacement = text(b) + source.substring(end(a), b.getStartPosition()) + text(a);
        int delta = direction < 0 ? start - n.getStartPosition() : text(other).length() + b.getStartPosition() - end(a);
        var edits = List.of(new TextEdit(start, finish, replacement));
        var result = checked(edits, caret + delta, anchor + delta, List.of("Reordering values changes argument/element order and can change program results. Confirm this deliberate change."), "Move Java list element");
        // Overloads must remain the same; a valid compilation is not enough.
        ASTNode parent = n.getParent(); IMethodBinding before = invocation(parent);
        if (before != null) {
            CompilationUnit changed = parse(apply(edits));
            IMethodBinding after = invocation(NodeFinder.perform(changed, parent.getStartPosition(), parent.getLength()));
            require(after != null && before.getMethodDeclaration().isEqualTo(after.getMethodDeclaration()), "The move changes overload resolution; use Change Signature instead.");
        }
        return result;
    }
    private boolean pureElement(ASTNode n) {
        if (n instanceof EnumConstantDeclaration e) return e.arguments().isEmpty() && e.getAnonymousClassDeclaration() == null && ((EnumDeclaration)e.getParent()).bodyDeclarations().isEmpty();
        return n instanceof Expression expression && expression.resolveConstantExpressionValue() != null;
    }
    private IMethodBinding invocation(ASTNode n) {
        if (n instanceof MethodInvocation m) return m.resolveMethodBinding();
        if (n instanceof SuperMethodInvocation m) return m.resolveMethodBinding();
        if (n instanceof ClassInstanceCreation c) return c.resolveConstructorBinding();
        if (n instanceof ConstructorInvocation c) return c.resolveConstructorBinding();
        if (n instanceof SuperConstructorInvocation c) return c.resolveConstructorBinding();
        return null;
    }
    Requests.ChangeSignature parameterMove(int offset, int direction) {
        SingleVariableDeclaration parameter = ancestor(at(offset, offset), SingleVariableDeclaration.class);
        if (parameter == null || !(parameter.getParent() instanceof MethodDeclaration method)) return null;
        require(!method.isConstructor(), "Constructor parameters can be reordered through Change Signature.");
        int index = method.parameters().indexOf(parameter), target = index + direction;
        require(target >= 0 && target < method.parameters().size(), "Already at the boundary of this parameter list.");
        require(method.resolveBinding() != null && !method.resolveBinding().isRecovered(), "Resolve the method before reordering parameters.");
        var parameters = new ArrayList<Requests.Parameter>();
        for (int i = 0; i < method.parameters().size(); i++) {
            var p = (SingleVariableDeclaration)method.parameters().get(i);
            String type = text(p.getType()) + "[]".repeat(p.extraDimensions().size()) + (p.isVarargs() ? "..." : "");
            parameters.add(new Requests.Parameter(i, type, p.getName().getIdentifier(), ""));
        }
        Collections.swap(parameters, index, target);
        Requests.Visibility visibility = Modifier.isPublic(method.getModifiers()) ? Requests.Visibility.PUBLIC
                : Modifier.isProtected(method.getModifiers()) ? Requests.Visibility.PROTECTED
                : Modifier.isPrivate(method.getModifiers()) ? Requests.Visibility.PRIVATE : Requests.Visibility.PACKAGE;
        return new Requests.ChangeSignature(new Model.Location(context.file(), method.getName().getStartPosition()), method.getName().getIdentifier(), text(method.getReturnType2()), visibility, parameters);
    }
    private EditResult surround(int caret, int anchor, Edit operation) {
        int start = Math.min(caret, anchor), finish = Math.max(caret, anchor);
        ASTNode n = at(start, finish);
        Statement statement = ancestor(n, Statement.class);
        require(statement != null && statement.getParent() instanceof Block && !(statement instanceof VariableDeclarationStatement), "Select a complete statement without introducing a narrower variable scope.");
        require(start == finish || start <= statement.getStartPosition() && finish >= end(statement), "Select a complete statement.");
        require(!(statement instanceof ConstructorInvocation) && !(statement instanceof SuperConstructorInvocation), "Constructor chaining must remain first.");
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        String body = text(statement);
        String replacement = operation == Edit.SURROUND_IF ? "if (true) {" + eol + body + eol + "}" : "try {" + eol + body + eol + "} finally {" + eol + "}";
        int p = statement.getStartPosition();
        return checked(List.of(new TextEdit(p, end(statement), replacement)), p + replacement.length(), p, List.of(), "Surround a complete Java statement");
    }
    private EditResult complete(int caret) {
        ASTNode n = at(caret, caret);
        Statement statement = ancestor(n, Statement.class);
        int p = statement == null || statement instanceof Block ? caret : Math.min(end(statement), source.length());
        while (p > 0 && Character.isWhitespace(source.charAt(p - 1))) p--;
        require((p == 0 || source.charAt(p - 1) != ';') && (p == source.length() || source.charAt(p) != ';'), "This statement is already complete.");
        CompilationUnit candidate = parse(source.substring(0, p) + ";" + source.substring(p));
        Statement completed = ancestor(NodeFinder.perform(candidate, Math.max(0, p - 1), 0), Statement.class);
        require((completed instanceof ExpressionStatement || completed instanceof ReturnStatement || completed instanceof ThrowStatement || completed instanceof VariableDeclarationStatement || completed instanceof AssertStatement)
                && end(completed) == p + 1, "The caret must end a Java expression, return, throw, assert or declaration that is missing its semicolon.");
        return checked(List.of(new TextEdit(p, p, ";")), p + 1, p + 1, List.of(), "Complete Java statement");
    }
    String text(ASTNode n) { return source.substring(n.getStartPosition(), end(n)); }
    String apply(List<TextEdit> edits) {
        StringBuilder result = new StringBuilder(); int p = 0;
        for (var edit : edits) { result.append(source, p, edit.start()).append(edit.text()); p = edit.end(); }
        return result.append(source, p, source.length()).toString();
    }
    CompilationUnit parse(String changed) {
        ASTParser parser = ASTParser.newParser(AST.JLS25); parser.setProject(workspace.javaProject(context.file()));
        parser.setSource(changed.toCharArray()); parser.setUnitName(workspace.unit(context.file()).getPath().toString());
        parser.setResolveBindings(true); parser.setBindingsRecovery(false); parser.setStatementsRecovery(false);
        parser.setCompilerOptions(workspace.javaProject(context.file()).getOptions(true));
        return (CompilationUnit)parser.createAST(null);
    }
    EditResult checked(List<TextEdit> edits, int caret, int anchor, List<String> warnings, String description) {
        Map<String, Integer> errors = errors(unit), changed = errors(parse(apply(edits)));
        for (var entry : changed.entrySet()) require(entry.getValue() <= errors.getOrDefault(entry.getKey(), 0), "The proposed change introduces a Java error: " + entry.getKey());
        return new EditResult(edits, caret, anchor, warnings.stream().distinct().toList(), description);
    }
    private Map<String,Integer> errors(CompilationUnit ast) {
        var errors = new HashMap<String,Integer>();
        for (var problem : ast.getProblems()) if (problem.isError()) errors.merge(problem.getID() + ": " + problem.getMessage(), 1, Integer::sum);
        return errors;
    }
    List<Target> navigate(int offset, Navigation kind, String query) {
        return new JdtKeyboardNavigation(this).navigate(offset, kind, query);
    }
}
