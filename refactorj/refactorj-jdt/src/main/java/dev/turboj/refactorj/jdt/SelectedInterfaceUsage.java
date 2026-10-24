package dev.turboj.refactorj.jdt;
import dev.turboj.refactorj.api.Requests.IntroduceInterface;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/** Explicit, binding-resolved usage selection accompanying Eclipse's interface extraction. */
final class SelectedInterfaceUsage {
    static void append(IntroduceInterface request,LtkWorkspace workspace,ChangePlans plans)throws CoreException{
        if(request.usages().isEmpty())return;
        IJavaElement selected=workspace.select(request.location());IType owner=selected instanceof IType t?t:(IType)selected.getAncestor(IJavaElement.TYPE);
        if(owner==null)throw new IllegalArgumentException("Select the class introducing the interface");
        String qualified=owner.getPackageFragment().getElementName();qualified=(qualified.isBlank()?"":qualified+".")+request.interfaceName();
        var rewrites=new LinkedHashMap<Path,ASTRewrite>();var seen=new HashSet<String>();
        for(var location:request.usages()){
            CompilationUnit tree=workspace.ast(location.file());ASTNode node=NodeFinder.perform(tree,location.start(),location.end()-location.start());
            while(node!=null&&!(node instanceof SimpleType))node=node.getParent();
            if(!(node instanceof SimpleType type))throw new IllegalArgumentException("Selected interface usage must identify a declared Java type");
            ITypeBinding binding=type.resolveBinding();if(binding==null||binding.isRecovered()||!owner.equals(binding.getTypeDeclaration().getJavaElement()))throw new IllegalArgumentException("Selected usage does not resolve to the extracted class");
            ASTNode context=type;while(context.getParent() instanceof Type)context=context.getParent();
            if(context.getParent() instanceof ClassInstanceCreation||context.getParent() instanceof TypeDeclaration||context.getParent() instanceof TypeLiteral)throw new IllegalArgumentException("Constructors, superclass clauses and class literals cannot be replaced with an interface");
            if(!seen.add(location.file()+":"+type.getStartPosition()))continue;
            ASTRewrite rewrite=rewrites.computeIfAbsent(location.file(),p->ASTRewrite.create(tree.getAST()));
            rewrite.replace(type,tree.getAST().newSimpleType(tree.getAST().newName(qualified)),null);
        }
        for(var entry:rewrites.entrySet())plans.appendRewrite(entry.getKey(),entry.getValue());
    }
}
