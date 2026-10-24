package dev.turboj.refactorj.formatj;

import dev.javafmt.core.*;
import dev.turboj.refactorj.api.EditComposer;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Model.TextEdit;
import java.util.*;

/** Optional FormatJ integration. Changes layout only inside generated replacement ranges. */
public final class GeneratedCodeFormatter {
    private final Map<String,JavaFormatter> formatters=new HashMap<>();
    private JavaFormatter formatter(Plan plan,java.nio.file.Path path){
        String level=plan.before().projects().stream().filter(p->p.sourceRoots().stream().anyMatch(path::startsWith)).map(JavaProjectConfiguration::languageLevel).findFirst().orElse("25");
        return formatters.computeIfAbsent(level,l->new JavaFormatter(new dev.javafmt.parser.JdtJavaParser(l)));
    }
    public Plan format(Plan plan) {
        if (!plan.valid()) return plan;
        boolean generated = switch (plan.operation()) {
            case RENAME, SAFE_DELETE, MOVE, ORGANIZE_IMPORTS, ADD_IMPORT, REMOVE_IMPORT -> false;
            default -> true;
        };
        if (!generated) return plan;
        List<FileEdits> files = new ArrayList<>();
        for (FileEdits file : plan.edits()) {
            String original = plan.before().source(file.file()).text();
            String candidate = TextEdit.apply(original, file.edits());
            var ranges = new ArrayList<TextRange>(); int delta = 0;
            for (TextEdit edit : file.edits()) {
                int start = edit.start() + delta;
                if (!edit.text().isBlank()) ranges.add(new TextRange(start, start + edit.text().length()));
                delta += edit.text().length() - (edit.end() - edit.start());
            }
            var formatting = new TreeMap<Integer, TextEdit>();
            for (TextRange range : ranges) {
                int start = range.start(), end = range.end();
                while (start < end && Character.isWhitespace(candidate.charAt(start))) start++;
                while (end > start && Character.isWhitespace(candidate.charAt(end - 1))) end--;
                var result = formatter(plan,file.file()).formatRange(new FormatRequest(candidate, plan.before().version(), LineEndingPolicy.PRESERVE), new TextRange(start, end));
                if (!result.success()) continue; // Optional formatting never discards a validated semantic plan.
                for (var edit : result.edits()) {
                    if (edit.start() >= range.start() && edit.end() <= range.end()) {
                        TextEdit converted = new TextEdit(edit.start(), edit.end(), edit.replacement());
                        var existing = formatting.get(converted.start());
                        if (existing == null || existing.equals(converted)) formatting.put(converted.start(), converted);
                    }
                }
            }
            List<TextEdit> whitespace = new ArrayList<>(formatting.values());
            try { files.add(new FileEdits(file.file(), EditComposer.compose(original, file.edits(), whitespace))); }
            catch (IllegalArgumentException overlap) { files.add(file); }
        }
        List<FileOperation> operations = new ArrayList<>();
        for (FileOperation operation : plan.fileOperations()) {
            if (operation.kind() == FileOperationKind.CREATE) {
                var formatted = formatter(plan,operation.path()).format(new FormatRequest(operation.content(), plan.before().version(), LineEndingPolicy.PRESERVE));
                operations.add(formatted.success() ? new FileOperation(operation.kind(), operation.path(), operation.destination(), formatted.formattedText()) : operation);
            } else operations.add(operation);
        }
        return new Plan(plan.id(), plan.operation(), plan.before(), plan.affectedSymbols(), files, operations,
                plan.diagnostics(), plan.status(), plan.description());
    }
}
