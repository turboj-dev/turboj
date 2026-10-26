package dev.turboj.console;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.ui.ConsolePane;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class OutputLogTest {
    private static final String RULE = "[INFO] " + "-".repeat(72) + "\n";

    @Test void aRunReadsLikeMavenAndTheProgramOutputIsLeftAlone() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var clock = new AtomicLong();
        var log = new OutputLog(console, clock::get, () -> LocalTime.of(9, 12, 3));
        edt(() -> {
            var run = log.start("Run", "HelloWorld · hello-world · Java 25");
            run.phase("compile");
            run.info("Compiled in 13 ms");
            run.phase("run HelloWorld.main");
            run.program("Hello World!", false); // no trailing newline: the summary still starts on its own line
            clock.set(4_200_000);
            run.finish(OutputLog.Outcome.SUCCESS, "exit 0", 2_000_000L);
            return null;
        });
        assertEquals(RULE + "[INFO] Run HelloWorld · hello-world · Java 25\n[INFO] --- compile ---\n[INFO] Compiled in 13 ms\n"
                + "[INFO] --- run HelloWorld.main ---\nHello World!\n" + RULE + "[INFO] RUN SUCCESS · exit 0\n"
                + "[INFO] Total time: 4 ms (run 2 ms) · Finished at 09:12:03\n" + RULE, edt(console::text));
    }

    @Test void overlappingActionsNeverInterleaveTheSecondWaitsForTheFirst() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var log = new OutputLog(console);
        edt(() -> {
            var run = log.start("Run", "HelloWorld");   // Save starts the Run and the tests together
            var tests = log.start("Test", "all tests");
            tests.phase("test");
            tests.program("test output\n", false);
            run.phase("run HelloWorld.main");
            run.program("Hello World!\n", false);
            tests.finish(OutputLog.Outcome.SUCCESS, "2 of 2 passed", null); // finishes first, still waits its turn
            assertFalse(console.text().contains("Test all tests") && !console.text().contains("RUN SUCCESS"));
            run.finish(OutputLog.Outcome.SUCCESS, "exit 0", null);
            return null;
        });
        String text = edt(console::text);
        int runEnd = text.indexOf("[INFO] RUN SUCCESS"), testStart = text.indexOf("[INFO] Test all tests");
        assertTrue(runEnd > 0 && testStart > runEnd, "the tests appear whole after the run:\n" + text);
        assertTrue(text.indexOf("Hello World!") < runEnd && text.indexOf("test output") > testStart, text);
    }

    @Test void everyTurboJLineIsPrefixedAndEveryActionEndsWithASummary() throws Exception {
        var console = edt(() -> new ConsolePane(ignored -> {}));
        var log = new OutputLog(console);
        edt(() -> {
            var compile = log.start("Compile", "A.java");
            compile.problem(OutputLog.Level.ERROR, "/p/src/A.java", 3, 18, "';' expected", Path.of("/p"));
            compile.finish(OutputLog.Outcome.FAILURE, "1 error", null);
            log.start("Test", "all tests").finish(OutputLog.Outcome.STOPPED, "superseded", null);
            return null;
        });
        String text = edt(console::text);
        assertTrue(text.lines().allMatch(line -> line.matches("\\[(INFO|WARN|ERROR)\\]( .*)?")), text);
        assertTrue(text.contains("[ERROR] src/A.java:[3,18] ';' expected"));
        assertTrue(text.contains("[ERROR] COMPILE FAILURE · 1 error"));
        assertTrue(text.contains("[ERROR] TEST STOPPED · superseded"));
        assertEquals(2, text.lines().filter(line -> line.startsWith("[INFO] Total time: ")).count());
    }

    @Test void oneFormatForDurationsLocationsAndCounts() {
        assertEquals("<1 ms", OutputLog.duration(400_000));
        assertEquals("13 ms", OutputLog.duration(13_350_000));
        assertEquals("1.234 s", OutputLog.duration(1_234_000_000));
        assertEquals("pom.xml", OutputLog.location("/p/pom.xml", 0, 0, Path.of("/p")));
        assertEquals("Tests run: 2, Failures: 1, Errors: 0, Skipped: 0", OutputLog.counts(2, 1, 0, 0));
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future);
        // The console drains appended text on the EDT; one more turn makes it visible.
        var drained = new FutureTask<>(() -> null); SwingUtilities.invokeAndWait(drained);
        return future.get();
    }
}
