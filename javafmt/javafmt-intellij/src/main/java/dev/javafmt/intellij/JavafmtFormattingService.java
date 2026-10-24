package dev.javafmt.intellij;

import com.google.gson.JsonObject;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;

/** Uses IDEA's formatting entry point, preserving unsaved buffers and applying minimal edits. */
public final class JavafmtFormattingService extends AsyncDocumentFormattingService {
    record Edit(int start, int end, String replacement) { }

    @Override
    public @NotNull Set<Feature> getFeatures() { return Set.of(Feature.FORMAT_FRAGMENTS); }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return "JAVA".equals(file.getLanguage().getID()) && JavafmtSettings.get(file.getProject()).getState().enabled;
    }

    @Override protected @NotNull String getName() { return "javafmt"; }
    @Override protected @NotNull String getNotificationGroupId() { return "javafmt"; }

    /** Engine consumes the in-memory snapshot; never save as a formatting side effect. */
    @Override protected void prepareForFormatting(@NotNull Document document, @NotNull FormattingContext context) { }

    /** The task applies the engine's minimal edits; IDEA must not replace the complete buffer. */
    @Override protected boolean needToUpdate() { return false; }

    @Override
    protected FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        Project project = request.getContext().getProject();
        Document document = PsiDocumentManager.getInstance(project).getDocument(request.getContext().getContainingFile());
        if (document == null) {
            request.onError("javafmt", "This file has no editable document.");
            return null;
        }
        return new Task(project, document, request);
    }

    final class Task implements FormattingTask {
        private final Project project;
        private final Document document;
        private final AsyncFormattingRequest request;
        private final String source;
        private final long version;
        private final String id = UUID.randomUUID().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final FormatterBackend backend;

        Task(Project project, Document document, AsyncFormattingRequest request) {
            this.project = project;
            this.document = document;
            this.request = request;
            source = request.getDocumentText();
            version = document.getModificationStamp();
            backend = project.getService(FormatterBackend.class);
        }

        @Override
        public void run() {
            try {
                if (cancelled.get() || project.isDisposed()) return;
                var ranges = request.getFormattingRanges();
                Integer start = null;
                Integer end = null;
                if (!ranges.isEmpty()) {
                    var ordered = ranges.stream().sorted(java.util.Comparator.comparingInt(r -> r.getStartOffset())).toList();
                    int from = ordered.getFirst().getStartOffset();
                    int to = ordered.getFirst().getEndOffset();
                    for (int i = 1; i < ordered.size(); i++) {
                        if (ordered.get(i).getStartOffset() > to) {
                            throw new IOException("javafmt supports one contiguous selection. Select a single range or format the whole file.");
                        }
                        to = Math.max(to, ordered.get(i).getEndOffset());
                    }
                    if (from != 0 || to != source.length()) { start = from; end = to; }
                }
                JsonObject result = backend.format(id, version, source, start, end, cancelled);
                if (cancelled.get()) return;
                List<Edit> edits = validate(result, source);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (cancelled.get() || project.isDisposed()) return;
                    applyEdits(project, document, source, version, edits);
                    request.onTextReady(null);
                });
            } catch (Exception failure) {
                if (!cancelled.get() && !project.isDisposed()) {
                    request.onError("javafmt", failure.getMessage() == null
                            ? "Formatting failed; source was not changed." : failure.getMessage());
                }
            }
        }

        @Override
        public boolean cancel() {
            cancelled.set(true);
            backend.cancel(id);
            return true;
        }
    }

    static boolean applyEdits(Project project, Document document, String source, long version, List<Edit> edits) {
        AtomicBoolean applied = new AtomicBoolean();
        WriteCommandAction.writeCommandAction(project).withName("Format with javafmt").run(() -> {
            if (!document.isWritable() || document.getModificationStamp() != version || !document.getText().equals(source)) return;
            for (int i = edits.size() - 1; i >= 0; i--) {
                Edit edit = edits.get(i);
                document.replaceString(edit.start(), edit.end(), edit.replacement());
            }
            applied.set(true);
        });
        return applied.get();
    }

    static List<Edit> validate(JsonObject response, String source) throws IOException {
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            String message = response.has("message") ? response.get("message").getAsString() : "Java source could not be safely formatted.";
            if (response.has("diagnostics") && !response.getAsJsonArray("diagnostics").isEmpty()) {
                message = response.getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("message").getAsString();
            }
            throw new IOException(message);
        }
        List<Edit> edits = new ArrayList<>();
        int previousEnd = 0;
        for (var element : response.getAsJsonArray("edits")) {
            JsonObject value = element.getAsJsonObject();
            Edit edit = new Edit(value.get("start").getAsInt(), value.get("end").getAsInt(), value.get("replacement").getAsString());
            if (edit.start() < previousEnd || edit.end() < edit.start() || edit.end() > source.length()
                    || !whitespace(source.substring(edit.start(), edit.end())) || !whitespace(edit.replacement())) {
                throw new IOException("Unsafe javafmt text edits; source was not changed.");
            }
            edits.add(edit);
            previousEnd = edit.end();
        }
        StringBuilder output = new StringBuilder(source);
        for (int i = edits.size() - 1; i >= 0; i--) {
            var edit = edits.get(i);
            output.replace(edit.start(), edit.end(), edit.replacement());
        }
        if (!output.toString().equals(response.get("formattedText").getAsString())) {
            throw new IOException("Inconsistent javafmt text edits; source was not changed.");
        }
        return List.copyOf(edits);
    }

    private static boolean whitespace(String text) {
        return text.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f');
    }
}
