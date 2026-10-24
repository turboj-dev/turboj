package dev.turboj.refactorj.intellij;

import com.google.gson.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Preflight off EDT; publish minimal changes as one IDE command, using native document/VFS undo. */
public final class RefactorJEdits {
    private record Buffer(Document document, String before, long stamp, JsonArray edits) {}

    private final Project project;
    private final Path root;
    private final JsonObject plan;
    private final List<Buffer> buffers = new ArrayList<> ();
    private final Map<Path, String> originals = new HashMap<> ();
    private final Map<Document, Long> observed = new HashMap<> ();

    public RefactorJEdits(Project project, JsonObject prepared) throws Exception {
        this.project = project;
        plan = prepared.getAsJsonObject("plan");
        root = Path.of(plan.getAsJsonObject("before").get("root").getAsString()).toRealPath();
        if (!root.equals(Path.of(project.getBasePath()).toRealPath()))
            throw new IOException("Plan root differs from project");
        if (!"VALID".equals(plan.get("status").getAsString()))
            throw new IOException("Plan has conflicts");
        var documents = FileDocumentManager.getInstance();

        for (JsonElement element : plan.getAsJsonObject("before").getAsJsonArray("sources")) {
            JsonObject source = element.getAsJsonObject();
            Path path = safe(source.get("file").getAsString());
            String expected = source.get("text").getAsString();
            originals.put(path, expected);
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            Document document = file == null ? null : documents.getCachedDocument(file);

            if (document != null) {
                ReadAction.run(() -> {
                    if (!document.getText().equals(expected))
                        throw new IllegalStateException("Source changed after preview: " + path);
                    observed.put(document, document.getModificationStamp());
                });
            } else if (!Files.readString(path).equals(expected))
                throw new IOException("Source changed on disk after preview: " + path);
        }
        for (JsonElement element : plan.getAsJsonArray("edits")) {
            JsonObject change = element.getAsJsonObject();
            Path path = safe(change.get("file").getAsString());
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            Document document = ReadAction.compute(() -> file == null ? null : documents
                    .getDocument(file));

            if (document == null)
                throw new IOException("Cannot open source document: " + path);
            if (!document.isWritable())
                throw new IOException("Source document is read-only: " + path);
            ReadAction.run(() -> {
                String before = document.getText();

                if (!before.equals(originals.get(path)))
                    throw new IllegalStateException("Source changed after preview");
                buffers.add(new Buffer(document, before, document.getModificationStamp(), change
                        .getAsJsonArray("edits")));
            });
        }
        for (JsonElement element : plan.getAsJsonArray("fileOperations")) {
            JsonObject op = element.getAsJsonObject();
            Path path = safe(op.get("path").getAsString());
            String kind = op.get("kind").getAsString();
            Path trackedPath = Files.exists(path) ? path : path.getParent();
            while (trackedPath != null && !Files.exists(trackedPath)) trackedPath = trackedPath.getParent();
            VirtualFile tracked = trackedPath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(trackedPath);
            if (tracked == null || !ReadAction.compute(() -> com.intellij.history.LocalHistory.getInstance().isUnderControl(tracked)))
                throw new IOException("File operations require active IntelliJ Local History for native undo. Open the source in a tracked local project.");

            if (kind.equals("CREATE") && Files.exists(path))
                throw new IOException("Created file already exists");
            if (kind.equals("MOVE") && Files.exists(safe(op.get("destination").getAsString())))
                throw new IOException("Destination already exists; cyclic moves require the native RefactorJ transaction adapter");
        }
    }

    public void apply() {
        WriteCommandAction.runWriteCommandAction(project, "RefactorJ: " + plan.get("description")
                .getAsString(), null, () -> {
            for (var entry : observed.entrySet())
                if (entry.getKey().getModificationStamp() != entry.getValue())
                    throw new IllegalStateException("Source changed after preview");
            for (Buffer buffer : buffers)
                if (buffer.document().getModificationStamp() != buffer.stamp())
                    throw new IllegalStateException("Source changed after preview");
            CommandProcessor.getInstance().markCurrentCommandAsGlobal(project);
            List<Rollback> reverse = new ArrayList<> ();

            try {
                for (Buffer buffer : buffers) {
                    reverse.add(() -> buffer.document().setText(buffer.before()));
                    for (int i = buffer.edits().size() - 1; i >= 0; i--) {
                        JsonObject edit = buffer.edits().get(i).getAsJsonObject();
                        buffer.document().replaceString(edit.get("start").getAsInt(), edit
                                .get("end").getAsInt(), edit.get("text").getAsString());
                    }
                }
                for (JsonElement element : plan.getAsJsonArray("fileOperations")) {
                    JsonObject op = element.getAsJsonObject();
                    Path path = safe(op.get("path").getAsString());
                    VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);

                    switch (op.get("kind").getAsString()) {
                        case "CREATE" -> {
                            VirtualFile parent = directory(path.getParent(), reverse);

                            if (parent.findChild(path.getFileName().toString()) != null)
                                throw new IOException("Create collision");
                            VirtualFile created = parent.createChildData(this, path.getFileName()
                                    .toString());
                            reverse.add(() -> created.delete(this));
                            VfsUtil.saveText(created, op.get("content").getAsString());
                        }
                        case "DELETE" -> {
                            if (file == null)
                                throw new IOException("Deleted source disappeared");
                            VirtualFile parent = file.getParent();
                            String name = file.getName();
                            byte[] bytes = file.contentsToByteArray();
                            file.delete(this);
                            reverse.add(() -> parent.createChildData(this, name)
                                    .setBinaryContent(bytes));
                        }
                        case "MOVE" -> {
                            if (file == null)
                                throw new IOException("Moved source disappeared");
                            Path destination = safe(op.get("destination").getAsString());
                            VirtualFile oldParent = file.getParent();
                            String oldName = file.getName();
                            VirtualFile targetParent = directory(destination.getParent(), reverse);

                            if (targetParent.findChild(destination.getFileName().toString())
                                    != null)
                                throw new IOException("Move collision");
                            reverse.add(() -> {
                                if (file.getParent() != oldParent)
                                    file.move(this, oldParent);
                                if (!file.getName().equals(oldName))
                                    file.rename(this, oldName);
                            });
                            if (file.getParent() != targetParent)
                                file.move(this, targetParent);
                            if (!file.getName().equals(destination.getFileName().toString()))
                                file.rename(this, destination.getFileName().toString());
                        }
                        default -> throw new IOException("Unsupported file operation");
                    }
                }
            } catch (Exception failure) {
                for (int i = reverse.size() - 1; i >= 0; i--)
                    try {
                        reverse.get(i).run();
                    } catch (Exception rollback) {
                        failure.addSuppressed(rollback);
                    }
                throw new IllegalStateException("Refactoring was not completed", failure);
            }
        });
    }

    private VirtualFile directory(Path path, List<Rollback> reverse) throws IOException {
        VirtualFile exists = LocalFileSystem.getInstance().findFileByNioFile(path);

        if (exists != null) {
            if (!exists.isDirectory())
                throw new IOException("Parent is not a directory");
            return exists;
        }
        if (path.equals(root) || !path.startsWith(root))
            throw new IOException("Missing project directory");
        VirtualFile created = directory(path.getParent(), reverse).createChildDirectory(this, path
                .getFileName().toString());
        reverse.add(() -> created.delete(this));
        return created;
    }

    private Path safe(String value) throws IOException {
        Path path = Path.of(value).toAbsolutePath().normalize();

        if (!path.startsWith(root) || path.equals(root) || path.startsWith(root
                .resolve(".refactorj")))
            throw new IOException("Path outside source root");
        for (Path p = path; p != null && !p.equals(root); p = p.getParent())
            if (Files.isSymbolicLink(p))
                throw new IOException("Symbolic source path");
        return path;
    }

    private interface Rollback {
        void run() throws Exception;
    }
}
