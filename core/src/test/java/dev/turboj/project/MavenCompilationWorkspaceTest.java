package dev.turboj.project;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import dev.turboj.intelligence.JdtJavaIntelligenceService;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.Location;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenCompilationWorkspaceTest {
    @TempDir Path root;
    private Module module(String id,List<ReactorDependency> dependencies,String release)throws Exception {
        Path directory=Files.createDirectories(root.resolve(id));Path main=Files.createDirectories(directory.resolve("src/main/java")),test=Files.createDirectories(directory.resolve("src/test/java"));
        var java=new JavaConfiguration(release,"","","UTF-8",Path.of(System.getProperty("java.home")),Map.of(),List.of(),ProcessorConfiguration.none(directory.resolve("target/generated-sources/annotations")),false);
        var classpaths=new Classpaths(Map.of(),Map.of(),true);
        return new Module(id,new Coordinates("example",id,"1","jar",""),directory,directory.resolve("pom.xml"),"jar","",List.of(),List.of(main),List.of(test),List.of(),List.of(),List.of(),List.of(),directory.resolve("target/classes"),directory.resolve("target/test-classes"),java,java,List.of(),dependencies,classpaths,List.of(),List.of(),Map.of(),"",FastPath.SUPPORTED,List.of());
    }
    private Workspace workspace(Module... modules){return new Workspace(root,root.resolve("pom.xml"),1,List.of(modules),Arrays.stream(modules).map(Module::id).toList(),List.of(),"model1",root.resolve("repository"),List.of(),true,List.of(),0,false);}
    private Path source(Module module,String name,String text)throws Exception{Path file=module.sourceRoots().getFirst().resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;}
    private int value(List<Path> cp,String name)throws Exception{try(var loader=new URLClassLoader(cp.stream().map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())){return (int)loader.loadClass(name).getMethod("value").invoke(null);}}

    @Test void reactorUsesUnsavedOutputsAndInvalidatesOnlyDependentsWithoutMavenInstall()throws Exception {
        Module common=module("common",List.of(),"17"),service=module("service",List.of(new ReactorDependency("common","compile",false)),"21"),app=module("app",List.of(new ReactorDependency("service","compile",false),new ReactorDependency("common","compile",false)),"25"),unrelated=module("unrelated",List.of(),"17");
        Path first=source(common,"a/Value.java","package a; public class Value { public static int value(){return 2;} }");
        source(service,"b/Value.java","package b; public class Value { public static int value(){return a.Value.value()*3;} }");
        source(app,"c/Value.java","package c; public class Value { public static int value(){return b.Value.value()+1;} }");
        source(unrelated,"Other.java","public class Other {}");var model=workspace(common,service,app,unrelated);
        try(var compiler=new MavenCompilationWorkspace()) {
            var initial=compiler.compile(model,Map.of(),10,false);assertTrue(initial.success(),initial.diagnostics().toString());assertEquals(7,value(compiler.runtimeClasspath("app",false),"c.Value"));
            try(var pinned=compiler.pinExecution("app",false,model.generation(),10)) {
            assertTrue(compiler.compile(model,Map.of(),11,false).modules().stream().allMatch(MavenCompilationWorkspace.ModuleResult::cacheHit));
            var changed=compiler.compile(model,Map.of(first,"package a; public class Value { public static int value(){return 4;} }"),12,false);
            assertTrue(changed.success(),changed.diagnostics().toString());assertEquals(List.of(false,false,false,true),changed.modules().stream().map(MavenCompilationWorkspace.ModuleResult::cacheHit).toList());assertEquals(13,value(compiler.runtimeClasspath("app",false),"c.Value"));
            assertEquals(7,value(pinned.classpath(),"c.Value"),"A running worker must keep the previous complete generation");
            assertThrows(IllegalStateException.class,()->compiler.pinExecution("app",false,model.generation(),10));
            assertTrue(Files.readString(first).contains("return 2"));assertFalse(Files.exists(common.outputDirectory()));
            var failed=compiler.compile(model,Map.of(first,"package a; public class Value {}"),13,false);assertFalse(failed.success());assertTrue(failed.combined().bytecode().isEmpty());assertThrows(IllegalStateException.class,()->compiler.runtimeClasspath("app",false));
            }
        }
    }
    @Test void mainCannotSeeTestSourcesAndReleaseIsRespected()throws Exception {
        Module module=module("scope",List.of(),"17");Path main=source(module,"Main.java","public class Main { TestOnly helper; }");
        Files.writeString(module.testRoots().getFirst().resolve("TestOnly.java"),"class TestOnly {}");
        try(var compiler=new MavenCompilationWorkspace()) {
            assertFalse(compiler.compile(workspace(module),Map.of(),1,true).success());
            assertFalse(compiler.compile(workspace(module),Map.of(main,"public class Main { int value(Object o){return switch(o){case String s -> 1;default -> 0;};} }"),2,false).success());
            assertTrue(compiler.compile(workspace(module),Map.of(main,"public class Main {}"),3,true).success());
            assertThrows(SecurityException.class,()->compiler.compile(workspace(module),Map.of(),4,true,true,false));
        }
    }
    @Test void modelErrorsRejectNativeCompilationAndInvalidatePreviouslyRunnableGeneration()throws Exception {
        Module valid=module("configured",List.of(),"17");source(valid,"Main.java","public class Main { public static int value(){return 17;} }");
        Workspace resolved=workspace(valid);
        try(var compiler=new MavenCompilationWorkspace()) {
            assertTrue(compiler.compile(resolved,Map.of(),1,false).success());
            assertEquals(17,value(compiler.runtimeClasspath(valid.id(),false),"Main"));
            // A failed explicit JDK selection has no toolchain requirements, but must never fall
            // back to the editor runtime just because --release compilation could succeed.
            Module missingJdk=valid.withJavaConfigurations(valid.mainJava().withJavaHome(null),valid.testJava().withJavaHome(null));
            Workspace unresolved=new Workspace(resolved.root(),resolved.rootPom(),2,List.of(missingJdk),resolved.buildOrder(),resolved.observedFiles(),"model2",resolved.localRepository(),resolved.activeProfiles(),true,List.of(Diagnostic.error("JDK_MISSING","Configured project JDK is unavailable",valid.pom())),0,false);
            var rejected=compiler.compile(unresolved,Map.of(),2,false);
            assertFalse(rejected.success());assertTrue(rejected.modules().isEmpty());
            assertTrue(rejected.diagnostics().stream().anyMatch(d->d.diagnostic().message().contains("JDK_MISSING")));
            assertThrows(IllegalStateException.class,()->compiler.runtimeClasspath(valid.id(),false));
            assertThrows(IllegalStateException.class,()->compiler.pinExecution(valid.id(),false,1,1));
            assertTrue(compiler.compile(resolved,Map.of(),3,false).success(),"A repaired model can reuse valid resident compiler state");
        }
    }
    @Test void failedOutputPublicationAndCancellationCannotReuseAnOldSuccessfulGeneration()throws Exception {
        Module valid=module("publication",List.of(),"17");Path source=source(valid,"Main.java","public class Main { public static int value(){return 1;} }");
        Module invalidResource=new Module(valid.id(),valid.coordinates(),valid.directory(),valid.pom(),valid.packaging(),valid.parentId(),valid.childModuleIds(),valid.sourceRoots(),valid.testRoots(),valid.generatedSourceRoots(),valid.generatedTestRoots(),List.of(new ResourceRoot(valid.sourceRoots().getFirst(),"../../outside",false,List.of(),List.of())),valid.testResources(),valid.outputDirectory(),valid.testOutputDirectory(),valid.mainJava(),valid.testJava(),valid.dependencies(),valid.reactorDependencies(),valid.classpaths(),valid.repositories(),valid.activeProfiles(),valid.properties(),valid.effectivePom(),valid.fastPath(),valid.diagnostics());
        Workspace model=workspace(valid);
        try(var compiler=new MavenCompilationWorkspace()) {
            assertTrue(compiler.compile(model,Map.of(),1,false).success());
            try(var pinned=compiler.pinExecution(valid.id(),false,model.generation(),1)) {
                var failed=compiler.compile(workspace(invalidResource),Map.of(source,"public class Main { public static int value(){return 2;} }"),2,false);
                assertFalse(failed.success());assertTrue(failed.diagnostics().stream().anyMatch(d->d.diagnostic().message().contains("Resource target escapes")));
                assertThrows(IllegalStateException.class,()->compiler.runtimeClasspath(valid.id(),false));
                assertEquals(1,value(pinned.classpath(),"Main"));
                var repaired=compiler.compile(model,Map.of(),3,false);
                assertTrue(repaired.success());assertFalse(repaired.modules().getFirst().cacheHit(),"Partially replaced output cannot satisfy the old source fingerprint");
                assertEquals(1,value(compiler.runtimeClasspath(valid.id(),false),"Main"));
                Thread.currentThread().interrupt();
                try { assertThrows(java.util.concurrent.CancellationException.class,()->compiler.compile(model,Map.of(),4,false)); }
                finally { Thread.interrupted(); }
                assertTrue(compiler.latest().isEmpty(),"Cancellation cannot leave a successful current generation");
                assertThrows(IllegalStateException.class,()->compiler.pinExecution(valid.id(),false,model.generation(),3));
                assertEquals(1,value(pinned.classpath(),"Main"));
                assertTrue(compiler.compile(model,Map.of(),5,false).success());
            }
        }
    }
    @Test void sharedSemanticModelResolvesAndRenamesAcrossModuleBoundaries()throws Exception {
        Module common=module("common",List.of(),"17"),consumer=module("consumer",List.of(new ReactorDependency("common","compile",false)),"21");
        String first="package api; public class Value { public static int number(){return 2;} }";
        Path declaration=source(common,"api/Value.java",first);String second="package usage; public class Use { int run(){return api.Value.number();} }";Path usage=source(consumer,"usage/Use.java",second);
        var model=workspace(common,consumer);var context=new JavaIntelligence.Context(declaration,first,1,Map.of(),MavenSemanticAdapter.sourceRoots(model),List.of(),1,List.of(),MavenSemanticAdapter.configurations(model));
        try(var service=new JdtJavaIntelligenceService()) {
            var engine=service.sharedRefactorJ(context);var symbol=engine.workspace().resolve(new Location(declaration,first.indexOf("number")),Cancellation.NONE);
            assertNotNull(symbol);assertTrue(engine.workspace().references(symbol.id(),Cancellation.NONE).stream().anyMatch(location->location.file().equals(usage)));
            var plan=engine.prepare(new Requests.Rename(new Location(declaration,first.indexOf("number")),"amount"),Cancellation.NONE);assertTrue(plan.valid(),plan.diagnostics().toString());assertEquals(Set.of(declaration,usage),plan.edits().stream().map(dev.turboj.refactorj.api.Model.FileEdits::file).collect(java.util.stream.Collectors.toSet()));
            assertEquals(4,plan.before().projects().size());assertEquals(first,Files.readString(declaration));
        }
    }
    @Test void sameQualifiedNamesInIndependentModulesNavigateToTheirOwnSource()throws Exception {
        Module one=module("one",List.of(),"17"),two=module("two",List.of(),"17");
        Path first=source(one,"p/Value.java","package p; public class Value { public static int number(){return 1;} }");
        Path second=source(two,"p/Value.java","package p; public class Value { public static int number(){return 2;} }");
        String code="package p; public class Use { int x(){return Value.number();} }";Path firstUse=source(one,"p/Use.java",code),secondUse=source(two,"p/Use.java",code);var model=workspace(one,two);
        try(var service=new JdtJavaIntelligenceService()) {
            for(var pair:List.of(Map.entry(firstUse,first),Map.entry(secondUse,second))) {
                var context=new JavaIntelligence.Context(pair.getKey(),code,1,Map.of(),MavenSemanticAdapter.sourceRoots(model),List.of(),1,List.of(),MavenSemanticAdapter.configurations(model));
                var symbol=service.resolveSymbol(context,code.indexOf("number"));assertNotNull(symbol);
                assertEquals(pair.getValue(),service.findDeclaration(context,symbol).file());
            }
        }
    }
    @Test void semanticMainDoesNotBindTestOnlyTypeAndRejectsNewerSyntax()throws Exception {
        Module module=module("isolated",List.of(),"17");String text="public class Main { TestOnly helper; }";Path file=source(module,"Main.java",text);
        Files.writeString(module.testRoots().getFirst().resolve("TestOnly.java"),"public class TestOnly {}");var model=workspace(module);
        var context=new JavaIntelligence.Context(file,text,1,Map.of(),MavenSemanticAdapter.sourceRoots(model),List.of(),1,List.of(),MavenSemanticAdapter.configurations(model));
        try(var service=new JdtJavaIntelligenceService()) {
            var symbol=service.resolveSymbol(context,text.indexOf("TestOnly"));assertNull(symbol,"main must not resolve a test-only declaration");
            String newerApi="class Main { int first(){return java.util.List.of(1).getFirst();} }";
            var apiContext=new JavaIntelligence.Context(file,newerApi,2,Map.of(),context.sourceRoots(),List.of(),1,List.of(),context.projects());
            assertNull(service.resolveSymbol(apiContext,newerApi.indexOf("getFirst")),"--release17 semantic API must not expose Java21 List.getFirst");
        }
        var analyzer=new dev.turboj.language.JdtSyntaxAnalyzer();assertFalse(analyzer.analyze(new dev.turboj.compiler.SourceSnapshot("Main.java","class Main { int f(Object x) { return switch(x) { case String s -> 1; default -> 0; }; } }",1),"17",false).syntacticallyValid());
    }

    @Test void processorsRequireTrustAndExecuteOnlyOnExplicitIsolatedCompilation()throws Exception {
        Path processorRoot=Files.createDirectories(root.resolve("processor"));Path processorSource=processorRoot.resolve("Generate.java");
        Files.writeString(processorSource,"""
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                import java.util.*;
                @SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_17)
                public class Generate extends AbstractProcessor {
                  boolean generated;
                  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if(!generated&&!round.processingOver()) { generated=true;
                      try(var writer=processingEnv.getFiler().createSourceFile("Generated").openWriter()) {
                        writer.write("public class Generated { public static int value(){return 17;} }");
                      }catch(Exception e){throw new RuntimeException(e);}
                    }return false;
                  }
                }
                """);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-proc:none","-d",processorRoot.toString(),processorSource.toString()));
        Module base=module("processed",List.of(),"17");var original=base.mainJava();
        var processor=new ProcessorConfiguration(List.of(),List.of(processorRoot),List.of(),List.of("Generate"),Map.of(),root.resolve("generated"),true);
        var java=new JavaConfiguration(original.release(),original.source(),original.target(),original.encoding(),original.javaHome(),Map.of(),List.of(),processor,false);
        Module module=base.withJavaConfigurations(java,java);source(module,"Main.java","public class Main { public static int value(){return Generated.value();} }");
        try(var compiler=new MavenCompilationWorkspace()) {
            assertFalse(compiler.compile(workspace(module),Map.of(),1,false).success(),"Passive native compilation must not execute configured processors");
            assertThrows(SecurityException.class,()->compiler.compile(workspace(module),Map.of(),2,false,true,false));
            var compiled=compiler.compile(workspace(module),Map.of(),3,false,true,true);
            assertTrue(compiled.success(),compiled.diagnostics().toString());assertEquals(17,value(compiler.runtimeClasspath(module.id(),false),"Main"));
            assertFalse(Files.exists(module.outputDirectory()));assertFalse(Files.exists(processor.generatedSources()));
            assertTrue(Files.isRegularFile(compiler.generatedSourceDirectory(module.id(),false).resolve("Generated.java")));
            assertTrue(Files.isRegularFile(compiler.generatedSourceArchive(module.id(),false)));
            var cached=compiler.compile(workspace(module),Map.of(),4,false);assertTrue(cached.success(),cached.diagnostics().toString());
            assertTrue(cached.diagnostics().stream().noneMatch(d->d.diagnostic().kind().equals("WARNING")));
        }
    }
}
