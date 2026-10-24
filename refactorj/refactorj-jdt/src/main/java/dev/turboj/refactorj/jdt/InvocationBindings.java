package dev.turboj.refactorj.jdt;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.jdt.core.dom.*;

/** Detects silently changed overload selection when selected class usages become interface types. */
final class InvocationBindings {
    record Target(String owner,String name,List<String> parameters,String result,boolean constructor){}
    static Map<Path,List<Target>> capture(LtkWorkspace workspace){
        var all=new LinkedHashMap<Path,List<Target>>();
        for(var source:workspace.snapshot().sources()){
            var targets=new ArrayList<Target>();
            workspace.validationAst(source.file()).accept(new ASTVisitor(){
                @Override public boolean visit(MethodInvocation node){add(node.resolveMethodBinding());return true;}
                @Override public boolean visit(SuperMethodInvocation node){add(node.resolveMethodBinding());return true;}
                @Override public boolean visit(ClassInstanceCreation node){add(node.resolveConstructorBinding());return true;}
                @Override public boolean visit(ExpressionMethodReference node){add(node.resolveMethodBinding());return true;}
                @Override public boolean visit(TypeMethodReference node){add(node.resolveMethodBinding());return true;}
                @Override public boolean visit(SuperMethodReference node){add(node.resolveMethodBinding());return true;}
                @Override public boolean visit(CreationReference node){add(node.resolveMethodBinding());return true;}
                private void add(IMethodBinding binding){if(binding==null||binding.isRecovered())targets.add(null);else{IMethodBinding declaration=binding.getMethodDeclaration();targets.add(new Target(declaration.getDeclaringClass().getErasure().getQualifiedName(),declaration.getName(),Arrays.stream(declaration.getParameterTypes()).map(t->t.getErasure().getQualifiedName()).toList(),declaration.getReturnType().getErasure().getQualifiedName(),declaration.isConstructor()));}}
            });all.put(source.file(),targets);
        }return all;
    }
    static boolean unchanged(Map<Path,List<Target>> before,Map<Path,List<Target>> after,Set<String> generatedInterfaces){
        for(var entry:before.entrySet()){
            var old=entry.getValue();var now=after.get(entry.getKey());if(now==null||old.size()!=now.size())return false;
            for(int i=0;i<old.size();i++){
                Target a=old.get(i),b=now.get(i);if(Objects.equals(a,b))continue;
                // Interface dispatch is the intended change; parameter/return types and overload remain identical.
                if(a==null||b==null||a.constructor()||b.constructor()||!generatedInterfaces.contains(b.owner())||!a.name().equals(b.name())||!a.parameters().equals(b.parameters())||!a.result().equals(b.result()))return false;
            }
        }return true;
    }
}
