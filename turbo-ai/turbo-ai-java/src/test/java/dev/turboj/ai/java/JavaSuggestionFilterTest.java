package dev.turboj.ai.java;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JavaSuggestionFilterTest {
    private static final String CALCULATOR = """
            public class Calculator {
                private int total;

                public int multiply(int a, int b) {
                    return\s
                }
            }
            """;
    private static final int CARET = CALCULATOR.indexOf("return ") + 7;

    @Test
    void knownNamesAndValidSyntaxPass() {
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(CALCULATOR, CARET, "a * b;", 25));
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(CALCULATOR, CARET, "total + a * b;", 25));
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(CALCULATOR, CARET, "Math.multiplyExact(a, b);", 25),
                "a member after a dot is not judged");
    }

    @Test
    void anInventedLocalIsRejected() {
        assertEquals(JavaSuggestionFilter.Verdict.UNKNOWN_NAME, JavaSuggestionFilter.check(CALCULATOR, CARET, "x * y;", 25));
        assertEquals(JavaSuggestionFilter.Verdict.UNKNOWN_NAME, JavaSuggestionFilter.check(CALCULATOR, CARET, "a * factor;", 25));
    }

    @Test
    void namesDeclaredByTheSuggestionLambdasAndCallsAreAllowed() {
        String loop = """
                class Sum {
                    int sum(int[] values) {
                        int total = 0;
                       \s
                        return total;
                    }
                }
                """;
        int at = loop.indexOf("       \n") + 7;
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(loop, at,
                "for (int i = 0; i < values.length; i++) {\n            total += values[i];\n        }", 25));
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(loop, at,
                "for (int value : values) total += value;", 25));
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(loop, at,
                "total = java.util.Arrays.stream(values).map(v -> v * v).sum();", 25));
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(loop, at, "log(total);", 25),
                "an unqualified call may reach an inherited method");
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(loop, at,
                "System.out.println(\"total of values\");", 25), "words in strings are not names");
    }

    @Test
    void aSuggestionThatBreaksTheFileIsRejected() {
        assertEquals(JavaSuggestionFilter.Verdict.SYNTAX_ERROR, JavaSuggestionFilter.check(CALCULATOR, CARET, "a * * b;", 25));
        assertEquals(JavaSuggestionFilter.Verdict.SYNTAX_ERROR, JavaSuggestionFilter.check(CALCULATOR, CARET, "a b;", 25));
    }

    @Test
    void membersCompletedAtTheCaretAndMethodReferencesAreNotInventedNames() {
        String queue = "class Q { java.util.ArrayDeque<Runnable> tasks; void next() { if (tasks.()) return; } }";
        int dot = queue.indexOf("tasks.") + 6;
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(queue, dot, "isEmpty", 25),
                "found in the benchmark: a member name right after the dot in the prefix");
        String cache = "class C extends java.util.LinkedHashMap<String, String> {\n    protected boolean (java.util.Map.Entry<String, String> e) {\n        return size() > 3;\n    }\n}";
        int name = cache.indexOf("boolean ") + 8;
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(cache, name, "removeEldestEntry", 25),
                "a method name before the parameter list in the suffix");
        String counter = "class W { java.util.Map<String, Integer> counts; void add(String word) {\n        \n    } }";
        int body = counter.indexOf("        \n") + 8;
        assertEquals(JavaSuggestionFilter.Verdict.ACCEPT, JavaSuggestionFilter.check(counter, body, "counts.merge(word, 1, Integer::sum);", 25),
                "a method reference after ::");
        assertEquals(JavaSuggestionFilter.Verdict.UNKNOWN_NAME, JavaSuggestionFilter.check(counter, body, "counts.merge(key, 1, Integer::sum);", 25));
    }
}
