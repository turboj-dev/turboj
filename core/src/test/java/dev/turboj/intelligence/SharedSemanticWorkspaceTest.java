package dev.turboj.intelligence;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaIntelligence.Context;
import dev.turboj.refactorj.api.Model.Location;
import dev.turboj.refactorj.api.Requests.Rename;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedSemanticWorkspaceTest {
    @TempDir Path root;
    @Test void sameModelServesCompletionAndRefactoringWithoutWritingUnsavedSource()throws Exception{
        Path file=root.resolve("Main.java");String disk="class Main {}";Files.writeString(file,disk);
        String source="class Main { int value(){return 1;} int f(){return value();} }";
        var context=new Context(file,source,2,Map.of(),List.of(root),List.of(),1);
        try(var service=new JdtJavaIntelligenceService()){
            assertNotNull(service.resolveSymbol(context,source.indexOf("value()")));
            var first=service.sharedRefactorJ(context);assertSame(first,service.sharedRefactorJ(context));
            var plan=first.prepare(new Rename(new Location(file,source.indexOf("value()")),"number"),Cancellation.NONE);assertTrue(plan.valid(),plan.diagnostics().toString());
            assertEquals(source,first.workspace().snapshot().source(file).text());assertEquals(disk,Files.readString(file));
            assertTrue(service.resolveSymbol(context,source.lastIndexOf("value()")).signature().contains("value"));
        }
    }
    @Test void excludesGeneratedTreesAndUnrelatedOpenBuffers()throws Exception{
        Path project=Files.createDirectories(root.resolve("project")),other=Files.createDirectories(root.resolve("other"));
        Files.createDirectories(project.resolve("target"));Files.writeString(project.resolve("target/Generated.java"),"class Generated {}");
        String source="class Main {}";var context=new Context(project.resolve("Main.java"),source,1,Map.of(other.resolve("Private.java"),"class Private {}"),List.of(project),List.of(),1);
        try(var service=new JdtJavaIntelligenceService()){var workspace=service.sharedRefactorJ(context).workspace().snapshot();assertEquals(project,workspace.root());assertEquals(List.of(project.resolve("Main.java")),workspace.sources().stream().map(s->s.file()).toList());}
    }
    @Test void discoversFilesAddedInsidePreviouslyEmptyNestedDirectory()throws Exception{
        Path nested=Files.createDirectories(root.resolve("demo/empty"));String source="class Main {}";var context=new Context(root.resolve("Main.java"),source,1,Map.of(),List.of(root),List.of(),1);
        try(var service=new JdtJavaIntelligenceService()){assertEquals(1,service.sharedRefactorJ(context).workspace().snapshot().sources().size());long old=Files.getLastModifiedTime(nested).toMillis();Path added=nested.resolve("Added.java");Files.writeString(added,"package demo.empty; class Added {}");Files.setLastModifiedTime(nested,java.nio.file.attribute.FileTime.fromMillis(old+1000));assertTrue(service.sharedRefactorJ(context).workspace().snapshot().sources().stream().anyMatch(s->s.file().equals(added)));}
    }
    @Test void junitTargetUsesSharedBindingsAndBinaryNestedName(){
        String source="class Outer { class Inner { void sample(int count, String[] names) {} } }";var context=new Context(root.resolve("Outer.java"),source,1,Map.of(),List.of(root),List.of(),1);
        try(var service=new JdtJavaIntelligenceService()){var target=service.testTarget(context,source.indexOf("sample"));assertNotNull(target);assertEquals("Outer$Inner",target.className());assertEquals("sample",target.methodName());assertEquals("int,java.lang.String[]",target.parameterTypes());}
    }
    @Test void untitledBufferCompletesWithoutScanningCwdAndCanRenameWithoutSaving()throws Exception{
        for(String child:List.of("one","two")){Path dir=Files.createDirectories(root.resolve(child));Files.writeString(dir.resolve("Duplicate.java"),"class Duplicate {}");}
        Path file=root.resolve(".turboj-untitled-fixture.java");
        String incomplete="class Main {\n void demo() {\n  System.out.pr\n }\n}\n";
        try(var service=new JdtJavaIntelligenceService()){
            var context=new Context(file,incomplete,1,Map.of(),List.of(),List.of(),1);
            assertEquals("println()",service.complete(context,incomplete.indexOf(".pr")+3).proposals().getFirst().text());
            assertEquals(List.of(file),service.sharedRefactorJ(context).workspace().snapshot().sources().stream().map(s->s.file()).toList());
            String valid="public class Main { public Main(){} Main copy(){return new Main();} }";
            context=new Context(file,valid,2,Map.of(),List.of(),List.of(),1);
            var engine=service.sharedRefactorJ(context);var plan=engine.prepare(new Rename(new Location(file,valid.indexOf("Main")),"Example"),Cancellation.NONE);
            assertTrue(plan.valid(),plan.diagnostics().toString());assertTrue(plan.fileOperations().isEmpty(),"A scratch alias must not save or move a real file");
            assertEquals(file,plan.edits().getFirst().file());assertFalse(Files.exists(file));assertFalse(Files.exists(root.resolve("Example.java")));
            Path second=root.resolve(".turboj-untitled-second.java");
            context=new Context(second,incomplete,1,Map.of(),List.of(),List.of(),1);
            assertEquals("println()",service.complete(context,incomplete.indexOf(".pr")+3).proposals().getFirst().text());
            assertEquals(second,service.sharedRefactorJ(context).workspace().snapshot().sources().getFirst().file());
        }
    }
}
