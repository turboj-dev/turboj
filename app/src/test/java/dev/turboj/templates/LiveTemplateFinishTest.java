package dev.turboj.templates;

import dev.turboj.ui.EditorComponent;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LiveTemplateFinishTest {
    @TempDir Path settings;
    private static final String SOURCE = "class C { void m(){ snippet } }";
    private record Fixture(EditorComponent editor, LiveTemplateController controller,
            CompletableFuture<LiveTemplateTransform.Result> transformed, CountDownLatch preparing) {}
    private Fixture fixture() throws Exception {
        return edt(() -> {
            var editor = new EditorComponent(); editor.setText(SOURCE);
            var controller = new LiveTemplateController(editor, settings, () -> "C.java", ignored -> {});
            var transformed = new CompletableFuture<LiveTemplateTransform.Result>(); var preparing = new CountDownLatch(1);
            controller.configureSemantics(null, (template, start, end, caret, anchor) -> { preparing.countDown(); return transformed; });
            int start = SOURCE.indexOf("snippet"), end = start + 7; editor.setSelection(end, end);
            var template = new LiveTemplateCatalog.Template("user/finish", "snippet", "int $NAME$=1;$END$",
                    List.of("JAVA_STATEMENT"), List.of(new LiveTemplateCatalog.Variable("NAME", "", "n", true)), false,
                    "Finish test", "", true, true);
            var apply = LiveTemplateController.class.getDeclaredMethod("apply", LiveTemplateCatalog.Template.class,
                    JavaTemplateContext.class, String.class, int.class, int.class, String.class);
            apply.setAccessible(true);
            apply.invoke(controller, template, new JavaTemplateContext(Set.of("JAVA_STATEMENT"), "C", "m", Map.of(), List.of(), "C.java"), SOURCE, start, end, "");
            return new Fixture(editor, controller, transformed, preparing);
        });
    }
    @Test void saveWaitsForFormattingAndEntireExpansionHasOneUndo() throws Exception {
        var f = fixture(); var saved = new AtomicReference<String>();
        try {
            edt(() -> { f.editor.replaceSelection("count"); assertTrue(f.controller.confirmThen(() -> saved.set(f.editor.getText()))); return null; });
            assertTrue(f.preparing.await(5, TimeUnit.SECONDS)); assertNull(saved.get());
            var result = edt(() -> {
                int equal = f.editor.getText().indexOf("=1");
                return new LiveTemplateTransform.Result(List.of(new EditorComponent.TextEdit(equal, equal + 1, " = ")),
                        f.editor.caretOffset() + 2, f.editor.caretOffset() + 2);
            });
            f.transformed.complete(result); edt(() -> null);
            assertNotNull(saved.get()); assertTrue(saved.get().contains("int count = 1;"));
            edt(() -> { f.editor.undo(); assertEquals(SOURCE, f.editor.getText()); f.editor.redo(); assertEquals(saved.get(), f.editor.getText()); return null; });
        } finally { edt(() -> { f.controller.close(); return null; }); }
    }
    @Test void lateFormattingCannotOverwriteTypingOrRunTheDeferredSave() throws Exception {
        var f = fixture(); var saved = new AtomicBoolean();
        try {
            edt(() -> { f.controller.confirmThen(() -> saved.set(true)); return null; });
            assertTrue(f.preparing.await(5, TimeUnit.SECONDS));
            String changed = edt(() -> { f.editor.setSelection(0, 0); f.editor.replaceSelection("// later edit\n"); return f.editor.getText(); });
            edt(() -> {
                assertFalse(f.controller.active(), "Obsolete formatting cannot block the next template or Save");
                assertFalse(f.controller.confirmThen(() -> saved.set(true)), "New commands must not queue behind an obsolete snapshot");
                return null;
            });
            f.transformed.complete(new LiveTemplateTransform.Result(List.of(new EditorComponent.TextEdit(0, 0, "WRONG")), 0, 0));
            edt(() -> { assertEquals(changed, f.editor.getText()); assertFalse(saved.get()); return null; });
        } finally { edt(() -> { f.controller.close(); return null; }); }
    }
    @Test void escapeRollsBackFieldsWithoutStartingPostprocessing() throws Exception {
        var f = fixture();
        try {
            edt(() -> { f.editor.replaceSelection("count"); assertTrue(f.controller.cancel()); assertEquals(SOURCE, f.editor.getText()); return null; });
            assertEquals(1, f.preparing.getCount());
        } finally { edt(() -> { f.controller.close(); return null; }); }
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        var task = new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
