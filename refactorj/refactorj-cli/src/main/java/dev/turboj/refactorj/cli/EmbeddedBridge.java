package dev.turboj.refactorj.cli;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java-only bridge for optional plugin classloaders. Only JDK types cross the loader boundary;
 * requests call the same semantic API directly, without protocol frames or source serialization.
 */
public final class EmbeddedBridge implements AutoCloseable {
    private final DiskWorkspace disk;
    private final InMemoryWorkspace buffers;
    private final WorkspaceSession session;
    private final Map<Path, Long> stamps = new HashMap<>();
    public EmbeddedBridge(String root, String sourceRoots, String classpath) throws Exception {
        Path real = Path.of(root).toRealPath();
        disk = DiskWorkspace.open(real, Main.paths(real, sourceRoots), Main.paths(real, classpath));
        buffers = new InMemoryWorkspace(disk.snapshot()); session = new WorkspaceSession(buffers);
    }
    public Object execute(String command, Object argument, List<Map<String, Object>> sources, AtomicBoolean cancelled) {
        Cancellation cancellation = cancelled::get; cancellation.check(); synchronize(sources);
        JsonElement json = Json.GSON.toJsonTree(argument);
        Object result = switch (command) {
            case "refactorj.catalog" -> Json.catalog();
            case "refactorj.prepare" -> session.prepare(json.getAsJsonObject(), cancellation);
            case "refactorj.validate", "refactorj.workspaceEdit" -> session.validatePrepared(Json.GSON.fromJson(json, WorkspaceSession.Prepared.class), cancellation);
            case "refactorj.available" -> {
                JsonObject location = json.getAsJsonObject(); Location selected = new Location(Json.safePath(session.snapshot().root(), Json.string(location, "file")), Json.integer(location, "start"), Json.integer(location, "end"));
                List<JsonObject> actions = new ArrayList<>();
                for (var descriptor : session.engine(cancellation).available(selected, cancellation)) if (descriptor.applicable()) actions.add(Json.object("title", descriptor.title(), "data", Json.object("request", Json.object("operation", descriptor.operation().id(), "location", selected))));
                yield actions;
            }
            default -> throw new IllegalArgumentException("Unknown embedded command: " + command);
        };
        cancellation.check(); return Json.GSON.fromJson(Json.GSON.toJsonTree(result), Object.class);
    }
    private void synchronize(List<Map<String, Object>> sources) {
        var snapshot = disk.snapshot(); Map<Path, SourceSnapshot> closed = new HashMap<>(); snapshot.sources().forEach(s -> closed.put(s.file(), s));
        Set<Path> next = new HashSet<>();
        for (var source : sources) next.add(Json.safePath(snapshot.root(), (String) source.get("file")));
        for (var source : new ArrayList<>(buffers.snapshot().sources())) {
            if (next.contains(source.file())) continue;
            var current = closed.get(source.file());
            if (current == null) buffers.remove(source.file());
            else if (source.open() || !current.text().equals(source.text())) buffers.update(current.file(), current.text(), false);
        }
        for (var source : snapshot.sources()) if (!next.contains(source.file()) && buffers.snapshot().sources().stream().noneMatch(s -> s.file().equals(source.file()))) buffers.update(source.file(), source.text(), false);
        for (var source : sources) {
            Path file = Json.safePath(snapshot.root(), (String) source.get("file")); String text = (String) source.get("text");
            var previous = buffers.snapshot().sources().stream().filter(s -> s.file().equals(file)).findFirst();
            long stamp = ((Number) source.get("stamp")).longValue();
            if (previous.isEmpty() || !previous.get().open() || !previous.get().text().equals(text) || !Objects.equals(stamps.get(file), stamp)) buffers.update(file, text, true);
            stamps.put(file, stamp);
        }
        stamps.keySet().retainAll(next);
    }
    @Override public void close() { session.close(); }
}
