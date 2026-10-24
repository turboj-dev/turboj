package dev.turboj.refactorj.intellij;

import com.google.gson.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.turboj.refactorj.intellij.RefactorJBackend.object;

/** Explicit RefactorJ action; never replaces IDEA's built-in refactoring actions. */
public final class RefactorJAction extends AnAction {
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && event
                .getData(CommonDataKeys.EDITOR) != null && file != null && "java".equals(file
                .getExtension()));
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || editor == null || file == null)
            return;
        String path = file.getPath();
        int start = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel()
                .getSelectionStart() : editor.getCaretModel().getOffset();
        int end = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel()
                .getSelectionEnd() : start;
        List<RefactorJBackend.Source> sources = sources(project, editor, file);
        RefactorJBackend.Source current = sources.stream().filter(s -> s.file().equals(path))
                .findFirst().orElseThrow();
        background(project, "RefactorJ: available refactorings", (backend, cancel) -> {
            JsonArray available = backend.available(current, start, end, sources, cancel);
            JsonArray catalog = backend.execute("refactorj.catalog", null, sources, cancel)
                    .getAsJsonArray();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed())
                    return;
                String[] names = available.asList().stream().map(e -> e.getAsJsonObject()
                        .get("title").getAsString()).toArray(String[]::new);

                if (names.length == 0) {
                    Messages.showInfoMessage(project,
                            "No semantic refactoring applies at this selection.", "RefactorJ");
                    return;
                }
                String chosen = Messages.showEditableChooseDialog("Choose a semantic operation",
                        "RefactorJ", null, names, names[0], null);

                if (chosen == null)
                    return;
                JsonObject action = available.asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(a -> chosen.equals(a.get("title").getAsString())).findFirst()
                        .orElse(null);

                if (action == null)
                    return;
                JsonObject request = action.getAsJsonObject("data").getAsJsonObject("request")
                        .deepCopy();
                String operation = request.get("operation").getAsString();
                JsonObject descriptor = catalog.asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(c -> operation.equals(c.get("operation").getAsString())).findFirst()
                        .orElseThrow();

                for (JsonElement field : descriptor.getAsJsonArray("fields")) {
                    JsonObject spec = field.getAsJsonObject();
                    String name = spec.get("name").getAsString(), type = spec.get("type")
                            .getAsString();

                    if (type.equals("boolean")) {
                        request.addProperty(name, operation.equals("rename") && name.equals("hierarchy"));
                        continue;
                    }
                    if (spec.has("values"))
                        request.add(name, spec.getAsJsonArray("values").get(0));
                    else if (type.equals("array"))
                        request.add(name, new JsonArray());
                    else
                        request.addProperty(name, "");
                }
                String json = Messages.showMultilineInputDialog(project,
                        "Typed refactoring request. Edit the fields for this operation; offsets are UTF-16.",
                        "RefactorJ — " + chosen, RefactorJBackend.JSON.toJson(request), null, null);

                if (json == null)
                    return;
                try {
                    JsonObject configured = RefactorJBackend.JSON.fromJson(json, JsonObject.class);
                    configured.addProperty("operation", operation);
                    configured.add("location", request.get("location"));
                    prepare(project, editor, file, configured);
                } catch (RuntimeException invalid) {
                    Messages.showErrorDialog(project, "Invalid JSON request: " + invalid
                            .getMessage(), "RefactorJ");
                }
            });
        });
    }

    private static void prepare(Project project, Editor editor, VirtualFile file,
            JsonObject request) {
        List<RefactorJBackend.Source> sources = sources(project, editor, file);
        background(project, "RefactorJ: preparing preview", (backend, cancel) -> {
            JsonObject prepared = backend.execute("refactorj.prepare", request, sources, cancel)
                    .getAsJsonObject();
            JsonObject plan = prepared.getAsJsonObject("plan");

            if (!"VALID".equals(plan.get("status").getAsString()))
                throw new IllegalStateException(plan.get("diagnostics").toString());
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || !new RefactorJPreview(project, prepared).showAndGet())
                    return;
                List<RefactorJBackend.Source> current = sources(project, editor, file);
                background(project, "RefactorJ: validating changes", (engine, cancelled) -> {
                    engine.execute("refactorj.workspaceEdit", prepared, current, cancelled);
                    var changes = new RefactorJEdits(project, prepared);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed() || cancelled.get())
                            return;
                        try {
                            changes.apply();
                        } catch (RuntimeException failure) {
                            Messages.showErrorDialog(project, failure.getMessage(), "RefactorJ");
                        }
                    });
                });
            });
        });
    }

    public static List<RefactorJBackend.Source> sources(Project project, Editor editor,
            VirtualFile file) {
        return ReadAction.compute(() -> {
            Map<String, RefactorJBackend.Source> sources = new LinkedHashMap<> ();
            Path root = Path.of(project.getBasePath()).toAbsolutePath().normalize();
            var manager = FileDocumentManager.getInstance();

            for (var document : manager.getUnsavedDocuments()) {
                var virtual = manager.getFile(document);

                if (virtual != null && "java".equals(virtual.getExtension()) && Path.of(virtual
                        .getPath()).toAbsolutePath().normalize().startsWith(root))
                    sources.put(virtual.getPath(), new RefactorJBackend.Source(virtual.getPath(),
                            document.getText(), document.getModificationStamp()));
            }
            sources.put(file.getPath(), new RefactorJBackend.Source(file.getPath(), editor
                    .getDocument().getText(), editor.getDocument().getModificationStamp()));
            return List.copyOf(sources.values());
        });
    }

    private interface Work {
        void run(RefactorJBackend backend, AtomicBoolean cancelled) throws Exception;
    }

    private static void background(Project project, String title, Work work) {
        new Task.Backgroundable(project, title, true) {
            @Override
            public void run(ProgressIndicator indicator) {
                AtomicBoolean cancelled = new AtomicBoolean();
                Thread watcher = Thread.ofVirtual().start(() -> {
                    while (!cancelled.get()) {
                        if (indicator.isCanceled()) {
                            cancelled.set(true);
                            break;
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException end) {
                            break;
                        }
                    }
                });

                try {
                    work.run(project.getService(RefactorJBackend.class), cancelled);
                } catch (Exception failure) {
                    if (!cancelled.get())
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!project.isDisposed())
                                Messages.showErrorDialog(project, failure.getMessage()
                                        == null ? failure.toString() : failure.getMessage(),
                                        "RefactorJ");
                        });
                } finally {
                    watcher.interrupt();
                }
            }
        }.queue();
    }
}
