package dev.turboj.project;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.config.SafeFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceEncodingTest {
    @TempDir Path directory;
    @Test void retainedJavaEncodingSurvivesWorkspaceSwitch() throws Exception {
        Path source = directory.resolve("Accent.java");
        var loaded = new SourceEncoding.Encoding(StandardCharsets.ISO_8859_1, new byte[0]);
        var selected = SourceEncoding.select(null, source, loaded, null);
        assertEquals(StandardCharsets.ISO_8859_1, selected.charset());
        SafeFiles.atomicWrite(source, "class Accent { String text = \"caffè\"; }", selected.charset(), selected.bom());
        assertEquals("class Accent { String text = \"caffè\"; }", Files.readString(source, StandardCharsets.ISO_8859_1));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> SafeFiles.readSnapshot(source));
    }
    @Test void xmlSaveAsCarriesTheEncodingAndBom() throws Exception {
        Path destination = directory.resolve("copy.xml");
        var origin = new SourceEncoding.Encoding(StandardCharsets.UTF_16LE, new byte[] {(byte) 0xff, (byte) 0xfe});
        var selected = SourceEncoding.select(null, destination, null, origin);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><project><name>caffè 😀</name></project>";
        SafeFiles.atomicWrite(destination, xml, selected.charset(), selected.bom());
        var read = SafeFiles.readXmlSnapshot(destination);
        assertEquals(xml, read.text()); assertEquals(origin.charset(), read.encoding()); assertArrayEquals(origin.bom(), read.bom());
    }
}
