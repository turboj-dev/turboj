package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.Request;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.*;
import org.eclipse.ltk.core.refactoring.*;

/** Same-JVM semantic refactoring over one versioned Java model. Serialize access per workspace. */
public final class LtkRefactorJ implements RefactorJ, JavaIntelligenceProvider {
    private final LtkWorkspace workspace;
    private SharedJavaIntelligence intelligence;
    private JdtQuickActions quickActions;
    private WorkspaceSnapshot baselineSnapshot;
    private Map<String,Integer> baselineErrors=Map.of();
    private record Checked(Plan plan,Validation validation){}
    private final LinkedHashMap<String,Checked> checked=new LinkedHashMap<>(8,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Checked> entry){return size()>8;}};
    public LtkRefactorJ(WorkspaceSnapshot snapshot)throws CoreException{workspace=new LtkWorkspace(snapshot);}
    @Override public JavaIntelligence intelligence(){if(intelligence==null)intelligence=new SharedJavaIntelligence(workspace);return intelligence;}
    @Override public LtkWorkspace workspace(){return workspace;}
    @Override public JavaQuickActions quickActions(){if(quickActions==null)quickActions=new JdtQuickActions(this);return quickActions;}
    @Override public List<Descriptor> available(Location location,Cancellation cancel){
        cancel.check();var ast=workspace.ast(location.file());var node=org.eclipse.jdt.core.dom.NodeFinder.perform(ast,location.start(),location.end()-location.start());
        IJavaElement selected;try{selected=workspace.select(location);}catch(JavaModelException ex){selected=null;}
        List<Descriptor> out=new ArrayList<>();for(Operation op:Operation.values()){
            boolean range=location.end()>location.start();
            boolean expression=ancestor(node,org.eclipse.jdt.core.dom.Expression.class)!=null;
            boolean invocation=ancestor(node,org.eclipse.jdt.core.dom.MethodInvocation.class)!=null;
            boolean local=selected instanceof ILocalVariable;
            boolean type=selected instanceof IType;
            boolean member=selected instanceof IMember;
            boolean problem=Arrays.stream(ast.getProblems()).anyMatch(p->p.isError()&&p.getSourceStart()<=location.end()&&p.getSourceEnd()>=location.start());
            boolean applicable=node!=null&&switch(op){
                case RENAME -> selected instanceof IMember||local||selected instanceof ITypeParameter||selected instanceof IPackageFragment;
                case EXTRACT_METHOD -> range&&ancestor(node,org.eclipse.jdt.core.dom.MethodDeclaration.class)!=null;
                case EXTRACT_VARIABLE,EXTRACT_CONSTANT,EXTRACT_PARAMETER -> range&&expression;
                case EXTRACT_FIELD,INLINE_VARIABLE -> local;
                case INLINE_METHOD,CHANGE_SIGNATURE -> selected instanceof IMethod;
                case MOVE,SAFE_DELETE -> member||local||selected instanceof IPackageFragment;
                case EXTRACT_INTERFACE,INTRODUCE_INTERFACE -> type;
                case PULL_UP,PUSH_DOWN -> member&&!type;
                case ENCAPSULATE_FIELD -> selected instanceof IField;
                case CONSTRUCTOR_TO_FACTORY -> selected instanceof IMethod method&&isConstructor(method);
                case ANONYMOUS_TO_LAMBDA -> ancestor(node,org.eclipse.jdt.core.dom.AnonymousClassDeclaration.class)!=null||ancestor(node,org.eclipse.jdt.core.dom.ClassInstanceCreation.class)!=null;
                case LAMBDA_TO_METHOD_REFERENCE -> ancestor(node,org.eclipse.jdt.core.dom.LambdaExpression.class)!=null;
                case METHOD_REFERENCE_TO_LAMBDA -> ancestor(node,org.eclipse.jdt.core.dom.MethodReference.class)!=null;
                case SWITCH_TO_EXPRESSION -> ancestor(node,org.eclipse.jdt.core.dom.SwitchStatement.class)!=null;
                case TYPE_TO_VAR,VAR_TO_TYPE -> ancestor(node,org.eclipse.jdt.core.dom.VariableDeclarationStatement.class)!=null;
                case ORGANIZE_IMPORTS -> true;
                case ADD_IMPORT -> problem;
                case REMOVE_IMPORT -> ancestor(node,org.eclipse.jdt.core.dom.ImportDeclaration.class)!=null;
                case IMPLEMENT_METHODS -> type||ancestor(node,org.eclipse.jdt.core.dom.TypeDeclaration.class)!=null&&problem;
                case CREATE_METHOD -> invocation&&problem;
                case CREATE_CONSTRUCTOR -> ancestor(node,org.eclipse.jdt.core.dom.ClassInstanceCreation.class)!=null&&problem;
                case CREATE_FIELD,CREATE_LOCAL,FIX_TYPE_MISMATCH,REMOVE_UNREACHABLE -> problem;
                case CREATE_CLASS -> false; // Offered by diagnostic-scoped Quick Fix, never a generic form.
            };
            out.add(new Descriptor(op,op.id().replace('-',' '),applicable,applicable?List.of():List.of(new Diagnostic(Severity.INFO,"context.not.applicable","Select an eligible Java declaration, expression or statement for this operation",location))));
        }return List.copyOf(out);
    }
    private static <T>T ancestor(org.eclipse.jdt.core.dom.ASTNode node,Class<T> type){for(var current=node;current!=null;current=current.getParent())if(type.isInstance(current))return type.cast(current);return null;}
    private static boolean isConstructor(IMethod method){try{return method.isConstructor();}catch(JavaModelException ex){return false;}}
    @Override public Plan prepare(Request request,Cancellation cancel){
        WorkspaceSnapshot before=workspace.snapshot();List<Diagnostic> diagnostics=new ArrayList<>();List<Symbol> symbols=new ArrayList<>();Change change=null;
        try{
            cancel.check();Location at=request.location();before.source(at.file());ICompilationUnit unit=workspace.unit(at.file());
            if(at.end()>unit.getSource().length())throw new IllegalArgumentException("Selection is outside the source snapshot");
            var selected=workspace.resolve(at,cancel);if(selected!=null)symbols.add(selected);
            if(selected!=null&&(request.operation()==Operation.RENAME||request.operation()==Operation.MOVE||request.operation()==Operation.CHANGE_SIGNATURE||request.operation()==Operation.CONSTRUCTOR_TO_FACTORY)){
                IJavaElement declaration=workspace.element(selected.id());
                if(declaration instanceof IPackageFragment||declaration instanceof IMember member&&(Flags.isPublic(member.getFlags())||Flags.isProtected(member.getFlags())))diagnostics.add(new Diagnostic(Severity.WARNING,"external.consumers","Reflection strings, generated sources and external binary consumers cannot be verified by source refactoring",at));
            }
            EvaluationSafety.check(request,workspace);
            var monitor=LtkWorkspace.monitor(cancel);
            Refactoring refactoring=ModernizationOperations.create(request,workspace,unit,workspace.ast(at.file()));
            if(refactoring==null)refactoring=ClassicOperations.create(request,workspace,monitor);
            if(refactoring==null)throw new IllegalArgumentException("No semantically applicable transformation at this location");
            RefactoringStatus initial=refactoring.checkInitialConditions(monitor);append(initial,at,diagnostics);
            if(initial.hasError())return result(request,before,symbols,List.of(),List.of(),diagnostics,ValidationStatus.CONFLICTED);
            ClassicOperations.configureAfterInitial(refactoring,request,workspace,monitor);
            RefactoringStatus finalStatus=refactoring.checkFinalConditions(monitor);append(finalStatus,at,diagnostics);
            if(finalStatus.hasError())return result(request,before,symbols,List.of(),List.of(),diagnostics,ValidationStatus.CONFLICTED);
            cancel.check();change=refactoring.createChange(monitor);var converted=new ChangePlans(workspace);converted.collect(change,monitor);
            if(request instanceof Requests.IntroduceInterface introduce)SelectedInterfaceUsage.append(introduce,workspace,converted);
            converted.normalize();
            if(converted.edits.isEmpty()&&converted.files.isEmpty())diagnostics.add(new Diagnostic(Severity.ERROR,"no.changes","No applicable source changes were produced",at));
            var provisional=result(request,before,symbols,converted.edits,converted.files,diagnostics,ValidationStatus.VALID);
            Validation validation=validate(provisional,cancel);diagnostics.addAll(validation.diagnostics());
            Plan finalPlan=new Plan(provisional.id(),request.operation(),before,symbols,converted.edits,converted.files,diagnostics,validation.valid()?ValidationStatus.VALID:ValidationStatus.CONFLICTED,provisional.description());checked.put(finalPlan.id(),new Checked(finalPlan,validation));return finalPlan;
        }catch(CancellationException|OperationCanceledException ex){return result(request,before,symbols,List.of(),List.of(),List.of(new Diagnostic(Severity.INFO,"cancelled","Refactoring cancelled",request.location())),ValidationStatus.CANCELLED);
        }catch(CoreException|RuntimeException ex){diagnostics.add(new Diagnostic(Severity.ERROR,"refactoring.rejected",Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()),request.location()));return result(request,before,symbols,List.of(),List.of(),diagnostics,ValidationStatus.CONFLICTED);
        }finally{if(change!=null)change.dispose();}
    }
    @Override public Validation validate(Plan plan,Cancellation cancel){
        cancel.check();if(!workspace.snapshot().equals(plan.before()))return new Validation(false,List.of(new Diagnostic(Severity.ERROR,"stale.plan","Workspace changed after this preview was prepared",null)));
        if(!plan.valid())return new Validation(false,List.of());
        Checked prior=checked.get(plan.id());if(prior!=null&&prior.plan().equals(plan))return prior.validation();
        WorkspaceSnapshot before=workspace.snapshot();List<Diagnostic> diagnostics=new ArrayList<>();
        try{
            if(!before.equals(baselineSnapshot)){baselineErrors=workspace.validationErrors(cancel);baselineSnapshot=before;}
            Map<String,Integer> oldErrors=remapErrors(baselineErrors,plan);
            var oldCalls=plan.operation()==Operation.INTRODUCE_INTERFACE?InvocationBindings.capture(workspace):null;
            WorkspaceSnapshot candidate=candidate(plan);workspace.synchronize(candidate,cancel);
            if(oldCalls!=null){
                Set<String> interfaces=new HashSet<>();
                for(var operation:plan.fileOperations())if(operation.kind()==FileOperationKind.CREATE){var tree=workspace.validationAst(operation.path());for(Object value:tree.types())if(value instanceof org.eclipse.jdt.core.dom.TypeDeclaration type&&type.isInterface()&&type.resolveBinding()!=null)interfaces.add(type.resolveBinding().getQualifiedName());}
                if(!InvocationBindings.unchanged(oldCalls,InvocationBindings.capture(workspace),interfaces))diagnostics.add(new Diagnostic(Severity.ERROR,"changed.overload","Selected interface migration would change an existing overload, constructor or method-reference target",null));
            }
            Map<String,Integer> newErrors=workspace.validationErrors(cancel);for(var entry:newErrors.entrySet())if(entry.getValue()>oldErrors.getOrDefault(entry.getKey(),0))diagnostics.add(new Diagnostic(Severity.ERROR,"introduced.compiler.error",entry.getKey(),null));
        }catch(RuntimeException ex){diagnostics.add(new Diagnostic(Severity.ERROR,"invalid.plan",Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()),null));}
        finally{workspace.synchronize(before,Cancellation.NONE);}
        Validation result=new Validation(diagnostics.isEmpty(),diagnostics);checked.put(plan.id(),new Checked(plan,result));return result;
    }
    private static Map<String,Integer> remapErrors(Map<String,Integer> errors,Plan plan){
        if(plan.fileOperations().isEmpty())return errors;
        var mapped=new HashMap<String,Integer>();for(var error:errors.entrySet()){
            String key=error.getKey();for(var operation:plan.fileOperations())if(operation.kind()==FileOperationKind.MOVE&&key.startsWith(operation.path()+" | "))key=operation.destination()+key.substring(operation.path().toString().length());
            mapped.merge(key,error.getValue(),Integer::sum);
        }return mapped;
    }
    private static WorkspaceSnapshot candidate(Plan plan){
        var files=new LinkedHashMap<Path,SourceSnapshot>();for(var source:plan.before().sources())files.put(source.file(),source);
        for(var changes:plan.edits()){var source=files.get(changes.file());if(source==null)throw new IllegalArgumentException("Edit target is not in the source snapshot");files.put(source.file(),new SourceSnapshot(source.file(),TextEdit.apply(source.text(),changes.edits()),source.version()+1,source.open()));}
        for(var operation:plan.fileOperations())switch(operation.kind()){
            case CREATE->{if(files.containsKey(operation.path()))throw new IllegalArgumentException("Creation would overwrite an existing source");files.put(operation.path(),new SourceSnapshot(operation.path(),operation.content(),0,false));}
            case DELETE->{if(files.remove(operation.path())==null)throw new IllegalArgumentException("Missing deletion target");}
            case MOVE->{var source=files.remove(operation.path());if(source==null||files.containsKey(operation.destination()))throw new IllegalArgumentException("Invalid move or destination collision");files.put(operation.destination(),new SourceSnapshot(operation.destination(),source.text(),source.version()+1,source.open()));}
        }
        return new WorkspaceSnapshot(plan.before().root(),plan.before().version()+1,List.copyOf(files.values()),plan.before().sourceRoots(),plan.before().classpath(),plan.before().projects());
    }
    private static Plan result(Request request,WorkspaceSnapshot before,List<Symbol> symbols,List<FileEdits> edits,List<FileOperation> files,List<Diagnostic> diagnostics,ValidationStatus status){return new Plan(UUID.randomUUID().toString(),request.operation(),before,symbols,edits,files,diagnostics,status,request.operation().id());}
    private static void append(RefactoringStatus status,Location at,List<Diagnostic> into){for(var entry:status.getEntries()){Severity severity=switch(entry.getSeverity()){case RefactoringStatus.FATAL->Severity.FATAL;case RefactoringStatus.ERROR->Severity.ERROR;case RefactoringStatus.WARNING->Severity.WARNING;default->Severity.INFO;};into.add(new Diagnostic(severity,"jdt."+entry.getCode(),entry.getMessage(),at));}}
    @Override public void close(){if(intelligence!=null)intelligence.close();workspace.close();checked.clear();baselineSnapshot=null;baselineErrors=Map.of();}
}
