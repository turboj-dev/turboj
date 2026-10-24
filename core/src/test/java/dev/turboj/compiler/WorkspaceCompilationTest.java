package dev.turboj.compiler;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCompilationTest {
    @Test void sourceTargetEightUsesCurrentPlatformApisWithoutPretendingToBeReleaseEight(){
        try(var compiler=new JavacCompilerBackend("","8","8","UTF-8",List.of(),List.of(),false)){
            var result=compiler.compile(new SourceSnapshot("SourceEight.java","public class SourceEight { public static int value(){return java.util.List.of(1,2,3).size();} }",1));
            assertTrue(result.success(),result.diagnostics().toString());
            assertEquals(52,java.lang.classfile.ClassFile.of().parse(result.bytecode().get("SourceEight")).majorVersion());
        }
        try(var compiler=new JavacCompilerBackend(8,List.of())){
            var result=compiler.compile(new SourceSnapshot("ReleaseEight.java","public class ReleaseEight { public static int value(){return java.util.List.of(1,2,3).size();} }",1));
            assertFalse(result.success(),"--release8 must reject APIs that source/target8 on the current JDK permits");
        }
    }
    @Test void activeSourceDoesNotRunAnUnrelatedMainAndSameFilenamesAreDistinct(){
        try(var compiler=new JavacCompilerBackend()){
            var result=compiler.compileSources(List.of(new SourceSnapshot("p/Main.java","package p; public class Main { public static void main(String[] args){} }",1),new SourceSnapshot("q/Main.java","package q; public class Main {}",1)),1);
            assertTrue(result.success());assertEquals("p.Main",JavacCompilerBackend.findMainForSource(result.bytecode(),"p/Main.java"));assertNull(JavacCompilerBackend.findMainForSource(result.bytecode(),"q/Main.java"));
        }
    }
    @Test void compilesMutuallyDependentUnsavedSourcesAndRejectsStaleArtifacts() {
        try (var compiler = new JavacCompilerBackend()) {
            var sources = List.of(new SourceSnapshot("p/A.java", "package p; public class A { public static int value() { return B.VALUE; } }", 4),
                    new SourceSnapshot("p/B.java", "package p; public class B { public static final int VALUE = 7; }", 9));
            var result = compiler.compileSources(sources, 15);
            assertTrue(result.success(), () -> result.diagnostics().toString());
            assertEquals(15, result.version()); assertEquals(Set.of("p.A", "p.B"), result.bytecode().keySet());
            var failure = compiler.compileSources(List.of(sources.getFirst(), new SourceSnapshot("p/B.java", "package p; public class B {}", 10)), 16);
            assertFalse(failure.success()); assertTrue(failure.bytecode().isEmpty());
            assertEquals("p/A.java", failure.diagnostics().stream().filter(d -> d.diagnostic().isError()).findFirst().orElseThrow().fileName());
            assertEquals(16, failure.version());
        }
    }
}
