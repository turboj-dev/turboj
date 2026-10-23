package com.turboj.editor.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * UTF-16 offsets, immutable original buffer and append-only immutable addition chunks.
 * Edits copy piece descriptors, never the complete document. Snapshots share immutable
 * buffers and can safely be consumed by a background compiler. Mutation belongs to the EDT.
 */
public final class PieceTable implements CharSequence {
    private record Piece(String buffer, int start, int length) {}

    public record Change(int start, String removed, String inserted) {}

    private String original = "";
    private final List<String> additions = new ArrayList<>();
    private List<Piece> pieces = List.of();
    private int[] ends = new int[0];
    private int[] lineStarts = {0};
    private int length;
    private long version;
    private final ArrayDeque<Change> undo = new ArrayDeque<>();
    private final ArrayDeque<Change> redo = new ArrayDeque<>();

    public PieceTable() {}

    public PieceTable(String text) {
        setText(text);
    }

    public void setText(String text) {
        original = Objects.requireNonNull(text);
        additions.clear();
        pieces = text.isEmpty() ? List.of() : List.of(new Piece(original, 0, text.length()));
        length = text.length();
        ends = text.isEmpty() ? new int[0] : new int[] {length};
        lineStarts = starts(text);
        undo.clear();
        redo.clear();
        version++;
    }

    @Override
    public int length() {
        return length;
    }

    public long version() {
        return version;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public int lineStart(int zeroBasedLine) {
        return lineStarts[zeroBasedLine];
    }

    public int lineEnd(int zeroBasedLine) {
        int end =
                zeroBasedLine + 1 < lineStarts.length ? lineStarts[zeroBasedLine + 1] - 1 : length;
        return end > lineStarts[zeroBasedLine] && charAt(end - 1) == '\r' ? end - 1 : end;
    }

    public int lineAt(int offset) {
        Objects.checkIndex(offset, length + 1);
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index : -index - 2;
    }

    public String lineText(int zeroBasedLine) {
        return substring(lineStart(zeroBasedLine), lineEnd(zeroBasedLine));
    }

    public String lineEnding() {
        if (lineStarts.length < 2) return "\n";
        int newline = lineStarts[1] - 1;
        return newline > 0 && charAt(newline - 1) == '\r' ? "\r\n" : "\n";
    }

    @Override
    public char charAt(int index) {
        return character(pieces, ends, length, index);
    }

    @Override
    public String subSequence(int start, int end) {
        return substring(start, end);
    }

    public String substring(int start, int end) {
        return range(pieces, ends, length, start, end);
    }

    @Override
    public String toString() {
        return substring(0, length);
    }

    public Snapshot snapshot() {
        return new Snapshot(List.copyOf(pieces), ends.clone(), lineStarts.clone(), length, version);
    }

    public Change insert(int offset, String text) {
        return replace(offset, offset, text);
    }

    public Change delete(int start, int end) {
        return replace(start, end, "");
    }

    public Change replace(int start, int end, String text) {
        Objects.checkFromToIndex(start, end, length);
        Objects.requireNonNull(text);
        Change change = new Change(start, substring(start, end), text);
        if (change.removed().equals(text)) return change;
        apply(change);
        undo.addLast(change);
        if (undo.size() > 2000) undo.removeFirst();
        redo.clear();
        return change;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public Change undo() {
        if (undo.isEmpty()) return null;
        Change change = undo.removeLast();
        Change inverse = new Change(change.start(), change.inserted(), change.removed());
        apply(inverse);
        redo.addLast(change);
        return inverse;
    }

    public Change redo() {
        if (redo.isEmpty()) return null;
        Change change = redo.removeLast();
        apply(change);
        undo.addLast(change);
        return change;
    }

    private void apply(Change change) {
        int start = change.start(), end = start + change.removed().length();
        List<Piece> updated = new ArrayList<>(pieces.size() + 2);
        appendRange(updated, 0, start);
        if (!change.inserted().isEmpty()) {
            additions.add(change.inserted());
            addPiece(updated, new Piece(change.inserted(), 0, change.inserted().length()));
        }
        appendRange(updated, end, length);
        int delta = change.inserted().length() - change.removed().length();
        int kept = upperBound(lineStarts, start), removedThrough = upperBound(lineStarts, end);
        int newLines = (int) change.inserted().chars().filter(c -> c == '\n').count();
        int[] updatedStarts = new int[kept + newLines + lineStarts.length - removedThrough];
        System.arraycopy(lineStarts, 0, updatedStarts, 0, kept);
        int cursor = kept;
        for (int i = 0; i < change.inserted().length(); i++)
            if (change.inserted().charAt(i) == '\n') updatedStarts[cursor++] = start + i + 1;
        for (int i = removedThrough; i < lineStarts.length; i++)
            updatedStarts[cursor++] = lineStarts[i] + delta;
        lineStarts = updatedStarts;
        pieces = updated;
        ends = new int[pieces.size()];
        int running = 0;
        for (int i = 0; i < pieces.size(); i++) ends[i] = running += pieces.get(i).length();
        length += delta;
        version++;
    }

    private void appendRange(List<Piece> target, int from, int to) {
        if (from == to) return;
        int index = upperBound(ends, from);
        int position = index == 0 ? 0 : ends[index - 1];
        for (; index < pieces.size() && position < to; index++) {
            Piece piece = pieces.get(index);
            int localStart = Math.max(0, from - position);
            int localEnd = Math.min(piece.length(), to - position);
            if (localEnd > localStart)
                addPiece(
                        target,
                        new Piece(
                                piece.buffer(), piece.start() + localStart, localEnd - localStart));
            position += piece.length();
        }
    }

    private static void addPiece(List<Piece> list, Piece piece) {
        if (!list.isEmpty()) {
            Piece last = list.getLast();
            if (last.buffer() == piece.buffer() && last.start() + last.length() == piece.start()) {
                list.set(
                        list.size() - 1,
                        new Piece(last.buffer(), last.start(), last.length() + piece.length()));
                return;
            }
        }
        list.add(piece);
    }

    private static int upperBound(int[] values, int value) {
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (values[mid] <= value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int[] starts(String text) {
        int[] result = new int[1 + (int) text.chars().filter(c -> c == '\n').count()];
        int index = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') result[index++] = i + 1;
        return result;
    }

    private static char character(List<Piece> pieces, int[] ends, int length, int index) {
        Objects.checkIndex(index, length);
        int pieceIndex = upperBound(ends, index);
        Piece piece = pieces.get(pieceIndex);
        int pieceStart = pieceIndex == 0 ? 0 : ends[pieceIndex - 1];
        return piece.buffer().charAt(piece.start() + index - pieceStart);
    }

    private static String range(List<Piece> pieces, int[] ends, int length, int start, int end) {
        Objects.checkFromToIndex(start, end, length);
        if (start == end) return "";
        StringBuilder result = new StringBuilder(end - start);
        int index = upperBound(ends, start), position = index == 0 ? 0 : ends[index - 1];
        for (; index < pieces.size() && position < end; index++) {
            Piece piece = pieces.get(index);
            int from = Math.max(0, start - position), to = Math.min(piece.length(), end - position);
            if (to > from) result.append(piece.buffer(), piece.start() + from, piece.start() + to);
            position += piece.length();
        }
        return result.toString();
    }

    public static final class Snapshot implements CharSequence {
        private final List<Piece> pieces;
        private final int[] ends, lineStarts;
        private final int length;
        private final long version;

        private Snapshot(
                List<Piece> pieces, int[] ends, int[] lineStarts, int length, long version) {
            this.pieces = pieces;
            this.ends = ends;
            this.lineStarts = lineStarts;
            this.length = length;
            this.version = version;
        }

        public long version() {
            return version;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            return character(pieces, ends, length, index);
        }

        @Override
        public String subSequence(int start, int end) {
            return range(pieces, ends, length, start, end);
        }

        @Override
        public String toString() {
            return subSequence(0, length);
        }

        public int lineCount() {
            return lineStarts.length;
        }

        public int lineStart(int line) {
            return lineStarts[line];
        }
    }
}
