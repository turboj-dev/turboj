package dev.turboj.refactorj.transactions;

import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import java.util.List;

/** Host integration boundary. Commit must compare expected before mutation and publish one logical update. */
public interface WorkspaceStore {
    enum Direction {
        APPLY,
        UNDO,
        REDO
    }

    record Metadata(String id, String historyId, Direction direction) {}

    record History(String id, ChangeSet changes, boolean applied) {}

    WorkspaceSnapshot snapshot();

    WorkspaceSnapshot commit(WorkspaceSnapshot expected, ChangeSet changes, Metadata metadata,
            Cancellation cancellation);

    /** Optional persistent history; entries are in original application order. */
    default List<History> history() {
        return List.of();
    }
}
