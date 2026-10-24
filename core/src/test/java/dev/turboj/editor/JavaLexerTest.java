package dev.turboj.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.editor.highlighting.JavaLexer;

import org.junit.jupiter.api.Test;

class JavaLexerTest {
    @Test
    void commentsAndStringsDoNotConfuseEachOther() {
        var line = JavaLexer.lex("String x = \"/* ; //\"; // comment", JavaLexer.State.NORMAL);
        assertEquals(JavaLexer.State.NORMAL, line.endState());
        assertEquals(
                1, line.spans().stream().filter(s -> s.kind() == JavaLexer.Kind.COMMENT).count());
        assertEquals(
                1, line.spans().stream().filter(s -> s.kind() == JavaLexer.Kind.STRING).count());
    }

    @Test
    void multilineStatesRecoverAfterClosingDelimiters() {
        var start = JavaLexer.lex("/** documentation", JavaLexer.State.NORMAL);
        assertEquals(JavaLexer.State.BLOCK_COMMENT, start.endState());
        var close = JavaLexer.lex(" * end */ public class Hello {}", start.endState());
        assertEquals(JavaLexer.State.NORMAL, close.endState());
        assertTrue(close.spans().stream().anyMatch(s -> s.kind() == JavaLexer.Kind.DECLARATION));
        assertEquals(
                JavaLexer.State.TEXT_BLOCK,
                JavaLexer.lex("String s = \"\"\"", JavaLexer.State.NORMAL).endState());
        assertEquals(
                JavaLexer.State.NORMAL,
                JavaLexer.lex(" end\"\"\";", JavaLexer.State.TEXT_BLOCK).endState());
    }
}
