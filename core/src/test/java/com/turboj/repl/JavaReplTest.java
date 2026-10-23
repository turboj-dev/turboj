package com.turboj.repl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Timeout(30)
class JavaReplTest {
    @Test
    void expressionsDeclarationsAndMethodsPersistInRemoteSession() throws Exception {
        try (var repl = new JavaRepl(System.out, System.err)) {
            assertEquals(java.util.List.of("3"), repl.submit("1 + 2").values());
            assertTrue(repl.submit("int a = 5;").success());
            assertTrue(repl.submit("int b = 7;").success());
            assertEquals(java.util.List.of("35"), repl.submit("a * b").values());
            assertTrue(repl.submit("int doubled(int x) { return x * 2; }").success());
            assertEquals(java.util.List.of("70"), repl.submit("doubled(a * b)").values());
            assertTrue(repl.complete("dou").stream().anyMatch(s -> s.startsWith("doubled")));
            assertEquals(6, repl.history().size());
        }
    }

    @Test
    void multilineWaitsForCompleteBlockAndSideEffectsStayOutsideController() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                var repl = new JavaRepl(out, System.err)) {
            assertFalse(repl.submit("for (int i=0; i<3; i++) {\n").complete());
            assertEquals(0, bytes.size());
            assertTrue(repl.submit("for (int i=0; i<3; i++) { System.out.println(i); }").success());
            assertEquals("0\n1\n2\n", bytes.toString(StandardCharsets.UTF_8));
            assertTrue(
                    repl.submit("System.setProperty(\"turboj.repl.remote\", \"yes\");").success());
            assertNull(System.getProperty("turboj.repl.remote"));
        }
    }

    @Test
    void rejectsInvalidCodeAndResetClearsVariables() throws Exception {
        try (var repl = new JavaRepl(System.out, System.err)) {
            var invalid = repl.submit("missingVariable + 1");
            assertTrue(invalid.complete());
            assertFalse(invalid.success());
            assertTrue(
                    invalid.diagnostics().stream().anyMatch(s -> s.contains("cannot find symbol")));
            repl.submit("int a=42;");
            repl.reset();
            assertFalse(repl.submit("a").success());
            assertEquals(java.util.List.of("3"), repl.submit("1+2").values());
        }
    }

    @Test
    void timeoutKillsRemoteProcessAndAllowsReset() throws Exception {
        try (var repl = new JavaRepl(System.out, System.err, Duration.ofSeconds(2))) {
            var result = repl.submit("while(true) {}");
            assertFalse(result.success());
            assertTrue(
                    result.diagnostics().stream()
                            .anyMatch(s -> s.contains("terminated") || s.contains("timed out")));
            repl.reset();
            assertEquals(java.util.List.of("3"), repl.submit("1+2").values());
        }
    }

    @Test
    void systemExitDoesNotTerminateController() throws Exception {
        try (var repl = new JavaRepl(System.out, System.err)) {
            assertFalse(repl.submit("System.exit(4);").success());
            repl.reset();
            assertEquals(java.util.List.of("35"), repl.submit("5*7").values());
        }
    }
}
