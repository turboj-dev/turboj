package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.TextEdit;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditComposerTest {
    @Test void preservesUnchangedMiddleSpansAndUtf16Characters() {
        String source = "a😀b unchanged tail";
        var first = List.of(new TextEdit(1, 3, "😁"), new TextEdit(15, 19, "end"));
        String middle = TextEdit.apply(source, first);
        var second = List.of(new TextEdit(0, 0, " "), new TextEdit(middle.length(), middle.length(), "\n"));
        var combined = EditComposer.compose(source, first, second);
        assertEquals(TextEdit.apply(middle, second), TextEdit.apply(source, combined));
        assertTrue(combined.stream().noneMatch(edit -> edit.start() < 5 && edit.end() > 14));
    }
    @Test void seededCompositionEqualsSequentialApplication() {
        Random random = new Random(21973);
        for (int iteration = 0; iteration < 1000; iteration++) {
            String source = "abcdefghijklmnopqrstuvwxyz0123456789";
            var first = edits(source, random);
            String intermediate = TextEdit.apply(source, first);
            var second = edits(intermediate, random);
            assertEquals(TextEdit.apply(intermediate, second), TextEdit.apply(source, EditComposer.compose(source, first, second)));
        }
    }
    private List<TextEdit> edits(String source, Random random) {
        List<TextEdit> edits = new ArrayList<>(); int offset = 0;
        while (offset < source.length()) {
            int start = Math.min(source.length(), offset + random.nextInt(5));
            int end = Math.min(source.length(), start + random.nextInt(4));
            edits.add(new TextEdit(start, end, "X".repeat(random.nextInt(4))));
            offset = Math.max(start + 1, end);
        }
        return edits;
    }
}
