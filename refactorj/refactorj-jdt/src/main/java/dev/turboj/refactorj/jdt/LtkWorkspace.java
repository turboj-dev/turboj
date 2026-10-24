package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.search.*;
import org.eclipse.jdt.core.manipulation.*;

/** One private Eclipse Java model; original project files are never written here. */
public final class LtkWorkspace implements JavaSemanticWorkspace {
    private WorkspaceSnapshot snapshot;
    private final IProject project;
    private final IJavaProject javaProject;
    private final IPackageFragmentRoot sourceRoot;
    private final String symbolPrefix;
    private final Map<Path,ICompilationUnit> units = new LinkedHashMap<>();
    private final Map<String,Path> resourcePaths = new HashMap<>();
    private final Map<Path,CompilationUnit> asts = new LinkedHashMap<>(32,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Path,CompilationUnit> e){return size()>64;}};
    private final Map<String,IJavaElement> elements = new LinkedHashMap<>(64,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,IJavaElement> e){return size()>2048;}};
    private final Map<Location,Symbol> resolved=new LinkedHashMap<>(32,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Location,Symbol> e){return size()>512;}};
    private final Map<String,List<Location>> referenceCache=new LinkedHashMap<>(32,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,List<Location>> e){return size()>128;}};
    private record ProjectState(String id,IProject project,IJavaProject java,IPackageFragmentRoot root,JavaProjectConfiguration configuration) {}
    private final Map<String,ProjectState> projects=new LinkedHashMap<>();
    private final JdkApiImages apiImages=new JdkApiImages();
    private boolean closed;
    private boolean synchronizedModel;

    public LtkWorkspace(WorkspaceSnapshot initial) throws CoreException {
        symbolPrefix = symbolPrefix(initial.root());
        JdtEnvironment.initialize();
        var workspace=ResourcesPlugin.getWorkspace();
        var description=workspace.getDescription();description.setAutoBuilding(false);workspace.setDescription(description);
        project=workspace.getRoot().getProject("refactorj_"+UUID.randomUUID().toString().replace("-",""));
        project.create(new NullProgressMonitor());project.open(new NullProgressMonitor());project.setDefaultCharset("UTF-8",new NullProgressMonitor());
        var pd=project.getDescription();pd.setNatureIds(new String[]{JavaCore.NATURE_ID});project.setDescription(pd,new NullProgressMonitor());
        IFolder src=project.getFolder("src"),bin=project.getFolder("bin");src.create(true,true,null);bin.create(true,true,null);
        javaProject=JavaCore.create(project);sourceRoot=javaProject.getPackageFragmentRoot(src);
        Map<String,String> options=new HashMap<>();JavaCore.setComplianceOptions(JavaCore.VERSION_25,options);
        options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES,JavaCore.DISABLED);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT,JavaCore.ENABLED);
        options.put("org.eclipse.jdt.core.formatter.tabulation.char","space");
        options.put("org.eclipse.jdt.core.formatter.tabulation.size","4");
        options.put("org.eclipse.jdt.core.formatter.indentation.size","4");
        javaProject.setOptions(options);
        try { synchronize(initial,Cancellation.NONE); }
        catch (RuntimeException failure) { close(); throw failure; }
    }
    @Override public WorkspaceSnapshot snapshot(){ ensureOpen();return snapshot; }
    public IJavaProject javaProject(){return javaProject;}
    public IJavaProject javaProject(Path file){return unit(file).getJavaProject();}
    private ProjectState owner(Path file,WorkspaceSnapshot model){
        return projects.values().stream().filter(p->p.configuration().sourceRoots().stream().anyMatch(file::startsWith))
                .max(Comparator.comparingInt(p->p.configuration().sourceRoots().stream().filter(file::startsWith).mapToInt(Path::getNameCount).max().orElse(0))).orElse(null);
    }
    private IJavaElement[] searchProjects(){var all=new ArrayList<IJavaElement>();all.add(javaProject);projects.values().forEach(p->all.add(p.java()));return all.toArray(IJavaElement[]::new);}
    private void configureProjects(WorkspaceSnapshot next)throws CoreException{
        boolean changed=snapshot==null||!snapshot.projects().equals(next.projects());
        if(!changed)return;
        for(var unit:units.values()){unit.discardWorkingCopy();unit.getResource().delete(true,null);}units.clear();resourcePaths.clear();
        for(var state:projects.values())state.project().delete(true,true,null);projects.clear();
        for(var configuration:next.projects()){
            IProject p=ResourcesPlugin.getWorkspace().getRoot().getProject("refactorj_"+UUID.randomUUID().toString().replace("-",""));
            p.create(null);p.open(null);p.setDefaultCharset(configuration.encoding(),null);
            var description=p.getDescription();description.setNatureIds(new String[]{JavaCore.NATURE_ID});p.setDescription(description,null);
            IFolder src=p.getFolder("src"),bin=p.getFolder("bin");src.create(true,true,null);bin.create(true,true,null);
            IJavaProject java=JavaCore.create(p);IPackageFragmentRoot root=java.getPackageFragmentRoot(src);
            var options=new HashMap<>(javaProject.getOptions(true));JavaCore.setComplianceOptions(configuration.languageLevel(),options);
            if(!configuration.source().isBlank())options.put(JavaCore.COMPILER_SOURCE,configuration.source());
            if(!configuration.target().isBlank())options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,configuration.target());
            options.put(JavaCore.COMPILER_RELEASE,configuration.release().isBlank()?JavaCore.DISABLED:JavaCore.ENABLED);
            options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES,configuration.preview()?JavaCore.ENABLED:JavaCore.DISABLED);java.setOptions(options);
            projects.put(configuration.id(),new ProjectState(configuration.id(),p,java,root,configuration));
        }
        for(var state:projects.values()){
            var config=state.configuration();var entries=new ArrayList<IClasspathEntry>();entries.add(JavaCore.newSourceEntry(state.root().getPath()));
            try{
                Path home=config.javaHome()==null?Path.of(System.getProperty("java.home")):config.javaHome();
                for(Path library:apiImages.libraries(home,config.release())) {
                    if(!java.nio.file.Files.isRegularFile(library))throw new IllegalArgumentException("Selected JDK has no Java runtime image: "+home);
                    entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(library.toString()),null,null,null,new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.MODULE,"true")},false));
                }
            }catch(IOException error){throw new IllegalStateException("Cannot establish project Java API level",error);}
            for(Path cp:config.classpath())if(java.nio.file.Files.exists(cp))entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(cp.toString()),null,null));
            var attributes=new IClasspathAttribute[]{JavaCore.newClasspathAttribute(IClasspathAttribute.MODULE,"true")};
            for(Path mp:config.modulePath())if(java.nio.file.Files.exists(mp))entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(mp.toString()),null,null,null,attributes,false));
            for(String dependency:config.dependencies()){
                var target=projects.get(dependency);
                boolean modular=next.sources().stream().anyMatch(s->s.file().getFileName().toString().equals("module-info.java")&&target.configuration().sourceRoots().stream().anyMatch(s.file()::startsWith));
                entries.add(JavaCore.newProjectEntry(target.project().getFullPath(),null,false,modular?attributes:new IClasspathAttribute[0],false));
            }
            state.java().setRawClasspath(entries.toArray(IClasspathEntry[]::new),state.project().getFolder("bin").getFullPath(),null);
        }
    }
    public Collection<ICompilationUnit> units(){return List.copyOf(units.values());}
    public ICompilationUnit unit(Path path){ensureOpen();var u=units.get(path.toAbsolutePath().normalize());if(u==null)throw new IllegalArgumentException("Source is outside the Java workspace");return u;}
    public Path original(IResource resource){
        if(resource==null)return null;
        Path known=resourcePaths.get(resource.getFullPath().toString());
        if(known!=null)return known;
        // New files inherit the real source root of existing units in the same package.
        String relative=resource.getProjectRelativePath().toString();if(relative.startsWith("src/"))relative=relative.substring(4);
        ProjectState state=projects.values().stream().filter(p->p.project().equals(resource.getProject())).findFirst().orElse(null);
        Path root=(state==null?snapshot.sourceRoots():state.configuration().sourceRoots()).stream().filter(p->p.startsWith(snapshot.root())).findFirst().orElse(snapshot.root());
        return root.resolve(relative).normalize();
    }
    public Path original(ICompilationUnit unit){return original(unit.getResource());}
    /** A scratch-buffer URI has an internal legal unit alias, never an implicit real-file rename. */
    public boolean virtualSource(Path path){return !unit(path).getElementName().equals(path.getFileName().toString());}
    @Override public void synchronize(WorkspaceSnapshot next,Cancellation cancel){
        ensureOpen();cancel.check();
        if(synchronizedModel&&snapshot!=null&&snapshot.equals(next))return;
        if(snapshot!=null&&!snapshot.root().equals(next.root()))throw new IllegalArgumentException("Cannot change workspace identity");
        try{
            configureProjects(next);
            var sourceNames=new LinkedHashMap<Path,UnitIdentity>();Set<String> identities=new HashSet<>();
            for(var source:next.sources()){
                cancel.check();ProjectState state=owner(source.file(),next);UnitIdentity identity=identity(source,state==null?javaProject:state.java());sourceNames.put(source.file(),identity);
                if(!identities.add((state==null?"":state.id())+"/"+identity.packageName()+"/"+identity.fileName()))throw new IllegalArgumentException("Duplicate Java source identity: "+identity.packageName()+"/"+identity.fileName());
            }
            synchronizedModel=false;
            if(snapshot==null||!snapshot.classpath().equals(next.classpath())){
                var entries=new ArrayList<IClasspathEntry>();entries.add(JavaCore.newSourceEntry(sourceRoot.getPath()));
                entries.add(JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));
                for(Path cp:next.classpath())if(java.nio.file.Files.exists(cp))entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(cp.toAbsolutePath().toString()),null,null));
                javaProject.setRawClasspath(entries.toArray(IClasspathEntry[]::new),project.getFolder("bin").getFullPath(),new NullProgressMonitor());
            }
            for(Path path:new ArrayList<>(units.keySet()))if(!sourceNames.containsKey(path)){var removed=units.remove(path);resourcePaths.remove(removed.getResource().getFullPath().toString());removed.discardWorkingCopy();removed.getResource().delete(true,null);}
            for(var source:next.sources()){
                cancel.check();UnitIdentity identity=sourceNames.get(source.file());String pkg=identity.packageName(),fileName=identity.fileName();
                ICompilationUnit unit=units.get(source.file());
                if(unit!=null&&(!unit.getParent().getElementName().equals(pkg)||!unit.getElementName().equals(fileName))){
                    resourcePaths.remove(unit.getResource().getFullPath().toString());unit.discardWorkingCopy();unit.getResource().delete(true,null);units.remove(source.file());unit=null;
                }
                if(unit==null){ProjectState state=owner(source.file(),next);var fragment=(state==null?sourceRoot:state.root()).createPackageFragment(pkg,true,new NullProgressMonitor());unit=fragment.createCompilationUnit(fileName,source.text(),true,new NullProgressMonitor());unit.becomeWorkingCopy(new NullProgressMonitor());units.put(source.file(),unit);resourcePaths.put(unit.getResource().getFullPath().toString(),source.file());}
                if(!unit.getSource().equals(source.text()))unit.getBuffer().setContents(source.text());
                unit.reconcile(ICompilationUnit.NO_AST, true, null, monitor(cancel));
                if(unit.hasUnsavedChanges())unit.commitWorkingCopy(true, monitor(cancel));
                // Search consults the same primary working copies, including unsaved source.
            }
            snapshot=next;synchronizedModel=true;asts.clear();elements.clear();resolved.clear();referenceCache.clear();
        }catch(CoreException ex){throw new IllegalStateException("Cannot synchronize semantic workspace: "+ex.getMessage(),ex);}
    }
    public CompilationUnit ast(Path path){
        ensureOpen();return asts.computeIfAbsent(path.toAbsolutePath().normalize(),p->{
            ASTParser parser=ASTParser.newParser(AST.JLS25);parser.setSource(unit(p));parser.setResolveBindings(true);parser.setBindingsRecovery(false);parser.setStatementsRecovery(false);parser.setCompilerOptions(unit(p).getJavaProject().getOptions(true));
            return (CompilationUnit)parser.createAST(new NullProgressMonitor());
        });
    }
    /** Candidate verification consumes the immutable snapshot, bypassing cached Eclipse file-buffer ASTs. */
    public CompilationUnit validationAst(Path path){
        Path normalized=path.toAbsolutePath().normalize();ASTParser parser=ASTParser.newParser(AST.JLS25);parser.setProject(javaProject(normalized));parser.setSource(snapshot.source(normalized).text().toCharArray());parser.setUnitName(unit(normalized).getPath().toString());parser.setResolveBindings(true);parser.setBindingsRecovery(false);parser.setStatementsRecovery(false);parser.setCompilerOptions(javaProject(normalized).getOptions(true));return (CompilationUnit)parser.createAST(new NullProgressMonitor());
    }
    /** One binding environment for the whole verification pass; private secondary copies pin snapshot text. */
    public Map<String,Integer> validationErrors(Cancellation cancellation){
        var errors=new TreeMap<String,Integer>();var copies=new ArrayList<ICompilationUnit>();
        WorkingCopyOwner owner=new WorkingCopyOwner(){};
        try{
            for(var source:snapshot.sources()){
                cancellation.check();ICompilationUnit copy=unit(source.file()).getWorkingCopy(owner,monitor(cancellation));
                copy.getBuffer().setContents(source.text());copies.add(copy);
            }
            for(IJavaElement projectElement:searchProjects()){
                IJavaProject current=(IJavaProject)projectElement;
                ICompilationUnit[] projectCopies=copies.stream().filter(c->c.getJavaProject().equals(current)).toArray(ICompilationUnit[]::new);
                if(projectCopies.length==0)continue;
                ASTParser parser=ASTParser.newParser(AST.JLS25);parser.setProject(current);parser.setWorkingCopyOwner(owner);parser.setResolveBindings(true);parser.setBindingsRecovery(false);parser.setStatementsRecovery(false);parser.setCompilerOptions(current.getOptions(true));
                parser.createASTs(projectCopies,new String[0],new ASTRequestor(){
                    @Override public void acceptAST(ICompilationUnit source,CompilationUnit tree){
                        cancellation.check();String file=original(source).toString();for(var problem:tree.getProblems())if(problem.isError())errors.merge(file+" | "+problem.getID()+": "+problem.getMessage(),1,Integer::sum);
                    }
                },monitor(cancellation));
            }return Map.copyOf(errors);
        }catch(JavaModelException ex){throw new IllegalStateException("Cannot verify source snapshots",ex);}
        finally{for(var copy:copies)try{copy.discardWorkingCopy();}catch(JavaModelException ignored){}}
    }
    public IJavaElement select(Location location)throws JavaModelException{
        ICompilationUnit unit=unit(location.file());int offset=Math.min(location.start(),unit.getSource().length());
        IJavaElement[] selected=unit.codeSelect(offset,location.end()-location.start());
        if(selected.length==0&&offset>0)selected=unit.codeSelect(offset-1,0);
        if(selected.length==1)return selected[0];
        if(selected.length>1)throw new IllegalArgumentException("Ambiguous Java symbol");
        IJavaElement enclosing=unit.getElementAt(offset);
        return enclosing instanceof IPackageDeclaration ? unit.getParent() : enclosing;
    }
    @Override public Symbol resolve(Location location,Cancellation cancel){cancel.check();if(resolved.containsKey(location))return resolved.get(location);try{Symbol result=symbol(select(location));resolved.put(location,result);return result;}catch(JavaModelException e){throw new IllegalStateException(e);}}
    public Symbol symbol(IJavaElement element){
        if(element==null)return null;
        String id=symbolId(element);elements.put(id,element);
        ICompilationUnit unit=(ICompilationUnit)element.getAncestor(IJavaElement.COMPILATION_UNIT);
        Location declaration=null;
        try{if(unit!=null&&element instanceof ISourceReference ref){ISourceRange range=element instanceof IMember member?member.getNameRange():element instanceof ILocalVariable local?local.getNameRange():element instanceof ITypeParameter parameter?parameter.getNameRange():ref.getSourceRange();Path file=original(unit);if(file!=null&&range!=null&&range.getOffset()>=0)declaration=new Location(file,range.getOffset(),range.getOffset()+range.getLength());}}catch(JavaModelException ignored){}
        String kind=switch(element.getElementType()){case IJavaElement.TYPE->"type";case IJavaElement.METHOD->"method";case IJavaElement.FIELD->"field";case IJavaElement.LOCAL_VARIABLE->"local";case IJavaElement.TYPE_PARAMETER->"type-parameter";case IJavaElement.PACKAGE_FRAGMENT->"package";default->"source";};
        String signature=element.getElementName();
        try{if(element instanceof IMethod method)signature=Signature.toString(method.getSignature(),method.getElementName(),method.getParameterNames(),false,true);}catch(JavaModelException ignored){}
        return new Symbol(id,element.getElementName(),kind,signature,declaration);
    }
    /** Opaque, versioned identity scoped to the original workspace, independent of its private Eclipse project. */
    public String symbolId(IJavaElement element) {
        ensureOpen();
        if (element == null || Arrays.stream(searchProjects()).noneMatch(p->p.equals(element.getJavaProject())))
            throw new IllegalArgumentException("Symbol is outside this semantic workspace");
        String projectHandle = element.getJavaProject().getHandleIdentifier();
        String projectId=projects.values().stream().filter(p->p.java().equals(element.getJavaProject())).map(ProjectState::id).findFirst().orElse("");
        String handle = element.getHandleIdentifier();
        if (!handle.startsWith(projectHandle)) throw new IllegalArgumentException("Invalid Java symbol identity");
        return symbolPrefix + Base64.getUrlEncoder().withoutPadding().encodeToString(
                (projectId.isEmpty()?handle.substring(projectHandle.length()):"#"+projectId+"\n"+handle.substring(projectHandle.length())).getBytes(StandardCharsets.UTF_8));
    }
    public IJavaElement element(String id) {
        ensureOpen();
        if (id == null || id.length() > 65536 || !id.startsWith(symbolPrefix))
            throw new IllegalArgumentException("Symbol identity belongs to another workspace or uses an unsupported version");
        IJavaElement cached = elements.get(id);
        if (cached != null) return cached;
        String encoded = id.substring(symbolPrefix.length());
        String relative = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8)).equals(encoded))
            throw new IllegalArgumentException("Invalid Java symbol identity encoding");
        IJavaProject target=javaProject;
        if(relative.startsWith("#")){int separator=relative.indexOf('\n');if(separator<0)throw new IllegalArgumentException("Invalid project symbol");ProjectState state=projects.get(relative.substring(1,separator));if(state==null)throw new IllegalArgumentException("Symbol project no longer exists");target=state.java();relative=relative.substring(separator+1);}
        IJavaElement result = JavaCore.create(target.getHandleIdentifier() + relative);
        if (result != null && !target.equals(result.getJavaProject()))
            throw new IllegalArgumentException("Symbol is outside this semantic workspace");
        return result;
    }
    private static String symbolPrefix(Path root) {
        try {
            byte[] bytes = root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
            return "refactorj-symbol:1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ":";
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
    @Override public List<Location> references(String id,Cancellation cancel){
        cancel.check();if(referenceCache.containsKey(id))return referenceCache.get(id);IJavaElement element=element(id);if(element==null||!element.exists())return List.of();
        List<Location> found=new ArrayList<>();
        try{
            var pattern=SearchPattern.createPattern(element,IJavaSearchConstants.REFERENCES,SearchPattern.R_EXACT_MATCH|SearchPattern.R_CASE_SENSITIVE);
            if(pattern==null)return List.of();
            new SearchEngine(units.values().toArray(ICompilationUnit[]::new)).search(pattern,new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},SearchEngine.createJavaSearchScope(searchProjects()),new SearchRequestor(){@Override public void acceptSearchMatch(SearchMatch match){cancel.check();if(match.getAccuracy()!=SearchMatch.A_ACCURATE)return;Path path=original(match.getResource());if(path!=null)found.add(new Location(path,match.getOffset(),match.getOffset()+match.getLength()));}},monitor(cancel));
        }catch(CoreException e){throw new IllegalStateException(e);}
        List<Location> result=found.stream().distinct().sorted(Comparator.comparing((Location l)->l.file().toString()).thenComparingInt(Location::start)).toList();referenceCache.put(id,result);return result;
    }
    @Override public Hierarchy hierarchy(String id,Cancellation cancel){cancel.check();IJavaElement e=element(id);if(!(e instanceof IType type))return null;try{var h=type.newTypeHierarchy(monitor(cancel));return new Hierarchy(symbol(type),Arrays.stream(h.getAllSupertypes(type)).map(this::symbol).toList(),Arrays.stream(h.getAllSubtypes(type)).map(this::symbol).toList());}catch(JavaModelException ex){throw new IllegalStateException(ex);}}
    @Override public List<Symbol> symbols(String query,Cancellation cancel){var result=new ArrayList<Symbol>();String needle=query.toLowerCase(Locale.ROOT);try{for(var unit:units.values())collect(unit,needle,cancel,result);}catch(JavaModelException e){throw new IllegalStateException(e);}return List.copyOf(result);}
    private void collect(IJavaElement element,String needle,Cancellation cancel,List<Symbol> out)throws JavaModelException{cancel.check();if(element instanceof IMember&&element.getElementName().toLowerCase(Locale.ROOT).contains(needle))out.add(symbol(element));if(element instanceof IParent parent)for(var child:parent.getChildren())collect(child,needle,cancel,out);}
    public static IProgressMonitor monitor(Cancellation cancellation){return new NullProgressMonitor(){@Override public boolean isCanceled(){return cancellation.cancelled()||Thread.currentThread().isInterrupted();}};}
    private record UnitIdentity(String packageName,String fileName){}
    private UnitIdentity identity(SourceSnapshot source,IJavaProject javaProject){
        ASTParser parser=ASTParser.newParser(AST.JLS25);parser.setCompilerOptions(javaProject.getOptions(true));parser.setSource(source.text().toCharArray());CompilationUnit tree=(CompilationUnit)parser.createAST(null);
        String name=source.file().getFileName().toString();
        if(JavaConventions.validateCompilationUnitName(name,javaProject.getOption(JavaCore.COMPILER_SOURCE,true),javaProject.getOption(JavaCore.COMPILER_COMPLIANCE,true)).matches(IStatus.ERROR)){
            if(!source.open()||java.nio.file.Files.exists(source.file()))throw new IllegalArgumentException("Invalid Java source file name: "+name);
            name="Scratch_"+Integer.toUnsignedString(source.file().toString().hashCode(),16)+".java";
            boolean chosen=false;
            for(Object value:tree.types())if(value instanceof AbstractTypeDeclaration type&&!(type instanceof ImplicitTypeDeclaration)){
                if(!chosen||Modifier.isPublic(type.getModifiers())){name=type.getName().getIdentifier()+".java";chosen=true;}if(Modifier.isPublic(type.getModifiers()))break;
            }
        }
        return new UnitIdentity(tree.getPackage()==null?"":tree.getPackage().getName().getFullyQualifiedName(),name);
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Semantic workspace is closed");}
    @Override public void close(){if(closed)return;closed=true;for(var unit:units.values())try{unit.discardWorkingCopy();}catch(JavaModelException ignored){}for(var state:projects.values())try{state.project().delete(true,true,null);}catch(CoreException ignored){}projects.clear();try{project.delete(true,true,new NullProgressMonitor());}catch(CoreException ignored){}apiImages.close();units.clear();asts.clear();elements.clear();resolved.clear();referenceCache.clear();}
}
