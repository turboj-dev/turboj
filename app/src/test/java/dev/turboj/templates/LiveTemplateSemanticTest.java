package dev.turboj.templates;

import dev.turboj.intelligence.JdtJavaIntelligenceService;
import dev.turboj.refactorj.api.JavaIntelligence;
import dev.turboj.refactorj.api.Model.JavaProjectConfiguration;
import dev.turboj.formatting.StandardFormatters;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LiveTemplateSemanticTest {
    @TempDir Path root;
    private JavaIntelligence.Context context(String source, String level, boolean preview) {
        var project = new JavaProjectConfiguration("main", List.of(root), List.of(), List.of(), List.of(),
                level, level, level, Path.of(System.getProperty("java.home")), "UTF-8", preview);
        return new JavaIntelligence.Context(root.resolve("C.java"), source, 1, Map.of(), List.of(root), List.of(), 1, List.of(), List.of(project));
    }
    private JavaTemplateContext inspect(JdtJavaIntelligenceService service, String marked) throws Exception {
        int at = marked.indexOf('|'); String source = marked.replace("|", "");
        return JavaTemplateContext.inspect(source, at, "C.java").resolved(service.templateSemantics(context(source, "25", false), at, at));
    }
    @Test void expectedTypesConsumerAndVisibleVariablesUseResolvedBindings() throws Exception {
        try (var service = new JdtJavaIntelligenceService()) {
            var c = inspect(service, "class C { int instance; static int shared; static void m(java.util.ArrayList<String> names){ {int hidden=1;} java.util.function.Consumer<String> action = |; } }");
            assertTrue(c.contexts().contains("JAVA_CONSUMER"));
            assertFalse(c.variables().containsKey("hidden")); assertFalse(c.variables().containsKey("instance"));
            assertTrue(c.variables().containsKey("shared"));
            assertEquals("names", LiveTemplateExpansion.evaluate("variableOfType(\"java.util.Collection\")", c, Map.of(), "VALUE"));
            assertEquals("java.lang.String", LiveTemplateExpansion.evaluate("iterableComponentType(LIST)", c, Map.of("LIST", "names"), "TYPE"));
            assertFalse(inspect(service, "class C { void m(){ String value = |; } }").contexts().contains("JAVA_CONSUMER"));
            assertEquals("java.lang.String", LiveTemplateExpansion.evaluate("componentTypeOf(expectedType())",
                    inspect(service, "class C { String[] m(){ return |; } }"), Map.of(), "TYPE"));
        }
    }
    @Test void ioAndStructuredConcurrencyRespectTheActualProjectLevelAndPreviewFlag() throws Exception {
        String source = "class C { void m(){  } }"; int at = source.indexOf("  ") + 1;
        try (var service = new JdtJavaIntelligenceService()) {
            var legacy = service.templateSemantics(context(source, "17", false), at, at);
            assertFalse(legacy.languageIO()); assertFalse(legacy.structuredConcurrency());
            var current = service.templateSemantics(context(source, "25", false), at, at);
            assertTrue(current.languageIO()); assertFalse(current.structuredConcurrency());
            assertTrue(service.templateSemantics(context(source, "25", true), at, at).structuredConcurrency());
            var expanded = LiveTemplateExpansion.expand(LiveTemplatesTest.builtin("sts"),
                    JavaTemplateContext.inspect(source, at, "C.java"), "", "", 4, "\n");
            Path file = root.resolve("C.java"); Files.writeString(file, source.substring(0, at) + expanded.text() + source.substring(at));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--enable-preview", "--release", "25", "-d", root.toString(), file.toString()));
        }
    }
    @Test void genericIteratorMacrosResolveRealReturnTypesAndLeaveAmbiguityEditable() throws Exception {
        try (var service = new JdtJavaIntelligenceService()) {
            var c = inspect(service, "class C { void m(java.util.Iterator<String> iterator){ | } }");
            var expanded = LiveTemplateExpansion.expand(LiveTemplatesTest.builtin("itit"), c, "", "", 4, "\n");
            assertTrue(expanded.text().contains("java.lang.String value =  iterator.next()"), expanded.text());
            assertTrue(expanded.unresolved().isEmpty(), expanded.unresolved().toString());
            var primitive = inspect(service, "class C { void m(int primitive){ | } }");
            assertNull(LiveTemplateExpansion.evaluate("variableOfType(\"Object\")", primitive, Map.of(), "VAR"));
            assertNull(LiveTemplateExpansion.evaluate("subtypes(unknown())", c, Map.of(), "TYPE"));
        }
    }
    @Test void selectedExpressionTypeSupportsSurroundIteration() throws Exception {
        String source = "class C { void m(java.util.List<String> names){ names } }";
        int start = source.lastIndexOf("names");
        try (var service = new JdtJavaIntelligenceService()) {
            var facts = service.templateSemantics(context(source, "25", false), start, start + 5);
            var c = JavaTemplateContext.inspect(source.substring(0, start) + source.substring(start + 5), start, "C.java").resolved(facts);
            var expansion = LiveTemplateExpansion.expand(LiveTemplatesTest.builtin("I"), c, "names", "", 4, "\n");
            assertTrue(expansion.text().contains("java.lang.String value : names"), expansion.text());
        }
    }
    @Test void qualifiedNamesShortenWithImportsWithoutChangingConflictingOrUnrelatedCode() throws Exception {
        String source = "import java.awt.List;\nclass C { String literal=\"java.util.ArrayList\"; void m(){ java.util.ArrayList<String> x=new java.util.ArrayList<>(); java.util.List<String> y=x; } }";
        int start = source.indexOf("java.util.ArrayList<String>");
        try (var service = new JdtJavaIntelligenceService()) {
            var edits = service.shortenTemplateNames(context(source, "25", false), start, source.length() - 3);
            String transformed = apply(source, edits);
            assertTrue(transformed.contains("import java.util.ArrayList;"), transformed);
            assertTrue(transformed.contains("ArrayList<String> x=new ArrayList<>()"), transformed);
            assertTrue(transformed.contains("java.util.List<String> y=x"), transformed);
            assertTrue(transformed.contains("\"java.util.ArrayList\""), transformed);
            Path file = root.resolve("C.java"); Files.writeString(file, transformed);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", root.toString(), file.toString()));
        }
    }
    @Test void templateFlagsRoundTripAndTransformMapsCaretPastImportsAndFormatting() throws Exception {
        assertTrue(LiveTemplatesTest.builtin("itit").reformat()); assertTrue(LiveTemplatesTest.builtin("itit").shortenNames());
        assertFalse(LiveTemplatesTest.builtin("St").reformat());
        String source = "class C { void m(){ java.util.List<String> names=new java.util.ArrayList<>();\nSystem.out.println(names); } }";
        int start = source.indexOf("java.util.List"), end = source.indexOf(" } }");
        try (var service = new JdtJavaIntelligenceService()) {
            var result = LiveTemplateTransform.prepare(service, context(source, "25", false), LiveTemplatesTest.builtin("itit"),
                    start, end, source.indexOf("(names)") + 1, source.indexOf("(names)") + 1, StandardFormatters.of("javafmt").engine());
            String transformed = apply(source, result.edits().stream().map(e -> new JavaIntelligence.TextEdit(e.start(), e.end(), e.text())).toList());
            assertTrue(transformed.contains("import java.util.List;"), transformed);
            assertTrue(transformed.substring(result.caret()).startsWith("names"), transformed + " caret=" + result.caret());
            assertEquals(result.caret(), result.anchor());
        }
    }
    private static String apply(String source, List<JavaIntelligence.TextEdit> edits) {
        var text = new StringBuilder(); int from = 0;
        for (var edit : edits) { text.append(source, from, edit.start()).append(edit.text()); from = edit.end(); }
        return text.append(source, from, source.length()).toString();
    }
}
