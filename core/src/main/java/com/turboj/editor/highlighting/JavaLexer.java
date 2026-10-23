package com.turboj.editor.highlighting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Small line lexer; multiline state is explicit and independent of compiler work. */
public final class JavaLexer {
    public enum Kind {
        TEXT,
        KEYWORD,
        STRING,
        COMMENT,
        NUMBER,
        ANNOTATION,
        DECLARATION,
        OPERATOR
    }

    public enum State {
        NORMAL,
        BLOCK_COMMENT,
        TEXT_BLOCK
    }

    public record Span(int start, int end, Kind kind) {}

    public record Line(List<Span> spans, State endState) {}

    private static final Set<String> KEYWORDS =
            Set.of(
                    "abstract",
                    "assert",
                    "boolean",
                    "break",
                    "byte",
                    "case",
                    "catch",
                    "char",
                    "class",
                    "const",
                    "continue",
                    "default",
                    "do",
                    "double",
                    "else",
                    "enum",
                    "extends",
                    "final",
                    "finally",
                    "float",
                    "for",
                    "goto",
                    "if",
                    "implements",
                    "import",
                    "instanceof",
                    "int",
                    "interface",
                    "long",
                    "native",
                    "new",
                    "package",
                    "private",
                    "protected",
                    "public",
                    "return",
                    "short",
                    "static",
                    "strictfp",
                    "super",
                    "switch",
                    "synchronized",
                    "this",
                    "throw",
                    "throws",
                    "transient",
                    "try",
                    "void",
                    "volatile",
                    "while",
                    "true",
                    "false",
                    "null",
                    "var",
                    "yield",
                    "record",
                    "sealed",
                    "permits",
                    "non-sealed",
                    "module",
                    "open",
                    "requires",
                    "exports",
                    "opens",
                    "to",
                    "uses",
                    "provides",
                    "with",
                    "transitive",
                    "when");

    private JavaLexer() {}

    public static Line lex(String text, State initialState) {
        List<Span> spans = new ArrayList<>();
        State state = initialState;
        boolean declaration = false;
        int i = 0;
        while (i < text.length()) {
            int start = i;
            if (state == State.BLOCK_COMMENT) {
                int end = text.indexOf("*/", i);
                i = end < 0 ? text.length() : end + 2;
                spans.add(new Span(start, i, Kind.COMMENT));
                if (end >= 0) state = State.NORMAL;
            } else if (state == State.TEXT_BLOCK) {
                int end = text.indexOf("\"\"\"", i);
                while (end > 0 && escaped(text, end)) end = text.indexOf("\"\"\"", end + 3);
                i = end < 0 ? text.length() : end + 3;
                spans.add(new Span(start, i, Kind.STRING));
                if (end >= 0) state = State.NORMAL;
            } else if (text.startsWith("//", i)) {
                spans.add(new Span(i, text.length(), Kind.COMMENT));
                i = text.length();
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                spans.add(new Span(start, i, Kind.COMMENT));
                if (end < 0) state = State.BLOCK_COMMENT;
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                while (end > 0 && escaped(text, end)) end = text.indexOf("\"\"\"", end + 3);
                i = end < 0 ? text.length() : end + 3;
                spans.add(new Span(start, i, Kind.STRING));
                if (end < 0) state = State.TEXT_BLOCK;
            } else {
                int cp = text.codePointAt(i);
                if (cp == '"' || cp == '\'') {
                    char quote = (char) cp;
                    i++;
                    while (i < text.length()) {
                        char c = text.charAt(i++);
                        if (c == '\\' && i < text.length()) i++;
                        else if (c == quote) break;
                    }
                    spans.add(new Span(start, i, Kind.STRING));
                } else if (cp == '@') {
                    i++;
                    while (i < text.length()
                            && (Character.isJavaIdentifierPart(text.codePointAt(i))
                                    || text.charAt(i) == '.'))
                        i += Character.charCount(text.codePointAt(i));
                    spans.add(new Span(start, i, Kind.ANNOTATION));
                } else if (Character.isJavaIdentifierStart(cp)) {
                    i += Character.charCount(cp);
                    while (i < text.length() && Character.isJavaIdentifierPart(text.codePointAt(i)))
                        i += Character.charCount(text.codePointAt(i));
                    String token = text.substring(start, i);
                    Kind kind =
                            KEYWORDS.contains(token)
                                    ? Kind.KEYWORD
                                    : declaration ? Kind.DECLARATION : Kind.TEXT;
                    spans.add(new Span(start, i, kind));
                    declaration = Set.of("class", "interface", "enum", "record").contains(token);
                } else if (Character.isDigit(cp)) {
                    i++;
                    while (i < text.length()
                            && (Character.isLetterOrDigit(text.charAt(i))
                                    || "._".indexOf(text.charAt(i)) >= 0)) i++;
                    spans.add(new Span(start, i, Kind.NUMBER));
                } else {
                    i += Character.charCount(cp);
                    spans.add(
                            new Span(
                                    start,
                                    i,
                                    "{}[]();,.+-*/=!<>?:&|%^~".indexOf(cp) >= 0
                                            ? Kind.OPERATOR
                                            : Kind.TEXT));
                }
            }
        }
        return new Line(List.copyOf(spans), state);
    }

    private static boolean escaped(String text, int offset) {
        int count = 0;
        for (int i = offset - 1; i >= 0 && text.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }
}
