package dev.turboj.refactorj.transactions;

import dev.turboj.refactorj.api.Model.*;
import java.nio.file.Path;
import java.util.*;

/** Preview value; null before/after denotes a nonexistent file. No mutation occurs here. */
public record ChangeSet(List<Transition> transitions) {
    public record Transition(Path path, SourceSnapshot before, SourceSnapshot after) {
        public Transition {
            path = path.toAbsolutePath().normalize();
            if (before == null && after == null)
                throw new IllegalArgumentException("Empty transition");
            if (before != null && !before.file().equals(path) || after != null && !after.file()
                    .equals(path))
                throw new IllegalArgumentException("Transition identity mismatch");
        }
    }

    public ChangeSet {
        transitions = transitions.stream().sorted(Comparator.comparing(t -> t.path().toString()))
                .toList();
        Set<Path> seen = new HashSet<> ();

        for (var t : transitions)
            if (!seen.add(t.path()))
                throw new IllegalArgumentException("Duplicate transition");
    }

    public static ChangeSet prepare(Plan plan) {
        if (!plan.valid())
            throw new TransactionException("INVALID_PLAN",
                    "A conflicted or cancelled plan cannot be applied");
        var initial = map(plan.before());
        var result = new TreeMap<Path, SourceSnapshot> (initial);
        Set<Path> edited = new HashSet<> ();

        for (var edit : plan.edits()) {
            safe(plan.before().root(), edit.file());
            if (!edited.add(edit.file()))
                throw new TransactionException("DUPLICATE_EDIT",
                        "One ordered edit list is required per file");
            var source = initial.get(edit.file());

            if (source == null)
                throw new TransactionException("MISSING_SOURCE",
                        "Edited file is absent from the snapshot");
            validateUnicode(source.text(), edit.edits());
            String text = TextEdit.apply(source.text(), edit.edits());
            result.put(edit.file(), new SourceSnapshot(edit.file(), text, source.version(), source
                    .open()));
        }
        Set<Path> consumed = new HashSet<> (), destinations = new HashSet<> ();
        // File operations are evaluated against the original identities, allowing atomic logical swaps.
        var transformed = new TreeMap<Path, SourceSnapshot> ();

        for (var op : plan.fileOperations()) {
            safe(plan.before().root(), op.path());
            if (op.destination() != null)
                safe(plan.before().root(), op.destination());
            switch (op.kind()) {
                case CREATE -> {
                    if (!destinations.add(op.path()))
                        throw conflict("Duplicate created destination");
                    validateText(op.content());
                    transformed.put(op.path(), new SourceSnapshot(op.path(), op.content(), 0,
                            false));
                }
                case DELETE -> {
                    if (!consumed.add(op.path()) || !initial.containsKey(op.path()))
                        throw conflict("Deleted source is missing or consumed twice");
                    if (edited.contains(op.path()))
                        throw conflict("Cannot edit and delete the same source");
                }
                case MOVE -> {
                    if (op.path().equals(op.destination()) || !consumed.add(op.path()) || !initial
                            .containsKey(op.path()))
                        throw conflict("Moved source is missing, unchanged or consumed twice");
                    if (!destinations.add(op.destination()))
                        throw conflict("Duplicate move destination");
                    var source = result.get(op.path());
                    transformed.put(op.destination(), new SourceSnapshot(op.destination(), source
                            .text(), source.version(), source.open()));
                }
            }
        }
        for (Path destination : destinations)
            if (initial.containsKey(destination) && !consumed.contains(destination))
                throw conflict("Destination already exists: " + destination.getFileName());
        consumed.forEach(result::remove);
        result.putAll(transformed);
        var paths = new TreeSet<Path> (initial.keySet());
        paths.addAll(result.keySet());
        List<Transition> transitions = new ArrayList<> ();

        for (Path path : paths)
            if (!sameContent(initial.get(path), result.get(path)))
                transitions.add(new Transition(path, initial.get(path), result.get(path)));
        return new ChangeSet(transitions);
    }

    static NavigableMap<Path, SourceSnapshot> map(WorkspaceSnapshot snapshot) {
        var result = new TreeMap<Path, SourceSnapshot> ();
        snapshot.sources().forEach(s -> result.put(s.file(), s));
        return result;
    }

    static void safe(Path root, Path path) {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root) || path
                .equals(root))
            throw new TransactionException("OUTSIDE_ROOT",
                    "File operation is outside the allowed workspace");
    }

    static boolean sameContent(SourceSnapshot a, SourceSnapshot b) {
        return a == null ? b == null : b != null && a.file().equals(b.file()) && a.text().equals(b
                .text()) && a.open() == b.open();
    }

    static void expect(WorkspaceSnapshot current, WorkspaceSnapshot expected) {
        if (!current.root().equals(expected.root()) || current.version() != expected.version()
                || !current.sources().equals(expected.sources()) || !current.sourceRoots()
                .equals(expected.sourceRoots()) || !current.classpath().equals(expected
                .classpath()))
            throw new TransactionException("STALE_WORKSPACE",
                    "Workspace or source changed since preparation");
    }

    static WorkspaceSnapshot apply(WorkspaceSnapshot current, ChangeSet changes) {
        var sources = map(current);
        long version = current.version() + 1;

        for (var t : changes.transitions()) {
            safe(current.root(), t.path());
            if (!Objects.equals(sources.get(t.path()), t.before()))
                throw new TransactionException("STALE_SOURCE",
                        "Affected source changed since preparation");
            if (t.after() == null)
                sources.remove(t.path());
            else
                sources.put(t.path(), new SourceSnapshot(t.path(), t.after().text(), Math
                        .max(version, t.before() == null ? t.after().version() + 1 : t.before()
                        .version() + 1), t.after().open()));
        }
        return new WorkspaceSnapshot(current.root(), version, new ArrayList<> (sources.values()),
                current.sourceRoots(), current.classpath(), current.projects());
    }

    private static void validateUnicode(String source, List<TextEdit> edits) {
        for (var edit : edits) {
            if (splitsPair(source, edit.start()) || splitsPair(source, edit.end()))
                throw new TransactionException("UNICODE_BOUNDARY",
                        "An edit splits a UTF-16 surrogate pair");
            validateText(edit.text());
        }
    }

    static void validateText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw conflict("Malformed UTF-16 insertion");
            } else if (Character.isLowSurrogate(c))
                throw conflict("Malformed UTF-16 insertion");
        }
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset
                - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }

    private static TransactionException conflict(String message) {
        return new TransactionException("FILE_CONFLICT", message);
    }
}
