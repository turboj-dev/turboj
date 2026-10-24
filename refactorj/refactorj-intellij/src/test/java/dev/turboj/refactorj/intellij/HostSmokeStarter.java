package dev.turboj.refactorj.intellij;

import com.google.gson.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.turboj.refactorj.intellij.RefactorJBackend.object;

/** Test-only starter executed inside a real isolated IDEA profile. */
public final class HostSmokeStarter implements ApplicationStarter {
    private Project project;
    private VirtualFile file;
    private Document document;
    private Editor editor;
    private int assertions;

    @Override
    public boolean isHeadless() {
        return false;
    }

    @Override
    public int getRequiredModality() {
        return NOT_IN_EDT;
    }

    @Override
    public void main(List<String> args) {
        Path result = Path.of(System.getProperty("refactorj.test.result"));

        try {
            test();
            Files.writeString(result, "PASS " + assertions + " real IDEA host assertions; IDEA "
                    + ApplicationInfo.getInstance().getFullVersion() + "; runtime " + Runtime
                    .version() + "\n");
            project.getService(RefactorJBackend.class).dispose();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                Files.writeString(result, "FAIL " + failure + "\n");
            } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    private void test() throws Exception {
        Path directory = Path.of(System.getProperty("refactorj.test.project"));
        Files.createDirectories(directory);
        Path sourceFile = directory.resolve("A.java");
        String disk = "class A {}\n",
                source = "class A { int run(){ int total = 1; return total; } }\n";
        Files.writeString(sourceFile, disk);
        project = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx()
                .openProject(directory, com.intellij.ide.impl.OpenProjectTask.build()
                .asNewProject());
        file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile);
        edt(() -> {
            check(project != null && file != null, "real project and virtual source");
            WriteCommandAction.runWriteCommandAction(project, () -> {
                var module = com.intellij.openapi.module.ModuleManager.getInstance(project).newModule(directory.resolve("test.iml").toString(), "JAVA_MODULE");
                var model = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).getModifiableModel();
                model.addContentEntry(directory.toUri().toString()); model.commit();
            });
            RefactorJSettings.get(project).getState().javaExecutable = System
                    .getProperty("refactorj.test.java");
            document = FileDocumentManager.getInstance().getDocument(file);
            editor = EditorFactory.getInstance().createEditor(document, project);
            UndoManager.getInstance(project);
            WriteCommandAction.runWriteCommandAction(project, () -> document.setText(source));
        });
        var backend = project.getService(RefactorJBackend.class);
        var cancel = new AtomicBoolean();
        JsonObject request = object("operation", "rename", "location", object("file", sourceFile
                .toString(), "start", source.indexOf("total"), "end", source.indexOf("total")),
                "name", "sum");
        JsonArray catalog = backend.execute("refactorj.catalog", null, sources(), cancel)
                .getAsJsonArray();
        check(catalog.size() >= 30, "complete typed request catalog available");
        check(backend.embedded() && backend.processId() == ProcessHandle.current().pid(), "Java 25 host embeds the engine in-process");
        JsonObject prepared = backend.execute("refactorj.prepare", request, sources(), cancel)
                .getAsJsonObject();
        check("VALID".equals(prepared.getAsJsonObject("plan").get("status").getAsString()),
                "real semantic rename prepared: " + prepared);
        check(Files.readString(sourceFile).equals(disk), "preview preserves disk source");
        edt(() -> check(document.getText().equals(source), "preview preserves unsaved buffer"));
        backend.execute("refactorj.workspaceEdit", prepared, sources(), cancel);
        var edits = new RefactorJEdits(project, prepared);
        long pid = backend.processId();
        edt(() -> {
            edits.apply();
            check(document.getText().equals(source.replace("total", "sum")),
                    "minimal edits applied to unsaved buffer");
            var manager = UndoManager.getInstance(project);
            var fileEditor = TextEditorProvider.getInstance().getTextEditor(editor);
            check(manager.isUndoAvailable(fileEditor), "native undo available");
            manager.undo(fileEditor);
            check(document.getText().equals(source), "one undo restores unsaved source");
            manager.redo(fileEditor);
            check(document.getText().contains("int sum"), "native redo");
            manager.undo(fileEditor);
        });
        JsonObject stale = backend.execute("refactorj.prepare", request, sources(), cancel)
                .getAsJsonObject();
        edt(() -> WriteCommandAction.runWriteCommandAction(project, () -> document
                .insertString(document.getTextLength(), "// newer\n")));
        try {
            new RefactorJEdits(project, stale);
            throw new AssertionError("Stale plan accepted");
        } catch (IllegalStateException expected) {
            assertions++;
        }
        check(backend.processId() == pid, "native engine reused");
        cancel.set(true);
        try {
            backend.execute("refactorj.prepare", request, sources(), cancel);
            throw new AssertionError("Cancellation ignored");
        } catch (java.util.concurrent.CancellationException expected) {
            assertions++;
        }
        edt(() -> {
            check(document.getText().equals(source + "// newer\n"),
                    "cancelled request preserves source");
        });
        cancel = new AtomicBoolean();
        JsonObject typeRequest = object("operation", "rename", "location", object("file", sourceFile.toString(), "start", 6, "end", 6), "name", "Renamed");
        JsonObject moved = backend.execute("refactorj.prepare", typeRequest, sources(), cancel).getAsJsonObject();
        check("VALID".equals(moved.getAsJsonObject("plan").get("status").getAsString()), "type rename with file operation prepared");
        backend.execute("refactorj.workspaceEdit", moved, sources(), cancel); var moveEdits = new RefactorJEdits(project, moved);
        edt(() -> {
            moveEdits.apply(); check(file.getName().equals("Renamed.java"), "native VFS file rename applied");
            var manager = UndoManager.getInstance(project); var fileEditor = TextEditorProvider.getInstance().getTextEditor(editor);
            manager.undo(fileEditor); check(file.getName().equals("A.java") && document.getText().equals(source + "// newer\n"), "one undo restores renamed file and unsaved content");
        });
        RefactorJSettings.get(project).getState().forceResident = true; backend.reset();
        JsonObject fallback = backend.execute("refactorj.prepare", request, sources(), cancel).getAsJsonObject();
        check("VALID".equals(fallback.getAsJsonObject("plan").get("status").getAsString()), "compatibility backend prepares the same semantic operation");
        check(!backend.embedded() && backend.processId() != ProcessHandle.current().pid(), "explicit compatibility mode uses one resident process");
        edt(() -> EditorFactory.getInstance().releaseEditor(editor));
        check(Files.readString(sourceFile).equals(disk), "no automatic save or execution");
    }

    private List<RefactorJBackend.Source> sources() {
        return RefactorJAction.sources(project, editor, file);
    }

    private static void edt(Runnable work) {
        ApplicationManager.getApplication().invokeAndWait(work);
    }

    private void check(boolean result, String label) {
        if (!result)
            throw new AssertionError(label);
        assertions++;
        System.out.println("RefactorJ host PASS: " + label);
    }
}
