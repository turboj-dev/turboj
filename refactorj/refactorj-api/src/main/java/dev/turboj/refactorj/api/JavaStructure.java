package dev.turboj.refactorj.api;

import java.nio.file.Path;
import java.util.List;

/** Immutable results for keyboard Java operations. Offsets are UTF-16, end exclusive. */
public final class JavaStructure {
    private JavaStructure() {}
    public enum Selection { EXPAND, STATEMENT, METHOD, BLOCK, TYPE, BRACKETS }
    public enum Edit {
        MOVE_STATEMENT_UP, MOVE_STATEMENT_DOWN, MOVE_BLOCK_UP, MOVE_BLOCK_DOWN,
        MOVE_ELEMENT_LEFT, MOVE_ELEMENT_RIGHT,
        CONSTRUCTOR, GETTERS, SETTERS, GETTERS_SETTERS, EQUALS, HASH_CODE, TO_STRING,
        IMPLEMENT_METHODS, OVERRIDE_METHODS, SURROUND_IF, SURROUND_TRY_FINALLY,
        COMPLETE_STATEMENT, JAVADOC
    }
    public enum Navigation { SYMBOLS, TYPES, IMPLEMENTATIONS, REFERENCES, TYPE_HIERARCHY,
        CALLERS, CALLEES, NEXT_METHOD, PREVIOUS_METHOD, MATCHING_BRACE }
    public record Range(int start, int end) {
        public Range { if (start < 0 || end < start) throw new IllegalArgumentException("Invalid range"); }
    }
    public record EditResult(List<JavaIntelligence.TextEdit> edits, int caret, int anchor,
                             List<String> warnings, String explanation) {
        public EditResult { edits = List.copyOf(edits); warnings = List.copyOf(warnings); }
        public boolean applicable() { return !edits.isEmpty(); }
        public static EditResult unavailable(String reason) { return new EditResult(List.of(), 0, 0, List.of(), reason); }
    }
    public record Target(Path file, int start, int end, String label) {}
}
