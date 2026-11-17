package dev.turboj.templates;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaTemplateTypesTest {
    @Test void validatesTypesWithoutAcceptingArbitraryTokenSequences() {
        for (String valid : new String[]{"int", "String[]", "java.util.Map<String, ? extends Number>", "List<int[]>",
                "Outer<T>.Inner<U>", "List<? super String[]>", "Αλφα", "Map < String , List < Integer > > []"})
            assertTrue(JavaTemplateTypes.type(valid), valid);
        for (String invalid : new String[]{"", "void", "var", "int int", "List<int>", "?", "String & Other", "List<>",
                "List<? extends int>", "int<T>", "String[", "String...", "A..B", "String;", "List<? Foo>"})
            assertFalse(JavaTemplateTypes.type(invalid), invalid);
    }
    @Test void namesAreSimpleIdentifiers() {
        for (String invalid : new String[]{"", "a.b", "int", "_", "2name", "a b"}) assertFalse(JavaTemplateTypes.name(invalid), invalid);
        assertTrue(JavaTemplateTypes.name("δείγμα"));
    }
}
