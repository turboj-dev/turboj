package dev.turboj.console;

import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.ConsolePane.Role;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The output as a log of the actions the user started, in Maven's grammar (docs/output-and-status.md): every line
 * TurboJ writes carries a level prefix, every action opens with a ruled header and phases and closes with the same
 * summary, and the program's own output passes through verbatim and unprefixed. Only this class formats those lines.
 * <p>
 * Actions that overlap, such as the Run and the tests Save starts together, never interleave: the first one writes
 * to the output, the next waits in order and appears whole as soon as the first has finished, as Maven runs one goal
 * after another.
 */
public final class OutputLog {
    public enum Level { INFO, WARN, ERROR }

    /** How an action ended; the word Maven would print. */
    public enum Outcome {
        SUCCESS, FAILURE, STOPPED;
        boolean failed() { return this != SUCCESS; }
    }

    static final String RULE = "-".repeat(72);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private record Chunk(String text, Role role) {}

    private final ConsolePane console;
    private final LongSupplier clock;
    private final Supplier<LocalTime> time;
    private final ArrayDeque<Action> queue = new ArrayDeque<>();
    private boolean clearBeforeEachAction;

    public OutputLog(ConsolePane console) { this(console, System::nanoTime, LocalTime::now); }

    OutputLog(ConsolePane console, LongSupplier clock, Supplier<LocalTime> time) {
        this.console = Objects.requireNonNull(console);
        this.clock = clock;
        this.time = time;
    }

    /** Clear Output Before Each Run: an action that starts on an idle output starts on an empty one. */
    public synchronized void setClearBeforeEachAction(boolean value) { clearBeforeEachAction = value; }

    /** {@code [INFO] Run HelloWorld · hello-world · Java 25} under a rule: a new action, written now or after the current ones. */
    public synchronized Action start(String action, String subject) {
        if (queue.isEmpty() && clearBeforeEachAction) console.clear();
        var started = new Action(action.toUpperCase(Locale.ROOT));
        queue.addLast(started);
        started.line(Level.INFO, RULE, Role.MUTED);
        started.line(Level.INFO, action + (subject == null || subject.isBlank() ? "" : " " + subject), Role.HEADING);
        return started;
    }

    /** One action in the output; safe to use from any thread. */
    public final class Action {
        private final String word;
        private final long started = clock.getAsLong();
        private final List<Chunk> waiting = new ArrayList<>();
        private boolean open = true, lineStart = true;

        private Action(String word) { this.word = word; }

        public boolean open() { synchronized (OutputLog.this) { return open; } }

        /** {@code [INFO] --- compile ---}: a step of the action, as Maven names a plugin goal. */
        public void phase(String name) { line(Level.INFO, "--- " + name + " ---", Role.HEADING); }

        public void info(String text) { line(Level.INFO, text, Role.MUTED); }
        public void warn(String text) { line(Level.WARN, text, Role.WARNING); }
        public void error(String text) { line(Level.ERROR, text, Role.ERROR); }

        /** A plain {@code [INFO]} line, as Maven separates sections. */
        public void blank() { line(Level.INFO, "", Role.MUTED); }

        /** A line in a role of its own, such as a passed or failed count. */
        public void info(String text, Role role) { line(Level.INFO, text, role); }

        /** {@code [ERROR] src/main/java/A.java:[3,18] ';' expected}: a compiler or test location, relative to the project. */
        public void problem(Level level, String file, long line, long column, String message, Path root) {
            String where = location(file, line, column, root);
            String text = (where.isEmpty() ? "" : where + " ") + message;
            if (level == Level.ERROR) error(text); else if (level == Level.WARN) warn(text); else info(text);
        }

        /** Detail that belongs under the previous line, unprefixed, as Surefire prints a stack trace. */
        public void detail(String text) {
            if (text == null || text.isBlank()) return;
            synchronized (OutputLog.this) {
                if (!open) return;
                if (!lineStart) emit("\n", Role.NORMAL);
                emit(text.endsWith("\n") ? text : text + "\n", Role.MUTED);
                lineStart = true;
            }
        }

        /** The program's own output: verbatim, unprefixed, stderr in the error color. */
        public void program(String text, boolean stderr) {
            if (text.isEmpty()) return;
            synchronized (OutputLog.this) {
                if (!open) return;
                emit(text, stderr ? Role.ERROR : Role.NORMAL);
                lineStart = text.endsWith("\n");
            }
        }

        /**
         * The summary that ends every action:
         * <pre>
         * [INFO] ------------------------------------------------------------------------
         * [INFO] RUN SUCCESS · exit 0
         * [INFO] Total time: 4 ms (run 2 ms) · Finished at 09:12:03
         * [INFO] ------------------------------------------------------------------------
         * </pre>
         * {@code detail} follows the outcome word; {@code partNanos}, when known, is the part the program itself took.
         */
        public void finish(Outcome outcome, String detail, Long partNanos) {
            synchronized (OutputLog.this) {
                if (!open) return;
                long total = clock.getAsLong() - started;
                line(Level.INFO, RULE, Role.MUTED);
                line(outcome.failed() ? Level.ERROR : Level.INFO, word + " " + (outcome == Outcome.STOPPED ? "STOPPED" : outcome.name())
                                + (detail == null || detail.isBlank() ? "" : " · " + detail),
                        outcome == Outcome.SUCCESS ? Role.SUCCESS : outcome == Outcome.STOPPED ? Role.WARNING : Role.ERROR);
                line(Level.INFO, "Total time: " + duration(total) + (partNanos == null ? "" : " (" + word.toLowerCase(Locale.ROOT)
                        + " " + duration(partNanos) + ")") + " · Finished at " + time.get().format(CLOCK), Role.MUTED);
                line(Level.INFO, RULE, Role.MUTED);
                open = false;
                // The finished head leaves; the next action's waiting text appears whole, and so on.
                while (!queue.isEmpty() && !queue.peekFirst().open) {
                    queue.removeFirst();
                    var next = queue.peekFirst();
                    if (next != null) next.flush();
                }
            }
        }

        private void line(Level level, String text, Role role) {
            synchronized (OutputLog.this) {
                if (!open) return;
                if (!lineStart) emit("\n", Role.NORMAL);
                emit("[" + level.name() + "]" + (text.isEmpty() ? "" : " " + text) + "\n",
                        level == Level.INFO && role == Role.NORMAL ? Role.MUTED : role);
                lineStart = true;
            }
        }

        private void emit(String text, Role role) {
            if (queue.peekFirst() == this) console.append(text, role);
            else waiting.add(new Chunk(text, role));
        }

        private void flush() {
            for (Chunk chunk : waiting) console.append(chunk.text(), chunk.role());
            waiting.clear();
        }
    }

    /** One format for every duration: {@code <1 ms}, {@code 13 ms}, {@code 1.234 s}. */
    public static String duration(long nanos) {
        double ms = Math.max(0, nanos) / 1e6;
        if (ms < 1) return "<1 ms";
        if (ms < 1000) return Math.round(ms) + " ms";
        return String.format(Locale.ROOT, "%.3f s", ms / 1000);
    }

    /** {@code path:[line,col]}, the path relative to the project when it lies inside it. */
    public static String location(String file, long line, long column, Path root) {
        String name = file == null ? "" : file;
        if (!name.isBlank() && root != null) {
            try {
                Path path = Path.of(name).toAbsolutePath().normalize(), base = root.toAbsolutePath().normalize();
                if (path.startsWith(base)) name = base.relativize(path).toString();
            } catch (RuntimeException ignored) { /* Keep compiler-supplied names intact. */ }
        }
        if (line <= 0) return name;
        return name + ":[" + line + (column > 0 ? "," + column : "") + "]";
    }

    /** Maven's count line: {@code Tests run: 2, Failures: 1, Errors: 0, Skipped: 0}. */
    public static String counts(long run, long failures, long errors, long skipped) {
        return "Tests run: " + run + ", Failures: " + failures + ", Errors: " + errors + ", Skipped: " + skipped;
    }
}
