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
    /** Declaration order is stable for a parameter-type-only Change Signature. */
    static Map<Path,List<Target>> declarations(LtkWorkspace workspace) {
        var result = new LinkedHashMap<Path,List<Target>>();
        for (var source : workspace.snapshot().sources()) {
            var values = new ArrayList<Target>();
            workspace.validationAst(source.file()).accept(new ASTVisitor() {
                @Override public boolean visit(MethodDeclaration method) {
                    var binding = method.resolveBinding();
                    values.add(binding == null || binding.isRecovered() ? null : target(binding));
                    return true;
                }
            });
            result.put(source.file(), values);
        }
        return result;
    }
    private static Target target(IMethodBinding binding) {
        var d = binding.getMethodDeclaration();
        return new Target(d.getDeclaringClass().getErasure().getQualifiedName(), d.getName(),
                Arrays.stream(d.getParameterTypes()).map(t -> t.getErasure().getQualifiedName()).toList(),
                d.getReturnType().getErasure().getQualifiedName(), d.isConstructor());
    }
    static boolean sameDeclarationsAfterSignatureEdit(Map<Path,List<Target>> oldCalls, Map<Path,List<Target>> newCalls,
            Map<Path,List<Target>> oldDeclarations, Map<Path,List<Target>> newDeclarations) {
        var changes = new HashMap<Target,Target>();
        for (var entry : oldDeclarations.entrySet()) {
            var before = entry.getValue(); var after = newDeclarations.get(entry.getKey());
            if (after == null || before.size() != after.size()) return false;
            for (int i = 0; i < before.size(); i++) if (before.get(i) != null) changes.put(before.get(i), after.get(i));
        }
        for (var entry : oldCalls.entrySet()) {
            var before = entry.getValue(); var after = newCalls.get(entry.getKey());
            if (after == null || before.size() != after.size()) return false;
            for (int i = 0; i < before.size(); i++) {
                var target = before.get(i);
                if (target != null && !Objects.equals(changes.getOrDefault(target, target), after.get(i))) return false;
            }
        }
        return true;
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
