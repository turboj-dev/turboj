package dev.turboj.editor.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * UTF-16 offsets, immutable original buffer and append-only immutable addition chunks. Edits copy
 * piece descriptors, never the complete document. Snapshots share immutable buffers and can safely
 * be consumed by a background compiler. Mutation belongs to the EDT.
 */
public final class PieceTable implements CharSequence {
    private record Piece(String buffer, int start, int length) {}

    public record Change(int start, String removed, String inserted) {}

    /** Replacement offsets refer to the unmodified document, in UTF-16 code units. */
    public record Edit(int start, int end, String text) {
        public Edit {
            Objects.requireNonNull(text);
        }
    }

    /** Changes are ordered original-coordinate replacements; an entry is one undo operation. */
    public record HistoryResult(long id, List<Change> changes) {
        public HistoryResult {
            changes = List.copyOf(changes);
        }
    }

    private String original = "";
    private final List<String> additions = new ArrayList<>();
    private List<Piece> pieces = List.of();
    private int[] ends = new int[0];
    private int[] lineStarts = {0};
    private int length;
    private long version;
    private long nextHistoryId;
    private boolean historyPrefixTrimmed;

    private record HistoryEntry(long id, List<Change> forward, List<Change> backward) {}

    private final ArrayDeque<HistoryEntry> undo = new ArrayDeque<>();
    private final ArrayDeque<HistoryEntry> redo = new ArrayDeque<>();

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
        historyPrefixTrimmed = false;
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
        remember(List.of(change));
        return change;
    }

    /**
     * Apply ordered, nonoverlapping edits as a single transaction without replacing unchanged
     * spans. All ranges are validated before mutation. Empty and identical edits create no history.
     */
    public HistoryResult replaceAll(List<Edit> edits) {
        Objects.requireNonNull(edits);
        List<Change> changes = new ArrayList<>();
        int previousEnd = -1;
        for (Edit edit : edits) {
            Objects.checkFromToIndex(edit.start(), edit.end(), length);
            if (edit.start() < previousEnd)
                throw new IllegalArgumentException("Overlapping or unordered document edits");
            previousEnd = edit.end();
            String removed = substring(edit.start(), edit.end());
            if (!removed.equals(edit.text()))
                changes.add(new Change(edit.start(), removed, edit.text()));
        }
        if (changes.isEmpty()) return null;
        applyAll(changes);
        return remember(changes);
    }

    private HistoryResult remember(List<Change> changes) {
        List<Change> backward = new ArrayList<>(changes.size());
        int delta = 0;
        for (Change change : changes) {
            backward.add(new Change(change.start() + delta, change.inserted(), change.removed()));
            delta += change.inserted().length() - change.removed().length();
        }
        HistoryEntry entry =
                new HistoryEntry(++nextHistoryId, List.copyOf(changes), List.copyOf(backward));
        undo.addLast(entry);
        if (undo.size() > 2000) { undo.removeFirst(); historyPrefixTrimmed = true; }
        redo.clear();
        return new HistoryResult(entry.id(), changes);
    }

    /** Host workspace undo coordinators may inspect the next transaction without mutating it. */
    public long peekUndoTransactionId() { return undo.isEmpty() ? -1 : undo.getLast().id(); }

    public long peekRedoTransactionId() { return redo.isEmpty() ? -1 : redo.getLast().id(); }

    /** Checkpoints belong to this document instance; -1 identifies its untrimmed history base. */
    public boolean canCoalesceSince(long checkpointId) {
        if (checkpointId == -1) return !historyPrefixTrimmed;
        return checkpointId >= 0 && undo.stream().anyMatch(entry -> entry.id() == checkpointId);
    }

    /**
     * Replaces subsequent undo records with one logical change, without modifying text or version.
     * The supplied baseline is verified by replaying inverse history before any history mutation.
     * Existing earlier edits are retained. A net-zero change removes only the subsequent history
     * and returns null. Checkpoint IDs are document-local; callers retain document identity too.
     */
    public HistoryResult coalesceSince(long checkpointId, String beforeText) {
        String current = toString();
        int count = validateCoalesce(checkpointId, beforeText, current);
        List<Change> combined = List.of();
        if (!beforeText.equals(current)) {
            int start = 0, beforeEnd = beforeText.length(), afterEnd = current.length();
            while (start < beforeEnd && start < afterEnd && beforeText.charAt(start) == current.charAt(start)) start++;
            while (!safeBoundary(beforeText, start) || !safeBoundary(current, start)) start--;
            while (beforeEnd > start && afterEnd > start && beforeText.charAt(beforeEnd - 1) == current.charAt(afterEnd - 1)) {
                beforeEnd--; afterEnd--;
            }
            while (!safeBoundary(beforeText, beforeEnd) || !safeBoundary(current, afterEnd)) {
                beforeEnd++; afterEnd++;
            }
            combined = List.of(new Change(start, beforeText.substring(start, beforeEnd), current.substring(start, afterEnd)));
        }
        for (int i = 0; i < count; i++) undo.removeLast();
        redo.clear();
        return combined.isEmpty() ? null : remember(combined);
    }

    /** Preflight for hosts which must validate all buffers before publishing any workspace edit. */
    public void validateCoalesceSince(long checkpointId, String beforeText) {
        validateCoalesce(checkpointId, beforeText, toString());
    }

    private int validateCoalesce(long checkpointId, String beforeText, String current) {
        Objects.requireNonNull(beforeText);
        if (!canCoalesceSince(checkpointId)) throw new IllegalArgumentException("Undo checkpoint is unavailable in this document");
        StringBuilder reconstructed = new StringBuilder(current);
        int count = 0;
        var entries = undo.descendingIterator();
        while (entries.hasNext()) {
            HistoryEntry entry = entries.next();
            if (entry.id() == checkpointId) break;
            for (int i = entry.backward().size() - 1; i >= 0; i--) {
                Change change = entry.backward().get(i);
                int end = change.start() + change.removed().length();
                if (change.start() < 0 || end > reconstructed.length()
                        || !reconstructed.substring(change.start(), end).equals(change.removed()))
                    throw new IllegalStateException("Undo history does not match the current document");
                reconstructed.replace(change.start(), end, change.inserted());
            }
            count++;
        }
        if (!beforeText.contentEquals(reconstructed))
            throw new IllegalArgumentException("Undo checkpoint does not match the supplied source baseline");
        return count;
    }

    private static boolean safeBoundary(String value, int offset) {
        if (offset == 0 || offset == value.length()) return true;
        char before = value.charAt(offset - 1), after = value.charAt(offset);
        return !(Character.isHighSurrogate(before) && Character.isLowSurrogate(after))
                && !(before == '\r' && after == '\n');
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public Change undo() {
        HistoryResult result = undoTransaction();
        return result == null ? null : result.changes().getFirst();
    }

    public Change redo() {
        HistoryResult result = redoTransaction();
        return result == null ? null : result.changes().getFirst();
    }

    public HistoryResult undoTransaction() {
        if (undo.isEmpty()) return null;
        HistoryEntry original = undo.removeLast();
        applyAll(original.backward());
        redo.addLast(original);
        return new HistoryResult(original.id(), original.backward());
    }

    public HistoryResult redoTransaction() {
        if (redo.isEmpty()) return null;
        HistoryEntry original = redo.removeLast();
        applyAll(original.forward());
        undo.addLast(original);
        return new HistoryResult(original.id(), original.forward());
    }

    /** One pass over pieces and line starts, even for thousands of formatter whitespace edits. */
    private void applyAll(List<Change> changes) {
        if (changes.size() == 1) {
            apply(changes.getFirst());
            return;
        }
        int lineCount = lineStarts.length;
        for (Change change : changes) {
            lineCount += (int) change.inserted().chars().filter(c -> c == '\n').count();
            lineCount -= (int) change.removed().chars().filter(c -> c == '\n').count();
        }
        int[] updatedStarts = new int[lineCount];
        List<Piece> updated = new ArrayList<>(pieces.size() + changes.size() * 2);
        int sourceAt = 0, oldLine = 1, newLine = 1, delta = 0;
        for (Change change : changes) {
            int start = change.start(), end = start + change.removed().length();
            appendRange(updated, sourceAt, start);
            while (oldLine < lineStarts.length && lineStarts[oldLine] <= start)
                updatedStarts[newLine++] = lineStarts[oldLine++] + delta;
            while (oldLine < lineStarts.length && lineStarts[oldLine] <= end) oldLine++;
            if (!change.inserted().isEmpty()) {
                additions.add(change.inserted());
                addPiece(updated, new Piece(change.inserted(), 0, change.inserted().length()));
                for (int i = 0; i < change.inserted().length(); i++)
                    if (change.inserted().charAt(i) == '\n')
                        updatedStarts[newLine++] = start + delta + i + 1;
            }
            delta += change.inserted().length() - change.removed().length();
            sourceAt = end;
        }
        appendRange(updated, sourceAt, length);
        while (oldLine < lineStarts.length)
            updatedStarts[newLine++] = lineStarts[oldLine++] + delta;
        if (newLine != lineCount) throw new IllegalStateException("Document line index mismatch");
        pieces = updated;
        lineStarts = updatedStarts;
        ends = new int[pieces.size()];
        int running = 0;
        for (int i = 0; i < pieces.size(); i++) ends[i] = running += pieces.get(i).length();
        length += delta;
        version++;
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
