package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.Location;
import java.util.List;
import java.util.Objects;

/** Typed operation inputs. Identifiers are validated in their resolved Java context by the engine. */
public final class Requests {
    private Requests() {}
    public interface Request { Operation operation(); Location location(); }
    public enum Visibility { PRIVATE, PACKAGE, PROTECTED, PUBLIC }
    public enum FieldInitialization { CURRENT_METHOD, FIELD_DECLARATION, CONSTRUCTORS }
    public enum MoveKind { TYPE, PACKAGE, STATIC_MEMBER, INSTANCE_METHOD, FIELD, NESTED_TYPE }
    public record Rename(Location location, String name, boolean hierarchy) implements Request {
        public Rename(Location location, String name) { this(location, name, true); }
        public Operation operation() { return Operation.RENAME; }
    }
    public record ExtractMethod(Location location, String name, Visibility visibility, boolean replaceDuplicates) implements Request {
        public ExtractMethod(Location location, String name) { this(location, name, Visibility.PRIVATE, false); }
        public Operation operation() { return Operation.EXTRACT_METHOD; }
    }
    public record ExtractVariable(Location location, String name, boolean replaceAll, boolean makeFinal) implements Request {
        public Operation operation() { return Operation.EXTRACT_VARIABLE; }
    }
    public record ExtractConstant(Location location, String name, Visibility visibility, boolean replaceAll) implements Request {
        public Operation operation() { return Operation.EXTRACT_CONSTANT; }
    }
    public record ExtractField(Location location, String name, Visibility visibility, FieldInitialization initialization) implements Request {
        public Operation operation() { return Operation.EXTRACT_FIELD; }
    }
    public record ExtractParameter(Location location, String name) implements Request {
        public Operation operation() { return Operation.EXTRACT_PARAMETER; }
    }
    public record InlineVariable(Location location) implements Request {
        public Operation operation() { return Operation.INLINE_VARIABLE; }
    }
    public record InlineMethod(Location location, boolean allInvocations, boolean removeDeclaration) implements Request {
        public Operation operation() { return Operation.INLINE_METHOD; }
    }
    /** originalIndex is -1 for an added parameter; defaultValue supplies its call-site expression. */
    public record Parameter(int originalIndex, String type, String name, String defaultValue) {
        public Parameter { if (originalIndex < -1) throw new IllegalArgumentException("Invalid original parameter index"); Objects.requireNonNull(type); Objects.requireNonNull(name); }
    }
    public record ChangeSignature(Location location, String name, String returnType, Visibility visibility,
                                  List<Parameter> parameters) implements Request {
        public ChangeSignature { parameters = List.copyOf(parameters); }
        public Operation operation() { return Operation.CHANGE_SIGNATURE; }
    }
    /** Destination is a package/type name, resolved by the Java workspace, not an arbitrary shell path. */
    public record Move(Location location, MoveKind kind, String destination, String receiver) implements Request {
        public Operation operation() { return Operation.MOVE; }
    }
    public record SafeDelete(Location location) implements Request {
        public Operation operation() { return Operation.SAFE_DELETE; }
    }
    public record ExtractInterface(Location location, String name, List<String> memberIds, boolean replaceUsages) implements Request {
        public ExtractInterface { memberIds = List.copyOf(memberIds); }
        public Operation operation() { return Operation.EXTRACT_INTERFACE; }
    }
    public record IntroduceInterface(Location location, String interfaceName, List<Location> usages) implements Request {
        public IntroduceInterface { usages = List.copyOf(usages); }
        public Operation operation() { return Operation.INTRODUCE_INTERFACE; }
    }
    public record PullUp(Location location, String supertype, List<String> memberIds) implements Request {
        public PullUp { memberIds = List.copyOf(memberIds); }
        public Operation operation() { return Operation.PULL_UP; }
    }
    public record PushDown(Location location, List<String> memberIds, boolean makeAbstract) implements Request {
        public PushDown { memberIds = List.copyOf(memberIds); }
        public Operation operation() { return Operation.PUSH_DOWN; }
    }
    public record EncapsulateField(Location location, String getterName, String setterName, Visibility visibility) implements Request {
        public Operation operation() { return Operation.ENCAPSULATE_FIELD; }
    }
    public record ConstructorToFactory(Location location, String name, boolean protectConstructor) implements Request {
        public Operation operation() { return Operation.CONSTRUCTOR_TO_FACTORY; }
    }
    public record AnonymousToLambda(Location location) implements Request { public Operation operation() { return Operation.ANONYMOUS_TO_LAMBDA; } }
    public record LambdaToMethodReference(Location location) implements Request { public Operation operation() { return Operation.LAMBDA_TO_METHOD_REFERENCE; } }
    public record MethodReferenceToLambda(Location location) implements Request { public Operation operation() { return Operation.METHOD_REFERENCE_TO_LAMBDA; } }
    public record SwitchToExpression(Location location) implements Request { public Operation operation() { return Operation.SWITCH_TO_EXPRESSION; } }
    public record TypeToVar(Location location) implements Request { public Operation operation() { return Operation.TYPE_TO_VAR; } }
    public record VarToType(Location location) implements Request { public Operation operation() { return Operation.VAR_TO_TYPE; } }
    public record OrganizeImports(Location location) implements Request { public Operation operation() { return Operation.ORGANIZE_IMPORTS; } }
    public record AddImport(Location location, String qualifiedName, boolean isStatic) implements Request { public Operation operation() { return Operation.ADD_IMPORT; } }
    public record RemoveImport(Location location) implements Request { public Operation operation() { return Operation.REMOVE_IMPORT; } }
    public record ImplementMethods(Location location, List<String> methodIds) implements Request {
        public ImplementMethods { methodIds = List.copyOf(methodIds); }
        public Operation operation() { return Operation.IMPLEMENT_METHODS; }
    }
    public record CreateMethod(Location location) implements Request { public Operation operation() { return Operation.CREATE_METHOD; } }
    public record CreateConstructor(Location location) implements Request { public Operation operation() { return Operation.CREATE_CONSTRUCTOR; } }
    public record CreateField(Location location) implements Request { public Operation operation() { return Operation.CREATE_FIELD; } }
    public record CreateLocal(Location location) implements Request { public Operation operation() { return Operation.CREATE_LOCAL; } }
    public record FixTypeMismatch(Location location) implements Request { public Operation operation() { return Operation.FIX_TYPE_MISMATCH; } }
    public record RemoveUnreachable(Location location) implements Request { public Operation operation() { return Operation.REMOVE_UNREACHABLE; } }
}
