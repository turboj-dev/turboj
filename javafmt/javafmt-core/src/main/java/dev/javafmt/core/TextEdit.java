package dev.javafmt.core;

import java.util.List;
import java.util.Objects;

/** Half-open UTF-16 replacement in the original source version. */
public record TextEdit(int start, int end, String replacement) {
    public TextEdit {
        if (start < 0 || end < start)
            throw new IllegalArgumentException("Invalid edit");
        Objects.requireNonNull(replacement);
    }

    public static String apply(String source, List<TextEdit> edits) {
        StringBuilder result = new StringBuilder(source.length());
        int at = 0;

        for (TextEdit edit : edits) {
            if (edit.start < at || edit.end > source.length())
                throw new IllegalArgumentException("Overlapping or out of bounds edits");
            result.append(source, at, edit.start).append(edit.replacement);
            at = edit.end;
        }
        return result.append(source, at, source.length()).toString();
    }

    /** Map original caret offsets, with right affinity for inserted whitespace. */
    public static int mapOffset(int offset, List<TextEdit> edits) {
        int delta = 0;

        for (TextEdit edit : edits) {
            if (offset < edit.start)
                break;
            if (offset <= edit.end)
                return edit.start + delta + (offset == edit.end ? edit.replacement.length() : Math
                        .min(offset - edit.start, edit.replacement.length()));
            delta += edit.replacement.length() - (edit.end - edit.start);
        }
        return offset + delta;
    }
}
