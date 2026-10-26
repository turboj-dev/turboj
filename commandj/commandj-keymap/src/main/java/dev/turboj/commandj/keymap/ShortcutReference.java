package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import java.util.*;

/** Registry-derived documentation and diagnostics. Never maintains a second command catalog. */
public final class ShortcutReference {
    private ShortcutReference() {}

    public record Row(String id, String title, String category, String description,
            String contexts, List<KeyBinding> bindings, Availability availability,
            List<String> aliases, List<String> warnings) {
        public Row {
            bindings = List.copyOf(bindings); aliases = List.copyOf(aliases);
            warnings = List.copyOf(warnings);
        }
        public String shortcuts() {
            return bindings.isEmpty() ? "Unassigned" : String.join(" / ", bindings.stream()
                    .map(b -> b.sequence().display()).toList());
        }
    }

    public static List<KeyBinding> bindings(CommandRegistry registry,
            Map<String, List<KeyBinding>> profile, String command) {
        var result = new LinkedHashSet<KeyBinding>();
        profile.forEach((id, values) -> {
            if (registry.canonicalId(id).equals(command)) result.addAll(values);
        });
        return List.copyOf(result);
    }

    public static List<Row> rows(CommandRegistry registry, KeymapManager maps,
            CommandContext context) {
        var collisions = maps.conflicts();
        return registry.commands().stream().sorted(Comparator.comparing(Command::category)
                .thenComparing(Command::title).thenComparing(Command::id)).map(command -> {
                    var values = bindings(registry, maps.bindings(), command.id());
                    var warnings = new LinkedHashSet<String>();
                    for (var value : values) ReservedShortcuts.warning(value.sequence()).ifPresent(warnings::add);
                    for (var collision : collisions)
                        if (registry.canonicalId(collision.command()).equals(command.id())
                                || registry.canonicalId(collision.other()).equals(command.id()))
                            warnings.add(collision.reason() + ": " + collision.command() + " / " + collision.other());
                    var aliases = new TreeSet<>(command.aliases());
                    registry.aliases().forEach((alias, target) -> { if (target.equals(command.id())) aliases.add(alias); });
                    return new Row(command.id(), command.title(), command.category(), command.description(),
                            command.requiredContexts().isEmpty() ? "Any" : String.join(", ", new TreeSet<>(command.requiredContexts())),
                            values, command.available(context), List.copyOf(aliases), List.copyOf(warnings));
                }).toList();
    }

    public static List<String> audit(CommandRegistry registry, KeymapManager maps) {
        var result = new ArrayList<String>();
        for (var conflict : maps.conflicts()) result.add(conflict.command() + " / " + conflict.other()
                + ": " + conflict.sequence().display() + " — " + conflict.reason());
        maps.bindings().forEach((id, values) -> {
            if (!values.isEmpty() && registry.find(id).isEmpty()) result.add(id + ": binding has no registered command");
            for (var value : values) ReservedShortcuts.warning(value.sequence())
                    .ifPresent(warning -> result.add(id + ": " + value.sequence().display() + " — " + warning));
        });
        result.addAll(maps.migrationNotices());
        return List.copyOf(result);
    }

    public static String markdown(CommandRegistry registry, KeymapManager maps, CommandContext context) {
        StringBuilder out = new StringBuilder("# Command reference\n\nActive keymap: **")
                .append(cell(maps.selected().name())).append("**\n\n")
                .append("Generated from the executable registry. Availability reflects the captured context.\n\n")
                .append("| Command ID | Command | Category | Shortcut | Context | Availability | Aliases |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows(registry, maps, context)) out.append("| ").append(cell(row.id())).append(" | ")
                .append(cell(row.title())).append(" | ").append(cell(row.category())).append(" | ")
                .append(cell(row.shortcuts())).append(" | ").append(cell(contexts(row))).append(" | ")
                .append(cell(row.availability().enabled() ? "Available" : row.availability().reason())).append(" | ")
                .append(cell(String.join(", ", row.aliases()))).append(" |\n");
        appendAudit(out, registry, maps);
        return out.toString();
    }

    public static String contexts(Row row) {
        var contexts = new LinkedHashSet<String>();
        contexts.add(row.contexts());
        for (var binding : row.bindings()) {
            var required = binding.context().required(); var forbidden = binding.context().forbidden();
            if (!required.isEmpty() || !forbidden.isEmpty()) contexts.add("binding requires "
                    + new TreeSet<>(required) + (forbidden.isEmpty() ? "" : "; excludes " + new TreeSet<>(forbidden)));
        }
        return String.join("; ", contexts);
    }

    public static String comparisonMarkdown(CommandRegistry registry, KeymapManager maps) {
        var profiles = maps.profiles();
        var out = new StringBuilder("# Keymap comparison\n\nGenerated from CommandJ; unassigned commands remain available in Find Action.\n\n| Command ID | Command | Category |");
        for (var profile : profiles) out.append(' ').append(cell(profile.name())).append(" |");
        out.append("\n| --- | --- | --- |");
        for (var ignored : profiles) out.append(" --- |");
        out.append('\n');
        var resolved = new LinkedHashMap<String, Map<String, List<KeyBinding>>>();
        profiles.forEach(profile -> resolved.put(profile.id(), maps.bindingsForProfile(profile.id())));
        for (var command : registry.commands().stream().sorted(Comparator.comparing(Command::id)).toList()) {
            out.append("| ").append(cell(command.id())).append(" | ").append(cell(command.title())).append(" | ")
                    .append(cell(command.category())).append(" |");
            for (var profile : profiles) {
                var keys = bindings(registry, resolved.get(profile.id()), command.id());
                out.append(' ').append(keys.isEmpty() ? "Unassigned" : cell(String.join(" / ", keys.stream()
                        .map(key -> key.sequence().words()).toList()))).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void appendAudit(StringBuilder out, CommandRegistry registry, KeymapManager maps) {
        out.append("\n## Shortcut diagnostics\n\n");
        var diagnostics = audit(registry, maps);
        if (diagnostics.isEmpty()) out.append("No binding conflicts or migration warnings.\n");
        else for (String diagnostic : diagnostics) out.append("- ").append(cell(diagnostic)).append('\n');
    }
    private static String cell(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
