package dev.turboj.testing;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.compiler.CompilerDiagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingJUnitTest {
    @Test void findsTestAnnotationsBeforeJUnit4ImportsAndIgnoresUnrelatedErrors() {
        String source = "import org.junit.Test;\nimport static org.junit.Assert.assertEquals;\n\npublic class CalculatorTest {\n"
                + "    @Test\n    public void test() {\n        Calculator calculator = new Calculator();\n    }\n}\n";
        int importDot = source.indexOf(".Test;"), staticDot = source.indexOf(".Assert"), annotation = source.indexOf("@Test") + 1;
        var diagnostics = List.of(
                error("package org.junit does not exist", importDot),
                error("package org.junit does not exist", staticDot),
                error("cannot find symbol\n  symbol:   class Test\n  location: class CalculatorTest", annotation),
                error("cannot find symbol\n  symbol:   class Calculator\n  location: class CalculatorTest", source.indexOf("Calculator calc")));
        assertEquals(List.of(annotation, importDot), MissingJUnit.locations(diagnostics, source),
                "the annotation first; the Assert static import and the missing Calculator are not JUnit fixes");
    }

    @Test void aMissingClassThatIsNotAnAnnotationIsNotJUnit() {
        String source = "class A { Test value; }";
        assertTrue(MissingJUnit.locations(List.of(error("cannot find symbol\n  symbol:   class Test", source.indexOf("Test"))), source).isEmpty());
    }

    private static CompilerDiagnostic error(String message, int start) {
        return new CompilerDiagnostic("ERROR", message, 1, 1, start, start + 1);
    }
}
