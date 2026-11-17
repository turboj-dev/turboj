package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Positive Maven attachment/parent/processor paths; real artifacts in isolated repositories. */
public final class KeyboardMavenBoundarySmoke {
    static Path repository, remote, project, source;
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-maven-boundary"));
        work=Files.createTempDirectory("turboj-maven-boundary-").toRealPath();
        System.setProperty("turboj.config",work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        repository=Files.createDirectories(work.resolve("repository"));remote=Files.createDirectories(work.resolve("remote"));
        project=Files.createDirectories(work.resolve("project"));
        String parent=pom("parent","<packaging>pom</packaging>");
        artifact(repository,"parent","pom",parent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] library=jar(Map.of("fixture/Library.java","package fixture; public class Library { public static final int VALUE=42; }"),false);
        artifact(repository,"library","pom",pom("library","").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        artifact(repository,"library","jar",library);
        for(String classifier:List.of("sources","javadoc")) {
            byte[] bytes=archive(Map.of(classifier.equals("sources")?"fixture/Library.java":"fixture/Library.html",
                    classifier.equals("sources")?"package fixture; public class Library { public static final int VALUE=42; }":"<html><body>Library VALUE: 42</body></html>"));
            artifact(remote,"library",classifier+".jar",bytes);
        }
        String processor="""
            package fixture;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            @SupportedAnnotationTypes("*")
            public final class Generator extends AbstractProcessor {
                private boolean generated;
                public SourceVersion getSupportedSourceVersion(){return SourceVersion.latestSupported();}
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round){
                    if(!generated && !round.processingOver()){
                        generated=true;
                        try(var out=processingEnv.getFiler().createSourceFile("fixture.Generated").openWriter()){
                            out.write("package fixture; public class Generated { public static final int VALUE=73; }");
                        }catch(java.io.IOException e){throw new RuntimeException(e);}
                    }
                    return false;
                }
            }
            """;
        artifact(repository,"processor","pom",pom("processor","").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        artifact(repository,"processor","jar",jar(Map.of("fixture/Generator.java",processor),true));
        String model="<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture.keyboard</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent><artifactId>consumer</artifactId>"
            +"<properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>fixture.keyboard</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>"
            +"<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><annotationProcessorPaths><path><groupId>fixture.keyboard</groupId><artifactId>processor</artifactId><version>1</version></path></annotationProcessorPaths></configuration></plugin></plugins></build></project>";
        Files.writeString(project.resolve("pom.xml"),model);
        source=project.resolve("src/main/java/fixture/App.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"package fixture; public class App { public static void main(String[] args){ System.out.println(Library.VALUE + Generated.VALUE); } }\n");
        Path mvnSettings=Files.writeString(work.resolve("settings.xml"),"<settings><localRepository>"+repository+"</localRepository><mirrors><mirror><id>fixture</id><mirrorOf>*</mirrorOf><url>"+remote.toUri()+"</url></mirror></mirrors></settings>");
        String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(project.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path preferences=Settings.directory().resolve("maven-projects");Files.createDirectories(preferences);
        Files.writeString(preferences.resolve(key+".json"),new com.google.gson.Gson().toJson(Map.of("userSettings",mvnSettings.toString(),"localRepository",repository.toString(),"offline",false,"repositories",Set.of(remote.toUri().toString()),"defaultRepositoryPolicy",false)));
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().workspace()!=null&&!frame.mavenProjects().busy(),"isolated Maven model");
            edt(()->{frame.openPath(source);return null;});await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source ready");
            for(String classifier:List.of("sources","javadoc")) {
                Path attached=repository.resolve("fixture/keyboard/library/1/library-1-"+classifier+".jar");
                check(!Files.exists(attached),"attachment is not preinstalled");
                action("project."+classifier);dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"attachment cancel");
                check(!Files.exists(attached),"cancel cannot acquire attachment");
                var priorWorkspace=edt(()->frame.mavenProjects().workspace());
                action("project."+classifier);dialog();key(KeyEvent.VK_ENTER,0);
                await(()->Files.isRegularFile(attached)&&!frame.mavenProjects().busy()&&frame.mavenProjects().workspace()!=priorWorkspace&&frame.statusMessages().contains("Attached "+attached),"attachment acquired and model reloaded");
                check(Arrays.equals(Files.readAllBytes(attached),Files.readAllBytes(remote.resolve("fixture/keyboard/library/1/library-1-"+classifier+".jar"))),"attachment bytes equal remote artifact");
                pass("project."+classifier,"keyboard cancel preserves missing attachment; Enter resolves actual "+classifier+" JAR from isolated file repository, exact bytes verified; workspace reloads");
            }
            action("project.parentPom");Path parentFile=repository.resolve("fixture/keyboard/parent/1/parent-1.pom");
            await(()->parentFile.equals(frame.currentFile()),"external parent POM opens");
            check(edt(()->frame.editor().getText()).equals(parent),"actual external parent bytes");
            check(edt(()->frame.editor().isReadOnly()),"external repository parent is read-only");
            pass("project.parentPom","opens actual external parent read-only from resolved local repository; exact source content verified");
            edt(()->{frame.openPath(source);return null;});await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"processor source");
            action("project.processors");dialog();check(edt(()->top().getTitle()).equals("Project Trust"),"processor trust boundary");key(KeyEvent.VK_ESCAPE,0);
            await(()->!OverlayHost.active(frame),"trust cancellation");check(!edt(()->frame.mavenProjects().trusted()),"cancel does not trust project");
            action("project.processors");dialog();button("OK");
            await(()->frame.currentResult()!=null&&frame.currentResult().success()&&frame.currentResult().bytecode().containsKey("fixture.Generated"),"processor compilation result");
            // Execute the class produced by the native compiler, including generated bytecode.
            action("run");await(()->frame.console().text().contains("115"),"generated class used by actual main");
            pass("project.processors","trust cancel then approve; actual custom javax.annotation.processing processor generates Generated; native compile/run prints 115 (42+73)");
        } finally {
            Files.writeString(output.resolve("effects.tsv"),report);Files.writeString(output.resolve("console.txt"),edt(()->frame.console().text()));
            Files.writeString(output.resolve("status.txt"),edt(()->frame.statusMessages()));
            edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});
        }
    }
    static String pom(String artifact,String rest){return "<project><modelVersion>4.0.0</modelVersion><groupId>fixture.keyboard</groupId><artifactId>"+artifact+"</artifactId><version>1</version>"+rest+"</project>";}
    static void artifact(Path root,String id,String extension,byte[] bytes)throws Exception {
        Path dir=root.resolve("fixture/keyboard/"+id+"/1");Files.createDirectories(dir);
        Files.write(dir.resolve(id+"-1"+(extension.contains(".")?"-":".")+extension),bytes);
    }
    static byte[] archive(Map<String,String> entries)throws Exception {
        var out=new java.io.ByteArrayOutputStream();try(var jar=new JarOutputStream(out)){for(var e:entries.entrySet()){jar.putNextEntry(new JarEntry(e.getKey()));jar.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}}return out.toByteArray();
    }
    static byte[] jar(Map<String,String> sources,boolean processor)throws Exception {
        Path root=Files.createTempDirectory(work,"javac-");var args=new ArrayList<String>(List.of("--release","17","-proc:none","-d",root.toString()));
        for(var e:sources.entrySet()){Path file=root.resolve(e.getKey());Files.createDirectories(file.getParent());Files.writeString(file,e.getValue());args.add(file.toString());}
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))==0,"fixture javac");
        var out=new java.io.ByteArrayOutputStream();try(var jar=new JarOutputStream(out);var paths=Files.walk(root)){
            for(Path file:paths.filter(p->p.toString().endsWith(".class")).toList()){jar.putNextEntry(new JarEntry(root.relativize(file).toString()));jar.write(Files.readAllBytes(file));jar.closeEntry();}
            if(processor){jar.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));jar.write("fixture.Generator\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}
        }return out.toByteArray();
    }
}
