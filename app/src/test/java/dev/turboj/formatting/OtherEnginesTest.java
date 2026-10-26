package dev.turboj.formatting;

import static org.junit.jupiter.api.Assertions.*;
import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.LineEndingPolicy;
import dev.javafmt.core.TextEdit;
import org.junit.jupiter.api.Test;

/** The engines besides google-java-format: Eclipse, IntelliJ IDEA's defaults and AWS's Smithy profile on the JDT formatter. */
class OtherEnginesTest {
    private static final String LONG = """
            class C {
            void f(java.util.List<String> names){
            String joined=names.stream().map(String::trim).filter(name -> !name.isEmpty()).map(String::toUpperCase).reduce("", String::concat);
            int total = 1 +
                    2;
            switch(names.size()){case 0 -> System.out.println("none"); default -> System.out.println("some");}
            }
            }
            """;

    private static String format(StandardFormatters style) {
        var result = style.engine().format(new FormatRequest(LONG, 3, LineEndingPolicy.PRESERVE), null, "25");
        assertTrue(result.success(), () -> style + ": " + result.diagnostics());
        assertEquals(result.formattedText(), TextEdit.apply(LONG, result.edits()));
        return result.formattedText();
    }

    @Test void intellijKeepsLinesAsWrittenAndIndentsCases() {
        String intellij = format(StandardFormatters.INTELLIJ);
        assertTrue(intellij.contains("        String joined = names.stream().map(String::trim).filter(name -> !name.isEmpty()).map(String::toUpperCase).reduce(\"\", String::concat);\n"), intellij);
        assertTrue(intellij.contains("        int total = 1 +\n                2;\n"), intellij);
        assertTrue(intellij.contains("        switch (names.size()) {\n            case 0 -> System.out.println(\"none\");"), intellij);
    }

    @Test void awsSmithyProfileWrapsAt120AndIndentsCases() {
        String aws = format(StandardFormatters.AWS);
        assertTrue(aws.lines().allMatch(line -> line.length() <= 120), aws);
        assertTrue(aws.contains("            case 0 -> System.out.println(\"none\");"), aws);
        assertEquals("4", StandardFormatters.profile("aws-smithy-eclipse-formatter.xml").get("org.eclipse.jdt.core.formatter.indentation.size"));
    }

    @Test void eclipseIsTheBuiltInProfileWithSpaces() {
        String eclipse = format(StandardFormatters.ECLIPSE);
        assertFalse(eclipse.contains("\t"));
        assertTrue(eclipse.contains("        case 0 -> System.out.println(\"none\");"), eclipse);
    }
}
