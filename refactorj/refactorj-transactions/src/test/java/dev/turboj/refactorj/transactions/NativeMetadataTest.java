package dev.turboj.refactorj.transactions;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NativeMetadataTest {
    @TempDir Path root;
    Path source;
    @BeforeEach void nativeFile() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
        root=root.toRealPath(); source=root.resolve("A.java"); Files.writeString(source,"class A { int old; }");
    }
    private void decorate() throws Exception {
        command("/bin/chmod","+a","everyone allow read",source.toString());
        command("/usr/bin/xattr","-w","dev.turboj.refactor-test","retained",source.toString());
    }
    private WorkspaceSnapshot snapshot() { return DiskWorkspace.open(root,List.of(root),List.of()).snapshot(); }
    private Plan edit(WorkspaceSnapshot snapshot) {
        int at=snapshot.source(source).text().indexOf("old");
        return new Plan("metadata",Operation.RENAME,snapshot,List.of(),List.of(new FileEdits(source,List.of(new TextEdit(at,at+3,"next")))),List.of(),List.of(),ValidationStatus.VALID,"Rename field");
    }
    private Plan remove(WorkspaceSnapshot snapshot,boolean rename) {
        var operation=new FileOperation(rename?FileOperationKind.MOVE:FileOperationKind.DELETE,source,rename?root.resolve("B.java"):null,null);
        return new Plan("metadata",Operation.RENAME,snapshot,List.of(),List.of(),List.of(operation),List.of(),ValidationStatus.VALID,"Remove source");
    }
    @Test void editUndoRedoAndRestartRetainNativeAclAndXattrs() throws Exception {
        decorate(); String acl=acl(); var mode=Files.getPosixFilePermissions(source);
        var store=DiskWorkspace.open(root,List.of(root),List.of()); var transactions=new WorkspaceTransactions(store);
        var applied=transactions.apply(edit(store.snapshot()),Cancellation.NONE);
        assertEquals("class A { int next; }",Files.readString(source)); metadata(acl,mode);
        var restarted=new WorkspaceTransactions(DiskWorkspace.open(root,List.of(root),List.of()));
        restarted.undo(applied.transactionId(),Cancellation.NONE);
        assertEquals("class A { int old; }",Files.readString(source)); metadata(acl,mode);
        restarted.redo(applied.transactionId(),Cancellation.NONE);
        assertEquals("class A { int next; }",Files.readString(source)); metadata(acl,mode);
    }
    @Test void deleteAndRenameWithNonJournaledMetadataRefuseBeforeMutation() throws Exception {
        decorate(); String acl=acl(); var mode=Files.getPosixFilePermissions(source);
        for(boolean rename:List.of(false,true)) {
            var store=DiskWorkspace.open(root,List.of(root),List.of());
            assertThrows(TransactionException.class,()->new WorkspaceTransactions(store).apply(remove(store.snapshot(),rename),Cancellation.NONE));
            assertEquals("class A { int old; }",Files.readString(source)); metadata(acl,mode);
            assertFalse(Files.exists(root.resolve("B.java")));
        }
    }
    @Test void metadataAddedImmediatelyBeforeDeletionIsRetained() throws Exception {
        var initial=snapshot();
        var store=new DiskWorkspace(initial,(index,path)->{
            try { decorate(); } catch(Exception failure) { throw new java.io.IOException(failure); }
        });
        assertThrows(TransactionException.class,()->new WorkspaceTransactions(store).apply(remove(store.snapshot(),false),Cancellation.NONE));
        assertEquals("class A { int old; }",Files.readString(source)); assertFalse(acl().isBlank());
        assertEquals("retained",command("/usr/bin/xattr","-p","dev.turboj.refactor-test",source.toString()).strip());
    }
    @Test void interruptedMultiFileEditRollsBackWithoutStrippingNativeMetadata() throws Exception {
        decorate(); String acl=acl(); var mode=Files.getPosixFilePermissions(source);
        Path other=root.resolve("B.java"); Files.writeString(other,"class B { int old; }");
        var initial=snapshot(); var store=new DiskWorkspace(initial,(index,path)->{if(index==1)throw new java.io.IOException("controlled interruption");});
        var changes=List.of(source,other).stream().map(path->{int at=initial.source(path).text().indexOf("old");return new FileEdits(path,List.of(new TextEdit(at,at+3,"next")));}).toList();
        var plan=new Plan("metadata",Operation.RENAME,initial,List.of(),changes,List.of(),List.of(),ValidationStatus.VALID,"Rename fields");
        assertThrows(TransactionException.class,()->new WorkspaceTransactions(store).apply(plan,Cancellation.NONE));
        assertEquals("class A { int old; }",Files.readString(source)); metadata(acl,mode);
        assertEquals("class B { int old; }",Files.readString(other));
    }
    @Test void concurrentNativeMetadataAtReplacementRefusesEditAndRetainsExternalAttributes()throws Exception {
        command("/usr/bin/xattr","-w","dev.turboj.refactor-test","first",source.toString());
        for(boolean acl:List.of(true,false)) {
            var initial=snapshot();
            var store=new DiskWorkspace(initial,new DiskWorkspace.FaultInjector(){
                public void beforeFile(int index,Path path){}
                public void beforeReplacement(Path path)throws java.io.IOException {
                    try {
                        if(acl)command("/bin/chmod","+a","everyone allow read",path.toString());
                        else command("/usr/bin/xattr","-w","dev.turboj.refactor-test","second",path.toString());
                    }catch(Exception error){throw new java.io.IOException(error);}
                }
            });
            var failure=assertThrows(TransactionException.class,()->new WorkspaceTransactions(store).apply(edit(store.snapshot()),Cancellation.NONE));
            assertTrue(failure.getCause().getMessage().contains("metadata changed"),failure.toString());
            assertEquals("class A { int old; }",Files.readString(source)); assertFalse(acl().isBlank());
            assertEquals(acl?"first":"second",command("/usr/bin/xattr","-p","dev.turboj.refactor-test",source.toString()).strip());
        }
    }
    private void metadata(String acl,Set<java.nio.file.attribute.PosixFilePermission> mode)throws Exception {
        assertEquals(acl,acl()); assertEquals(mode,Files.getPosixFilePermissions(source));
        assertEquals("retained",command("/usr/bin/xattr","-p","dev.turboj.refactor-test",source.toString()).strip());
        try(var files=Files.list(root)){assertTrue(files.noneMatch(path->path.getFileName().toString().startsWith(".refactorj-")));}
    }
    private String acl()throws Exception{return command("/bin/ls","-le",source.toString()).lines().filter(line->line.matches("\\s*\\d+:.*")).collect(java.util.stream.Collectors.joining("\n"));}
    private static String command(String... args)throws Exception{
        var process=new ProcessBuilder(args).redirectErrorStream(true).start();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);return output;
    }
}
