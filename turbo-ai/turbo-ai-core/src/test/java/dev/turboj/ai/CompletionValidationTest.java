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

    @Test
    void finishedMiddlesInsideALineAreKeptWhenTheyLeaveParenthesesBalanced() {
        var chain = request("List<String> emails() {\n    return users.stream()\n        ", "\n        .toList();\n}");
        assertEquals(".map(User::email)", CompletionValidation.usable(chain, ".map(User::email)", true));
        assertEquals("", CompletionValidation.usable(chain, ".map(User::email", true), "an unclosed call is not a middle");
        var header = request("void f(int[] values) {\n    for (", ") {\n    }\n}");
        assertEquals("int i = 0; i < values.length; i++", CompletionValidation.usable(header, "int i = 0; i < values.length; i++", true));
        var member = request("boolean empty(Deque<Task> tasks) {\n    return tasks.", "();\n}");
        assertEquals("isEmpty", CompletionValidation.usable(member, "isEmpty", true));
    }

    @Test
    void controlTokensEndTheSuggestionAndRepeatsAreDropped() {
        var r = request("int sum(int a, int b) {\n    return ", "\n}");
        assertEquals("a + b;", CompletionValidation.usable(r, "a + b;<|endoftext|>class Other {}", true));
        assertEquals("a + b;", CompletionValidation.usable(r, "a + b;<|file_sep|>Other.java", true));
        assertEquals("a + b;", CompletionValidation.usable(r, "a + b;;", true));
        var repeated = request("void log() {\n    System.out.println(\"start\");\n    ", "\n}");
        assertEquals("", CompletionValidation.usable(repeated, "System.out.println(\"start\");", true));
        var block = request("void f() {\n", "\n}");
        String many = "    a();\n".repeat(40);
        assertEquals(16, CompletionValidation.usable(block, many, true).lines().count(), "excessive output is cut at a line");
    }

    @Test
    void aFenceAfterLeadingBlankLinesIsUnwrappedNotACrash() {
        var r = request("int sum(int a, int b) {\n    return ", "\n}");
        assertEquals("a + b;", CompletionValidation.usable(r, "\n\n```java\na + b;\n```\n", true).strip());
        assertEquals("", CompletionValidation.usable(r, "\n```", true));
    }
}
