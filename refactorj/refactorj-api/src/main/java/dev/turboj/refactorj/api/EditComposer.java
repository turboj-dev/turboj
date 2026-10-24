package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.TextEdit;
import java.util.*;

/** Composes edits in successive UTF-16 coordinate spaces while retaining unchanged source spans. */
public final class EditComposer {
    private EditComposer() {}
    private record Piece(int start, int end, String added) {
        int length() { return added == null ? end - start : added.length(); }
        Piece slice(int from, int to) { return added == null ? new Piece(start + from, start + to, null) : new Piece(0, 0, added.substring(from, to)); }
    }

    public static List<TextEdit> compose(String original, List<TextEdit> first, List<TextEdit> second) {
        List<Piece> pieces = new ArrayList<>();
        int at = 0;
        for (TextEdit edit : first) {
            if (edit.start() < at || edit.end() > original.length()) throw new IllegalArgumentException("Invalid first edit sequence");
            if (edit.start() > at) pieces.add(new Piece(at, edit.start(), null));
            if (!edit.text().isEmpty()) pieces.add(new Piece(0, 0, edit.text()));
            at = edit.end();
        }
        if (at < original.length()) pieces.add(new Piece(at, original.length(), null));
        Cursor cursor = new Cursor(pieces);
        List<Piece> composed = new ArrayList<>();
        at = 0;
        for (TextEdit edit : second) {
            if (edit.start() < at || edit.end() > cursor.length) throw new IllegalArgumentException("Invalid second edit sequence");
            cursor.take(edit.start() - at, composed);
            cursor.take(edit.end() - edit.start(), null);
            if (!edit.text().isEmpty()) composed.add(new Piece(0, 0, edit.text()));
            at = edit.end();
        }
        cursor.take(cursor.length - at, composed);
        List<TextEdit> result = new ArrayList<>();
        StringBuilder added = new StringBuilder();
        at = 0;
        for (Piece piece : composed) {
            if (piece.added != null) added.append(piece.added);
            else {
                append(original, at, piece.start, added.toString(), result);
                added.setLength(0); at = piece.end;
            }
        }
        append(original, at, original.length(), added.toString(), result);
        return List.copyOf(result);
    }

    private static void append(String source, int start, int end, String replacement, List<TextEdit> result) {
        int left = 0, right = replacement.length();
        while (start < end && left < right && source.charAt(start) == replacement.charAt(left)) { start++; left++; }
        if (split(source, start)) { start--; left--; }
        while (end > start && right > left && source.charAt(end - 1) == replacement.charAt(right - 1)) { end--; right--; }
        if (split(source, end)) { end++; right++; }
        if (start != end || left != right) result.add(new TextEdit(start, end, replacement.substring(left, right)));
    }
    private static boolean split(String text, int at) {
        return at > 0 && at < text.length() && Character.isHighSurrogate(text.charAt(at - 1)) && Character.isLowSurrogate(text.charAt(at));
    }
    private static final class Cursor {
        final List<Piece> pieces;
        final int length;
        int index, offset;
        Cursor(List<Piece> pieces) { this.pieces = pieces; length = pieces.stream().mapToInt(Piece::length).sum(); }
        void take(int count, List<Piece> destination) {
            while (count > 0) {
                Piece piece = pieces.get(index);
                int size = Math.min(count, piece.length() - offset);
                if (destination != null) destination.add(piece.slice(offset, offset + size));
                count -= size; offset += size;
                if (offset == piece.length()) { index++; offset = 0; }
            }
        }
    }
}
