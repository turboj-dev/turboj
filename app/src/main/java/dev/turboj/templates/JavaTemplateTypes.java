package dev.turboj.templates;

import java.util.Set;
import javax.lang.model.SourceVersion;

/** Bounded syntax check for editable return types; resolution remains the compiler's responsibility. */
public final class JavaTemplateTypes {
    private JavaTemplateTypes() {}
    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double");
    public static boolean name(String text) {
        return SourceVersion.isIdentifier(text) && !SourceVersion.isKeyword(text) && !text.equals("_");
    }
    public static boolean type(String text) {
        if (text.length() > 4096) return false;
        Parser parser = new Parser(text);
        return parser.type(false, 0) && parser.end();
    }
    private static final class Parser {
        final String text; int at;
        Parser(String text) { this.text = text; }
        void spaces() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        boolean end() { spaces(); return at == text.length(); }
        boolean take(char c) { spaces(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        String identifier() {
            spaces(); int start = at;
            if (at >= text.length() || !Character.isJavaIdentifierStart(text.codePointAt(at))) return "";
            at += Character.charCount(text.codePointAt(at));
            while (at < text.length() && Character.isJavaIdentifierPart(text.codePointAt(at))) at += Character.charCount(text.codePointAt(at));
            return text.substring(start, at);
        }
        boolean type(boolean reference, int depth) {
            if (depth > 32) return false;
            String first = identifier(); boolean primitive = PRIMITIVES.contains(first);
            if (!primitive && !typeName(first)) return false;
            if (!primitive) {
                if (!arguments(depth)) return false;
                while (take('.')) if (!typeName(identifier()) || !arguments(depth)) return false;
            }
            boolean array = false;
            while (take('[')) { if (!take(']')) return false; array = true; }
            return !reference || !primitive || array;
        }
        boolean arguments(int depth) {
            if (!take('<')) return true;
            do {
                if (take('?')) {
                    int saved = at; String bound = identifier();
                    if (bound.equals("extends") || bound.equals("super")) { if (!type(true, depth + 1)) return false; }
                    else at = saved;
                } else if (!type(true, depth + 1)) return false;
            } while (take(','));
            return take('>');
        }
        boolean typeName(String name) {
            return JavaTemplateTypes.name(name) && !Set.of("var", "yield", "record", "sealed", "permits").contains(name);
        }
    }
}
