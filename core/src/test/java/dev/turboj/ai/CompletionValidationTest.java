package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class CompletionValidationTest {
    private AICompletionRequest request(String prefix, String suffix) {
        return new AICompletionRequest(
                Path.of("MathOps.java"), 1, prefix.length(), prefix, suffix, "", 25, false);
    }

    @Test
    void methodBodyKeepsExistingSuffixAndDoesNotDuplicateBraces() {
        var r = request("class MathOps { int square(int value) {\n", "\n    }\n}");
        assertEquals(
                "    return value * value;",
                CompletionValidation.usable(r, "    return value * value;\n    }\n}", true));
        assertEquals(
                "    return value * value;",
                CompletionValidation.usable(r, "    return value * value;", true));
    }

    @Test
    void genuinelyMissingClosingBraceIsPreserved() {
        var r = request("class MathOps { int square(int value) {\n", "\n}");
        assertEquals(
                "    return value * value;\n}",
                CompletionValidation.usable(r, "    return value * value;\n}", true));
    }

    @Test
    void streamingOnlyPublishesWholeStatements() {
        var r = request("class MathOps { void run() {", "} }");
        assertEquals("", CompletionValidation.usable(r, "int total =", false));
        assertEquals(
                "int total = 0;\n",
                CompletionValidation.usable(r, "int total = 0;\nfor (int", false));
        assertEquals("", CompletionValidation.usable(r, "String value = \"unfinished;", false));
    }

    @Test
    void fencesAreCleanedButProseOrMalformedControlDataAreRejected() {
        var r = request("class MathOps { int x() {", "} }");
        assertEquals(
                "return 4;\n", CompletionValidation.usable(r, "```java\nreturn 4;\n```", true));
        assertEquals(
                "", CompletionValidation.usable(r, "Here is the implementation: return 4;", true));
        assertEquals("", CompletionValidation.usable(r, "return 4;\0", true));
        assertEquals("", CompletionValidation.usable(r, "<|fim_suffix|>", true));
        assertEquals("", CompletionValidation.usable(r, "x".repeat(32769), true));
    }

    @Test
    void commentsStringsAndCrLfDoNotConfuseDelimiterValidation() {
        var r = request("class MathOps { void x() {\r\n", "\r\n} }");
        assertEquals(
                "String s = \"}\";\r\n// }\r\n",
                CompletionValidation.usable(r, "String s = \"}\";\n// }\n", true));
    }

    @Test
    void existingFollowingStatementIsNotRepeated() {
        var r = request("class MathOps { void x() {\n", "log();\n} }");
        assertEquals("int n = 1;\n", CompletionValidation.usable(r, "int n = 1;\nlog();", true));
    }
}
