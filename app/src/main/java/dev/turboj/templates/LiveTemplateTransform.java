package dev.turboj.templates;

import dev.turboj.refactorj.api.JavaIntelligence;
import dev.turboj.formatting.FormattingController;
import dev.turboj.ui.EditorComponent;
import dev.javafmt.core.*;
import java.util.*;

/** Pure preparation on the shared semantic worker. The host checks the source revision before applying. */
public final class LiveTemplateTransform {
    private LiveTemplateTransform() {}
    public record Result(List<EditorComponent.TextEdit> edits, int caret, int anchor) {}
    public static Result prepare(JavaIntelligence service, JavaIntelligence.Context context,
            LiveTemplateCatalog.Template template, int start, int end, int caret, int anchor,
            FormattingController.Engine formatter) {
        String source = context.source(), text = source;
        if (template.shortenNames()) {
            var edits = service.shortenTemplateNames(context, start, end);
            text = apply(text, edits);
            caret = map(caret, edits); anchor = map(anchor, edits);
            start = map(start, edits); end = map(end, edits);
        }
        if (template.reformat()) {
            String level = context.projects().stream().filter(p -> p.sourceRoots().stream().anyMatch(context.file()::startsWith))
                    .map(p -> p.languageLevel()).findFirst().orElse("25");
            var result = formatter.format(new FormatRequest(text, context.version(), LineEndingPolicy.PRESERVE),
                    new TextRange(start, end), level);
            if (result.success()) {
                var edits = result.edits().stream().map(e -> new JavaIntelligence.TextEdit(e.start(), e.end(), e.replacement())).toList();
                caret = map(caret, edits); anchor = map(anchor, edits); text = result.formattedText();
            }
        }
        if (text.equals(source)) return new Result(List.of(), caret, anchor);
        int first = 0, oldEnd = source.length(), newEnd = text.length();
        while (first < oldEnd && first < newEnd && source.charAt(first) == text.charAt(first)) first++;
        // Never split a surrogate or CRLF when the common boundary falls between their code units.
        if (first > 0 && first < source.length() && (Character.isLowSurrogate(source.charAt(first))
                || source.charAt(first) == '\n' && source.charAt(first - 1) == '\r')) first--;
        while (oldEnd > first && newEnd > first && source.charAt(oldEnd - 1) == text.charAt(newEnd - 1)) { oldEnd--; newEnd--; }
        if (oldEnd < source.length() && oldEnd > first && (Character.isLowSurrogate(source.charAt(oldEnd))
                || source.charAt(oldEnd) == '\n' && source.charAt(oldEnd - 1) == '\r')) { oldEnd++; newEnd++; }
        return new Result(List.of(new EditorComponent.TextEdit(first, oldEnd, text.substring(first, newEnd))), caret, anchor);
    }
    private static String apply(String text, List<JavaIntelligence.TextEdit> edits) {
        var out = new StringBuilder(); int offset = 0;
        for (var edit : edits) {
            if (edit.start() < offset || edit.end() > text.length()) throw new IllegalArgumentException("Invalid template edits");
            out.append(text, offset, edit.start()).append(edit.text()); offset = edit.end();
        }
        return out.append(text, offset, text.length()).toString();
    }
    private static int map(int offset, List<JavaIntelligence.TextEdit> edits) {
        int delta = 0;
        for (var edit : edits) {
            if (offset < edit.start()) break;
            if (offset < edit.end()) return edit.start() + delta + Math.min(offset - edit.start(), edit.text().length());
            delta += edit.text().length() - (edit.end() - edit.start());
        }
        return offset + delta;
    }
}
