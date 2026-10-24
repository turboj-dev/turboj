package dev.turboj.project;

import dev.turboj.compiler.*;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Resident native reactor compiler. Call on one background executor. Outputs are private to this
 * session: importing or compiling never changes a Maven target directory or installs artifacts.
 * The immutable Maven model is refreshed separately; Java edits only invalidate compilation units.
 */
public final class MavenCompilationWorkspace implements AutoCloseable {
    public record ModuleResult(String moduleId, boolean tests, boolean success, boolean cacheHit,
            Path output, WorkspaceCompilationResult compilation) {}
    public record Result(long workspaceGeneration, long sourceGeneration, boolean success,
            List<ModuleResult> modules, List<WorkspaceCompilationResult.SourceDiagnostic> diagnostics,
            long durationNanos) {
        public Result { modules=List.copyOf(modules); diagnostics=List.copyOf(diagnostics); }
        public WorkspaceCompilationResult combined() {
            var sources=new ArrayList<SourceSnapshot>(); var bytes=new LinkedHashMap<String,byte[]>();
            for(var module:modules) { sources.addAll(module.compilation().sources()); module.compilation().bytecode().forEach(bytes::put); }
            return new WorkspaceCompilationResult(sourceGeneration,success,sources,diagnostics,success?bytes:Map.of(),durationNanos);
        }
    }
    /** Immutable executable generation. Close after the worker finishes, including cancellation. */
    public static final class ExecutionSnapshot implements AutoCloseable {
        private final Path directory;
        private final Map<String,byte[]> bytecode;
        private final List<Path> classpath,modulePath;
        private final String moduleName;
        private ExecutionSnapshot(Path directory,Map<String,byte[]> bytecode,List<Path> classpath,List<Path> modulePath,String moduleName){this.directory=directory;this.bytecode=bytecode;this.classpath=List.copyOf(classpath);this.modulePath=List.copyOf(modulePath);this.moduleName=moduleName;}
        public Map<String,byte[]> bytecode(){var copy=new LinkedHashMap<String,byte[]>();bytecode.forEach((name,value)->copy.put(name,value.clone()));return Map.copyOf(copy);}
        public List<Path> classpath(){return classpath;}
        public List<Path> modulePath(){return modulePath;}
        public String moduleName(){return moduleName;}
        @Override public void close(){try{clearDirectory(directory);}catch(IOException ignored){}}
    }
    private record Entry(String fingerprint, JavaConfiguration configuration, List<Path> classpath,
            List<Path> modulePath, JavacCompilerBackend compiler, WorkspaceCompilationResult result) {}
    private final Path outputs;
    private final Map<String,Entry> entries=new HashMap<>();
    private final Map<String,String> processorInputs=new HashMap<>();
    private Workspace workspace;
    private Result latest;
    private boolean closed;
    public MavenCompilationWorkspace() throws IOException { outputs=Files.createTempDirectory("turboj-native-reactor-"); }

    /** No annotation processors execute here, regardless of project trust. */
    public synchronized Result compile(Workspace model, Map<Path,String> overlays, long generation,
            boolean includeTests) {
        return compile(model,overlays,generation,includeTests,false,false);
    }

    /** Processor execution is a separate deliberate operation; both switches must be true. */
    public synchronized Result compile(Workspace model, Map<Path,String> overlays, long generation,
            boolean includeTests, boolean runProcessors, boolean trusted) {
        if(closed)throw new IllegalStateException("Compiler workspace is closed");
        if(runProcessors&&!trusted)throw new SecurityException("Annotation processing requires a trusted project");
        long started=System.nanoTime(); latest=null; workspace=model;
        var results=new ArrayList<ModuleResult>(); var diagnostics=new ArrayList<WorkspaceCompilationResult.SourceDiagnostic>();
        if(!model.resolved()) {
            var errors=java.util.stream.Stream.concat(model.diagnostics().stream(),model.modules().stream().flatMap(module->module.diagnostics().stream()))
                    .filter(diagnostic->diagnostic.severity()==Severity.ERROR).distinct().toList();
            for(var diagnostic:errors)diagnostics.add(error(diagnostic.file()==null?model.rootPom():diagnostic.file(),diagnostic.code()+": "+diagnostic.message()));
            if(diagnostics.isEmpty())diagnostics.add(error(model.rootPom(),"Maven dependencies are unresolved; native compilation cannot use an incomplete project model"));
            latest=new Result(model.generation(),generation,false,List.of(),diagnostics,System.nanoTime()-started);return latest;
        }
        var failed=new HashSet<String>(); boolean success=true;
        Map<Path,String> buffers=new HashMap<>();overlays.forEach((p,s)->buffers.put(p.toAbsolutePath().normalize(),s));
        for(String id:model.buildOrder()) {
            checkCancelled(); Module module=model.module(id).orElseThrow();
            if(module.packaging().equals("pom"))continue;
            if(module.reactorDependencies().stream().anyMatch(d->failed.contains(d.moduleId()))) {
                diagnostics.add(error(module.pom(),"A reactor dependency failed to compile; stale output was not used"));failed.add(id);success=false;continue;
            }
            for(boolean tests:includeTests?List.of(false,true):List.of(false)) {
                try {
                    var result=compileModule(model,module,tests,buffers,generation,runProcessors);
                    results.add(result);diagnostics.addAll(result.compilation().diagnostics());
                    if(!result.success()) { failed.add(id);success=false;break; }
                }catch(Exception failure) {
                    if(failure instanceof java.util.concurrent.CancellationException cancelled)throw cancelled;
                    diagnostics.add(error(module.pom(),Objects.requireNonNullElse(failure.getMessage(),failure.getClass().getSimpleName())));failed.add(id);success=false;break;
                }
            }
        }
        latest=new Result(model.generation(),generation,success,results,diagnostics,System.nanoTime()-started);return latest;
    }

    private ModuleResult compileModule(Workspace model,Module module,boolean tests,Map<Path,String> overlays,
            long generation,boolean processors) throws Exception {
        JavaConfiguration config=tests?module.testJava():module.mainJava();
        if(config.languageLevel().isBlank())throw new IllegalArgumentException("Maven did not specify a Java language level; configure maven.compiler.release/source");
        if(config.javaHome()==null&&!config.toolchainRequirements().isEmpty())throw new IllegalArgumentException("The required Maven Java toolchain is unavailable");
        if(config.preview()&&!config.languageLevel().equals(Integer.toString(Runtime.version().feature())))throw new IllegalArgumentException("Preview Java "+config.languageLevel()+" requires its matching compiler. Use the explicit Maven build; TurboJ Native runs Java "+Runtime.version().feature());
        if(!module.classpaths().resolved())throw new IllegalStateException("Dependencies are unresolved; native compilation cannot use an incomplete classpath");
        JavacCompilerBackend.safeCompilerArguments(config.compilerArguments());
        String key=key(module.id(),tests);Path output=output(module.id(),tests);
        List<Path> cp=classpath(model,module,tests?ClasspathScope.TEST_COMPILE:ClasspathScope.MAIN_COMPILE,false);
        List<Path> mp=classpath(model,module,tests?ClasspathScope.TEST_COMPILE:ClasspathScope.MAIN_COMPILE,true);
        var roots=new ArrayList<>(tests?module.testRoots():module.sourceRoots());roots.addAll(tests?module.generatedTestRoots():module.generatedSourceRoots());
        var sources=readSources(roots,overlays,config.encoding(),generation);
        MessageDigest inputs=digest();update(inputs,config.toString());for(var source:sources){update(inputs,source.fileName());update(inputs,source.source());}String processorInput=HexFormat.of().formatHex(inputs.digest());
        boolean generated=Files.isDirectory(generatedSourceDirectory(module.id(),tests));
        if(!processors&&generated){roots.add(generatedSourceDirectory(module.id(),tests));sources=readSources(roots,overlays,config.encoding(),generation);}
        String fingerprint=fingerprint(model,module,tests,sources,config,cp,mp,processors);
        Entry old=entries.get(key);
        if(old!=null&&old.fingerprint().equals(fingerprint)&&old.result().success()) {
            var cached=new WorkspaceCompilationResult(generation,true,sources,old.result().diagnostics(),old.result().bytecode(),0);
            return new ModuleResult(module.id(),tests,true,true,output,cached);
        }
        JavacCompilerBackend compiler=old==null?null:old.compiler();
        if(old!=null&&(!old.configuration().equals(config)||!old.classpath().equals(cp)||!old.modulePath().equals(mp))) {
            if(compiler!=null)compiler.close();compiler=null;
        }
        WorkspaceCompilationResult compilation;
        // Publication can fail after classes have been written (for example resource copying).
        // Invalidate the old cache entry before touching its output directory, so a reverted
        // source cannot mistake those partial/new outputs for the old successful generation.
        entries.put(key,new Entry("",config,cp,mp,compiler,new WorkspaceCompilationResult(generation,false,sources,List.of(),Map.of(),0)));
        clearDirectory(output);Files.createDirectories(output);
        boolean alternateJdk=config.javaHome()!=null&&!config.javaHome().toAbsolutePath().normalize().equals(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize());
        if(sources.isEmpty())compilation=new WorkspaceCompilationResult(generation,true,sources,List.of(),Map.of(),0);
        else if(processors)compilation=externalCompile(module,tests,sources,config,cp,mp,generation,true);
        else {
            if(compiler==null){compiler=new JavacCompilerBackend(config.release(),config.source(),config.target(),config.encoding(),cp,mp,config.preview(),config.compilerArguments(),alternateJdk?config.javaHome():null);entries.put(key,new Entry("",config,cp,mp,compiler,new WorkspaceCompilationResult(generation,false,sources,List.of(),Map.of(),0)));}
            compilation=compiler.compileSources(sources,generation);
            if(alternateJdk){var messages=new ArrayList<>(compilation.diagnostics());messages.add(new WorkspaceCompilationResult.SourceDiagnostic(module.pom().toString(),new CompilerDiagnostic("NOTE","TurboJ Native uses resident javac "+Runtime.version().feature()+" with project language/API settings; execution and explicit Maven builds use "+config.javaHome(),-1,-1,-1,-1)));compilation=new WorkspaceCompilationResult(generation,compilation.success(),sources,messages,compilation.bytecode(),compilation.durationNanos());}
        }
        // Remove obsolete classes (deleted/renamed types) before publishing this module generation.
        if(!compilation.success())clearDirectory(output);
        if(compilation.success()) {
            if(processors&&config.processors().requested())processorInputs.put(key,processorInput);
            for(var entry:compilation.bytecode().entrySet()) {
                Path file=output.resolve(entry.getKey().replace('.','/')+".class");Files.createDirectories(file.getParent());Files.write(file,entry.getValue());
            }
            copyResources(List.of(new ResourceRoot(generatedResourceDirectory(module.id(),tests),"",false,List.of(),List.of())),output);
            copyResources(tests?module.testResources():module.resources(),output);
        }
        if(!processors&&generated&&!Objects.equals(processorInputs.get(key),processorInput)){
            var messages=new ArrayList<>(compilation.diagnostics());messages.add(new WorkspaceCompilationResult.SourceDiagnostic(module.pom().toString(),new CompilerDiagnostic("WARNING","Annotation processor inputs changed. Generated sources are from the previous explicit run; regenerate annotation sources or run Maven to validate the complete build.",-1,-1,-1,-1)));
            compilation=new WorkspaceCompilationResult(generation,compilation.success(),sources,messages,compilation.bytecode(),compilation.durationNanos());
        }
        entries.put(key,new Entry(fingerprint,config,cp,mp,compiler,compilation));
        return new ModuleResult(module.id(),tests,compilation.success(),false,output,compilation);
    }

    /** Valid only after a successful compile of the same model/source generation. */
    public synchronized List<Path> runtimeClasspath(String moduleId,boolean tests) {
        if(latest==null||!latest.success())throw new IllegalStateException("No successful current reactor compilation");
        Module module=workspace.module(moduleId).orElseThrow();
        var paths=new LinkedHashSet<Path>();if(tests)paths.add(output(moduleId,true));paths.add(output(moduleId,false));
        paths.addAll(classpath(workspace,module,tests?ClasspathScope.TEST_RUNTIME:ClasspathScope.MAIN_RUNTIME,false));return List.copyOf(paths);
    }
    public synchronized List<Path> runtimeModulePath(String moduleId,boolean tests) {
        if(latest==null||!latest.success())throw new IllegalStateException("No successful current reactor compilation");
        var paths=new LinkedHashSet<Path>();if(Files.isRegularFile(output(moduleId,false).resolve("module-info.class")))paths.add(output(moduleId,false));
        paths.addAll(classpath(workspace,workspace.module(moduleId).orElseThrow(),tests?ClasspathScope.TEST_RUNTIME:ClasspathScope.MAIN_RUNTIME,true));return List.copyOf(paths);
    }
    public synchronized String runtimeModuleName(String moduleId){if(latest==null||!latest.success())throw new IllegalStateException("No successful current reactor compilation");if(!Files.isRegularFile(output(moduleId,false).resolve("module-info.class")))return "";return java.lang.module.ModuleFinder.of(output(moduleId,false)).findAll().stream().map(m->m.descriptor().name()).findFirst().orElse("");}
    public synchronized Map<String,byte[]> runtimeBytecode(String moduleId,boolean tests){if(latest==null||!latest.success())throw new IllegalStateException("No successful current reactor compilation");var bytes=new LinkedHashMap<String,byte[]>();for(var module:latest.modules())if(module.moduleId().equals(moduleId)&&(!module.tests()||tests))module.compilation().bytecode().forEach(bytes::put);return Map.copyOf(bytes);}
    public synchronized Optional<Result> latest(){return Optional.ofNullable(latest);}
    public synchronized String sourceFingerprint(String moduleId,boolean tests){Entry entry=entries.get(key(moduleId,tests));return entry==null?"":entry.fingerprint();}
    /** Pins private reactor output inodes before another source generation may publish replacements. */
    public synchronized ExecutionSnapshot pinExecution(String moduleId,boolean tests,long workspaceGeneration,long sourceGeneration)throws IOException {
        if(latest==null||!latest.success()||latest.workspaceGeneration()!=workspaceGeneration||latest.sourceGeneration()!=sourceGeneration)throw new IllegalStateException("Compilation was superseded before execution");
        Path pinned=Files.createTempDirectory("turboj-execution-generation-");
        try {
            var copied=new LinkedHashMap<Path,Path>();var cp=new ArrayList<Path>();var mp=new ArrayList<Path>();
            for(Path path:runtimeClasspath(moduleId,tests))cp.add(pinPath(path,pinned,copied));
            for(Path path:runtimeModulePath(moduleId,tests))mp.add(pinPath(path,pinned,copied));
            return new ExecutionSnapshot(pinned,runtimeBytecode(moduleId,tests),cp,mp,runtimeModuleName(moduleId));
        }catch(Throwable failure){clearDirectory(pinned);throw failure;}
    }
    private Path pinPath(Path path,Path destination,Map<Path,Path> copied)throws IOException {
        if(copied.containsKey(path))return copied.get(path);
        Path container=destination.resolve(Integer.toString(copied.size()));Files.createDirectories(container);
        if(Files.isRegularFile(path)) {
            Path target=container.resolve(path.getFileName());var before=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            Files.copy(path,target);var after=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("Runtime dependency changed while pinning execution: "+path);
            copied.put(path,target);return target;
        }
        Path target=container;copied.put(path,target);
        if(Files.isDirectory(path))try(var files=Files.walk(path)){for(Path file:files.filter(Files::isRegularFile).toList()){
            Path copy=target.resolve(path.relativize(file));Files.createDirectories(copy.getParent());
            if(path.startsWith(outputs))try{Files.createLink(copy,file);}catch(IOException|UnsupportedOperationException unsupported){Files.copy(file,copy);}
            else Files.copy(file,copy);
        }}
        return target;
    }
    public Path output(String moduleId,boolean tests){return outputs.resolve(HexFormat.of().formatHex(hash(moduleId.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,24)).resolve(tests?"test":"main");}
    public Path generatedSourceDirectory(String moduleId,boolean tests){return output(moduleId,tests).resolveSibling(tests?"generated-test":"generated-main");}
    public Path generatedSourceArchive(String moduleId,boolean tests){return output(moduleId,tests).resolveSibling(tests?"generated-test-sources.jar":"generated-main-sources.jar");}
    private Path generatedResourceDirectory(String moduleId,boolean tests){return output(moduleId,tests).resolveSibling(tests?"generated-test-resources":"generated-main-resources");}
    private static String key(String id,boolean tests){return id+(tests?"#test":"#main");}

    private List<Path> classpath(Workspace model,Module module,ClasspathScope scope,boolean modules) {
        var paths=new LinkedHashSet<Path>();
        for(Path path:modules?module.classpaths().modulesForScope(scope):module.classpaths().forScope(scope)) {
            Path replacement=path;
            for(Module other:model.modules()) {
                if(path.equals(other.outputDirectory()))replacement=output(other.id(),false);
                if(path.equals(other.testOutputDirectory()))replacement=output(other.id(),true);
            }
            paths.add(replacement);
        }
        boolean tests=scope==ClasspathScope.TEST_COMPILE||scope==ClasspathScope.TEST_RUNTIME;
        if(!modules) {
            if(tests)paths.add(output(module.id(),false));
            for(ReactorDependency dependency:module.reactorDependencies()) {
                if((!tests&&dependency.scope().equals("test"))||((scope==ClasspathScope.MAIN_RUNTIME||scope==ClasspathScope.TEST_RUNTIME)&&dependency.scope().equals("provided"))||((scope==ClasspathScope.MAIN_COMPILE)&&dependency.scope().equals("runtime")))continue;
                Module other=model.module(dependency.moduleId()).orElseThrow();
                boolean modular=java.util.stream.Stream.concat(other.sourceRoots().stream(),other.generatedSourceRoots().stream()).anyMatch(p->Files.exists(p.resolve("module-info.java")));
                if(!modular)paths.add(output(dependency.moduleId(),dependency.testArtifact()));
            }
        }
        return List.copyOf(paths);
    }

    private String fingerprint(Workspace model,Module module,boolean tests,List<SourceSnapshot> sources,
            JavaConfiguration config,List<Path> cp,List<Path> mp,boolean processors) throws IOException {
        MessageDigest digest=digest();update(digest,config.toString());update(digest,Boolean.toString(processors));
        for(SourceSnapshot source:sources){update(digest,source.fileName());update(digest,source.source());}
        for(Path path:java.util.stream.Stream.concat(cp.stream(),mp.stream()).toList()) {
            update(digest,path.toString()); if(Files.isRegularFile(path)){update(digest,Long.toString(Files.size(path)));update(digest,Files.getLastModifiedTime(path).toString());}
        }
        if(tests){Entry main=entries.get(key(module.id(),false));if(main!=null)update(digest,main.fingerprint());}
        for(ReactorDependency dependency:module.reactorDependencies()) {
            if(!tests&&dependency.scope().equals("test"))continue;
            Entry entry=entries.get(key(dependency.moduleId(),dependency.testArtifact()));if(entry!=null)update(digest,entry.fingerprint());
        }
        for(ResourceRoot root:tests?module.testResources():module.resources())if(Files.isDirectory(root.directory()))try(var files=Files.walk(root.directory())){for(Path file:files.filter(Files::isRegularFile).sorted().toList()){update(digest,file.toString());digest.update(Files.readAllBytes(file));}}
        return HexFormat.of().formatHex(digest.digest());
    }

    public static List<SourceSnapshot> readSources(List<Path> roots,Map<Path,String> overlays,String encoding,long generation) throws IOException {
        var files=new TreeMap<Path,String>();Charset charset=Charset.forName(encoding);
        for(Path root:roots)if(Files.isDirectory(root))try(var paths=Files.walk(root)){for(Path file:paths.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".java")).sorted().toList()){checkCancelled();Path normalized=file.toAbsolutePath().normalize();files.put(normalized,overlays.containsKey(normalized)?overlays.get(normalized):Files.readString(file,charset));}}
        overlays.forEach((file,source)->{if(file.toString().endsWith(".java")&&roots.stream().anyMatch(file::startsWith))files.put(file,source);});
        return files.entrySet().stream().map(e->new SourceSnapshot(e.getKey().toString(),e.getValue(),generation)).toList();
    }

    private WorkspaceCompilationResult externalCompile(Module module,boolean tests,List<SourceSnapshot> sources,
            JavaConfiguration config,List<Path> cp,List<Path> mp,long generation,boolean processors) throws Exception {
        long start=System.nanoTime();Path job=Files.createTempDirectory(outputs,"javac-");
        Path home=config.javaHome()==null?Path.of(System.getProperty("java.home")):config.javaHome();Path javac=home.resolve("bin/javac");
        if(!Files.isExecutable(javac))throw new IllegalArgumentException("Selected project JDK has no javac: "+home);
        var arguments=new ArrayList<String>();arguments.add(javac.toString());
        if(!config.release().isBlank())arguments.addAll(List.of("--release",config.release()));else{arguments.addAll(List.of("-source",config.source()));if(!config.target().isBlank())arguments.addAll(List.of("-target",config.target()));}
        arguments.addAll(List.of("-encoding",config.encoding(),"-g","-d",job.resolve("classes").toString(),"-classpath",join(cp.isEmpty()?List.of(job.resolve("empty")):cp),"-sourcepath",job.resolve("empty").toString()));
        arguments.addAll(JavacCompilerBackend.safeCompilerArguments(config.compilerArguments()));
        if(config.preview())arguments.add("--enable-preview");if(!mp.isEmpty())arguments.addAll(List.of("--module-path",join(mp)));
        if(processors&&config.processors().requested()) {
            var processor=config.processors();arguments.addAll(List.of("-s",job.resolve("generated").toString()));
            if(!processor.path().isEmpty())arguments.addAll(List.of("-processorpath",join(processor.path())));
            if(!processor.modulePath().isEmpty())arguments.addAll(List.of("--processor-module-path",join(processor.modulePath())));
            if(!processor.processors().isEmpty())arguments.addAll(List.of("-processor",String.join(",",processor.processors())));
            processor.options().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->arguments.add("-A"+e.getKey()+"="+e.getValue()));
            arguments.add("-proc:full");
        }else arguments.add("-proc:none");
        int ordinal=0;for(SourceSnapshot source:sources){Path file=job.resolve("sources/"+(ordinal++)).resolve(Path.of(source.fileName()).getFileName());Files.createDirectories(file.getParent());Files.writeString(file,source.source(),Charset.forName(config.encoding()));arguments.add(file.toString());}
        // Use a javac argument file, preserving literal spaces/quotes and avoiding OS command limits.
        Path argumentFile=job.resolve("arguments");Files.writeString(argumentFile,arguments.subList(1,arguments.size()).stream().map(MavenCompilationWorkspace::quoteArgument).collect(java.util.stream.Collectors.joining("\n")));
        Path log=job.resolve("diagnostics");Process process=new ProcessBuilder(javac.toString(),"@"+argumentFile).directory(module.directory().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
            while(!process.waitFor(50,TimeUnit.MILLISECONDS)){checkCancelled();if(Files.exists(log)&&Files.size(log)>4*1024*1024)throw new IOException("Compiler diagnostic output exceeded 4 MiB");if(System.nanoTime()>deadline)throw new IOException("Selected JDK compilation exceeded two minutes");}
            boolean success=process.exitValue()==0;var messages=new ArrayList<WorkspaceCompilationResult.SourceDiagnostic>();
            if(Files.size(log)>0){String text=Files.readString(log);if(text.length()>262144)text=text.substring(0,262144);messages.add(new WorkspaceCompilationResult.SourceDiagnostic(module.pom().toString(),new CompilerDiagnostic(success?"NOTE":"ERROR",text,-1,-1,-1,-1)));}
            var bytes=new LinkedHashMap<String,byte[]>();Path classes=job.resolve("classes");
            if(success&&Files.isDirectory(classes))try(var files=Files.walk(classes)){for(Path file:files.filter(p->p.toString().endsWith(".class")).toList())bytes.put(classes.relativize(file).toString().replace(File.separatorChar,'.').replaceFirst("\\.class$",""),Files.readAllBytes(file));}
            if(success&&processors){clearDirectory(generatedResourceDirectory(module.id(),tests));if(Files.isDirectory(classes))try(var files=Files.walk(classes)){for(Path file:files.filter(p->Files.isRegularFile(p)&&!p.toString().endsWith(".class")).toList()){Path destination=generatedResourceDirectory(module.id(),tests).resolve(classes.relativize(file));Files.createDirectories(destination.getParent());Files.copy(file,destination,StandardCopyOption.REPLACE_EXISTING);}}}
            if(success&&processors&&config.processors().requested()){
                Path generated=job.resolve("generated"),destination=generatedSourceDirectory(module.id(),tests);clearDirectory(destination);Files.createDirectories(destination);
                Path archive=generatedSourceArchive(module.id(),tests);
                try(var zip=new java.util.jar.JarOutputStream(Files.newOutputStream(archive))){
                    if(Files.isDirectory(generated))try(var files=Files.walk(generated)){for(Path file:files.filter(Files::isRegularFile).sorted().toList()){
                        Path relative=generated.relativize(file),target=destination.resolve(relative);Files.createDirectories(target.getParent());Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);
                        zip.putNextEntry(new java.util.jar.JarEntry(relative.toString().replace(File.separatorChar,'/')));Files.copy(file,zip);zip.closeEntry();
                    }}
                }
            }
            return new WorkspaceCompilationResult(generation,success,sources,messages,bytes,System.nanoTime()-start);
        }finally{if(process.isAlive())process.destroyForcibly();clearDirectory(job);}
    }
    private static String quoteArgument(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String join(List<Path> paths){return String.join(File.pathSeparator,paths.stream().map(Path::toString).toList());}
    private static void copyResources(List<ResourceRoot> roots,Path output)throws IOException {
        for(var root:roots) {
            if(root.filtering())continue; // Model diagnostics describe Maven filtering; never publish unfiltered placeholders.
            if(!Files.isDirectory(root.directory()))continue;
            Path destination=output.resolve(root.targetPath()).normalize();if(!destination.startsWith(output))throw new IOException("Resource target escapes module output");
            try(var files=Files.walk(root.directory())){for(Path file:files.filter(Files::isRegularFile).toList()) {
                Path relative=root.directory().relativize(file);String name=relative.toString().replace(File.separatorChar,'/');
                if(!root.includes().isEmpty()&&root.includes().stream().noneMatch(p->matches(p,name)))continue;
                if(root.excludes().stream().anyMatch(p->matches(p,name)))continue;
                Path target=destination.resolve(relative);Files.createDirectories(target.getParent());Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);
            }}
        }
    }
    private static boolean matches(String glob,String path){return FileSystems.getDefault().getPathMatcher("glob:"+glob).matches(Path.of(path))||(glob.startsWith("**/")&&FileSystems.getDefault().getPathMatcher("glob:"+glob.substring(3)).matches(Path.of(path)));}
    private static WorkspaceCompilationResult.SourceDiagnostic error(Path file,String message){return new WorkspaceCompilationResult.SourceDiagnostic(file.toString(),new CompilerDiagnostic("ERROR",message,-1,-1,-1,-1));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static byte[] hash(byte[] bytes){return digest().digest(bytes);}
    private static void update(MessageDigest digest,String text){digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}
    private static void checkCancelled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Compilation cancelled");}
    private static void clearDirectory(Path path)throws IOException{if(Files.exists(path))try(var files=Files.walk(path)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    @Override public synchronized void close(){if(closed)return;closed=true;entries.values().forEach(e->{if(e.compiler()!=null)e.compiler().close();});entries.clear();try{clearDirectory(outputs);}catch(IOException ignored){}}
}
