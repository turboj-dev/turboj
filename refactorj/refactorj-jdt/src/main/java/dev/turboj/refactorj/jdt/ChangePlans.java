package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.Model.*;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.internal.corext.refactoring.changes.*;
import org.eclipse.jdt.internal.corext.refactoring.nls.changes.CreateTextFileChange;
import org.eclipse.ltk.core.refactoring.*;
import org.eclipse.text.edits.*;

/** Converts LTK's preview tree; never invokes Change.perform(). */
final class ChangePlans {
    final LtkWorkspace workspace;
    final List<FileEdits> edits=new ArrayList<>();
    final List<FileOperation> files=new ArrayList<>();
    ChangePlans(LtkWorkspace workspace){this.workspace=workspace;}
    void collect(Change change,IProgressMonitor monitor)throws CoreException{
        if(change instanceof CompositeChange composite){for(Change child:composite.getChildren())collect(child,monitor);return;}
        if(change instanceof TextChange text){
            Object target=text.getModifiedElement();IResource resource=target instanceof IJavaElement e?e.getResource():target instanceof IResource r?r:null;
            if(resource==null)throw new IllegalArgumentException("Unsupported text target: "+target);
            Path path=workspace.original(resource);String before=workspace.snapshot().source(path).text();String after;try{var document=new org.eclipse.jface.text.Document(before);text.getEdit().copy().apply(document);after=document.get();}catch(org.eclipse.jface.text.BadLocationException ex){throw new IllegalArgumentException("Invalid source-versioned text edit",ex);}
            if(before.equals(after))return;
            var leaves=new ArrayList<dev.turboj.refactorj.api.Model.TextEdit>();boolean simple=flatten(text.getEdit(),leaves);
            leaves.sort(Comparator.comparingInt(dev.turboj.refactorj.api.Model.TextEdit::start));
            try{if(!simple||!dev.turboj.refactorj.api.Model.TextEdit.apply(before,leaves).equals(after))leaves=new ArrayList<>(difference(before,after));}catch(IllegalArgumentException ex){leaves=new ArrayList<>(difference(before,after));}
            edits.add(new FileEdits(path,leaves));return;
        }
        if(change instanceof CreateTextFileChange create){Path path=workspace.original(ResourcesPlugin.getWorkspace().getRoot().getFile(create.getPath()));files.add(new FileOperation(FileOperationKind.CREATE,path,null,create.getPreview()));return;}
        if(change instanceof RenameCompilationUnitChange rename){Object modified=rename.getModifiedElement();IResource resource=modified instanceof IJavaElement e?e.getResource():(IResource)modified;Path path=workspace.original(resource);renamePrimaryType(path,rename.getOldName(),rename.getNewName());if(!workspace.virtualSource(path))files.add(new FileOperation(FileOperationKind.MOVE,path,path.resolveSibling(rename.getNewName()),null));return;}
        if(change instanceof MoveCompilationUnitChange move){Path path=workspace.original(move.getCu());changePackage(path,move.getDestinationPackage().getElementName());Path destination=workspace.original(move.getDestinationPackage().getCompilationUnit(move.getCu().getElementName()));files.add(new FileOperation(FileOperationKind.MOVE,path,destination,null));return;}
        if(change instanceof RenamePackageChange rename){Object modified=rename.getModifiedElement();IPackageFragment fragment=modified instanceof IPackageFragment p?p:(IPackageFragment)JavaCore.create((IResource)modified);String old=fragment.getElementName();for(var unit:workspace.units()){String pkg=unit.getParent().getElementName();if(pkg.equals(old)||(rename.getRenameSubpackages()&&pkg.startsWith(old+"."))){String name=rename.getNewName()+pkg.substring(old.length());changePackage(workspace.original(unit),name);var root=(IPackageFragmentRoot)fragment.getParent();Path dest=workspace.original(root.getPackageFragment(name).getCompilationUnit(unit.getElementName()));files.add(new FileOperation(FileOperationKind.MOVE,workspace.original(unit),dest,null));}}return;}
        if(change instanceof DeleteSourceManipulationChange delete){Object modified=delete.getModifiedElement();if(modified instanceof ICompilationUnit unit){files.add(new FileOperation(FileOperationKind.DELETE,workspace.original(unit),null,null));return;}if(modified instanceof IPackageFragment fragment){for(var unit:fragment.getCompilationUnits())files.add(new FileOperation(FileOperationKind.DELETE,workspace.original(unit),null,null));return;}}
        if(change instanceof CreatePackageChange)return; // Parent directories are created with their source files.
        if(change instanceof NullChange)return;
        throw new IllegalArgumentException("Cannot safely represent Eclipse change "+change.getClass().getName());
    }
    private void renamePrimaryType(Path path,String oldFile,String newFile)throws CoreException{
        String oldName=oldFile.endsWith(".java")?oldFile.substring(0,oldFile.length()-5):oldFile;
        String newName=newFile.endsWith(".java")?newFile.substring(0,newFile.length()-5):newFile;
        CompilationUnit tree=workspace.ast(path);ASTRewrite rewrite=ASTRewrite.create(tree.getAST());
        for(Object value:tree.types())if(value instanceof AbstractTypeDeclaration type&&type.getName().getIdentifier().equals(oldName)){
            rewrite.replace(type.getName(),tree.getAST().newSimpleName(newName),null);
            for(Object body:type.bodyDeclarations())if(body instanceof MethodDeclaration method&&method.isConstructor())rewrite.replace(method.getName(),tree.getAST().newSimpleName(newName),null);
        }
        appendRewrite(path,rewrite);
    }
    private void changePackage(Path path,String name)throws CoreException{
        CompilationUnit tree=workspace.ast(path);ASTRewrite rewrite=ASTRewrite.create(tree.getAST());PackageDeclaration old=tree.getPackage();
        if(name.isEmpty()){if(old!=null)rewrite.remove(old,null);}
        else if(old==null){PackageDeclaration added=tree.getAST().newPackageDeclaration();added.setName(tree.getAST().newName(name));rewrite.set(tree,CompilationUnit.PACKAGE_PROPERTY,added,null);}
        else if(!old.getName().getFullyQualifiedName().equals(name))rewrite.replace(old.getName(),tree.getAST().newName(name),null);
        appendRewrite(path,rewrite);
    }
    void appendRewrite(Path path,ASTRewrite rewrite)throws CoreException{
        var document=new org.eclipse.jface.text.Document(workspace.snapshot().source(path).text());
        var edit=rewrite.rewriteAST(document,workspace.javaProject().getOptions(true));
        var converted=new ArrayList<dev.turboj.refactorj.api.Model.TextEdit>();if(!flatten(edit,converted))throw new IllegalArgumentException("Unsupported implicit Java-model rewrite");
        if(!converted.isEmpty())edits.add(new FileEdits(path,converted));
    }
    void normalize(){
        var grouped=new TreeMap<Path,List<dev.turboj.refactorj.api.Model.TextEdit>>();
        for(var file:edits)grouped.computeIfAbsent(file.file(),p->new ArrayList<>()).addAll(file.edits());
        edits.clear();for(var entry:grouped.entrySet()){
            var distinctReplacements=new HashSet<dev.turboj.refactorj.api.Model.TextEdit>();
            var ordered=entry.getValue().stream().filter(e->e.start()==e.end()||distinctReplacements.add(e)).sorted(Comparator.comparingInt(dev.turboj.refactorj.api.Model.TextEdit::start).thenComparingInt(dev.turboj.refactorj.api.Model.TextEdit::end)).toList();
            dev.turboj.refactorj.api.Model.TextEdit.apply(workspace.snapshot().source(entry.getKey()).text(),ordered);
            edits.add(new FileEdits(entry.getKey(),ordered));
        }
    }
    private static boolean flatten(org.eclipse.text.edits.TextEdit edit,List<dev.turboj.refactorj.api.Model.TextEdit> into){
        if(edit instanceof ReplaceEdit r){into.add(new dev.turboj.refactorj.api.Model.TextEdit(r.getOffset(),r.getExclusiveEnd(),r.getText()));return true;}
        if(edit instanceof InsertEdit i){into.add(new dev.turboj.refactorj.api.Model.TextEdit(i.getOffset(),i.getOffset(),i.getText()));return true;}
        if(edit instanceof DeleteEdit d){into.add(new dev.turboj.refactorj.api.Model.TextEdit(d.getOffset(),d.getExclusiveEnd(),""));return true;}
        if(!(edit instanceof MultiTextEdit))return false;boolean simple=true;for(var child:edit.getChildren())simple&=flatten(child,into);return simple;
    }
    static List<dev.turboj.refactorj.api.Model.TextEdit> difference(String before,String after){int start=0,limit=Math.min(before.length(),after.length());while(start<limit&&before.charAt(start)==after.charAt(start))start++;int oldEnd=before.length(),newEnd=after.length();while(oldEnd>start&&newEnd>start&&before.charAt(oldEnd-1)==after.charAt(newEnd-1)){oldEnd--;newEnd--;}return List.of(new dev.turboj.refactorj.api.Model.TextEdit(start,oldEnd,after.substring(start,newEnd)));}
}
