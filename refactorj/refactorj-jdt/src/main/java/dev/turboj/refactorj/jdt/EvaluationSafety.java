package dev.turboj.refactorj.jdt;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Requests.*;
import java.util.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;

/** Additional guards for transformations whose textual validity does not preserve evaluation. */
final class EvaluationSafety {
    static void check(Request request,LtkWorkspace workspace)throws JavaModelException{
        if(request instanceof ChangeSignature signature)checkSignature(signature,workspace);
        if(request instanceof ExtractConstant constant){
            ASTNode node=NodeFinder.perform(workspace.ast(constant.location().file()),constant.location().start(),constant.location().end()-constant.location().start());
            if(!(node instanceof Expression expression)||!(expression instanceof NullLiteral)&&expression.resolveConstantExpressionValue()==null)throw new IllegalArgumentException("Extract Constant requires a proven Java constant expression; moving object creation or calls into class initialization can change behavior");
        }
        if(request instanceof Move move&&move.kind()==MoveKind.FIELD)checkFieldMove(move,workspace);
        if(request instanceof InlineVariable inline){
            IJavaElement selected=workspace.select(inline.location());if(!(selected instanceof ILocalVariable local))return;
            ASTNode node=NodeFinder.perform(workspace.ast(inline.location().file()),local.getNameRange().getOffset(),local.getNameRange().getLength());
            while(node!=null&&!(node instanceof VariableDeclarationFragment))node=node.getParent();
            if(node instanceof VariableDeclarationFragment declaration&&declaration.getInitializer()!=null&&!pure(declaration.getInitializer())){
                var references=workspace.references(workspace.symbolId(local),Cancellation.NONE);
                if(references.size()!=1)throw new IllegalArgumentException("Inlining this initializer would duplicate or remove side effects");
                // Even one use can move evaluation across other effects or a conditional boundary.
                ASTNode statement=declaration.getParent();if(!(statement instanceof VariableDeclarationStatement)||!(statement.getParent() instanceof Block block))throw new IllegalArgumentException("Initializer evaluation order cannot be established");
                int index=block.statements().indexOf(statement);if(index+1>=block.statements().size())throw new IllegalArgumentException("Initializer has no immediately following use");
                ASTNode next=(ASTNode)block.statements().get(index+1);int use=references.getFirst().start();
                if(use<next.getStartPosition()||use>=next.getStartPosition()+next.getLength()||!(next instanceof ReturnStatement ret)||!(ret.getExpression() instanceof SimpleName))throw new IllegalArgumentException("Inlining would move a side effect across another expression or statement");
            }
        }
    }
    private static void checkSignature(ChangeSignature request,LtkWorkspace workspace)throws JavaModelException{
        IJavaElement selected=workspace.select(request.location());if(!(selected instanceof IMethod method))return;
        List<Integer> indices=request.parameters().stream().filter(p->p.originalIndex()>=0).map(Parameter::originalIndex).toList();
        List<Integer> sorted=indices.stream().sorted().toList();boolean reordered=!indices.equals(sorted);Set<Integer> kept=new HashSet<>(indices);
        if(!reordered&&kept.size()==method.getNumberOfParameters())return;
        final IMethodBinding[] selectedBinding={null};
        if(method.getCompilationUnit()!=null)workspace.ast(workspace.original(method.getCompilationUnit())).accept(new ASTVisitor(){@Override public boolean visit(MethodDeclaration declaration){var binding=declaration.resolveBinding();if(binding!=null&&method.equals(binding.getJavaElement()))selectedBinding[0]=binding;return selectedBinding[0]==null;}});
        for(var source:workspace.snapshot().sources())workspace.ast(source.file()).accept(new ASTVisitor(){
            @Override public boolean visit(MethodInvocation invocation){check(invocation.resolveMethodBinding(),invocation.arguments());return true;}
            @Override public boolean visit(ClassInstanceCreation invocation){check(invocation.resolveConstructorBinding(),invocation.arguments());return true;}
            private void check(IMethodBinding binding,List<?> arguments){
                if(binding==null)return;
                boolean same=method.equals(binding.getMethodDeclaration().getJavaElement())||selectedBinding[0]!=null&&(binding.overrides(selectedBinding[0])||selectedBinding[0].overrides(binding));if(!same)return;
                for(int i=0;i<arguments.size();i++)if((reordered||!kept.contains(i))&&!pure((Expression)arguments.get(i)))throw new IllegalArgumentException("Changing this signature would reorder or remove an argument with side effects or observable evaluation");
            }
        });
    }
    private static void checkFieldMove(Move request,LtkWorkspace workspace)throws JavaModelException{
        IJavaElement selected=workspace.select(request.location());if(!(selected instanceof IField field)||Flags.isStatic(field.getFlags()))return;
        ASTNode node=NodeFinder.perform(workspace.ast(request.location().file()),field.getNameRange().getOffset(),field.getNameRange().getLength());
        while(node!=null&&!(node instanceof VariableDeclarationFragment))node=node.getParent();
        if(!(node instanceof VariableDeclarationFragment declaration)||declaration.getInitializer()==null)return;
        IVariableBinding binding=declaration.resolveBinding();if(binding!=null&&binding.getConstantValue()!=null)return;
        if(declaration.getInitializer().resolveConstantExpressionValue()==null)throw new IllegalArgumentException("Moving an instance field with a nonconstant initializer may change construction-time evaluation");
        // A non-final constant-valued initializer is safe only if no earlier construction step can observe it.
        IType destination=workspace.javaProject().findType(request.destination());if(destination==null)return;
        var hierarchy=field.getDeclaringType().newSupertypeHierarchy(null);
        var types=new ArrayList<IType>();types.add(field.getDeclaringType());types.add(destination);types.addAll(Arrays.asList(hierarchy.getAllSuperclasses(field.getDeclaringType())));
        for(IType type:types){
            if(type.getFullyQualifiedName().equals("java.lang.Object"))continue;
            if(type.isBinary()||type.getCompilationUnit()==null)throw new IllegalArgumentException("Superclass construction behavior is not available for a safe field move");
            CompilationUnit tree=workspace.ast(workspace.original(type.getCompilationUnit()));
            final boolean[] hazardous={false};tree.accept(new ASTVisitor(){
                @Override public boolean visit(Initializer initializer){hazardous[0]=true;return false;}
                @Override public boolean visit(FieldDeclaration fields){if(Modifier.isStatic(fields.getModifiers()))return false;for(Object value:fields.fragments()){var fragment=(VariableDeclarationFragment)value;if(fragment!=declaration&&fragment.getInitializer()!=null)hazardous[0]=true;}return false;}
                @Override public boolean visit(MethodDeclaration method){if(method.isConstructor()&&method.getBody()!=null&&!method.getBody().statements().isEmpty())hazardous[0]=true;return false;}
            });if(hazardous[0])throw new IllegalArgumentException("Field relocation could change values observed during constructors or instance initialization");
        }
    }
    private static boolean pure(Expression expression){
        if(expression instanceof NullLiteral||expression.resolveConstantExpressionValue()!=null)return true;
        if(expression instanceof ParenthesizedExpression p)return pure(p.getExpression());
        return expression instanceof SimpleName name&&name.resolveBinding() instanceof IVariableBinding variable&&!variable.isField();
    }
}
