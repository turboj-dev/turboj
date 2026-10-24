package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.config.SafeFiles;
import dev.turboj.editor.highlighting.JavaLexer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XmlEditingTest {
    @TempDir Path directory;

    @Test void pomHighlightingPreservesMultilineCommentsAndProperties() {
        var first = XmlLexer.lex("<!-- project configuration", JavaLexer.State.NORMAL);
        assertEquals(JavaLexer.State.BLOCK_COMMENT, first.endState());
        var second = XmlLexer.lex("comment --> <version>${revision}</version>", first.endState());
        assertEquals(JavaLexer.State.NORMAL, second.endState());
        assertTrue(second.spans().stream().anyMatch(span -> span.kind() == JavaLexer.Kind.COMMENT));
        assertTrue(second.spans().stream().anyMatch(span -> span.kind() == JavaLexer.Kind.ANNOTATION));
        assertTrue(second.spans().stream().anyMatch(span -> span.kind() == JavaLexer.Kind.KEYWORD));
        var cdata = XmlLexer.lex("<![CDATA[something <", JavaLexer.State.NORMAL);
        assertEquals(JavaLexer.State.TEXT_BLOCK, cdata.endState());
        assertEquals(JavaLexer.State.NORMAL, XmlLexer.lex("still data ]]>", cdata.endState()).endState());
    }

    @Test void xmlDiagnosticsArePreciseAndNeverResolveEntities() throws Exception {
        assertTrue(XmlDiagnostics.inspect("<project><modelVersion>4.0.0</modelVersion></project>").isEmpty());
        var broken = XmlDiagnostics.inspect("<project>\n <name>Test</other>\n</project>");
        assertEquals(1, broken.size()); assertEquals(2, broken.getFirst().line());
        Path secret = directory.resolve("never-read.txt"); Files.writeString(secret, "sensitive fixture");
        String external = "<!DOCTYPE project [<!ENTITY ext SYSTEM '" + secret.toUri() + "'>]><project>&ext;</project>";
        var rejected = XmlDiagnostics.inspect(external);
        assertEquals(1, rejected.size());
        assertTrue(rejected.getFirst().message().contains("DOCTYPE"));
        assertFalse(rejected.getFirst().message().contains("sensitive fixture"));
    }

    @Test void malformedPomCanBeOpenedAndSavedWithOriginalEncodingAndBom() throws Exception {
        Path pom = directory.resolve("pom.xml");
        String malformed = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><project><name>caffè 😀</name>";
        byte[] bom = {(byte) 0xff, (byte) 0xfe};
        SafeFiles.atomicWrite(pom, malformed, StandardCharsets.UTF_16LE, bom);
        var snapshot = SafeFiles.readXmlSnapshot(pom);
        assertEquals(malformed, snapshot.text()); assertEquals(StandardCharsets.UTF_16LE, snapshot.encoding()); assertArrayEquals(bom, snapshot.bom());
        String repaired = malformed + "</project>";
        SafeFiles.atomicWriteIfUnchanged(pom, repaired, snapshot.fingerprint(), snapshot.encoding(), snapshot.bom());
        assertEquals(repaired, SafeFiles.readXmlSnapshot(pom).text());
        assertTrue(XmlDiagnostics.inspect(repaired).isEmpty());
        Files.writeString(pom, "external edit");
        assertThrows(java.io.IOException.class, () -> SafeFiles.atomicWriteIfUnchanged(pom, repaired, snapshot.fingerprint(), snapshot.encoding(), snapshot.bom()));
        assertEquals("external edit", Files.readString(pom));
    }
}
