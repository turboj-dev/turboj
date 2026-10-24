package dev.javafmt.core.internal;

import dev.javafmt.core.*;
import dev.javafmt.parser.*;
import java.util.*;
import org.eclipse.jdt.core.dom.*;

/** Original AST-guided whitespace document and bounded, greedy line-layout solver. */
public final class Layout {
    private static final Set<String> CONTROL = Set.of("if", "while", "for", "switch", "catch",
            "synchronized", "try", "return", "throw", "yield", "assert", "case");
    private static final Set<String> OPERATORS = Set.of("=", "+", "-", "*", "/", "%", "==", "!=",
            "<", ">", "<=", ">=", "&&", "||", "&", "|", "^", "<<", ">>", ">>>", "+=", "-=", "*=",
            "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "->", "?", ":", "instanceof");
    private final ParsedSource parsed;
    private final List<JavaToken> tokens;
    private final String source, newline;
    private final int n;
    private final int[] partner, indent, delta, hard;
    private final boolean[] blockOpen, blockClose, generic, genericSpaceBefore, tightAfter,
            unaryBefore, unaryAfter, binary, soft;
    private final String[] gaps;

    public Layout(ParsedSource parsed, LineEndingPolicy endings) {
        this.parsed = parsed;
        source = parsed.source();
        tokens = parsed.tokens();
        n = tokens.size();
        int lf = source.indexOf('\n');
        newline = endings == LineEndingPolicy.PRESERVE && lf > 0 && source.charAt(lf - 1)
                == '\r' ? "\r\n" : "\n";
        partner = new int[n];
        Arrays.fill(partner, -1);
        indent = new int[n + 1];
        delta = new int[n + 1];
        hard = new int[n + 1];
        blockOpen = new boolean[n];
        blockClose = new boolean[n];
        generic = new boolean[n];
        genericSpaceBefore = new boolean[n];
        tightAfter = new boolean[n];
        unaryBefore = new boolean[n];
        unaryAfter = new boolean[n];
        binary = new boolean[n];
        soft = new boolean[n + 1];
        gaps = new String[n + 1];
        pairTokens();
        syntax();
        for (int i = 0, level = 0; i < n; i++) {
            level += delta[i];
            indent[i] = Math.max(0, level);
        }
        for (int i = 0; i <= n; i++)
            gaps[i] = spacing(i);
        comments();
    }

    private String text(int i) {
        return i < 0 || i >= n ? "" : tokens.get(i).text();
    }

    private void pairTokens() {
        ArrayDeque<Integer> stack = new ArrayDeque<> ();

        for (int i = 0; i < n; i++) {
            String t = text(i);

            if (tokens.get(i).comment() || tokens.get(i).literal())
                continue;
            if (t.equals("(") || t.equals("{") || t.equals("["))
                stack.push(i);
            else if (t.equals(")") || t.equals("}") || t.equals("]")) {
                if (stack.isEmpty())
                    throw new IllegalArgumentException("Unpaired delimiter");
                int open = stack.pop();
                partner[open] = i;
                partner[i] = open;
            }
        }
    }

    private int at(int position) {
        int lo = 0, hi = n;

        while (lo < hi) {
            int mid = (lo + hi) >>> 1;

            if (tokens.get(mid).start() < position)
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo;
    }

    private int first(ASTNode node) {
        return at(node.getStartPosition());
    }

    private int last(ASTNode node) {
        return at(node.getStartPosition() + node.getLength()) - 1;
    }

    private void deeper(int first, int end, int amount) {
        if (first >= end || first < 0 || end > n)
            return;
        delta[first] += amount;
        delta[end] -= amount;
    }

    private void hard(int gap, int lines) {
        if (gap >= 0 && gap <= n)
            hard[gap] = Math.max(hard[gap], lines);
    }

    private int leading(int i) {
        while (i > 0 && tokens.get(i - 1).comment()) {
            int c = i - 1;

            if (c > 0 && !hasNewline(source.substring(tokens.get(c - 1).end(), tokens.get(c)
                    .start())))
                break;
            i--;
        }
        return i;
    }

    private void block(ASTNode node) {
        int close = last(node);

        if (close < 0 || !text(close).equals("}"))
            return;
        int open = partner[close];

        if (open < first(node) || open < 0 || blockOpen[open])
            return;
        blockOpen[open] = true;
        blockClose[close] = true;
        deeper(open + 1, close, 4);
        if (close > open + 1) {
            hard(open + 1, 1);
            hard(close, 1);
        }
        String next = text(close + 1);

        if (!Set.of(";", ",", ")", "]", ".", "::", "else", "catch", "finally", "while")
                .contains(next))
            hard(close + 1, 1);
    }

    private void sequence(List<?> nodes, boolean members) {
        ASTNode prior = null;

        for (Object item : nodes) {
            if (!(item instanceof ASTNode node))
                continue;
            int begin = leading(first(node));
            int lines = 1;

            if (prior != null && (members && (node instanceof MethodDeclaration
                    || prior instanceof MethodDeclaration || node instanceof AbstractTypeDeclaration
                    || prior instanceof AbstractTypeDeclaration)
                    || prior instanceof VariableDeclarationStatement && isControl(node)))
                lines = 2;
            hard(begin, lines);
            prior = node;
        }
    }

    private static boolean isControl(ASTNode node) {
        return node instanceof IfStatement || node instanceof ForStatement
                || node instanceof EnhancedForStatement || node instanceof WhileStatement
                || node instanceof DoStatement || node instanceof TryStatement
                || node instanceof SwitchStatement;
    }

    private void body(Statement child) {
        if (child == null || child instanceof Block)
            return;
        int start = first(child), end = last(child) + 1;
        hard(leading(start), 1);
        deeper(start, end, 4);
    }

    private void angleList(ASTNode owner, List<?> list) {
        if (list.isEmpty() || !(list.getFirst() instanceof ASTNode first))
            return;
        ASTNode last = (ASTNode) list.getLast();
        int l = this.first(first) - 1, r = at(last.getStartPosition() + last.getLength());

        while (l >= this.first(owner) && tokens.get(l).comment())
            l--;
        while (r <= this.last(owner) && r < n && tokens.get(r).comment())
            r++;
        if (l >= 0 && text(l).equals("<")) {
            generic[l] = true;
            genericSpaceBefore[l] = owner instanceof MethodDeclaration;
        }
        if (r < n && (text(r).equals(">") || text(r).equals(">>") || text(r).equals(">>>"))) {
            generic[r] = true;
            if (owner instanceof MethodInvocation || owner instanceof SuperMethodInvocation
                    || owner instanceof ExpressionMethodReference
                    || owner instanceof TypeMethodReference)
                tightAfter[r] = true;
        }
    }

    private void syntax() {
        parsed.unit().accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                for (Object descriptor : node.structuralPropertiesForType()) {
                    StructuralPropertyDescriptor p = (StructuralPropertyDescriptor) descriptor;

                    if (p.isChildListProperty() && (p.getId().equals("typeArguments") || p.getId()
                            .equals("typeParameters")))
                        angleList(node, (List<?>) node.getStructuralProperty(p));
                }
                if (node instanceof Block b) {
                    block(b);
                    sequence(b.statements(), false);
                } else if (node instanceof AbstractTypeDeclaration t) {
                    block(t);
                    sequence(t.bodyDeclarations(), true);
                    if (t instanceof EnumDeclaration e)
                        sequence(e.enumConstants(), false);
                } else if (node instanceof AnonymousClassDeclaration a) {
                    block(a);
                    sequence(a.bodyDeclarations(), true);
                } else if (node instanceof ModuleDeclaration m) {
                    block(m);
                    sequence(m.moduleStatements(), false);
                } else if (node instanceof SwitchStatement s) {
                    block(s);
                    switchBody(s.statements());
                } else if (node instanceof SwitchExpression s) {
                    block(s);
                    switchBody(s.statements());
                } else if (node instanceof IfStatement s) {
                    body(s.getThenStatement());
                    if (!(s.getElseStatement() instanceof IfStatement))
                        body(s.getElseStatement());
                } else if (node instanceof ForStatement s)
                    body(s.getBody());
                else if (node instanceof EnhancedForStatement s)
                    body(s.getBody());
                else if (node instanceof WhileStatement s)
                    body(s.getBody());
                else if (node instanceof DoStatement s)
                    body(s.getBody());
                else if (node instanceof PrefixExpression p)
                    unaryAfter[first(p)] = true;
                else if (node instanceof PostfixExpression p)
                    unaryBefore[last(p)] = true;
                else if (node instanceof InfixExpression e) {
                    operatorBetween(e.getLeftOperand(), e.getRightOperand());
                    ASTNode previous = e.getRightOperand();

                    for (Object operand : e.extendedOperands()) {
                        operatorBetween(previous, (ASTNode) operand);
                        previous = (ASTNode) operand;
                    }
                } else if (node instanceof Assignment e)
                    operatorBetween(e.getLeftHandSide(), e.getRightHandSide());
                else if (node instanceof ParameterizedType p) {
                    int l = at(p.getType().getStartPosition() + p.getType().getLength()),
                            r = last(p);

                    if (text(l).equals("<"))
                        generic[l] = true;
                    if (r >= 0 && text(r).startsWith(">"))
                        generic[r] = true;
                }
                if (node instanceof Annotation && (node.getParent() instanceof BodyDeclaration
                        || node.getParent() instanceof PackageDeclaration))
                    hard(last(node) + 1, 1);
                if (node instanceof ImportDeclaration || node instanceof PackageDeclaration
                        || node instanceof ModuleDirective || node instanceof FieldDeclaration
                        || node instanceof AnnotationTypeMemberDeclaration
                        || node instanceof Statement && text(last(node)).equals(";"))
                    hard(last(node) + 1, 1);
                if (node instanceof MethodDeclaration m && m.getBody() == null)
                    hard(last(node) + 1, 1);
                // A method-chain dot is a safe wrap opportunity, but package/type qualification is not.
                if (node instanceof MethodInvocation m && m.getExpression() != null) {
                    int dot = at(m.getExpression().getStartPosition() + m.getExpression()
                            .getLength());

                    if (text(dot).equals("."))
                        soft[dot] = true;
                }
            }
        });
        sequence(parsed.unit().types(), true);
        if (parsed.unit().getPackage() != null)
            hard(last(parsed.unit().getPackage()) + 1, 2);
        List<?> imports = parsed.unit().imports();

        if (!imports.isEmpty())
            hard(last((ASTNode) imports.getLast()) + 1, 2);
        if (n > 0) {
            hard[0] = 0;
            hard[n] = 1;
        }
    }

    private void operatorBetween(ASTNode a, ASTNode b) {
        for (int i = at(a.getStartPosition() + a.getLength()); i < n && tokens.get(i).start() < b
                .getStartPosition(); i++) {
            if (!tokens.get(i).comment()) {
                binary[i] = true;
                soft[i] = true;
            }
        }
    }

    private void switchBody(List<?> statements) {
        SwitchCase prior = null;

        for (int i = 0; i < statements.size(); i++) {
            ASTNode node = (ASTNode) statements.get(i);

            if (node instanceof SwitchCase c) {
                hard(leading(first(c)), 1);
                prior = c;
            } else if (prior != null && !prior.isSwitchLabeledRule()) {
                hard(leading(first(node)), 1);
                deeper(first(node), last(node) + 1, 4);
            }
        }
    }

    private String spacing(int i) {
        if (i == 0)
            return "";
        if (i == n)
            return newline;
        String a = text(i - 1), b = text(i);

        if (a.equals(",")) {
            soft[i] = true;
            return " ";
        }
        if (b.equals(";") || b.equals(",") || b.equals(")") || b.equals("]") || b.equals(".") || b
                .equals("::"))
            return "";
        if (a.equals(".") || a.equals("::") || a.equals("@") || a.equals("(") || a.equals("["))
            return "";
        if (b.equals("("))
            return CONTROL.contains(a) || binary[i - 1] || OPERATORS.contains(a) && !unaryAfter[i
                    - 1] ? " " : "";
        if (b.equals("["))
            return "";
        if (unaryAfter[i - 1] || unaryBefore[i])
            return needsOperatorSeparation(a, b) ? " " : "";
        if (genericSpaceBefore[i])
            return " ";
        if (generic[i] && b.startsWith(">") || generic[i - 1] && a.equals("<") || generic[i] && b
                .equals("<"))
            return "";
        if (tightAfter[i - 1])
            return "";
        if (a.equals("{") && b.equals("}"))
            return "";
        if (a.equals("{") && !blockOpen[i - 1] || b.equals("}") && !blockClose[i])
            return "";
        if (a.equals("...") || b.equals("..."))
            return b.equals("...") ? "" : " ";
        if (OPERATORS.contains(a) || OPERATORS.contains(b))
            return " ";
        return " ";
    }

    private static boolean needsOperatorSeparation(String a, String b) {
        return Set.of("++", "--", "+=", "-=", "!=", "==", "&&", "||", "<<", ">>", "/*", "//")
                .contains(a + b);
    }

    private static boolean hasNewline(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private void comments() {
        for (int i = 0; i < n; i++) {
            JavaToken token = tokens.get(i);

            if (!token.comment())
                continue;
            boolean ownLine = i == 0 || hasNewline(source.substring(tokens.get(i - 1).end(), token
                    .start()));

            if (token.lineComment()) {
                if (!ownLine) {
                    hard(i + 1, hard[i]);
                    hard[i] = 0;
                    gaps[i] = " ";
                } else
                    hard(i, 1);
                hard(i + 1, 1);
            } else if (ownLine) {
                hard(i, 1);
                if (i + 1 < n && hasNewline(source.substring(token.end(), tokens.get(i + 1)
                        .start())))
                    hard(i + 1, 1);
            } else if (hard[i] == 0)
                gaps[i] = " ";
            if (!token.lineComment() && i + 1 < n && !hasNewline(source.substring(token.end(),
                    tokens.get(i + 1).start())) && hard[i + 1] == 0)
                gaps[i + 1] = " ";
        }
        if (n > 0)
            hard[0] = 0;
    }

    public List<TextEdit> edits(TextRange range, boolean entireFile) {
        List<TextEdit> edits = new ArrayList<> ();

        if (n == 0) {
            addEdit(edits, 0, source.length(), newline);
            return edits;
        }
        // Full syntax supplies context, but only selected gaps are laid out or emitted.
        int column = 0, lineIndent = 0;
        boolean continuation = false;

        for (int i = 0; i <= n; i++) {
            int start = i == 0 ? 0 : tokens.get(i - 1).end();
            int end = i == n ? source.length() : tokens.get(i).start();
            String original = source.substring(start, end);
            boolean selected = start >= range.start() && end <= range.end();
            String whitespace = original;

            if (selected) {
                whitespace = gaps[i];
                if (hard[i] > 0) {
                    whitespace = newline.repeat(hard[i]) + " ".repeat(i < n ? indent[i] : 0);
                    continuation = false;
                } else if (i < n && soft[i] && column + flatSegment(i) > 100) {
                    int base = Math.max(indent[i], lineIndent) + (continuation ? 0 : 8);
                    whitespace = newline + " ".repeat(Math.min(base, indent[i] + 8));
                    continuation = true;
                }
                // Unicode escapes in gaps are protected; never guess at pre-lexing whitespace.
                if (!original.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n'
                        || c == '\f'))
                    whitespace = original;
                addEdit(edits, start, end, whitespace);
            }
            if (hasNewline(whitespace)) {
                int last = Math.max(whitespace.lastIndexOf('\n'), whitespace.lastIndexOf('\r'));
                column = whitespace.length() - last - 1;
                lineIndent = column;
            } else
                column += whitespace.length();
            if (i < n) {
                String raw = tokens.get(i).raw();

                if (hasNewline(raw))
                    column = raw.length() - Math.max(raw.lastIndexOf('\n'), raw.lastIndexOf('\r'))
                            - 1;
                else
                    column += raw.length();
            }
        }
        return List.copyOf(edits);
    }

    private int flatSegment(int i) {
        int width = gaps[i].length();

        for (int j = i; j < n; j++) {
            String raw = tokens.get(j).raw();

            if (hasNewline(raw))
                return 101;
            width += raw.length();
            if (width > 100 || j + 1 == n || hard[j + 1] > 0 || soft[j + 1])
                break;
            width += gaps[j + 1].length();
        }
        return width;
    }

    private void addEdit(List<TextEdit> result, int start, int end, String replacement) {
        String old = source.substring(start, end);

        if (old.equals(replacement))
            return;
        int left = 0, right = 0;

        while (left < old.length() && left < replacement.length() && old.charAt(left) == replacement
                .charAt(left))
            left++;
        while (right < old.length() - left && right < replacement.length() - left && old.charAt(old
                .length() - right - 1) == replacement.charAt(replacement.length() - right - 1))
            right++;
        result.add(new TextEdit(start + left, end - right, replacement.substring(left, replacement
                .length() - right)));
    }
}
