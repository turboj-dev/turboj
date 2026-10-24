package dev.turboj.refactorj.cli;

import com.google.gson.*;
import com.google.gson.stream.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Shared wire values. Protocol adapters contain no semantic transformations. */
public final class Json {
    private Json() {}

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setStrictness(Strictness.STRICT).registerTypeHierarchyAdapter(Path.class,
            new TypeAdapter<Path> () {
        public void write(JsonWriter out, Path value) throws IOException {
            if (value == null)
                out.nullValue();
            else
                out.value(value.toString());
        }

        public Path read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Path.of(in.nextString());
        }
    }).create();

    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();

        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    public static String string(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key)
                .isString())
            throw new IllegalArgumentException("Missing string: " + key);
        return value.get(key).getAsString();
    }

    public static String string(JsonObject value, String key, String fallback) {
        return value.has(key) ? string(value, key) : fallback;
    }

    public static boolean bool(JsonObject value, String key, boolean fallback) {
        if (!value.has(key)) return fallback;
        if (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException("Expected boolean: " + key);
        return value.get(key).getAsBoolean();
    }

    public static int integer(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
                || !value.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("Expected integer: " + key);
        try {
            return value.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Integer outside range or fractional: " + key, invalid);
        }
    }

    public static Operation operation(String id) {
        return Arrays.stream(Operation.values()).filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown operation: " + id));
    }

    public static Class<? extends Requests.Request> requestClass(Operation op) {
        return switch (op) {
            case RENAME -> Requests.Rename.class;
            case EXTRACT_METHOD -> Requests.ExtractMethod.class;
            case EXTRACT_VARIABLE -> Requests.ExtractVariable.class;
            case EXTRACT_CONSTANT -> Requests.ExtractConstant.class;
            case EXTRACT_FIELD -> Requests.ExtractField.class;
            case EXTRACT_PARAMETER -> Requests.ExtractParameter.class;
            case INLINE_VARIABLE -> Requests.InlineVariable.class;
            case INLINE_METHOD -> Requests.InlineMethod.class;
            case CHANGE_SIGNATURE -> Requests.ChangeSignature.class;
            case MOVE -> Requests.Move.class;
            case SAFE_DELETE -> Requests.SafeDelete.class;
            case EXTRACT_INTERFACE -> Requests.ExtractInterface.class;
            case INTRODUCE_INTERFACE -> Requests.IntroduceInterface.class;
            case PULL_UP -> Requests.PullUp.class;
            case PUSH_DOWN -> Requests.PushDown.class;
            case ENCAPSULATE_FIELD -> Requests.EncapsulateField.class;
            case CONSTRUCTOR_TO_FACTORY -> Requests.ConstructorToFactory.class;
            case ANONYMOUS_TO_LAMBDA -> Requests.AnonymousToLambda.class;
            case LAMBDA_TO_METHOD_REFERENCE -> Requests.LambdaToMethodReference.class;
            case METHOD_REFERENCE_TO_LAMBDA -> Requests.MethodReferenceToLambda.class;
            case SWITCH_TO_EXPRESSION -> Requests.SwitchToExpression.class;
            case TYPE_TO_VAR -> Requests.TypeToVar.class;
            case VAR_TO_TYPE -> Requests.VarToType.class;
            case ORGANIZE_IMPORTS -> Requests.OrganizeImports.class;
            case ADD_IMPORT -> Requests.AddImport.class;
            case REMOVE_IMPORT -> Requests.RemoveImport.class;
            case IMPLEMENT_METHODS -> Requests.ImplementMethods.class;
            case CREATE_METHOD -> Requests.CreateMethod.class;
            case CREATE_CONSTRUCTOR -> Requests.CreateConstructor.class;
            case CREATE_FIELD -> Requests.CreateField.class;
            case CREATE_LOCAL -> Requests.CreateLocal.class;
            case FIX_TYPE_MISMATCH -> Requests.FixTypeMismatch.class;
            case REMOVE_UNREACHABLE -> Requests.RemoveUnreachable.class;
        };
    }

    public static JsonArray catalog() {
        JsonArray result = new JsonArray();

        for (Operation operation : Operation.values()) {
            JsonArray fields = new JsonArray();

            for (var field : requestClass(operation).getRecordComponents())
                if (!field.getName().equals("location")) {
                    String type = field.getType() == boolean.class ? "boolean" : field.getType()
                            == List.class ? "array" : "string";
                    JsonObject description = object("name", field.getName(), "type", type);

                    if (field.getType().isEnum())
                        description.add("values", GSON.toJsonTree(Arrays.stream(field.getType()
                                .getEnumConstants()).map(Object::toString).toList()));
                    if (field.getType() == List.class)
                        description.addProperty("elementType", field.getGenericType()
                                .getTypeName());
                    fields.add(description);
                }
            result.add(object("operation", operation.id(), "fields", fields));
        }
        return result;
    }

    public static Requests.Request request(JsonObject wire, Path root) {
        Operation operation = operation(string(wire, "operation"));
        JsonObject fields = wire.deepCopy();
        fields.remove("operation");
        var type = requestClass(operation);
        Set<String> allowed = new HashSet<> ();

        for (var component : type.getRecordComponents()) {
            allowed.add(component.getName());
            if (component.getType() == List.class && !fields.has(component.getName()))
                fields.add(component.getName(), new JsonArray());
            if (component.getType() == boolean.class && fields.has(component.getName())
                    && (!fields.get(component.getName()).isJsonPrimitive()
                    || !fields.getAsJsonPrimitive(component.getName()).isBoolean()))
                throw new IllegalArgumentException("Expected boolean: " + component.getName());
        }
        for (String key : fields.keySet())
            if (!allowed.contains(key))
                throw new IllegalArgumentException("Unknown parameter: " + key);
        if (allowed.contains("visibility") && !fields.has("visibility"))
            fields.addProperty("visibility", "PRIVATE");
        if (allowed.contains("initialization") && !fields.has("initialization"))
            fields.addProperty("initialization", "CURRENT_METHOD");
        if (allowed.contains("kind") && !fields.has("kind"))
            fields.addProperty("kind", "TYPE");
        if (operation == Operation.RENAME && !fields.has("hierarchy"))
            fields.addProperty("hierarchy", true);
        JsonObject location = fields.getAsJsonObject("location");

        if (location == null)
            throw new IllegalArgumentException("Missing location");
        normalizeLocation(location, root);
        if (operation == Operation.INTRODUCE_INTERFACE)
            for (JsonElement usage : fields.getAsJsonArray("usages"))
                normalizeLocation(usage.getAsJsonObject(), root);
        if (operation == Operation.CHANGE_SIGNATURE)
            for (JsonElement parameter : fields.getAsJsonArray("parameters"))
                integer(parameter.getAsJsonObject(), "originalIndex");
        Requests.Request request = GSON.fromJson(fields, type);

        if (request.location() == null)
            throw new IllegalArgumentException("Missing location");
        if (request instanceof Requests.IntroduceInterface introduce)
            for (Location usage : introduce.usages())
                safePath(root, usage.file().toString());
        return request;
    }

    private static void normalizeLocation(JsonObject location, Path root) {
        Path file = safePath(root, string(location, "file"));
        location.addProperty("file", file.toString());
        int start = integer(location, "start");
        int end = location.has("end") ? integer(location, "end") : start;
        location.addProperty("start", start);
        location.addProperty("end", end);
    }

    public static JsonObject request(Requests.Request request) {
        JsonObject wire = GSON.toJsonTree(request).getAsJsonObject();
        wire.addProperty("operation", request.operation().id());
        return wire;
    }

    public static Path safePath(Path root, String value) {
        Path path = Path.of(value);

        if (!path.isAbsolute())
            path = root.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root) || path.startsWith(root
                .resolve(".refactorj")))
            throw new IllegalArgumentException("Path is outside the allowed source root");
        for (Path at = path; at != null && !at.equals(root); at = at.getParent())
            if (java.nio.file.Files.isSymbolicLink(at))
                throw new IllegalArgumentException("Symbolic source paths are not followed");
        return path;
    }

    public static int offset(String source, int line, int character) {
        if (line < 0 || character < 0)
            throw new IllegalArgumentException("Negative source position");
        int start = 0;

        for (int i = 0; i < line; i++) {
            int next = source.indexOf('\n', start);

            if (next < 0)
                throw new IllegalArgumentException("Line outside source");
            start = next + 1;
        }
        int end = source.indexOf('\n', start);

        if (end < 0)
            end = source.length();
        if (end > start && source.charAt(end - 1) == '\r')
            end--;
        if (character > end - start)
            throw new IllegalArgumentException("Column outside source");
        int offset = start + character;

        if (offset > 0 && offset < source.length() && Character.isHighSurrogate(source.charAt(offset
                - 1)) && Character.isLowSurrogate(source.charAt(offset)))
            throw new IllegalArgumentException("Position divides surrogate pair");
        return offset;
    }

    public static JsonObject position(String source, int offset) {
        if (offset < 0 || offset > source.length())
            throw new IllegalArgumentException("Offset outside source");
        int line = 0, start = 0;

        for (int i = 0; i < offset; i++)
            if (source.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        return object("line", line, "character", offset - start);
    }

    public static JsonObject range(String source, int start, int end) {
        return object("start", position(source, start), "end", position(source, end));
    }
}
