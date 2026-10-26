package dev.turboj.console;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.ui.ConsolePane;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** The terminal shows the program and real commands verbatim; everything TurboJ says goes to notices. */
class OutputLogTest {
    @Test void aRunShowsOnlyWhatTheProgramPrintsAndItsOutcomeIsANotice() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var clock = new AtomicLong();
        var notices = new CopyOnWriteArrayList<OutputLog.Notice>();
        var log = new OutputLog(console, notices::add, clock::get);
        edt(() -> {
            var run = log.start("Run", "HelloWorld");
            run.phase("compile");
            run.info("Compiled in 13 ms");
            run.phase("run HelloWorld.main");
            run.program("Hello World!\n", false);
            clock.set(4_200_000);
            run.finish(OutputLog.Outcome.SUCCESS, "exit 0", 2_000_000L);
            return null;
        });
        assertEquals("Hello World!\n", edt(console::text), "no header, phase, rule or summary line");
        assertEquals(List.of(new OutputLog.Notice(OutputLog.Level.INFO, "Run HelloWorld done · exit 0 · 4 ms (2 ms running)")), notices);
    }

    @Test void aRealCommandIsEchoedAsAShellDoesThenItsOutputVerbatim() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var log = new OutputLog(console);
        edt(() -> {
            console.append("previous output without newline", ConsolePane.Role.NORMAL);
            var maven = log.start("Maven", "mvn -q test");
            maven.command("mvn -q test");
            maven.program("[INFO] Tests run: 3, Failures: 0\n", false);
            maven.program("warning on stderr\n", true);
            maven.finish(OutputLog.Outcome.SUCCESS, null, null);
            return null;
        });
        assertEquals("previous output without newline\n$ mvn -q test\n[INFO] Tests run: 3, Failures: 0\nwarning on stderr\n",
                edt(console::text), "a command starts on its own line; Maven's own [INFO] lines are its output");
    }

    @Test void overlappingActionsNeverInterleaveTheSecondWaitsForTheFirst() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var log = new OutputLog(console);
        var runs = new CopyOnWriteArrayList<OutputLog.Action>();
        edt(() -> {
            var run = log.start("Run", "HelloWorld");
            runs.add(run);
            var command = log.start("Command", "ls");
            command.command("ls");
            command.program("a.txt\n", false);
            run.program("Hello ", false);
            command.finish(OutputLog.Outcome.SUCCESS, "exit 0", null); // finishes first, still waits its turn
            return null;
        });
        assertEquals("Hello ", edt(console::text), "the command waits for the run");
        edt(() -> {
            var run = runs.getFirst();
            run.program("World!\n", false);
            run.finish(OutputLog.Outcome.SUCCESS, "exit 0", null);
            return null;
        });
        assertEquals("Hello World!\n$ ls\na.txt\n", edt(console::text));
    }

    @Test void problemsAndFailuresReachTheStatusLineNotTheTerminal() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var notices = new CopyOnWriteArrayList<OutputLog.Notice>();
        var log = new OutputLog(console, notices::add, () -> 0);
        edt(() -> {
            var compile = log.start("Compile", "A.java");
            compile.problem(OutputLog.Level.ERROR, "/p/src/A.java", 3, 18, "';' expected", Path.of("/p"));
            compile.error("Unable to start");
            compile.finish(OutputLog.Outcome.FAILURE, "1 error", null);
            log.start("Run", "B").finish(OutputLog.Outcome.STOPPED, "superseded", null);
            return null;
        });
        assertEquals("", edt(console::text));
        assertEquals(List.of(new OutputLog.Notice(OutputLog.Level.ERROR, "Compile A.java: Unable to start"),
                new OutputLog.Notice(OutputLog.Level.ERROR, "Compile A.java failed · 1 error · src/A.java:[3,18] ';' expected · <1 ms"),
                new OutputLog.Notice(OutputLog.Level.WARN, "Run B stopped · superseded · <1 ms")), notices);
    }

    @Test void oneFormatForDurationsLocationsAndCounts() {
        assertEquals("<1 ms", OutputLog.duration(400_000));
        assertEquals("13 ms", OutputLog.duration(13_350_000));
        assertEquals("1.234 s", OutputLog.duration(1_234_000_000));
        assertEquals("pom.xml", OutputLog.location("/p/pom.xml", 0, 0, Path.of("/p")));
        assertEquals("Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", OutputLog.counts(2, 1, 0, 0));
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        // The console drains appended text on the EDT; one more turn makes it visible.
        SwingUtilities.invokeAndWait(() -> {});
        return future.get();
    }
}
