package dev.turboj.refactorj.api;

import dev.turboj.refactorj.api.Model.*;
import java.util.*;

/** Contextual Java fixes/assists. All calls belong on the same serial worker as the workspace.
 * Inspection is read-only; preparation never writes user files. Proposals are scoped to the
 * exact inspected snapshot and must be inspected again after any source/project change. */
public interface JavaQuickActions {
    enum Kind { FIX, ASSIST }
    /** ADD_JUNIT_LIBRARY prepares only the Java import; the host adds the library to the project. */
    enum Action { CREATE_CLASS, CREATE_METHOD, ADD_IMPORT, CREATE_JUNIT_TEST, ADD_JUNIT_LIBRARY, REFACTOR }

    /** Parameters describe inferred targets and UI defaults, never arbitrary executable code. */
    record Proposal(String id, Kind kind, Action action, String title, String explanation,
                    Location location, Operation operation, Map<String, String> parameters) {
        public Proposal {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(action);
            Objects.requireNonNull(title); Objects.requireNonNull(explanation); Objects.requireNonNull(location);
            parameters = Map.copyOf(parameters);
        }
    }
    record Inspection(WorkspaceSnapshot snapshot, Location location, List<Diagnostic> diagnostics,
                      List<Proposal> proposals) {
        public Inspection { diagnostics = List.copyOf(diagnostics); proposals = List.copyOf(proposals); }
        public List<Proposal> fixes() { return proposals.stream().filter(p -> p.kind() == Kind.FIX).toList(); }
        public List<Proposal> assists() { return proposals.stream().filter(p -> p.kind() == Kind.ASSIST).toList(); }
    }
    /** A linked-edit stop in the resulting source. Related occurrences share a group id. */
    record Placeholder(String group, Location location) {
        public Placeholder { Objects.requireNonNull(group); Objects.requireNonNull(location); }
    }
    record Prepared(Plan plan, Location focus, List<Placeholder> placeholders) {
        public Prepared { Objects.requireNonNull(plan); placeholders = List.copyOf(placeholders); }
    }
    Inspection inspect(Location location, Cancellation cancellation);
    Prepared prepare(Proposal proposal, Cancellation cancellation);
    /** Complete a linked method-name or method-parameter-name edit. The current snapshot must
     * differ from beforeTyping only in the indicated complete declaration identifier. The
     * returned transaction is rebased on current source; successful preparation supplies fresh
     * method-template ranges. Hosts coalesce preceding local typing with that transaction. */
    default Prepared renameLinked(WorkspaceSnapshot beforeTyping, Location declaration, String newName, Cancellation cancellation) {
        throw new UnsupportedOperationException("Linked semantic rename is unavailable");
    }
    /** Every occurrence, in its compilation unit, of the local variable or parameter at the location, in source order;
     * empty for any other symbol. Hosts edit them together as one linked field. */
    default List<Location> localOccurrences(Location location, Cancellation cancellation) { return List.of(); }
}
