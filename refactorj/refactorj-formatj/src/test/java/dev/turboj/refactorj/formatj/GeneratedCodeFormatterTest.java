package dev.turboj.refactorj.formatj;

import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Operation;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedCodeFormatterTest {
    @Test void formatsGeneratedMethodWithoutRewritingOtherDeclarations() {
        Path root = Path.of("/workspace"), file = root.resolve("C.java");
        String source = "class C {\n  int untouched( ){return 3;}\n}\n";
        int insertion = source.lastIndexOf('}');
        String generated = "\nprivate int square(int value){\n\treturn value*value;\n}\n";
        Plan plan = new Plan("example", Operation.EXTRACT_METHOD,
                new WorkspaceSnapshot(root, 1, List.of(new SourceSnapshot(file, source, 1, true)), List.of(root), List.of()),
                List.of(), List.of(new FileEdits(file, List.of(new TextEdit(insertion, insertion, generated)))), List.of(), List.of(), ValidationStatus.VALID, "Extract method");
        Plan formatted = new GeneratedCodeFormatter().format(plan);
        String output = TextEdit.apply(source, formatted.edits().getFirst().edits());
        assertTrue(output.contains("  int untouched( ){return 3;}"));
        assertTrue(output.contains("return value * value;"));
        assertFalse(output.contains("\t"));
        assertTrue(formatted.edits().stream().flatMap(edits -> edits.edits().stream()).allMatch(edit -> edit.start() >= insertion));
    }
}
