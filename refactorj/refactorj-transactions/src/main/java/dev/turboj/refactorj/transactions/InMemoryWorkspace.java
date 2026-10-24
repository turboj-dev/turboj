package dev.turboj.refactorj.transactions;

import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.Path;
import java.util.*;

/** Reference buffer adapter. Unsaved content remains in memory; no filesystem access occurs. */
public final class InMemoryWorkspace implements WorkspaceStore {
    private WorkspaceSnapshot current;
    private final LinkedHashMap<String, History> history = new LinkedHashMap<> ();

    public InMemoryWorkspace(WorkspaceSnapshot snapshot) {
        current = Objects.requireNonNull(snapshot);
    }

    @Override
    public synchronized WorkspaceSnapshot snapshot() {
        return current;
    }

    @Override
    public synchronized WorkspaceSnapshot commit(WorkspaceSnapshot expected, ChangeSet changes,
            Metadata metadata, Cancellation cancellation) {
        cancellation.check();
        ChangeSet.expect(current, expected);
        WorkspaceSnapshot next = ChangeSet.apply(current, changes);
        cancellation.check();
        current = next;
        remember(history, changes, metadata);
        return next;
    }

    /** Integrates an ordinary editor change and invalidates previews without touching undo history. */
    public synchronized WorkspaceSnapshot update(Path file, String source, boolean open) {
        Path path = file.toAbsolutePath().normalize();
        ChangeSet.safe(current.root(), path);
        var entries = ChangeSet.map(current);
        var before = entries.get(path);
        return current = ChangeSet.apply(current, new ChangeSet(List
                .of(new ChangeSet.Transition(path, before, new SourceSnapshot(path, source, before
                == null ? 0 : before.version(), open)))));
    }

    /** Removes a closed/deleted editor source without creating a refactoring undo entry. */
    public synchronized WorkspaceSnapshot remove(Path file) {
        Path path = file.toAbsolutePath().normalize();
        ChangeSet.safe(current.root(), path);
        var before = ChangeSet.map(current).get(path);

        if (before == null)
            return current;
        return current = ChangeSet.apply(current, new ChangeSet(List
                .of(new ChangeSet.Transition(path, before, null))));
    }

    @Override
    public synchronized List<History> history() {
        return List.copyOf(history.values());
    }

    static void remember(LinkedHashMap<String, History> history, ChangeSet changes,
            Metadata metadata) {
        if (metadata.direction() == Direction.APPLY) {
            history.entrySet().removeIf(e -> !e.getValue().applied());
            history.put(metadata.historyId(), new History(metadata.historyId(), changes, true));
        } else {
            var original = history.get(metadata.historyId());

            if (original != null)
                history.put(original.id(), new History(original.id(), original.changes(), metadata
                        .direction() == Direction.REDO));
        }
    }
}
