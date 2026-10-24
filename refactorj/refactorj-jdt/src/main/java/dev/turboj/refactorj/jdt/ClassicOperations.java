package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Requests.*;
import java.util.*;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.code.*;
import org.eclipse.jdt.internal.corext.refactoring.reorg.*;
import org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.structure.*;
import org.eclipse.ltk.core.refactoring.*;
import org.eclipse.ltk.core.refactoring.participants.*;

/** Version-pinned adapter to Eclipse's proven, headless refactoring processors. */
final class ClassicOperations {
    private ClassicOperations(){}
    static Refactoring create(Request request,LtkWorkspace ws,IProgressMonitor monitor)throws CoreException{
        var at=request.location();var unit=ws.unit(at.file());int start=at.start(),length=at.end()-start;IJavaElement selected=ws.select(at);
        return switch(request){
            case Rename r -> rename(selected,r.name(),r.hierarchy(),ws);
            case ExtractMethod r -> new ExtractMethodRefactoring(unit,start,length);
            case ExtractVariable r -> new ExtractTempRefactoring(unit,start,length);
            case ExtractConstant r -> new ExtractConstantRefactoring(unit,start,length);
            case ExtractField r -> new PromoteTempToFieldRefactoring(unit,start,length);
            case ExtractParameter r -> new IntroduceParameterRefactoring(unit,start,length);
            case InlineVariable r -> new InlineTempRefactoring(unit,ws.ast(at.file()),start,length);
            case InlineMethod r -> InlineMethodRefactoring.create(unit,ws.ast(at.file()),start,length);
            case ChangeSignature r -> wrap(new ChangeSignatureProcessor(require(selected,IMethod.class)));
            case EncapsulateField r -> new SelfEncapsulateFieldRefactoring(require(selected,IField.class));
            case ConstructorToFactory r -> new IntroduceFactoryRefactoring(unit,start,length);
            case ExtractInterface r -> wrap(new ExtractInterfaceProcessor(type(selected),settings()));
            case IntroduceInterface r -> wrap(new ExtractInterfaceProcessor(type(selected),settings()));
            case PullUp r -> wrap(new PullUpRefactoringProcessor(members(ws,selected,r.memberIds()),settings()));
            case PushDown r -> wrap(new PushDownRefactoringProcessor(members(ws,selected,r.memberIds())));
            case SafeDelete r -> {for(var source:ws.snapshot().sources())if(Arrays.stream(ws.ast(source.file()).getProblems()).anyMatch(org.eclipse.jdt.core.compiler.IProblem::isError))throw new IllegalArgumentException("Safe delete cannot establish absence of usages while the workspace contains unresolved Java errors");var symbol=ws.symbol(selected);if(symbol==null)throw new IllegalArgumentException("Select a declaration");var uses=ws.references(symbol.id(),Cancellation.NONE);if(!uses.isEmpty())throw new IllegalArgumentException("Safe delete rejected: "+uses.size()+" resolved references remain");var p=new JavaDeleteProcessor(new Object[]{selected});p.setSuggestGetterSetterDeletion(false);p.setQueries(QUERIES);yield wrap(p);}
            case Move r -> move(r,selected,ws,monitor);
            default -> null;
        };
    }
    static void configureAfterInitial(Refactoring refactoring,Request request,LtkWorkspace ws,IProgressMonitor monitor)throws CoreException{
        Object target=refactoring instanceof ProcessorBasedRefactoring p?p.getProcessor():refactoring;
        switch(request){
            case ExtractMethod r -> {var p=(ExtractMethodRefactoring)target;p.setMethodName(r.name());p.setVisibility(visibility(r.visibility()));p.setReplaceDuplicates(r.replaceDuplicates());}
            case ExtractVariable r -> {var p=(ExtractTempRefactoring)target;p.setTempName(r.name());p.setReplaceAllOccurrences(r.replaceAll());p.setDeclareFinal(r.makeFinal());p.setCheckResultForCompileProblems(true);}
            case ExtractConstant r -> {var p=(ExtractConstantRefactoring)target;p.setConstantName(r.name());p.setVisibility(switch(r.visibility()){case PRIVATE->"private";case PUBLIC->"public";case PROTECTED->"protected";case PACKAGE->"";});p.setReplaceAllOccurrences(r.replaceAll());p.setCheckResultForCompileProblems(true);}
            case ExtractField r -> {var p=(PromoteTempToFieldRefactoring)target;p.setFieldName(r.name());p.setVisibility(visibility(r.visibility()));p.setInitializeIn(switch(r.initialization()){case CURRENT_METHOD->PromoteTempToFieldRefactoring.INITIALIZE_IN_METHOD;case FIELD_DECLARATION->PromoteTempToFieldRefactoring.INITIALIZE_IN_FIELD;case CONSTRUCTORS->PromoteTempToFieldRefactoring.INITIALIZE_IN_CONSTRUCTOR;});}
            case ExtractParameter r -> ((IntroduceParameterRefactoring)target).setParameterName(r.name());
            case InlineVariable r -> ((InlineTempRefactoring)target).setCheckResultForCompileProblems(true);
            case InlineMethod r -> {var p=(InlineMethodRefactoring)target;p.setCurrentMode(r.allInvocations()?InlineMethodRefactoring.Mode.INLINE_ALL:InlineMethodRefactoring.Mode.INLINE_SINGLE);p.setDeleteSource(r.removeDeclaration());}
            case ChangeSignature r -> {var p=(ChangeSignatureProcessor)target;p.setNewMethodName(r.name());p.setNewReturnTypeName(r.returnType());p.setVisibility(visibility(r.visibility()));List<ParameterInfo> original=new ArrayList<>(p.getParameterInfos()),ordered=new ArrayList<>();Set<Integer> used=new HashSet<>();for(var arg:r.parameters()){ParameterInfo info;if(arg.originalIndex()<0)info=ParameterInfo.createInfoForAddedParameter(arg.type(),arg.name(),arg.defaultValue());else{if(arg.originalIndex()>=original.size()||!used.add(arg.originalIndex()))throw new IllegalArgumentException("Invalid or duplicate original parameter index");info=original.get(arg.originalIndex());info.setNewTypeName(arg.type());info.setNewName(arg.name());}ordered.add(info);}for(var info:original)if(!used.contains(info.getOldIndex())){info.markAsDeleted();ordered.add(info);}p.getParameterInfos().clear();p.getParameterInfos().addAll(ordered);}
            case EncapsulateField r -> {var p=(SelfEncapsulateFieldRefactoring)target;p.setGetterName(r.getterName());p.setSetterName(r.setterName());p.setVisibility(visibility(r.visibility()));p.setGenerateJavadoc(false);p.setEncapsulateDeclaringClass(true);}
            case ConstructorToFactory r -> {var p=(IntroduceFactoryRefactoring)target;p.setNewMethodName(r.name());p.setProtectConstructor(r.protectConstructor());}
            case ExtractInterface r -> {var p=(ExtractInterfaceProcessor)target;p.setTypeName(r.name());p.setExtractedMembers(r.memberIds().isEmpty()?p.getExtractableMembers():members(ws,p.getType(),r.memberIds()));p.setReplace(r.replaceUsages());p.setComments(false);p.setAnnotations(true);}
            case IntroduceInterface r -> {var p=(ExtractInterfaceProcessor)target;p.setTypeName(r.interfaceName());p.setExtractedMembers(p.getExtractableMembers());p.setReplace(false);p.setComments(false);}
            case PullUp r -> {var p=(PullUpRefactoringProcessor)target;IType dest=ws.javaProject().findType(r.supertype());if(dest==null)throw new IllegalArgumentException("Unknown superclass "+r.supertype());p.setDestinationType(dest);p.setMembersToMove(members(ws,ws.select(r.location()),r.memberIds()));p.setAbstractMethods(new IMethod[0]);p.setDeletedMethods(Arrays.stream(members(ws,ws.select(r.location()),r.memberIds())).filter(IMethod.class::isInstance).map(IMethod.class::cast).toArray(IMethod[]::new));p.setCreateMethodStubs(false);}
            case PushDown r -> {var p=(PushDownRefactoringProcessor)target;Set<IMember> chosen=Set.of(members(ws,ws.select(r.location()),r.memberIds()));for(var info:p.getMemberActionInfos())if(chosen.contains(info.getMember()))info.setAction(r.makeAbstract()?PushDownRefactoringProcessor.MemberActionInfo.PUSH_ABSTRACT_ACTION:PushDownRefactoringProcessor.MemberActionInfo.PUSH_DOWN_ACTION);}
            case Move r -> {
                if(target instanceof MoveInstanceMethodProcessor p){var possible=p.getPossibleTargets();var chosen=Arrays.stream(possible).filter(v->v.getName().equals(r.receiver())&&v.getType().getQualifiedName().equals(r.destination())).findFirst().orElseThrow(()->new IllegalArgumentException("No semantically valid target receiver"));p.setTarget(chosen);p.setInlineDelegator(true);p.setRemoveDelegator(true);}
                else if(target instanceof PullUpRefactoringProcessor p){var member=require(ws.select(r.location()),IMember.class);p.setDestinationType(ws.javaProject().findType(r.destination()));p.setMembersToMove(new IMember[]{member});p.setAbstractMethods(new IMethod[0]);p.setDeletedMethods(new IMethod[0]);p.setCreateMethodStubs(false);}
                else if(target instanceof PushDownRefactoringProcessor p){var member=ws.select(r.location());for(var info:p.getMemberActionInfos())if(info.getMember().equals(member))info.setAction(PushDownRefactoringProcessor.MemberActionInfo.PUSH_DOWN_ACTION);}
                else if(target instanceof MoveInnerToTopRefactoring p){if(p.isCreatingInstanceFieldPossible()&&!p.isCreatingInstanceFieldMandatory())p.setCreateInstanceField(true);if(p.getCreateInstanceField()){p.setEnclosingInstanceName(r.receiver().isBlank()?"enclosing":r.receiver());p.setMarkInstanceFieldAsFinal(true);}}
            }
            default -> {}
        }
    }
    private static Refactoring move(Move r,IJavaElement selected,LtkWorkspace ws,IProgressMonitor monitor)throws CoreException{
        if(r.kind()==MoveKind.PACKAGE){IPackageFragment pkg=selected instanceof IPackageFragment p?p:(IPackageFragment)selected.getAncestor(IJavaElement.PACKAGE_FRAGMENT);return rename(pkg,r.destination(),true,ws);}
        if(r.kind()==MoveKind.FIELD&&selected instanceof IField field&&!Flags.isStatic(field.getFlags())){
            IType destination=ws.javaProject().findType(r.destination());if(destination==null)throw new IllegalArgumentException("Unknown destination type");
            var hierarchy=field.getDeclaringType().newTypeHierarchy(ws.javaProject(),monitor);
            if(Arrays.asList(hierarchy.getAllSuperclasses(field.getDeclaringType())).contains(destination))return wrap(new PullUpRefactoringProcessor(new IMember[]{field},settings()));
            if(Arrays.equals(hierarchy.getSubtypes(field.getDeclaringType()),new IType[]{destination}))return wrap(new PushDownRefactoringProcessor(new IMember[]{field}));
            throw new IllegalArgumentException("Moving an instance field requires a compatible superclass or sole direct subtype; unrelated object ownership cannot be preserved");
        }
        if(r.kind()==MoveKind.NESTED_TYPE&&selected instanceof IType nested&&!Flags.isStatic(nested.getFlags())){
            if(!r.destination().equals(nested.getPackageFragment().getElementName()))throw new IllegalArgumentException("An inner class can be promoted to a top-level type in its current package; then move the top-level type separately");
            return new MoveInnerToTopRefactoring(nested,settings());
        }
        if(r.kind()==MoveKind.STATIC_MEMBER||r.kind()==MoveKind.FIELD||r.kind()==MoveKind.NESTED_TYPE){var member=require(selected,IMember.class);if(!Flags.isStatic(member.getFlags()))throw new IllegalArgumentException("Selected member is not static");var p=new MoveStaticMembersProcessor(new IMember[]{member},settings());p.setDestinationTypeFullyQualifiedName(r.destination());return wrap(p);}
        if(r.kind()==MoveKind.INSTANCE_METHOD)return wrap(new MoveInstanceMethodProcessor(require(selected,IMethod.class),settings()));
        IType type=type(selected);if(type.getDeclaringType()!=null)throw new IllegalArgumentException("Use nested-type move for nested declarations");ICompilationUnit unit=type.getCompilationUnit();IPackageFragmentRoot root=(IPackageFragmentRoot)unit.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);IPackageFragment destination=root.createPackageFragment(r.destination(),true,monitor);
        var policy=ReorgPolicyFactory.createMovePolicy(new IResource[0],new IJavaElement[]{unit});var processor=new JavaMoveProcessor(policy);processor.setReorgQueries(QUERIES);processor.setDestination(ReorgDestinationFactory.createDestination(destination));processor.setUpdateReferences(true);return wrap(processor);
    }
    private static Refactoring rename(IJavaElement selected,String name,boolean hierarchy,LtkWorkspace ws)throws CoreException{
        if(selected==null)throw new IllegalArgumentException("No resolved declaration");String id=switch(selected.getElementType()){case IJavaElement.TYPE->IJavaRefactorings.RENAME_TYPE;case IJavaElement.METHOD->IJavaRefactorings.RENAME_METHOD;case IJavaElement.FIELD->Flags.isEnum(((IField)selected).getFlags())?IJavaRefactorings.RENAME_ENUM_CONSTANT:IJavaRefactorings.RENAME_FIELD;case IJavaElement.LOCAL_VARIABLE->IJavaRefactorings.RENAME_LOCAL_VARIABLE;case IJavaElement.TYPE_PARAMETER->IJavaRefactorings.RENAME_TYPE_PARAMETER;case IJavaElement.PACKAGE_FRAGMENT->IJavaRefactorings.RENAME_PACKAGE;default->throw new IllegalArgumentException("Select a Java declaration");};
        var descriptor=new RenameJavaElementDescriptor(id);descriptor.setProject(ws.javaProject().getElementName());descriptor.setJavaElement(selected);descriptor.setNewName(name);descriptor.setUpdateReferences(true);if(selected instanceof IPackageFragment)descriptor.setUpdateHierarchy(hierarchy);RefactoringStatus status=new RefactoringStatus();var refactoring=descriptor.createRefactoring(status);if(status.hasError()||refactoring==null)throw new IllegalArgumentException(status.toString());return refactoring;
    }
    private static Refactoring wrap(RefactoringProcessor processor){return new ProcessorBasedRefactoring(processor);}
    private static int visibility(Visibility visibility){return switch(visibility){case PUBLIC->Modifier.PUBLIC;case PROTECTED->Modifier.PROTECTED;case PRIVATE->Modifier.PRIVATE;case PACKAGE->0;};}
    private static CodeGenerationSettings settings(){var s=new CodeGenerationSettings();s.createComments=false;s.overrideAnnotation=true;s.tabWidth=4;s.indentWidth=4;return s;}
    private static IType type(IJavaElement selected){if(selected instanceof IType t)return t;if(selected==null)throw new IllegalArgumentException("Select a type or member");IJavaElement ancestor=selected.getAncestor(IJavaElement.TYPE);if(ancestor instanceof IType t)return t;throw new IllegalArgumentException("No enclosing type");}
    private static <T>T require(Object value,Class<T> type){if(!type.isInstance(value))throw new IllegalArgumentException("Selected symbol is not a "+type.getSimpleName());return type.cast(value);}
    private static IMember[] members(LtkWorkspace ws,IJavaElement selected,List<String> ids){if(ids.isEmpty())return new IMember[]{require(selected,IMember.class)};return ids.stream().map(ws::element).map(e->require(e,IMember.class)).toArray(IMember[]::new);}
    private static final IReorgQueries QUERIES=new IReorgQueries(){private IConfirmQuery refuse(){return new IConfirmQuery(){public boolean confirm(String q){throw new OperationCanceledException("Refactoring requires explicit conflict resolution: "+q);}public boolean confirm(String q,Object[] e){return confirm(q);}};}public IConfirmQuery createSkipQuery(String q,int id){return refuse();}public IConfirmQuery createYesNoQuery(String q,boolean yes,int id){return refuse();}public IConfirmQuery createYesYesToAllNoNoToAllQuery(String q,boolean yes,int id){return refuse();}};
}
