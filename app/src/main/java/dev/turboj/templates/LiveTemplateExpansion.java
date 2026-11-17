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
        var values = new LinkedHashMap<String, String>(); var editable = new LinkedHashSet<String>(); var unresolved = new ArrayList<String>();
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
        String e = expression.strip();
        if (e.isEmpty()) return null;
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
        if (e.equals("arrayVariable()")) return variable(context, type -> type.endsWith("[]"));
        if (e.equals("iterableVariable()")) return variable(context, type -> type.endsWith("[]") || type.matches("(?:java.util.)?(?:List|Set|Collection|Iterable|ArrayList|HashSet)(?:<.*>)?"));
        if ((e.startsWith("variableOfType(") || e.startsWith("suggestFirstVariableName(")) && e.endsWith(")")) {
            String arg = e.substring(e.indexOf('(') + 1, e.length() - 1); String type = literal(arg);
            if (type == null) type = values.get(arg);
            if (type == null) return null;
            String wanted = type;
            return variable(context, t -> wanted.isEmpty() || wanted.equals("Object") || wanted.equals("java.lang.Object")
                    || raw(t).equals(raw(wanted)));
        }
        for (String function : List.of("componentTypeOf", "iterableComponentType", "guessElementType", "typeOfVariable"))
            if (e.startsWith(function + "(") && e.endsWith(")")) {
                String value = values.get(e.substring(function.length() + 1, e.length() - 1));
                String type = context.variables().get(value);
                if (type == null) return null;
                if (function.equals("typeOfVariable")) return type;
                if (type.endsWith("[]")) return type.substring(0, type.length() - 2);
                if (!function.equals("componentTypeOf")) { var args = arguments(type); return args.isEmpty() ? "Object" : args.getFirst(); }
            }
        if (e.startsWith("typeParameterOf(") && e.endsWith(")")) {
            String[] args = e.substring(16, e.length() - 1).split(",");
            if (args.length == 2) {
                var types = arguments(context.variables().getOrDefault(values.get(args[0]), ""));
                try { int at = Integer.parseInt(args[1].replace("\"", "").strip()); return at >= 0 && at < types.size() ? types.get(at) : null; }
                catch (NumberFormatException ignored) { return null; }
            }
        }
        // Full expected-type/subtype inference and casts need a semantic provider. Never guess a successful result.
        return null;
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
