package dev.javafmt.intellij;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.formatting.service.FormattingService;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Included only in the isolated host-test plugin, never in the release ZIP. */
public final class HostSmokeStarter implements ApplicationStarter {
    private static final String SOURCE = "class Hello{int square(int x){return x*x;}}";
    private Project project;
    private PsiFile file;
    private Document document;
    private Editor editor;
    private JavafmtFormattingService service;
    private int passed;

    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }

    @Override
    public void main(List<String> args) {
        Path result = Path.of(System.getProperty("javafmt.test.result"));
        try {
            test();
            Files.writeString(result, "PASS " + passed + " real IDEA host assertions; IDEA "
                    + ApplicationInfo.getInstance().getFullVersion() + "; runtime " + Runtime.version() + "\n");
            project.getService(FormatterBackend.class).dispose();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.writeString(result, "FAIL " + failure + "\n"); } catch (Exception ignored) { }
            System.exit(1);
        }
    }

    private void test() throws Exception {
        Path directory = Path.of(System.getProperty("javafmt.test.project"));
        Files.createDirectories(directory);
        project = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().openProject(
                directory, com.intellij.ide.impl.OpenProjectTask.build().asNewProject());
        onEdt(() -> {
            if (project == null) throw new AssertionError("Project creation failed");
            var settings = JavafmtSettings.get(project).getState();
            check(!settings.enabled, "disabled by default");
            settings.enabled = true;
            settings.javaExecutable = System.getProperty("javafmt.test.java");
            var virtualFile = new com.intellij.testFramework.LightVirtualFile("Hello.java", JavaFileType.INSTANCE, SOURCE);
            document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile);
            file = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile);
            editor = EditorFactory.getInstance().createEditor(document, project);
            UndoManager.getInstance(project);
            service = FormattingService.EP_NAME.getExtensionList().stream()
                    .filter(JavafmtFormattingService.class::isInstance).map(JavafmtFormattingService.class::cast).findFirst().orElseThrow();
            check(service.canFormat(file), "registered formatting service supports Java PSI");
        });
        Request full = request(0, SOURCE.length());
        var task = task(full);
        task.run();
        full.done.get(30, TimeUnit.SECONDS);
        long pid = project.getService(FormatterBackend.class).processId();
        onEdt(() -> {
            check(document.getText().contains("return x * x;"), "document formatted from unsaved PSI buffer");
            check(document.getText().startsWith("class Hello {\n    int square"), "canonical indentation");
            var undo = UndoManager.getInstance(project);
            var fileEditor = TextEditorProvider.getInstance().getTextEditor(editor);
            check(undo.isUndoAvailable(fileEditor), "format has one undo command");
            undo.undo(fileEditor);
            check(document.getText().equals(SOURCE), "one undo restores exact source");
            undo.redo(fileEditor);
            check(document.getText().contains("return x * x;"), "redo restores formatted buffer");
        });

        String two = "class Pair{int a(){return 1+2;}int b(){return 3+4;}}";
        replace(two);
        int selected = two.indexOf("return 3");
        Request selection = request(selected, selected + 11);
        task(selection).run();
        selection.done.get(30, TimeUnit.SECONDS);
        onEdt(() -> {
            check(document.getText().contains("int a(){return 1+2;}"), "selection preserves unrelated method");
            check(document.getText().contains("return 3 + 4;"), "selection formats enclosing method");
            check(project.getService(FormatterBackend.class).processId() == pid, "resident JVM reused");
        });

        replace(SOURCE);
        Request stale = request(0, SOURCE.length());
        var staleTask = task(stale);
        replace(SOURCE + " // newer");
        staleTask.run();
        stale.done.get(30, TimeUnit.SECONDS);
        onEdt(() -> check(document.getText().equals(SOURCE + " // newer"), "stale result rejected"));

        replace(SOURCE);
        Request cancelled = request(0, SOURCE.length());
        var cancelledTask = task(cancelled);
        check(cancelledTask.cancel(), "cancellation accepted");
        cancelledTask.run();
        onEdt(() -> check(document.getText().equals(SOURCE), "cancelled task does not modify source"));

        replace("class Broken {");
        Request broken = request(0, document.getTextLength());
        task(broken).run();
        try { broken.done.get(30, TimeUnit.SECONDS); throw new AssertionError("Expected syntax error"); }
        catch (java.util.concurrent.ExecutionException expected) { passed++; }
        onEdt(() -> check(document.getText().equals("class Broken {"), "syntax failure preserves source"));

        replace(SOURCE);
        Request standard = request(0, SOURCE.length());
        service.formatDocument(document, standard.ranges, standard.context, true, false);
        onEdt(() -> check(document.getText().contains("return x * x;"), "standard AsyncDocumentFormattingService entry point"));

        var backend = project.getService(FormatterBackend.class);
        ProcessHandle process = ProcessHandle.of(backend.processId()).orElseThrow();
        process.destroy();
        process.onExit().get(10, TimeUnit.SECONDS);
        replace(SOURCE);
        Request restarted = request(0, SOURCE.length());
        task(restarted).run();
        restarted.done.get(30, TimeUnit.SECONDS);
        onEdt(() -> {
            check(backend.processId() != pid, "engine restarts after process failure");
            check(document.getText().contains("return x * x;"), "formatting recovers after engine failure");
        });
        onEdt(() -> EditorFactory.getInstance().releaseEditor(editor));
    }

    private void replace(String source) {
        onEdt(() -> WriteCommandAction.runWriteCommandAction(project, () -> {
            document.setText(source);
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }));
    }

    private JavafmtFormattingService.FormattingTask task(Request request) {
        AtomicReference<JavafmtFormattingService.FormattingTask> task = new AtomicReference<>();
        onEdt(() -> task.set(service.createFormattingTask(request)));
        return task.get();
    }

    private Request request(int start, int end) {
        AtomicReference<Request> request = new AtomicReference<>();
        onEdt(() -> request.set(new Request(document.getText(), start, end)));
        return request.get();
    }

    private final class Request implements AsyncFormattingRequest {
        final String source;
        final List<TextRange> ranges;
        final FormattingContext context;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Request(String source, int start, int end) {
            this.source = source;
            ranges = List.of(new TextRange(start, end));
            context = FormattingContext.create(file, new CodeStyleSettings());
        }
        @Override public String getDocumentText() { return source; }
        @Override public File getIOFile() { throw new AssertionError("Must not require a saved file"); }
        @Override public List<TextRange> getFormattingRanges() { return ranges; }
        @Override public boolean canChangeWhitespaceOnly() { return true; }
        @Override public boolean isQuickFormat() { return false; }
        @Override public FormattingContext getContext() { return context; }
        @Override public void onTextReady(String text) { done.complete(null); }
        @Override public void onError(String title, String message) { done.completeExceptionally(new IllegalStateException(message)); }
        @Override public void onError(String title, String message, int offset) { onError(title, message); }
    }

    private static void onEdt(Runnable action) { ApplicationManager.getApplication().invokeAndWait(action); }
    private void check(boolean valid, String name) {
        if (!valid) throw new AssertionError(name);
        passed++;
        System.out.println("javafmt host PASS: " + name);
    }
}
