package dev.turboj.refactorj.transactions;

import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.Path;
import java.util.*;

/** Versioned logical transactions, with complete ordered workspace undo/redo. */
public final class WorkspaceTransactions {
    public record Result(String transactionId, WorkspaceSnapshot snapshot, ChangeSet changes) {}

    private final WorkspaceStore store;
    private final LinkedHashMap<String, WorkspaceStore.History> history = new LinkedHashMap<> ();

    public WorkspaceTransactions(WorkspaceStore store) {
        this.store = Objects.requireNonNull(store);
        store.history().forEach(h -> history.put(h.id(), h));
    }

    public ChangeSet preview(Plan plan) {
        return ChangeSet.prepare(plan);
    }

    public synchronized Result apply(Plan plan, Cancellation cancellation) {
        cancellation.check();
        WorkspaceSnapshot current = store.snapshot();
        ChangeSet.expect(current, plan.before());
        ChangeSet changes = preview(plan);
        String id = UUID.randomUUID().toString();

        if (changes.transitions().isEmpty())
            return new Result(id, current, changes);
        var metadata = new WorkspaceStore.Metadata(id, id, WorkspaceStore.Direction.APPLY);
        WorkspaceSnapshot result = store.commit(current, changes, metadata, cancellation);
        InMemoryWorkspace.remember(history, changes, metadata);
        return new Result(id, result, changes);
    }

    public synchronized Result undo(String transactionId, Cancellation cancellation) {
        return history(transactionId, false, cancellation);
    }

    public synchronized Result redo(String transactionId, Cancellation cancellation) {
        return history(transactionId, true, cancellation);
    }

    public synchronized List<WorkspaceStore.History> history() {
        return List.copyOf(history.values());
    }

    private Result history(String id, boolean forward, Cancellation cancellation) {
        cancellation.check();
        var entry = history.get(id);

        if (entry == null || entry.applied() == forward)
            throw new TransactionException("HISTORY_STATE",
                    "Transaction is not available for this operation");
        // Ordered history prevents overlapping transactions being undone in the wrong order.
        var entries = new ArrayList<> (history.values());
        int index = entries.indexOf(entry);

        if (!forward && entries.subList(index + 1, entries.size()).stream()
                .anyMatch(WorkspaceStore.History::applied) || forward && entries.subList(0, index)
                .stream().anyMatch(h -> !h.applied()))
            throw new TransactionException("HISTORY_ORDER",
                    "Undo/redo must follow workspace transaction order");
        WorkspaceSnapshot current = store.snapshot();
        var sources = ChangeSet.map(current);
        List<ChangeSet.Transition> transitions = new ArrayList<> ();

        for (var original : entry.changes().transitions()) {
            var expected = forward ? original.before() : original.after();
            var desired = forward ? original.after() : original.before();
            var actual = sources.get(original.path());

            if (!ChangeSet.sameContent(expected, actual))
                throw new TransactionException("HISTORY_CONFLICT",
                        "Affected file changed after refactoring; no history changes applied");
            transitions.add(new ChangeSet.Transition(original.path(), actual, desired));
        }
        var changes = new ChangeSet(transitions);
        var metadata = new WorkspaceStore.Metadata(UUID.randomUUID().toString(), id,
                forward ? WorkspaceStore.Direction.REDO : WorkspaceStore.Direction.UNDO);
        WorkspaceSnapshot result = store.commit(current, changes, metadata, cancellation);
        InMemoryWorkspace.remember(history, changes, metadata);
        return new Result(id, result, changes);
    }
}
