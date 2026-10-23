package com.turboj.language;

import static org.junit.jupiter.api.Assertions.*;

import com.turboj.compiler.SourceSnapshot;

import org.junit.jupiter.api.Test;

class JdtSyntaxAnalyzerTest {
    private final JdtSyntaxAnalyzer analyzer = new JdtSyntaxAnalyzer();

    @Test
    void handlesIncompleteJavaWithoutThrowing() {
        for (String incomplete : new String[] {"System.out.", "String name =", "if (value >"}) {
            var result =
                    analyzer.analyze(
                            new SourceSnapshot(
                                    "Hello.java", "class Hello { void main() { " + incomplete, 1));
            assertFalse(result.syntacticallyValid());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.isError()));
        }
    }

    @Test
    void semicolonsInsideStringsAndCommentsAreNotStatements() {
        String source =
                "class Hello { void main() { String value = \"inside;string\"; /* comment; */ System.out.println(value); } }";
        var result = analyzer.analyze(new SourceSnapshot("Hello.java", source, 1));
        assertTrue(result.syntacticallyValid(), result.diagnostics().toString());
        assertEquals(2, result.statements().size());
        var statement = result.statementAt(source.indexOf("println"));
        assertEquals(
                "System.out.println(value);", source.substring(statement.start(), statement.end()));
    }

    @Test
    void supportsJava25CompactSource() {
        var result =
                analyzer.analyze(
                        new SourceSnapshot("Compact.java", "void main() { IO.println(25); }", 1));
        assertTrue(result.syntacticallyValid(), result.diagnostics().toString());
        assertEquals(1, result.statements().size());
    }

    @Test
    void infersUnsavedTypeNameFromAstIgnoringCommentText() {
        assertEquals(
                "Calculator.java",
                SourceSnapshot.forBuffer(
                                "// public class Wrong {}\nclass Helper {} public class Calculator {}",
                                2)
                        .fileName());
        assertEquals(
                "RecordName.java",
                SourceSnapshot.forBuffer("public record RecordName(int value) {}", 2).fileName());
        assertEquals(
                "PackageMain.java",
                SourceSnapshot.forBuffer("package demo; class PackageMain { void main() {} }", 2)
                        .fileName());
        assertEquals(
                "Untitled.java",
                SourceSnapshot.forBuffer("void main() { IO.println(25); }", 2).fileName());
        assertEquals("Untitled.java", SourceSnapshot.forBuffer("", 2).fileName());
    }
}
