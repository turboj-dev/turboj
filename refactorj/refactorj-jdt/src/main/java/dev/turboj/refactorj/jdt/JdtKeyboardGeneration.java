package dev.turboj.refactorj.jdt;

import dev.turboj.refactorj.api.JavaIntelligence.TextEdit;
import dev.turboj.refactorj.api.JavaStructure.*;
import org.eclipse.jdt.core.dom.*;
import java.util.*;
import java.util.stream.Collectors;
import static dev.turboj.refactorj.jdt.JdtKeyboardEditing.*;

/** Generates source only from resolved declarations; candidate compilation rejects invalid stubs. */
final class JdtKeyboardGeneration {
    private final JdtKeyboardEditing editing;
    JdtKeyboardGeneration(JdtKeyboardEditing editing) { this.editing = editing; }
    EditResult generate(int caret, Edit operation) {
        ASTNode selected = editing.at(caret, caret);
        if (operation == Edit.JAVADOC) return javadoc(selected);
        TypeDeclaration type = ancestor(selected, TypeDeclaration.class);
        require(type != null && !type.isInterface(), "Select a class declaration; this generator does not change records, enums or interfaces.");
        ITypeBinding binding = type.resolveBinding();
        require(binding != null && !binding.isRecovered() && !binding.isAnonymous(), "The selected class must resolve.");
        List<IVariableBinding> fields = Arrays.stream(binding.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()).toList();
        String name = type.getName().getIdentifier(), eol = editing.source.contains("\r\n") ? "\r\n" : "\n";
        String indent = indentation(type.getStartPosition()) + "    ";
        List<String> methods = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        switch (operation) {
            case CONSTRUCTOR -> {
                List<IVariableBinding> initialized = fields.stream().filter(f -> !Modifier.isFinal(f.getModifiers()) || !hasInitializer(type, f)).toList();
                require(Arrays.stream(binding.getDeclaredMethods()).noneMatch(m -> m.isConstructor() && !m.isDefaultConstructor() && sameParameters(m.getParameterTypes(), initialized)), "A constructor with these parameter types already exists.");
                String parameters = initialized.stream().map(f -> renderType(f.getType()) + " " + f.getName()).collect(Collectors.joining(", "));
                String body = initialized.stream().map(f -> "this." + f.getName() + " = " + f.getName() + ";").collect(Collectors.joining(eol));
                methods.add("public " + name + "(" + parameters + ") {" + eol + body + eol + "}");
            }
            case GETTERS, SETTERS, GETTERS_SETTERS -> {
                for (IVariableBinding field : fields) {
                    String capitalized = field.getName().substring(0, 1).toUpperCase(Locale.ROOT) + field.getName().substring(1);
                    String getter = (field.getType().isPrimitive() && field.getType().getName().equals("boolean") ? "is" : "get") + capitalized;
                    String setter = "set" + capitalized;
                    if (operation != Edit.SETTERS && !hasMethod(binding, getter, 0)) methods.add("public " + renderType(field.getType()) + " " + getter + "() {" + eol + "return this." + field.getName() + ";" + eol + "}");
                    if (operation != Edit.GETTERS && !Modifier.isFinal(field.getModifiers()) && !hasMethod(binding, setter, 1)) methods.add("public void " + setter + "(" + renderType(field.getType()) + " " + field.getName() + ") {" + eol + "this." + field.getName() + " = " + field.getName() + ";" + eol + "}");
                }
            }
            case EQUALS -> {
                require(!hasMethod(binding, "equals", 1), "An equals method is already declared.");
                require(binding.getSuperclass() == null || binding.getSuperclass().getQualifiedName().equals("java.lang.Object"), "Generating equality for a class with a non-Object superclass requires an explicit superclass equality policy.");
                String comparison = fields.stream().map(this::equality).collect(Collectors.joining(" && "));
                String classType = name + (binding.getTypeParameters().length == 0 ? "" : "<" + Collections.nCopies(binding.getTypeParameters().length, "?").stream().collect(Collectors.joining(", ")) + ">");
                methods.add("@Override" + eol + "public boolean equals(Object other) {" + eol + "if (this == other) return true;" + eol + "if (other == null || getClass() != other.getClass()) return false;" + eol + classType + " that = (" + classType + ") other;" + eol + "return " + (comparison.isEmpty() ? "true" : comparison) + ";" + eol + "}");
                if (!hasMethod(binding, "hashCode", 0)) methods.add(hashCode(fields, eol));
                else warnings.add("The existing hashCode implementation must agree with the generated equality fields.");
            }
            case HASH_CODE -> { require(!hasMethod(binding, "hashCode", 0), "A hashCode method is already declared."); methods.add(hashCode(fields, eol)); warnings.add("Review consistency with the class's equals implementation."); }
            case TO_STRING -> {
                require(!hasMethod(binding, "toString", 0), "A toString method is already declared.");
                StringBuilder body = new StringBuilder("return \"").append(name).append("[\"");
                for (int i = 0; i < fields.size(); i++) { var f = fields.get(i); body.append(" + \"").append(i == 0 ? "" : ", ").append(f.getName()).append("=\" + ").append(f.getType().isArray() ? "java.util.Arrays." + (f.getType().getDimensions() > 1 ? "deepToString" : "toString") + "(this." + f.getName() + ")" : "this." + f.getName()); }
                body.append(" + \"]\";");
                methods.add("@Override" + eol + "public String toString() {" + eol + body + eol + "}");
                warnings.add("Generated toString includes all instance fields; review sensitive values before applying.");
            }
            case IMPLEMENT_METHODS, OVERRIDE_METHODS -> {
                var inherited = new LinkedHashMap<String, IMethodBinding>(); collectMethods(binding.getSuperclass(), inherited, new HashSet<>()); for (var face : binding.getInterfaces()) collectMethods(face, inherited, new HashSet<>());
                for (IMethodBinding method : inherited.values()) {
                    if (Modifier.isFinal(method.getModifiers()) || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers()) || method.isConstructor()) continue;
                    if (!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers()) && !binding.getPackage().getName().equals(method.getDeclaringClass().getPackage().getName())) continue;
                    if (Arrays.stream(binding.getDeclaredMethods()).anyMatch(existing -> existing.overrides(method) || existing.isSubsignature(method))) continue;
                    if (operation == Edit.IMPLEMENT_METHODS && !Modifier.isAbstract(method.getModifiers())) continue;
                    if (operation == Edit.OVERRIDE_METHODS && Modifier.isAbstract(method.getModifiers())) continue;
                    // Interface default calls require a chosen direct interface and conflict policy.
                    if (operation == Edit.OVERRIDE_METHODS && method.getDeclaringClass().isInterface()) continue;
                    methods.add(methodStub(method, eol));
                }
                if (operation == Edit.IMPLEMENT_METHODS) warnings.add("Generated abstract-method bodies throw UnsupportedOperationException until implemented.");
            }
            default -> throw new IllegalArgumentException("This source-generation action is not applicable here.");
        }
        require(!methods.isEmpty(), "No eligible missing members were found; existing declarations are retained.");
        int insertion = end(type) - 1;
        String generated = eol + methods.stream().map(m -> indentBlock(m, indent, eol)).collect(Collectors.joining(eol + eol)) + eol + indentation(type.getStartPosition());
        return editing.checked(List.of(new TextEdit(insertion, insertion, generated)), insertion + generated.length(), insertion, warnings, "Generate " + operation.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }
    private boolean sameParameters(ITypeBinding[] parameters, List<IVariableBinding> fields) {
        if (parameters.length != fields.size()) return false;
        for (int i = 0; i < parameters.length; i++) if (!parameters[i].getErasure().isEqualTo(fields.get(i).getType().getErasure())) return false;
        return true;
    }
    private boolean hasInitializer(TypeDeclaration type, IVariableBinding field) {
        for (FieldDeclaration declaration : type.getFields()) for (Object object : declaration.fragments()) { var fragment = (VariableDeclarationFragment)object; if (fragment.resolveBinding() != null && fragment.resolveBinding().isEqualTo(field)) return fragment.getInitializer() != null; }
        return false;
    }
    private boolean hasMethod(ITypeBinding type, String name, int parameters) { return Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name) && m.getParameterTypes().length == parameters && !m.isConstructor()); }
    private String equality(IVariableBinding field) {
        String a = "this." + field.getName(), b = "that." + field.getName(); ITypeBinding type = field.getType();
        if (type.isArray()) return "java.util.Arrays." + (type.getDimensions() > 1 ? "deepEquals" : "equals") + "(" + a + ", " + b + ")";
        if (type.isPrimitive()) return switch (type.getName()) { case "float" -> "Float.compare(" + a + ", " + b + ") == 0"; case "double" -> "Double.compare(" + a + ", " + b + ") == 0"; default -> a + " == " + b; };
        return "java.util.Objects.equals(" + a + ", " + b + ")";
    }
    private String hashCode(List<IVariableBinding> fields, String eol) {
        String values = fields.stream().map(f -> f.getType().isArray() ? "java.util.Arrays." + (f.getType().getDimensions() > 1 ? "deepHashCode" : "hashCode") + "(this." + f.getName() + ")" : "this." + f.getName()).collect(Collectors.joining(", "));
        return "@Override" + eol + "public int hashCode() {" + eol + "return java.util.Objects.hash(" + values + ");" + eol + "}";
    }
    private void collectMethods(ITypeBinding type, Map<String, IMethodBinding> output, Set<String> visited) {
        if (type == null || !visited.add(type.getKey())) return;
        for (var m : type.getDeclaredMethods()) {
            String key = m.getName() + Arrays.stream(m.getParameterTypes()).map(t -> t.getErasure().getQualifiedName()).collect(Collectors.joining(",", "(", ")"));
            output.putIfAbsent(key, m);
        }
        collectMethods(type.getSuperclass(), output, visited); for (var i : type.getInterfaces()) collectMethods(i, output, visited);
    }
    private String methodStub(IMethodBinding method, String eol) {
        StringBuilder out = new StringBuilder("@Override").append(eol).append(Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass().isInterface() ? "public " : "protected ");
        var variables = method.getTypeParameters();
        if (variables.length > 0) out.append(Arrays.stream(variables).map(t -> t.getName() + (t.getTypeBounds().length == 0 || t.getTypeBounds().length == 1 && t.getTypeBounds()[0].getQualifiedName().equals("java.lang.Object") ? "" : " extends " + Arrays.stream(t.getTypeBounds()).map(JdtKeyboardGeneration::renderType).collect(Collectors.joining(" & ")))).collect(Collectors.joining(", ", "<", "> ")));
        out.append(renderType(method.getReturnType())).append(' ').append(method.getName()).append('(');
        var parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) { if (i > 0) out.append(", "); out.append(method.isVarargs() && i == parameters.length - 1 ? renderType(parameters[i].getElementType()) + "..." : renderType(parameters[i])).append(" arg").append(i); }
        out.append(')'); if (method.getExceptionTypes().length > 0) out.append(" throws ").append(Arrays.stream(method.getExceptionTypes()).map(JdtKeyboardGeneration::renderType).collect(Collectors.joining(", ")));
        out.append(" {").append(eol);
        if (Modifier.isAbstract(method.getModifiers())) out.append("throw new UnsupportedOperationException(\"Implement ").append(method.getName()).append("\");");
        else { if (!method.getReturnType().getName().equals("void")) out.append("return "); out.append("super.").append(method.getName()).append('('); for (int i = 0; i < parameters.length; i++) { if (i > 0) out.append(", "); out.append("arg").append(i); } out.append(");"); }
        return out.append(eol).append('}').toString();
    }
    static String renderType(ITypeBinding type) {
        require(type != null && !type.isRecovered() && !type.isCapture() && !type.isAnonymous(), "The generated signature needs a denotable resolved Java type.");
        if (type.isArray()) return renderType(type.getElementType()) + "[]".repeat(type.getDimensions());
        if (type.isPrimitive() || type.isTypeVariable()) return type.getName();
        if (type.isWildcardType()) return type.getBound() == null ? "?" : "? " + (type.isUpperbound() ? "extends " : "super ") + renderType(type.getBound());
        String name = type.getErasure().getQualifiedName(); require(!name.isBlank(), "The generated type has no source name.");
        return name + (type.getTypeArguments().length == 0 ? "" : Arrays.stream(type.getTypeArguments()).map(JdtKeyboardGeneration::renderType).collect(Collectors.joining(", ", "<", ">")));
    }
    private EditResult javadoc(ASTNode selected) {
        BodyDeclaration declaration = ancestor(selected, BodyDeclaration.class);
        require(declaration != null && declaration.getJavadoc() == null, "Select a declaration without an existing Javadoc comment.");
        String eol = editing.source.contains("\r\n") ? "\r\n" : "\n", indent = indentation(declaration.getStartPosition());
        StringBuilder doc = new StringBuilder("/**").append(eol).append(indent).append(" * ").append(eol);
        if (declaration instanceof MethodDeclaration method) {
            for (Object item : method.typeParameters()) doc.append(indent).append(" * @param <").append(((TypeParameter)item).getName()).append("> ").append(eol);
            for (Object item : method.parameters()) doc.append(indent).append(" * @param ").append(((SingleVariableDeclaration)item).getName()).append(' ').append(eol);
            if (!method.isConstructor() && method.getReturnType2() != null && !method.getReturnType2().toString().equals("void")) doc.append(indent).append(" * @return ").append(eol);
            for (Object item : method.thrownExceptionTypes()) doc.append(indent).append(" * @throws ").append(item).append(' ').append(eol);
        } else if (declaration instanceof TypeDeclaration type) for (Object item : type.typeParameters()) doc.append(indent).append(" * @param <").append(((TypeParameter)item).getName()).append("> ").append(eol);
        doc.append(indent).append(" */").append(eol).append(indent);
        int start = declaration.getStartPosition(); return editing.checked(List.of(new TextEdit(start, start, doc.toString())), start + 3 + eol.length() + indent.length() + 3, start + 3 + eol.length() + indent.length() + 3, List.of(), "Generate Javadoc stub");
    }
    private String indentation(int offset) { int start = offset; while (start > 0 && editing.source.charAt(start - 1) != '\n' && editing.source.charAt(start - 1) != '\r') start--; int i = start; while (i < editing.source.length() && (editing.source.charAt(i) == ' ' || editing.source.charAt(i) == '\t')) i++; return editing.source.substring(start, i); }
    private String indentBlock(String source, String indent, String eol) {
        String[] lines = source.split("\\r?\\n", -1); StringBuilder result = new StringBuilder(); int nesting = 0;
        for (int i = 0; i < lines.length; i++) { String line = lines[i]; if (line.startsWith("}")) nesting--; if (i > 0) result.append(eol); result.append(indent).append("    ".repeat(Math.max(0, nesting))).append(line); if (line.endsWith("{")) nesting++; }
        return result.toString();
    }
}
