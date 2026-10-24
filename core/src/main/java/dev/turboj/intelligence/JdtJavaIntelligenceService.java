package dev.turboj.intelligence;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.runtime.RefactorJRuntime;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Compatibility facade borrowing one embedded Java model for all semantic operations. */
public final class JdtJavaIntelligenceService implements JavaIntelligenceService {
    private RefactorJRuntime runtime;
    private RefactorJ engine;
    private JavaIntelligence delegate;
    private boolean closed;
    private long workspaceVersion;
    private List<Path> indexedRoots=List.of();
    private long indexedProjectVersion=Long.MIN_VALUE;
    private final Map<Path,Long> stamps=new HashMap<>();
    private final Map<Path,String> diskSources=new HashMap<>();
    private final Map<Path,Long> directoryStamps=new HashMap<>();
    private long inventoryAt;
    private List<JavaProjectConfiguration> projectConfigurations=List.of();

    @Override public RefactorJ sharedRefactorJ(Context context){intelligence(context);return engine;}
    @Override public RefactorJ sharedRefactorJ(Context context,WorkspaceSnapshot snapshot){
        ensureOpen();try{
            if(runtime==null)runtime=new RefactorJRuntime();
            if(engine!=null&&!engine.workspace().snapshot().root().equals(snapshot.root())){engine.close();engine=null;}
            if(engine==null){engine=runtime.openWorkspace(snapshot);delegate=((JavaIntelligenceProvider)engine).intelligence();}
            else engine.workspace().synchronize(snapshot,Cancellation.NONE);
            return engine;
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Cannot initialize embedded Java semantic workspace: "+Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName()),ex);}
    }
    private JavaIntelligence intelligence(Context context){
        ensureOpen();try{
            List<Path> roots=context.sourceRoots().stream().map(p->p.toAbsolutePath().normalize()).toList();
            boolean memoryOnly=roots.isEmpty();
            if(!memoryOnly&&roots.stream().noneMatch(context.file()::startsWith))roots=List.of(context.file().getParent());
            Path root=memoryOnly?context.file().getParent():roots.getFirst();for(Path sourceRoot:roots)while(!sourceRoot.startsWith(root)&&root.getParent()!=null)root=root.getParent();
            boolean configurationChanged=!indexedRoots.equals(roots)||indexedProjectVersion!=context.projectVersion();
            if(!projectConfigurations.equals(context.projects())){configurationChanged=true;projectConfigurations=context.projects();diskSources.clear();stamps.clear();}
            boolean inventoryChanged=configurationChanged||System.nanoTime()-inventoryAt>5_000_000_000L;
            if(!inventoryChanged)for(var entry:directoryStamps.entrySet())if(!Files.isDirectory(entry.getKey())||Files.getLastModifiedTime(entry.getKey()).toMillis()!=entry.getValue()){inventoryChanged=true;break;}
            if(inventoryChanged){
                indexedRoots=roots;indexedProjectVersion=context.projectVersion();
                var inventory=memoryOnly?new SourceInventory.Inventory(List.of(),List.of()):SourceInventory.scanProjectDetailed(root,roots,100000);
                Set<Path> found=new LinkedHashSet<>(inventory.sources());
                diskSources.keySet().retainAll(found);stamps.keySet().retainAll(found);directoryStamps.clear();
                for(Path path:found)if(!diskSources.containsKey(path))read(path);
                for(Path directory:inventory.directories())directoryStamps.put(directory,Files.getLastModifiedTime(directory).toMillis());
                inventoryAt=System.nanoTime();
            }
            for(Path path:new ArrayList<>(diskSources.keySet())){if(!Files.isRegularFile(path)){diskSources.remove(path);stamps.remove(path);}else if(Files.getLastModifiedTime(path).toMillis()!=stamps.getOrDefault(path,-1L))read(path);}
            var sources=new LinkedHashMap<Path,String>(diskSources);
            for(var entry:context.openSources().entrySet()){Path path=entry.getKey().toAbsolutePath().normalize();if(SourceInventory.isProjectSource(roots,path))sources.put(path,entry.getValue());}
            sources.put(context.file(),context.source());
            var snapshots=new ArrayList<SourceSnapshot>();for(var entry:sources.entrySet()){Path path=entry.getKey().toAbsolutePath().normalize();boolean open=context.openSources().containsKey(path)||path.equals(context.file());long version=path.equals(context.file())?context.version():open?entry.getValue().hashCode():stamps.getOrDefault(path,0L);snapshots.add(new SourceSnapshot(path,entry.getValue(),version,open));}
            var candidate=new WorkspaceSnapshot(root,workspaceVersion,snapshots,roots,context.classpath(),context.projects());
            if(engine!=null){var previous=engine.workspace().snapshot();workspaceVersion=Math.max(workspaceVersion,previous.version());if(configurationChanged||!previous.sources().equals(candidate.sources())||!previous.classpath().equals(candidate.classpath())||!previous.sourceRoots().equals(candidate.sourceRoots())||!previous.projects().equals(context.projects()))candidate=new WorkspaceSnapshot(root,++workspaceVersion,snapshots,roots,context.classpath(),context.projects());else candidate=previous;}
            sharedRefactorJ(context,candidate);return delegate;
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    private void read(Path path)throws IOException{path=path.toAbsolutePath().normalize();Path file=path;String encoding=projectConfigurations.stream().filter(p->p.sourceRoots().stream().anyMatch(file::startsWith)).map(JavaProjectConfiguration::encoding).findFirst().orElse("UTF-8");diskSources.put(path,Files.readString(path,java.nio.charset.Charset.forName(encoding)));stamps.put(path,Files.getLastModifiedTime(path).toMillis());}
    @Override public TestTarget testTarget(Context context,int offset){return intelligence(context).testTarget(context,offset);}
    @Override public CompletionResult complete(Context context,int offset){return intelligence(context).complete(context,offset);}
    @Override public Symbol resolveSymbol(Context context,int offset){return intelligence(context).resolveSymbol(context,offset);}
    @Override public Declaration findDeclaration(Context context,Symbol symbol){return intelligence(context).findDeclaration(context,symbol);}
    @Override public Documentation documentation(Context context,Symbol symbol){return intelligence(context).documentation(context,symbol);}
    @Override public SignatureHelp signatureHelp(Context context,int offset){return intelligence(context).signatureHelp(context,offset);}
    public static String plainJavadoc(String comment){return DocumentationText.plainJavadoc(comment);}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Semantic service is closed");}
    @Override public void close(){if(closed)return;closed=true;if(runtime!=null)runtime.close();diskSources.clear();stamps.clear();directoryStamps.clear();}
}
