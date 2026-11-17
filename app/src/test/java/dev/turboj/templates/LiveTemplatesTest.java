package dev.turboj.templates;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveTemplatesTest {
    static LiveTemplateCatalog.Template builtin(String name) throws Exception {
        return LiveTemplateCatalog.builtins().stream().filter(t -> t.id().equals("Java/" + name)).findFirst().orElseThrow();
    }
    static JavaTemplateContext context(String marked) throws Exception {
        int at = marked.indexOf('|'); return JavaTemplateContext.inspect(marked.replace("|", ""), at, "C.java");
    }
    @Test void catalogContainsEveryDefinitionInThePinnedJavaGroups() throws Exception {
        var defaults = LiveTemplateCatalog.builtins(); assertEquals(51, defaults.size());
        assertEquals(51, defaults.stream().map(LiveTemplateCatalog.Template::id).distinct().count());
        var c = context("class C { void m() { | } }");
        for (var t : defaults) {
            var expansion = LiveTemplateExpansion.expand(t, c, "work();", "    ", 4, "\n");
            assertFalse(expansion.text().contains("$END$"), t.id());
            assertFalse(expansion.text().contains("$SELECTION$"), t.id());
            for (var group : expansion.fields()) for (var r : group)
                assertTrue(r.start() >= 0 && r.end() <= expansion.text().length(), t.id());
        }
    }
    @Test void soutLeavesCaretInTheCallAndNoFields() throws Exception {
        var expansion = LiveTemplateExpansion.expand(builtin("sout"), context("class C { void f(){ | } }"), "", "", 4, "\n");
        assertEquals("System.out.println();", expansion.text()); assertEquals("System.out.println(".length(), expansion.exit());
        assertTrue(expansion.fields().isEmpty());
    }
    @Test void fieldsLinkAndUnknownMacrosStayEditable() throws Exception {
        var c = context("class C { void f(int[] data){ | } }");
        var expansion = LiveTemplateExpansion.expand(builtin("itar"), c, "", "    ", 4, "\r\n");
        assertTrue(expansion.text().contains("data.length")); assertTrue(expansion.text().contains("int value = data[i]"));
        assertEquals(4, expansion.fields().get(expansion.names().indexOf("INDEX")).size());
        assertTrue(expansion.text().contains("\r\n        ")); assertTrue(expansion.unresolved().isEmpty());
        var unresolved = LiveTemplateExpansion.expand(builtin("lazy"), c, "", "", 4, "\n");
        assertTrue(unresolved.editable().contains("TYPE")); assertFalse(unresolved.unresolved().isEmpty());
        assertNull(LiveTemplateExpansion.evaluate("variableOfType(", c, Map.of(), "NAME"));
        assertNull(LiveTemplateExpansion.evaluate("futureProvider()", c, Map.of(), "NAME"));
    }
    @Test void contextExcludesLiteralsAndOtherScopes() throws Exception {
        for (String source : List.of("class C { void f(){ // |\n } }", "class C { String s = \"|\"; }", "class C { /* | */ }",
                "class C { String s = \"\"\"\n|\n\"\"\"; }")) assertTrue(context(source).contexts().isEmpty(), source);
        assertTrue(context("class C { void f(){ | } }").accepts(builtin("sout")));
        assertTrue(context("class C { void f(){ // comment before a statement\n | } }").accepts(builtin("sout")));
        assertFalse(context("class C { | }").accepts(builtin("sout")));
        assertFalse(context("class C { void f(){ Object x = |; } }").accepts(builtin("sout")));
        assertTrue(context("class C { | }").accepts(builtin("psvm")));
        assertTrue(context("class C { void f(){ if (true) {} /* next branch */ | } }").accepts(builtin("else-if")));
        assertFalse(context("class C { void f(){ while (true) {} | } }").accepts(builtin("else-if")));
        assertFalse(context("class C { void f(){ if (true) {} else {} | } }").accepts(builtin("else-if")));
        var c = context("class C { void other(int hidden){} void f(int visible){ {int gone=0;} | } }");
        assertEquals(Map.of("visible", "int"), c.variables()); assertEquals("f", c.methodName());
    }
    @Test void userDefinitionsOverrideDisableAndRejectBadInput() throws Exception {
        String json = """
                {"schemaVersion":1,"templates":[{"id":"Java/sout","abbreviation":"sout","body":"println($END$);","contexts":["JAVA_STATEMENT"],"disabled":true}]}
                """;
        var catalog = new LiveTemplateCatalog(LiveTemplateCatalog.builtins(), LiveTemplateCatalog.parse(json));
        assertEquals(51, catalog.templates().size()); assertTrue(catalog.matching("sout").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> LiveTemplateCatalog.parse(json.replace("$END$", "$END$ $END$")));
        assertThrows(IllegalArgumentException.class, () -> LiveTemplateCatalog.parse(json.replace("JAVA_STATEMENT", "ANYTHING")));
        assertThrows(IllegalArgumentException.class, () -> LiveTemplateCatalog.parse(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertTrue(catalog.startingWith("so").stream().noneMatch(t -> t.id().equals("Java/sout")));
        var defaults = new LiveTemplateCatalog(LiveTemplateCatalog.builtins(), List.of());
        assertEquals("sout", defaults.startingWith("sout").getFirst().abbreviation());
        assertTrue(defaults.startingWith("sou").stream().allMatch(t -> t.abbreviation().startsWith("sou")));
        assertTrue(defaults.startingWith("").isEmpty());
    }
    @Test void customLinkedFieldsSelectionAndLiteralDollar() throws Exception {
        var t = new LiveTemplateCatalog.Template("user/repeat", "repeat", "$NAME$ + $NAME$; $$ $SELECTION$$END$", List.of("JAVA_STATEMENT"), List.of(), false);
        var expanded = LiveTemplateExpansion.expand(t, context("class C { void f(){ | } }"), "😀", "", 4, "\n");
        assertEquals(" + ; $ 😀", expanded.text()); assertEquals(2, expanded.fields().getFirst().size());
        assertEquals(expanded.text().length(), expanded.exit());
    }
}
