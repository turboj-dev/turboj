package dev.turboj.templates;

import java.util.*;
import dev.turboj.ui.EditorComponent.Selection;

/** Pure expansion; no scripts, network, clipboard access or workspace mutation. */
public record LiveTemplateExpansion(String text, int exit, List<String> names, List<List<Selection>> fields,
                                    Set<String> editable, List<String> unresolved) {
    public static LiveTemplateExpansion expand(LiveTemplateCatalog.Template template, JavaTemplateContext context,
                                                String selected, String indent, int tabWidth, String separator) {
        var definitions = new LinkedHashMap<String, LiveTemplateCatalog.Variable>();
        template.variables().forEach(v -> definitions.put(v.name(), v));
        String body = template.body().replace("\r\n", "\n");
        // Catalog indentation is two columns; keep user-written indentation exactly as declared.
        if (template.id().startsWith("Java")) {
            body = body.replaceAll("(?m)^  ", java.util.regex.Matcher.quoteReplacement(" ".repeat(tabWidth)));
        }
        body = body.replace("\n", separator + indent);
        var values = new LinkedHashMap<String, String>(); values.put("SELECTION", selected); values.put("__body", template.body()); var editable = new LinkedHashSet<String>(); var unresolved = new ArrayList<String>();
        for (var variable : definitions.values()) {
            String value = evaluate(variable.expression(), context, values, variable.name());
            if (value == null) {
                if (!variable.expression().isBlank()) unresolved.add(variable.name() + ": " + variable.expression());
                value = literal(variable.defaultValue());
                if (value == null) value = variable.defaultValue();
                editable.add(variable.name());
            } else if (variable.alwaysStop()) editable.add(variable.name());
            values.put(variable.name(), value);
        }
        var text = new StringBuilder(); var ranges = new LinkedHashMap<String, List<Selection>>(); int previous = 0, exit = -1;
        for (var token : LiveTemplateCatalog.placeholders(body)) {
            text.append(body, previous, token.start()); String name = token.name();
            if (name.isEmpty()) text.append('$');
            else if (name.equals("END")) exit = text.length();
            else if (name.equals("SELECTION")) text.append(selected);
            else {
                if (!values.containsKey(name)) { values.put(name, ""); editable.add(name); }
                String canonical = name;
                var def = definitions.get(name);
                // Simple aliases are truly linked fields, not a copied value that can drift.
                if (def != null && values.containsKey(def.expression().strip())) canonical = def.expression().strip();
                int start = text.length(); text.append(values.get(name));
                ranges.computeIfAbsent(canonical, k -> new ArrayList<>()).add(new Selection(text.length(), start));
            }
            previous = token.end();
        }
        text.append(body, previous, body.length());
        var names = new ArrayList<String>();
        for (String name : definitions.keySet()) if (ranges.containsKey(name)) names.add(name);
        for (String name : ranges.keySet()) if (!names.contains(name)) names.add(name);
        return new LiveTemplateExpansion(text.toString(), exit < 0 ? text.length() : exit, List.copyOf(names),
                names.stream().map(n -> List.copyOf(ranges.get(n))).toList(), Set.copyOf(editable), List.copyOf(unresolved));
    }
    public static String evaluate(String expression, JavaTemplateContext context, Map<String, String> values, String name) {
        return evaluate(expression, context, values, name, 0);
    }
    private static String evaluate(String expression, JavaTemplateContext context, Map<String, String> values, String name, int depth) {
        if (depth > 24) return null;
        String e = expression.strip();
        if (e.isEmpty()) return null;
        if (e.equals("SELECTION") && context.semantics() != null && !context.semantics().selectionType().isEmpty()) return context.semantics().selectionType();
        if (values.containsKey(e)) return values.get(e);
        String literal = literal(e); if (literal != null) return literal;
        if (e.equals("className") || e.equals("className()")) return nonempty(context.className());
        if (e.equals("methodName()")) return nonempty(context.methodName());
        if (e.equals("fileName()")) return context.fileName();
        if (e.equals("fileNameWithoutExtension()")) return context.fileName().replaceFirst("\\.[^.]+$", "");
        if (e.equals("suggestIndexName()")) return fresh("i", context, values);
        if (e.equals("suggestVariableName()")) return fresh(switch (name) {
            case "ENTRY" -> "entry"; case "KEY" -> "key"; case "VALUE" -> "value"; case "ITER" -> "iterator";
            case "TOKENIZER" -> "tokenizer"; default -> "value";
        }, context, values);
        if (e.equals("parameterInfo()")) {
            return context.parameters().isEmpty() ? "\"\"" : String.join(" + \", \" + ", context.parameters().stream().map(p -> "\"" + p + " = \" + " + p).toList());
        }
        if (e.startsWith("escapeString(") && e.endsWith(")")) {
            String value = values.get(e.substring(13, e.length() - 1).strip());
            return value == null ? null : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
        if (e.equals("expectedType()")) return context.semantics() == null ? null : nonempty(context.semantics().expectedType());
        if (e.equals("rightSideType()")) return rightSideType(context, values, name);
        if (e.equals("castToLeftSideType()")) {
            String body = values.getOrDefault("__body", "");
            var pattern = java.util.regex.Pattern.compile("\\$(\\w+)\\$\\s+\\$\\w+\\$\\s*=\\s*\\$" + java.util.regex.Pattern.quote(name) + "\\$\\s*\\$(\\w+)\\$\\.(\\w+)\\(");
            var match = pattern.matcher(body);
            if (!match.find() || context.semantics() == null) return null;
            String left = values.get(match.group(1));
            String right = context.semantics().expressionTypes().get(values.get(match.group(2)) + "#" + match.group(3));
            if (left == null || right == null) return null;
            if (sameType(left, right) || parents(context, right).stream().anyMatch(t -> sameType(t, left))) return "";
            if (parents(context, left).stream().anyMatch(t -> sameType(t, right))) return "(" + left + ")";
            return null;
        }
        if (e.equals("arrayVariable()")) return variable(context, type -> type.endsWith("[]"));
        if (e.equals("iterableVariable()")) return variable(context, type -> type.endsWith("[]") || isType(context, type, "java.lang.Iterable")
                || context.semantics() == null && type.matches("(?:java.util.)?(?:List|Set|Collection|Iterable|ArrayList|HashSet)(?:<.*>)?"));
        int open = e.indexOf('(');
        if (open > 0 && e.endsWith(")")) {
            String function = e.substring(0, open); List<String> args = macroArguments(e.substring(open + 1, e.length() - 1));
            if (args == null) return null;
            var evaluated = new ArrayList<String>();
            for (String arg : args) evaluated.add(evaluate(arg, context, values, name, depth + 1));
            if (evaluated.stream().anyMatch(Objects::isNull)) return null;
            if (Set.of("variableOfType", "suggestFirstVariableName").contains(function) && evaluated.size() == 1) {
                String wanted = evaluated.getFirst();
                return variable(context, type -> wanted.isEmpty() || isType(context, type, wanted));
            }
            if (function.equals("typeOfVariable") && evaluated.size() == 1) return typeOf(context, evaluated.getFirst());
            if (Set.of("componentTypeOf", "iterableComponentType", "guessElementType").contains(function) && evaluated.size() == 1) {
                String type = typeOf(context, evaluated.getFirst());
                if (type == null && (args.getFirst().contains("(") || args.getFirst().equals("SELECTION") || args.getFirst().startsWith("\""))
                        && JavaTemplateTypes.type(evaluated.getFirst())) type = evaluated.getFirst();
                if (type == null) return null;
                if (type.endsWith("[]")) return type.substring(0, type.length() - 2);
                if (function.equals("componentTypeOf")) return null;
                var types = arguments(type);
                if (types.isEmpty()) for (String parent : parents(context, type)) {
                    if (raw(parent).equals("Iterable") || raw(parent).equals("Collection")) { types = arguments(parent); if (!types.isEmpty()) break; }
                }
                return types.isEmpty() ? "java.lang.Object" : readable(types.getFirst());
            }
            if (function.equals("typeParameterOf") && evaluated.size() == 2) {
                String type = typeOf(context, evaluated.getFirst()); if (type == null) return null;
                var types = arguments(type);
                try { int index = Integer.parseInt(evaluated.get(1)); return index >= 0 && index < types.size() ? readable(types.get(index)) : null; }
                catch (NumberFormatException invalid) { return null; }
            }
            if (function.equals("subtypes") && evaluated.size() == 1 && context.semantics() != null) {
                String type = evaluated.getFirst(); var choices = context.semantics().concreteTypes().getOrDefault(type, List.of());
                if (choices.isEmpty()) choices = context.semantics().concreteTypes().getOrDefault(type.replaceFirst("<.*", ""), List.of());
                return choices.size() == 1 ? choices.getFirst() : null;
            }
        }
        // Unknown functions and ambiguous semantic queries remain explicit editable fields.
        return null;
    }
    private static String typeOf(JavaTemplateContext context, String value) {
        if (context.semantics() != null && value.equals("SELECTION")) return nonempty(context.semantics().selectionType());
        return context.variables().get(value);
    }
    private static List<String> parents(JavaTemplateContext context, String type) {
        return context.semantics() == null ? List.of() : context.semantics().supertypes().getOrDefault(type, List.of());
    }
    private static boolean sameType(String left, String right) {
        return left.equals(right) || !left.contains(".") && left.equals(right.replaceFirst(".*\\.", ""))
                || !right.contains(".") && right.equals(left.replaceFirst(".*\\.", ""));
    }
    private static boolean isType(JavaTemplateContext context, String type, String wanted) {
        String rawType = type.replaceFirst("<.*", ""), rawWanted = wanted.replaceFirst("<.*", "");
        if (sameType(rawType, rawWanted)) return true;
        if (rawWanted.equals("Object") || rawWanted.equals("java.lang.Object"))
            return !Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double", "void").contains(type);
        return parents(context, type).stream().anyMatch(parent -> sameType(parent.replaceFirst("<.*", ""), rawWanted));
    }
    private static String readable(String type) {
        if (type.equals("?") || type.startsWith("? super ")) return "java.lang.Object";
        return type.startsWith("? extends ") ? type.substring(10) : type;
    }
    private static String rightSideType(JavaTemplateContext context, Map<String, String> values, String name) {
        if (context.semantics() == null) return null;
        String body = values.getOrDefault("__body", "");
        var pattern = java.util.regex.Pattern.compile("\\$" + java.util.regex.Pattern.quote(name) + "\\$\\s+\\$\\w+\\$\\s*=\\s*(?:\\$CAST\\$\\s*)?\\$(\\w+)\\$\\.(\\w+)\\(");
        var match = pattern.matcher(body);
        return match.find() ? context.semantics().expressionTypes().get(values.get(match.group(1)) + "#" + match.group(2)) : null;
    }
    private static List<String> macroArguments(String text) {
        if (text.isBlank()) return List.of();
        var result = new ArrayList<String>(); int depth = 0, from = 0; boolean quote = false, escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') quote = false; }
            else if (c == '"') quote = true;
            else if (c == '(') { if (++depth > 24) return null; }
            else if (c == ')') { if (--depth < 0) return null; }
            else if (c == ',' && depth == 0) { result.add(text.substring(from, i).strip()); from = i + 1; }
        }
        if (quote || depth != 0) return null;
        result.add(text.substring(from).strip()); return result;
    }
    private static String nonempty(String value) { return value.isEmpty() ? null : value; }
    private static String raw(String value) { return value.replaceFirst("<.*", "").replaceFirst(".*\\.", ""); }
    private static String variable(JavaTemplateContext context, java.util.function.Predicate<String> predicate) {
        String result = null;
        for (var entry : context.variables().entrySet()) if (predicate.test(entry.getValue())) result = entry.getKey();
        return result;
    }
    private static String fresh(String seed, JavaTemplateContext context, Map<String, String> values) {
        String name = seed; int suffix = 1;
        while (context.variables().containsKey(name) || values.containsValue(name)) name = seed + suffix++;
        return name;
    }
    private static List<String> arguments(String type) {
        int start = type.indexOf('<'), end = type.lastIndexOf('>');
        if (start < 0 || end <= start) return List.of();
        var args = new ArrayList<String>(); int depth = 0, from = start + 1;
        for (int i = from; i < end; i++) {
            char c = type.charAt(i); if (c == '<') depth++; else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { args.add(type.substring(from, i).strip()); from = i + 1; }
        }
        args.add(type.substring(from, end).strip()); return args;
    }
    private static String literal(String value) {
        if (!value.startsWith("\"") || !value.endsWith("\"")) return null;
        try { return com.google.gson.JsonParser.parseString(value).getAsString(); } catch (RuntimeException ignored) { return null; }
    }
}
