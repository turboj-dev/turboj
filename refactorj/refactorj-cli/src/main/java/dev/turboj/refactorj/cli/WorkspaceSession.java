package dev.turboj.refactorj.cli;

import com.google.gson.JsonObject;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.runtime.RefactorJRuntime;
import dev.turboj.refactorj.transactions.*;
import java.nio.file.Path;
import java.util.*;

/** One resident semantic engine shared by every operation of an adapter. Use on one worker. */
public final class WorkspaceSession implements AutoCloseable {
    public record Prepared(int schemaVersion, String sessionId, JsonObject request, Plan plan) {}

    private final WorkspaceStore store;
    private final WorkspaceTransactions transactions;
    private final RefactorJRuntime runtime;
    private final RefactorJ engine;
    private final String sessionId = UUID.randomUUID().toString();

    public WorkspaceSession(WorkspaceStore store) throws Exception {
        this.store = store;
        transactions = new WorkspaceTransactions(store);
        runtime = new RefactorJRuntime();
        try {
            engine = runtime.openWorkspace(store.snapshot());
        } catch (Exception error) {
            runtime.close();
            throw error;
        }
    }

    public RefactorJ engine(Cancellation cancellation) {
        engine.workspace().synchronize(store.snapshot(), cancellation);
        return engine;
    }

    public WorkspaceSnapshot snapshot() {
        return store.snapshot();
    }

    public Prepared prepare(JsonObject wire, Cancellation cancellation) {
        var api = engine(cancellation);
        var request = Json.request(wire, snapshot().root());
        var plan = api.prepare(request, cancellation);
        return new Prepared(1, sessionId, Json.request(request), plan);
    }

    /** Serialized plans are never blindly applied: regenerate from the typed request and compare edits. */
    public Prepared validatePrepared(Prepared prepared, Cancellation cancellation) {
        if (prepared == null || prepared.schemaVersion() != 1 || prepared.request() == null
                || prepared.plan() == null)
            throw new IllegalArgumentException("Invalid plan envelope");
        WorkspaceSnapshot before = prepared.plan().before(), now = snapshot();

        if (sessionId.equals(prepared.sessionId()) && (before.version() != now.version() || !before
                .sources().equals(now.sources())))
            throw new TransactionException("STALE_PLAN", "Source versions changed after preview");
        if (!sameSourceContent(before, now))
            throw new TransactionException("STALE_PLAN",
                    "Workspace source/configuration changed after preview");
        Prepared fresh = prepare(prepared.request(), cancellation);

        if (fresh.plan().operation() != prepared.plan().operation() || !fresh.plan().edits()
                .equals(prepared.plan().edits()) || !fresh.plan().fileOperations().equals(prepared
                .plan().fileOperations()))
            throw new TransactionException("PLAN_CHANGED",
                    "Recomputed changes differ; request a fresh preview");
        Validation validation = engine(cancellation).validate(fresh.plan(), cancellation);

        if (!validation.valid() || !fresh.plan().valid())
            throw new TransactionException("CONFLICTED_PLAN", Json.GSON.toJson(validation
                    .diagnostics()));
        transactions.preview(fresh.plan());
        return fresh;
    }

    public WorkspaceTransactions.Result apply(Prepared prepared, Cancellation cancellation) {
        Prepared validated = validatePrepared(prepared, cancellation);
        return transactions.apply(validated.plan(), cancellation);
    }

    public WorkspaceTransactions.Result undo(String id, Cancellation cancellation) {
        return transactions.undo(id, cancellation);
    }

    public WorkspaceTransactions.Result redo(String id, Cancellation cancellation) {
        return transactions.redo(id, cancellation);
    }

    public List<WorkspaceStore.History> history() {
        return transactions.history();
    }

    private static boolean sameSourceContent(WorkspaceSnapshot a, WorkspaceSnapshot b) {
        if (!a.root().equals(b.root()) || !a.sourceRoots().equals(b.sourceRoots()) || !a.classpath()
                .equals(b.classpath()) || a.sources().size() != b.sources().size())
            return false;
        for (int i = 0; i < a.sources().size(); i++) {
            var x = a.sources().get(i);
            var y = b.sources().get(i);

            if (!x.file().equals(y.file()) || !x.text().equals(y.text()) || x.open() != y.open())
                return false;
        }
        return true;
    }

    @Override
    public void close() {
        engine.close();
        runtime.close();
    }
}
