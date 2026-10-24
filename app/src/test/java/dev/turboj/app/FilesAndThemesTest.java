package dev.turboj.app;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.config.SafeFiles;
import dev.turboj.ui.themes.Theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class FilesAndThemesTest {
    @TempDir Path directory;

    @Test
    void utf8AndLineEndingsSurviveAtomicSave() throws Exception {
        Path file = directory.resolve("Hello.java");
        String text = "// caffè 😀\r\nclass Hello {}\r\n";
        SafeFiles.atomicWrite(file, text);
        assertEquals(text, SafeFiles.readUtf8(file));
        byte[] before = SafeFiles.fingerprint(file);
        SafeFiles.atomicWrite(file, text.replace("Hello", "World"));
        assertFalse(java.util.Arrays.equals(before, SafeFiles.fingerprint(file)));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void invalidUtf8IsRejectedWithoutReplacementCharacters() throws Exception {
        Path file = directory.resolve("broken.java");
        Files.write(file, new byte[] {(byte) 0xc0, (byte) 0xaf});
        assertThrows(
                java.nio.charset.CharacterCodingException.class, () -> SafeFiles.readUtf8(file));
    }

    @Test
    void readSnapshotAndReturnedSaveHashIdentifyTheExactBytes() throws Exception {
        Path file = directory.resolve("Snapshot.java");
        String source = "// café\r\nclass Snapshot {}\r\n";
        byte[] written = SafeFiles.atomicWrite(file, source);
        var loaded = SafeFiles.readSnapshot(file);
        assertEquals(source, loaded.text());
        assertArrayEquals(written, loaded.fingerprint());
        Files.writeString(file, "external edit");
        assertFalse(java.util.Arrays.equals(written, SafeFiles.fingerprint(file)));
        assertArrayEquals(written, loaded.fingerprint());
        byte[] exposed = loaded.fingerprint();
        exposed[0] ^= 1;
        assertArrayEquals(written, loaded.fingerprint());
    }

    @Test
    void conditionalSaveRejectsExternalEditsWithoutDestroyingThem() throws Exception {
        Path file = directory.resolve("Conflict.java");
        byte[] expected = SafeFiles.atomicWrite(file, "original");
        Files.writeString(file, "external edit");
        assertThrows(
                java.io.IOException.class,
                () -> SafeFiles.atomicWriteIfUnchanged(file, "editor edit", expected));
        assertEquals("external edit", Files.readString(file));
        byte[] fresh = SafeFiles.fingerprint(file);
        SafeFiles.atomicWriteIfUnchanged(file, "accepted", fresh);
        assertEquals("accepted", Files.readString(file));
    }

    @Test
    void atomicSavePreservesSymlinkAndPermissions() throws Exception {
        Path actual = directory.resolve("real.java"), link = directory.resolve("link.java");
        Files.writeString(actual, "old");
        Files.createSymbolicLink(link, actual);
        var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(actual, perms);
        SafeFiles.atomicWrite(link, "new");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(actual));
        assertEquals(perms, Files.getPosixFilePermissions(actual));
    }

    @Test
    void allBundledThemesValidateAndFallbackIsSafe() throws Exception {
        assertEquals("Turbo Classic", Theme.bundled("classic").name());
        assertEquals("Turbo Classic", Theme.bundled("amber").name());
        assertEquals("Modern Dark", Theme.bundled("modern").name());
        assertEquals("Modern Light", Theme.bundled("light").name());
        assertArrayEquals(new String[] {"classic", "dark", "light"}, Theme.BUNDLED);
        assertEquals("Modern Dark", Theme.bundled("dark").name());
        assertTrue(Theme.bundled("light").background().getRed() > 240);
        assertEquals(0x0000aa, Theme.bundled("classic").background().getRGB() & 0xffffff);
        assertEquals("Turbo Classic", Theme.bundled("missing").name());
        Path broken = directory.resolve("broken.json");
        Files.writeString(broken, "{\"schemaVersion\":999}");
        assertThrows(java.io.IOException.class, () -> Theme.load(broken));
    }
}
