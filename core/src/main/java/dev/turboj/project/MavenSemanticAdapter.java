package dev.turboj.project;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import dev.turboj.refactorj.api.Model.JavaProjectConfiguration;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.stream.Stream;
import java.util.*;

/** Maps immutable Maven boundaries into the editor's one shared semantic workspace. */
public final class MavenSemanticAdapter {
    private MavenSemanticAdapter() {}
    public static List<JavaProjectConfiguration> configurations(Workspace workspace) {
        return configurations(workspace,null);
    }
    /** Optional generated-class visibility; generated sources remain read-only attachments. */
    public static List<JavaProjectConfiguration> configurations(Workspace workspace,MavenCompilationWorkspace compiler) {
        var configurations=new ArrayList<JavaProjectConfiguration>();
        Set<Path> reactorOutputs=new HashSet<>();for(Module module:workspace.modules()){reactorOutputs.add(module.outputDirectory());reactorOutputs.add(module.testOutputDirectory());}
        for(Module module:workspace.modules()) {
            if(module.packaging().equals("pom"))continue;
            for(boolean test:List.of(false,true)) {
                JavaConfiguration java=test?module.testJava():module.mainJava();
                if(java.languageLevel().isBlank())throw new IllegalArgumentException("Java language level is not defined for "+module.coordinates().gav());
                var roots=new ArrayList<>(test?module.testRoots():module.sourceRoots());roots.addAll(test?module.generatedTestRoots():module.generatedSourceRoots());
                var dependencies=new LinkedHashSet<String>();if(test)dependencies.add(id(module.id(),false));
                ClasspathScope scope=test?ClasspathScope.TEST_COMPILE:ClasspathScope.MAIN_COMPILE;
                var resolved=Stream.concat(module.classpaths().forScope(scope).stream(),module.classpaths().modulesForScope(scope).stream()).toList();
                for(Path entry:resolved)for(Module other:workspace.modules())if(!other.packaging().equals("pom")){
                    if(entry.equals(other.outputDirectory())&&!other.id().equals(module.id()))dependencies.add(id(other.id(),false));
                    if(entry.equals(other.testOutputDirectory())&&!other.id().equals(module.id()))dependencies.add(id(other.id(),true));
                }
                // Hand-built API snapshots may omit artifact paths; declared direct edges remain useful.
                if(resolved.isEmpty())for(var dependency:module.reactorDependencies())if((test||!dependency.scope().equals("test"))&&!dependency.scope().equals("runtime"))dependencies.add(id(dependency.moduleId(),dependency.testArtifact()));
                var classpath=new ArrayList<>(module.classpaths().forScope(scope).stream().filter(p->!reactorOutputs.contains(p)).toList());
                if(compiler!=null&&Files.isDirectory(compiler.generatedSourceDirectory(module.id(),test)))classpath.add(compiler.output(module.id(),test));
                configurations.add(new JavaProjectConfiguration(id(module.id(),test),roots,classpath,
                        module.classpaths().modulesForScope(scope).stream().filter(p->!reactorOutputs.contains(p)).toList(),
                        List.copyOf(dependencies),java.release(),java.source(),java.target(),java.javaHome(),java.encoding(),java.preview()));
            }
        }
        return List.copyOf(configurations);
    }
    public static String id(String moduleId,boolean test){return moduleId+(test?"#test":"#main");}
    public static List<Path> sourceRoots(Workspace workspace){return workspace.modules().stream().flatMap(m->java.util.stream.Stream.of(m.sourceRoots(),m.testRoots(),m.generatedSourceRoots(),m.generatedTestRoots()).flatMap(Collection::stream)).distinct().toList();}
}
