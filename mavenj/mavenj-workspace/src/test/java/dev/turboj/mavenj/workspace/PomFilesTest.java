package dev.turboj.mavenj.workspace;

import java.nio.charset.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PomFilesTest {
    @TempDir
    Path root;

    @Test
    void preservesEncodingAndRejectsStaleEditsAndSymlinkEscapes() throws Exception {
        Path pom = root.resolve("pom.xml");
        String source = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><project><!-- caffè --></project>";
        Files.write(pom, source.getBytes(StandardCharsets.ISO_8859_1));
        var document = PomFiles.read(root, pom);
        var edit = PomEdits.addModule(document.text(), "module");
        PomFiles.apply(root, document, edit);
        assertEquals(edit.result(), Files.readString(pom, StandardCharsets.ISO_8859_1));
        assertTrue(Files.readString(pom, StandardCharsets.ISO_8859_1).contains("caffè"));
        assertThrows(IllegalStateException.class, () -> PomFiles.apply(root, document, edit));
        Path inside = Files.createDirectories(root.resolve("inside"));
        Files.createSymbolicLink(inside.resolve("pom.xml"), Path.of("/etc/hosts"));
        assertThrows(SecurityException.class, () -> PomFiles.read(inside, Path.of("pom.xml")));
    }

    @Test
    void preservesUtf16BomAndNonAsciiText() throws Exception {
        Path pom = root.resolve("pom.xml");
        String source = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><project><!-- 日本語 🧪 --></project>";
        Files.write(pom, source.getBytes(StandardCharsets.UTF_16));
        var document = PomFiles.read(root, pom);
        var edit = PomEdits.addModule(document.text(), "child");
        PomFiles.apply(root, document, edit);
        assertEquals(edit.result(), Files.readString(pom, StandardCharsets.UTF_16));
        assertArrayEquals(new byte[] {(byte) 0xfe, (byte) 0xff}, java.util.Arrays.copyOf(Files
                .readAllBytes(pom), 2));
    }

    @Test
    void preservesBomlessUtf16AndRejectsUnvalidatedPlanResults() throws Exception {
        Path pom = root.resolve("pom.xml");
        String source = "<?xml version='1.0' encoding='UTF-16LE'?><project><!-- 日本 --></project>";
        Files.write(pom, source.getBytes(StandardCharsets.UTF_16LE));
        var document = PomFiles.read(root, pom);
        assertEquals(source, document.text());
        var edit = PomEdits.addModule(source, "child");
        PomFiles.apply(root, document, edit);
        assertEquals(edit.result(), Files.readString(pom, StandardCharsets.UTF_16LE));
        assertEquals('<', Files.readAllBytes(pom)[0]);
        var current = PomFiles.read(root, pom);
        var invalid = new PomEdits.Plan(current.text(),
                "<!DOCTYPE project SYSTEM 'file:///etc/passwd'><project/>", java.util.List.of(),
                "forged");
        assertThrows(IllegalArgumentException.class, () -> PomFiles.apply(root, current, invalid));
        assertEquals(edit.result(), Files.readString(pom, StandardCharsets.UTF_16LE));
    }
}
