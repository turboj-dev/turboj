package dev.turboj.ui;

import dev.turboj.editor.highlighting.JavaLexer;
import java.util.ArrayList;

/** A bounded line lexer for XML buffers, sharing the editor's theme token vocabulary. */
public final class XmlLexer {
    private XmlLexer() {}

    public static JavaLexer.Line lex(String text, JavaLexer.State initial) {
        var spans = new ArrayList<JavaLexer.Span>();
        JavaLexer.State state = initial;
        int i = 0;
        while (i < text.length()) {
            int start = i;
            if (state == JavaLexer.State.BLOCK_COMMENT || text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + (state == JavaLexer.State.BLOCK_COMMENT ? 0 : 4));
                i = end < 0 ? text.length() : end + 3;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.COMMENT));
                state = end < 0 ? JavaLexer.State.BLOCK_COMMENT : JavaLexer.State.NORMAL;
            } else if (state == JavaLexer.State.TEXT_BLOCK || text.startsWith("<![CDATA[", i)) {
                int end = text.indexOf("]]>", i + (state == JavaLexer.State.TEXT_BLOCK ? 0 : 9));
                i = end < 0 ? text.length() : end + 3;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.STRING));
                state = end < 0 ? JavaLexer.State.TEXT_BLOCK : JavaLexer.State.NORMAL;
            } else if (text.startsWith("${", i)) {
                int end = text.indexOf('}', i + 2);
                i = end < 0 ? text.length() : end + 1;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.ANNOTATION));
            } else if (text.charAt(i) == '<') {
                i++;
                if (i < text.length() && "/?!".indexOf(text.charAt(i)) >= 0) i++;
                while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || ":-.".indexOf(text.charAt(i)) >= 0)) i++;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.KEYWORD));
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i++);
                while (i < text.length() && text.charAt(i) != quote) i++;
                if (i < text.length()) i++;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.STRING));
            } else if (text.charAt(i) == '&') {
                int end = text.indexOf(';', i + 1);
                i = end < 0 ? i + 1 : end + 1;
                spans.add(new JavaLexer.Span(start, i, JavaLexer.Kind.ANNOTATION));
            } else {
                boolean operator = ">=/".indexOf(text.charAt(i)) >= 0;
                i++;
                if (!operator) while (i < text.length() && "<>&\"'>=/".indexOf(text.charAt(i)) < 0 && !text.startsWith("${", i)) i++;
                spans.add(new JavaLexer.Span(start, i, operator ? JavaLexer.Kind.OPERATOR : JavaLexer.Kind.TEXT));
            }
        }
        return new JavaLexer.Line(java.util.List.copyOf(spans), state);
    }
}
