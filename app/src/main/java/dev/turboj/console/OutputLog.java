package dev.turboj.console;

import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.ConsolePane.Role;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The terminal (docs/output-and-status.md): the program's output and the output of real commands, verbatim, and
 * nothing TurboJ writes of its own. A command line is echoed after {@code $ }, as a shell shows it. An action's
 * problems and outcome become {@link Notice}s for the status line; test results have their own tab.
 * <p>
 * Actions that overlap, such as the Run and the tests Save starts together, never interleave: the first one writes
 * to the terminal, the next waits in order and appears whole as soon as the first has finished.
 */
public final class OutputLog {
    public enum Level { INFO, WARN, ERROR }

    /** How an action ended. */
    public enum Outcome {
        SUCCESS, FAILURE, STOPPED;
        boolean failed() { return this != SUCCESS; }
    }

    /** A message about an action, for the status line: never written to the terminal. */
    public record Notice(Level level, String text) {}

    private record Chunk(String text, Role role) {}

    /** Marks an action's first queued text: it starts on a line of its own when it is finally written. */
    private static final String LINE_START = "\u0000";

    private final ConsolePane console;
    private final Consumer<Notice> notices;
    private final LongSupplier clock;
    private final ArrayDeque<Action> queue = new ArrayDeque<>();
    private boolean clearBeforeEachAction;

    public OutputLog(ConsolePane console) { this(console, notice -> {}); }

    public OutputLog(ConsolePane console, Consumer<Notice> notices) { this(console, notices, System::nanoTime); }

    OutputLog(ConsolePane console, Consumer<Notice> notices, LongSupplier clock) {
        this.console = Objects.requireNonNull(console);
        this.notices = Objects.requireNonNull(notices);
        this.clock = clock;
    }

    /** Clear Output Before Each Run: an action that starts on an idle terminal starts on an empty one. */
    public synchronized void setClearBeforeEachAction(boolean value) { clearBeforeEachAction = value; }

    /** A new action, written now or after the current ones; {@code action} and {@code subject} name it in notices. */
    public synchronized Action start(String action, String subject) {
        if (queue.isEmpty() && clearBeforeEachAction) console.clear();
        // "HelloWorld · hello-world · Java 25": the status line needs only what was run.
        String what = subject == null ? "" : subject.split(" · ")[0].strip();
        var started = new Action(action + (what.isEmpty() ? "" : " " + what));
        queue.addLast(started);
        return started;
    }

    /** One action in the terminal; safe to use from any thread. */
    public final class Action {
        private final String name;
        private final long started = clock.getAsLong();
        private final List<Chunk> waiting = new ArrayList<>();
        private boolean open = true, wrote;
        private String firstProblem;

        private Action(String name) { this.name = name; }

        public boolean open() { synchronized (OutputLog.this) { return open; } }

        /** A step of the action; the terminal shows only what the step prints. */
        public void phase(String name) { }

        /** Progress worth no line anywhere: the status line and the outcome say it. */
        public void info(String text) { }

        public void info(String text, Role role) { }

        public void blank() { }

        /** A warning about the action, for the status line. */
        public void warn(String text) { notice(Level.WARN, text); }

        /** An error about the action, for the status line. */
        public void error(String text) { notice(Level.ERROR, text); }

        /**
         * A compiler or test location, {@code src/main/java/A.java:[3,18] ';' expected}: the editor marks it and Next
         * Error reaches it; the first error is named in the action's outcome.
         */
        public void problem(Level level, String file, long line, long column, String message, Path root) {
            synchronized (OutputLog.this) {
                if (level == Level.ERROR && firstProblem == null) {
                    String where = location(file, line, column, root);
                    firstProblem = (where.isEmpty() ? "" : where + " ") + message;
                }
            }
        }

        /** Detail under a previous line, such as a stack trace: not terminal material. */
        public void detail(String text) { }

        /** The command line a real command runs, as a shell echoes it: {@code $ mvn test}. */
        public void command(String line) {
            if (line == null || line.isBlank()) return;
            synchronized (OutputLog.this) {
                if (!open) return;
                write("$ " + line.strip() + "\n", Role.MUTED);
            }
        }

        /** The program's own output: verbatim, stderr in the error color. */
        public void program(String text, boolean stderr) {
            if (text.isEmpty()) return;
            synchronized (OutputLog.this) {
                if (!open) return;
                write(text, stderr ? Role.ERROR : Role.NORMAL);
            }
        }

        /**
         * Ends the action: {@code Run HelloWorld · exit 0 · 8 ms (run 1 ms)} goes to the status line. {@code detail}
         * follows the outcome; {@code partNanos}, when known, is the part the program itself took.
         */
        public void finish(Outcome outcome, String detail, Long partNanos) {
            synchronized (OutputLog.this) {
                if (!open) return;
                long total = clock.getAsLong() - started;
                String word = switch (outcome) { case SUCCESS -> "done"; case FAILURE -> "failed"; case STOPPED -> "stopped"; };
                String text = name + " " + word + (detail == null || detail.isBlank() ? "" : " · " + detail)
                        + (outcome == Outcome.FAILURE && firstProblem != null ? " · " + firstProblem : "")
                        + " · " + duration(total) + (partNanos == null ? "" : " (" + duration(partNanos) + " running)");
                open = false;
                notices.accept(new Notice(outcome == Outcome.SUCCESS ? Level.INFO : outcome == Outcome.STOPPED ? Level.WARN : Level.ERROR, text));
                // The finished head leaves; the next action's waiting text appears whole, and so on.
                while (!queue.isEmpty() && !queue.peekFirst().open) {
                    queue.removeFirst();
                    var next = queue.peekFirst();
                    if (next != null) next.flush();
                }
            }
        }

        private void notice(Level level, String text) {
            if (text == null || text.isBlank()) return;
            synchronized (OutputLog.this) {
                if (!open) return;
            }
            notices.accept(new Notice(level, name + ": " + text.strip()));
        }

        /** The first text of an action starts on a line of its own, as a shell prompt does. */
        private void write(String text, Role role) {
            boolean first = !wrote;
            wrote = true;
            if (queue.peekFirst() != this) {
                waiting.add(new Chunk(first ? LINE_START + text : text, role));
                return;
            }
            if (first && !console.atLineStart()) console.append("\n", Role.NORMAL);
            console.append(text, role);
        }

        private void flush() {
            for (Chunk chunk : waiting) {
                String text = chunk.text();
                if (text.startsWith(LINE_START)) {
                    text = text.substring(LINE_START.length());
                    if (!console.atLineStart()) console.append("\n", Role.NORMAL);
                }
                console.append(text, chunk.role());
            }
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
