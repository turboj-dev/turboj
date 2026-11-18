package dev.turboj.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryAndSaveTest {
    @TempDir Path root;
    @Test void macNativeAclAndExtendedAttributesSurviveARealAtomicSave() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
        Path source = root.resolve("Native.java"); Files.writeString(source,"class Native { int old; }");
        nativeCommand("/bin/chmod","+a","everyone allow read",source.toString());
        nativeCommand("/usr/bin/xattr","-w","dev.turboj.save-test","retained",source.toString());
        var mode = Files.getPosixFilePermissions(source);
        String acl = nativeAcl(source);
        assertFalse(acl.isBlank());
        SafeFiles.atomicWriteIfUnchanged(source,"class Native {}",SafeFiles.fingerprint(source));
        assertEquals("class Native {}",Files.readString(source));
        assertEquals(acl,nativeAcl(source));
        assertEquals(mode,Files.getPosixFilePermissions(source));
        assertEquals("retained",nativeCommand("/usr/bin/xattr","-p","dev.turboj.save-test",source.toString()).strip());
        try (var files = Files.list(root)) { assertEquals(List.of(source),files.toList()); }
    }

    @Test void macNativeDeniedWriteFailsClosedAndKeepsOriginalMetadata() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
        Path source = root.resolve("Protected.java"); Files.writeString(source,"original");
        nativeCommand("/bin/chmod","+a","everyone deny write",source.toString());
        try {
            String acl = nativeAcl(source);
            assertThrows(java.io.IOException.class,()->SafeFiles.atomicWriteIfUnchanged(source,"replacement",SafeFiles.fingerprint(source)));
            assertEquals("original",Files.readString(source)); assertEquals(acl,nativeAcl(source));
            try (var files=Files.list(root)) { assertEquals(List.of(source),files.toList()); }
        } finally { nativeCommand("/bin/chmod","-N",source.toString()); }
    }

    @Test void concurrentNativeAclAndAttributeChangesRejectSaveWithoutChangingSource() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
        Path source = root.resolve("Concurrent.java"); Files.writeString(source,"original");
        nativeCommand("/usr/bin/xattr","-w","dev.turboj.save-test","first",source.toString());
        for (boolean acl : List.of(true,false)) {
            var failure = assertThrows(java.io.IOException.class,()->SafeFiles.write(source,"replacement",StandardCharsets.UTF_8,
                    new byte[0],SafeFiles.fingerprint(source),true,()-> {
                        try {
                            if (acl) nativeCommand("/bin/chmod","+a","everyone allow read",source.toString());
                            else nativeCommand("/usr/bin/xattr","-w","dev.turboj.save-test","second",source.toString());
                        } catch(Exception error) { throw new java.io.IOException(error); }
                    }));
            assertTrue(failure.getMessage().contains("metadata changed"),failure.toString());
            assertEquals("original",Files.readString(source)); assertFalse(nativeAcl(source).isBlank());
            assertEquals(acl?"first":"second",nativeCommand("/usr/bin/xattr","-p","dev.turboj.save-test",source.toString()).strip());
            try(var files=Files.list(root)){assertEquals(List.of(source),files.toList());}
        }
    }

    @Test void concurrentPermissionChangeRejectsSaveAndUnknownNativeAttributeNamesFailClosed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
        Path source = root.resolve("Permissions.java"); Files.writeString(source,"original");
        var mode = new HashSet<>(Files.getPosixFilePermissions(source)); mode.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        var changed = assertThrows(java.io.IOException.class,()->SafeFiles.write(source,"replacement",StandardCharsets.UTF_8,
                new byte[0],SafeFiles.fingerprint(source),true,()->Files.setPosixFilePermissions(source,mode)));
        assertTrue(changed.getMessage().contains("metadata changed"));
        assertEquals(mode,Files.getPosixFilePermissions(source)); assertEquals("original",Files.readString(source));
        nativeCommand("/usr/bin/xattr","-w","dev.turboj.unknown name","retained",source.toString());
        var unknown = assertThrows(java.io.IOException.class,()->SafeFiles.atomicWriteIfUnchanged(source,"replacement",SafeFiles.fingerprint(source)));
        assertTrue(unknown.getMessage().contains("Unsupported native attribute name"));
        assertEquals("original",Files.readString(source));
        assertEquals("retained",nativeCommand("/usr/bin/xattr","-p","dev.turboj.unknown name",source.toString()).strip());
        try(var files=Files.list(root)){assertEquals(List.of(source),files.toList());}
    }

    private static String nativeAcl(Path file) throws Exception {
        return nativeCommand("/bin/ls","-le",file.toString()).lines().filter(line->line.matches("\\s*\\d+:.*"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    private static String nativeCommand(String... args) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),output); return output;
    }
    SessionRecovery.Entry entry(String name, String source, boolean dirty) {
        return new SessionRecovery.Entry(root.resolve(name).toString(), root.resolve(name).toString(), source,
                HexFormat.of().formatHex(SafeFiles.utf8Fingerprint(source)), HexFormat.of().formatHex(SafeFiles.utf8Fingerprint("disk")),
                dirty, source.length(), 0, 2, 3, "UTF-8", "");
    }
    SessionRecovery.Snapshot snapshot() {
        var a = entry("A.java", "// café 😀\r\nclass A {}", true);
        var b = entry("B.java", "class B {}", false);
        return new SessionRecovery.Snapshot(2, a.key(), List.of(a,b));
    }
    @Test void liveInstancesAreNotOfferedAndOneShutdownCannotEraseAnotherSession() throws Exception {
        var one = new SessionRecovery(root); var two = new SessionRecovery(root);
        one.write(snapshot()); two.write(snapshot());
        assertTrue(one.claimPrevious().isEmpty()); assertTrue(two.claimPrevious().isEmpty());
        one.finish(); two.close();
        try (var next = new SessionRecovery(root)) {
            var claims = next.claimPrevious(); assertEquals(1, claims.size());
            try (var claim = claims.getFirst()) { assertEquals(snapshot(), claim.snapshot()); }
        }
    }
    @Test void abandonedAllBufferSnapshotPreservesPathsSelectionEncodingAndDirtyState() throws Exception {
        var owner = new SessionRecovery(root); owner.write(snapshot()); owner.close();
        try (var first = new SessionRecovery(root); var second = new SessionRecovery(root)) {
            var claim = first.claimPrevious().getFirst();
            assertEquals(snapshot(), claim.snapshot());
            assertTrue(second.claimPrevious().isEmpty());
            claim.close();
            try (var retry = second.claimPrevious().getFirst()) {
                second.write(retry.snapshot()); retry.discard();
            }
            assertTrue(first.claimPrevious().isEmpty());
        }
    }
    @Test void invalidSnapshotRetainsPriorDurableSessionAndRejectsCorruption() throws Exception {
        var owner = new SessionRecovery(root); owner.write(snapshot());
        assertThrows(IllegalArgumentException.class, () -> new SessionRecovery.Snapshot(2,"missing",snapshot().entries()));
        owner.close();
        Path manifest;
        try (var files = Files.list(root.resolve("v2"))) { manifest = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("class B {}", "class C {}"));
        try (var next = new SessionRecovery(root)) {
            assertTrue(next.claimPrevious().isEmpty()); assertEquals(1, next.warnings().size());
            assertTrue(Files.exists(manifest));
            Files.writeString(manifest, original);
            try (var claim = next.claimPrevious().getFirst()) { assertEquals(snapshot(), claim.snapshot()); }
        }
    }
    @Test void boundedStreamingFailureKeepsThePreviousAtomicManifestAndRemovesItsTemporary() throws Exception {
        try (var owner = new SessionRecovery(root)) {
            owner.write(snapshot());
            Path manifest;
            try (var files = Files.list(root.resolve("v2"))) { manifest = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
            byte[] previous = Files.readAllBytes(manifest);
            assertThrows(java.io.IOException.class,() -> owner.write(snapshot(),64));
            assertArrayEquals(previous,Files.readAllBytes(manifest));
            try (var files = Files.list(root.resolve("v2"))) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        }
        try (var next = new SessionRecovery(root); var claim = next.claimPrevious().getFirst()) {
            assertEquals(snapshot(),claim.snapshot());
        }
    }
    @Test void legacyRecoveryMigratesOnlyAfterDurableAcceptance() throws Exception {
        Files.writeString(root.resolve("buffer.java"),"legacy");
        try (var next = new SessionRecovery(root)) {
            try (var claim = next.claimPrevious().getFirst()) {
                assertNull(claim.snapshot().entries().getFirst().file());
                assertTrue(claim.snapshot().entries().getFirst().dirty());
            }
            assertTrue(Files.exists(root.resolve("buffer.java")));
            try (var claim = next.claimPrevious().getFirst()) { next.write(claim.snapshot()); claim.discard(); }
            assertFalse(Files.exists(root.resolve("buffer.java")));
        }
    }
    @Test void corruptLegacyRecoveryDoesNotHideValidSessionsAndItsLockIsReleased() throws Exception {
        try (var owner = new SessionRecovery(root)) { owner.write(snapshot()); }
        Path legacy = root.resolve("buffer.java");
        byte[] corrupt = {(byte)0xc3,(byte)0x28}; Files.write(legacy,corrupt);
        try (var next = new SessionRecovery(root)) {
            var claims = next.claimPrevious();
            assertEquals(1,claims.size()); assertEquals(1,next.warnings().size());
            assertTrue(next.warnings().getFirst().contains("buffer.java"));
            assertArrayEquals(corrupt,Files.readAllBytes(legacy));
            try (var valid = claims.getFirst()) {
                assertEquals(snapshot(),valid.snapshot());
                Files.writeString(legacy,"repaired legacy source");
                try (var repaired = next.claimPrevious().getFirst()) {
                    assertEquals("repaired legacy source",repaired.snapshot().entries().getFirst().text());
                    assertTrue(next.warnings().isEmpty());
                }
            }
        }
    }
    @Test void rejectsEditBetweenPreparationAndReplaceWithoutDiscardingEitherSource() throws Exception {
        Path file = root.resolve("A.java"); Files.writeString(file,"before");
        byte[] expected = SafeFiles.fingerprint(file);
        var failure = assertThrows(java.io.IOException.class, () -> SafeFiles.write(file,"editor", StandardCharsets.UTF_8,
                new byte[0], expected, true, () -> Files.writeString(file,"external")));
        assertTrue(failure.getMessage().contains("before replacement"));
        assertEquals("external",Files.readString(file));
        try (var files = Files.list(root)) { assertEquals(1,files.count()); }
    }
    @Test void newFileAndRetargetedLinkConflictsArePreserved() throws Exception {
        Path absent = root.resolve("new.java");
        assertThrows(java.io.IOException.class, () -> SafeFiles.write(absent,"editor",StandardCharsets.UTF_8,new byte[0],null,true,
                () -> Files.writeString(absent,"external")));
        assertEquals("external",Files.readString(absent));
        Path a=root.resolve("A.java"), b=root.resolve("B.java"), link=root.resolve("Link.java");
        Files.writeString(a,"same"); Files.writeString(b,"same"); Files.createSymbolicLink(link,a);
        assertThrows(java.io.IOException.class, () -> SafeFiles.write(link,"editor",StandardCharsets.UTF_8,new byte[0],SafeFiles.fingerprint(a),true,
                () -> { Files.delete(link); Files.createSymbolicLink(link,b); }));
        assertEquals("same",Files.readString(a)); assertEquals("same",Files.readString(b));
    }
    @Test void aRealProcessCrashReleasesOwnershipButKeepsDurableSource() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), OwnerProcess.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try (var next = new SessionRecovery(root)) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(root.resolve("ready")), Files.readString(root.resolve("child.log")));
            assertTrue(next.claimPrevious().isEmpty());
            child.destroyForcibly(); assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            try (var claim = next.claimPrevious().getFirst()) { assertEquals("unsaved", claim.snapshot().entries().getFirst().text()); }
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    public static final class OwnerProcess {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            var recovery = new SessionRecovery(root);
            String key = root.resolve("A.java").toString();
            recovery.write(new SessionRecovery.Snapshot(2, key, List.of(new SessionRecovery.Entry(key, key, "unsaved",
                    HexFormat.of().formatHex(SafeFiles.utf8Fingerprint("unsaved")), null, true, 7, 0, 0, 0, "UTF-8", ""))));
            Files.writeString(root.resolve("ready"), "ready");
            System.in.read();
        }
    }
    @Test void failureAfterTemporaryWriteKeepsPreviousBytesAndCleansTemporaryFile() throws Exception {
        Path file = root.resolve("A.java"); Files.writeString(file,"original");
        assertThrows(java.io.IOException.class, () -> SafeFiles.write(file,"replacement",StandardCharsets.UTF_8,new byte[0],SafeFiles.fingerprint(file),true,
                () -> { throw new java.io.IOException("injected storage interruption"); }));
        assertEquals("original",Files.readString(file));
        try (var paths = Files.list(root)) { assertEquals(List.of(file),paths.toList()); }
    }
}
