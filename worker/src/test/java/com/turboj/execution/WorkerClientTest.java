package com.turboj.execution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import javax.tools.*;

@Timeout(25)
class WorkerClientTest {
    @Test
    void helloAndChangedBytecodeUseSameWarmJvmAndFreshStaticState() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            worker.warmup().get(10, TimeUnit.SECONDS);
            long pid = worker.workerPid().orElseThrow();
            StringBuffer output = new StringBuffer();
            Map<String, byte[]> first =
                    compile(
                            "Hello",
                            "public class Hello { static int x; public static void main(String[] a)"
                                    + " { System.out.println(\"Hello TurboJ! \"+(++x)); } }");
            assertEquals(
                    0,
                    worker.run(first, "Hello", List.of(), false, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("Hello TurboJ! 1\n", output.toString());
            output.setLength(0);
            assertEquals(
                    0,
                    worker.run(first, "Hello", List.of(), false, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("Hello TurboJ! 1\n", output.toString());
            output.setLength(0);
            Map<String, byte[]> second =
                    compile(
                            "Hello",
                            "public class Hello { public static void main(String[] a) {"
                                    + " System.out.println(\"Updated output\"); } }");
            assertEquals(
                    0,
                    worker.run(second, "Hello", List.of(), false, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("Updated output\n", output.toString());
            assertEquals(
                    pid,
                    worker.workerPid().orElseThrow(),
                    "Fast Run should keep the healthy JVM alive");
        }
    }

    @Test
    void scannerReceivesInteractiveInputAndEof() throws Exception {
        Map<String, byte[]> classes =
                compile(
                        "Input",
                        """
                        import java.util.*;
                        public class Input {
                            public static void main(String[] a) {
                                System.out.print("Name: ");
                                Scanner s=new Scanner(System.in);
                                System.out.println("Hello "+s.nextLine());
                                System.out.println(s.hasNextLine());
                            }
                        }
                        """);
        try (WorkerClient worker = new WorkerClient()) {
            StringBuffer output = new StringBuffer();
            CountDownLatch prompt = new CountDownLatch(1);
            var result =
                    worker.run(
                            classes,
                            "Input",
                            List.of(),
                            false,
                            e -> {
                                output.append(e.text());
                                if (output.toString().contains("Name:")) prompt.countDown();
                            });
            assertTrue(prompt.await(8, TimeUnit.SECONDS));
            worker.sendInput("Ada Lovelace\n");
            worker.closeInput();
            assertEquals(0, result.get().exitCode());
            assertTrue(output.toString().contains("Hello Ada Lovelace"));
            assertTrue(output.toString().contains("false"));
        }
    }

    @Test
    void menuReadsNumericInputOneLineAtATimeWithoutEof() throws Exception {
        var source =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("..", "examples", "Menu.java"));
        var classes = compile("Menu", source);
        try (WorkerClient worker = new WorkerClient()) {
            StringBuffer output = new StringBuffer();
            var result =
                    worker.run(
                            classes,
                            "Menu",
                            List.of(),
                            false,
                            event -> {
                                synchronized (output) {
                                    output.append(event.text());
                                    output.notifyAll();
                                }
                            });
            awaitOccurrences(output, "Quanti voti vuoi inserire? ", 1);
            worker.sendInput("oops\n");
            awaitOccurrences(output, "Valore non valido. Quanti voti vuoi inserire? ", 1);
            worker.sendInput("0\n");
            awaitOccurrences(output, "Valore non valido. Quanti voti vuoi inserire? ", 2);
            worker.sendInput("3\n");
            awaitOccurrences(output, "Inserisci il voto: ", 1);
            worker.sendInput("bad\n");
            awaitOccurrences(output, "Valore non valido. Inserisci il voto? ", 1);
            worker.sendInput("-1\n");
            awaitOccurrences(output, "Valore non valido. Inserisci il voto? ", 2);
            worker.sendInput("3\n");
            awaitOccurrences(output, "Inserisci il voto: ", 2);
            worker.sendInput("4\n");
            awaitOccurrences(output, "Inserisci il voto: ", 3);
            worker.sendInput("5\n");
            assertEquals(0, result.get(8, TimeUnit.SECONDS).exitCode(), output.toString());
            assertTrue(
                    output.toString()
                            .endsWith(
                                    "Totale note: 12.0\n"
                                            + "Media: 4.0\n"
                                            + "Note positive: \n"
                                            + "4.0\n"
                                            + "5.0\n"
                                            + "4.0\n"
                                            + "5.0\n"),
                    output.toString());
        }
    }

    private static void awaitOccurrences(StringBuffer output, String expected, int occurrences)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        synchronized (output) {
            while (output.toString().split(java.util.regex.Pattern.quote(expected), -1).length - 1
                    < occurrences) {
                long remaining = deadline - System.nanoTime();
                assertTrue(
                        remaining > 0,
                        "Waiting for "
                                + occurrences
                                + " occurrences of "
                                + expected
                                + "; actual: "
                                + output);
                TimeUnit.NANOSECONDS.timedWait(output, remaining);
            }
        }
    }

    @Test
    void timeoutBudgetPausesForStdinThenStillTerminatesAnInfiniteLoop() throws Exception {
        var classes =
                compile(
                        "WaitThenLoop",
                        """
                        import java.util.Scanner;
                        public class WaitThenLoop {
                            public static void main(String[] args) {
                                System.out.print("Input: ");
                                new Scanner(System.in).nextLine();
                                System.out.println("loop started");
                                while (true) {}
                            }
                        }
                        """);
        try (WorkerClient worker = new WorkerClient(Duration.ofMillis(800), 4096, 64)) {
            worker.warmup().get(10, TimeUnit.SECONDS);
            StringBuffer output = new StringBuffer();
            var states = new CopyOnWriteArrayList<Boolean>();
            var waiting = new CountDownLatch(1);
            var result =
                    worker.run(
                            classes,
                            "WaitThenLoop",
                            List.of(),
                            false,
                            event -> output.append(event.text()),
                            value -> {
                                states.add(value);
                                if (value) {
                                    assertTrue(
                                            output.toString().contains("Input: "),
                                            "The prompt must precede the input-state event");
                                    waiting.countDown();
                                }
                            });
            assertTrue(waiting.await(8, TimeUnit.SECONDS));
            // This exceeds the complete execution budget: a human input wait must not time out.
            assertThrows(TimeoutException.class, () -> result.get(1100, TimeUnit.MILLISECONDS));
            worker.sendInput("go\n");
            assertEquals(124, result.get(4, TimeUnit.SECONDS).exitCode());
            assertTrue(output.toString().contains("loop started"));
            assertEquals(List.of(true, false), states);
        }
    }

    @Test
    void stoppingWhileWaitingForStdinClearsStateAndAllowsNextRun() throws Exception {
        var classes =
                compile(
                        "Waiting",
                        """
                        public class Waiting {
                            public static void main(String[] args) throws Exception { System.in.read(); }
                        }
                        """);
        try (WorkerClient worker = new WorkerClient()) {
            var states = new CopyOnWriteArrayList<Boolean>();
            var waiting = new CountDownLatch(1);
            var result =
                    worker.run(
                            classes,
                            "Waiting",
                            List.of(),
                            false,
                            ignored -> {},
                            value -> {
                                states.add(value);
                                if (value) waiting.countDown();
                            });
            assertTrue(waiting.await(8, TimeUnit.SECONDS));
            worker.stop();
            assertEquals(130, result.get(5, TimeUnit.SECONDS).exitCode());
            assertEquals(List.of(true, false), states);
            var next = worker.run(classes, "Waiting", List.of(), false, ignored -> {});
            worker.closeInput();
            assertEquals(0, next.get(5, TimeUnit.SECONDS).exitCode());
        }
    }

    @Test
    void infiniteLoopStopsAndNextRunWorks() throws Exception {
        Map<String, byte[]> loop =
                compile(
                        "Loop",
                        "public class Loop { public static void main(String[] a) {"
                                + " System.out.println(\"started\"); while(true) {} } }");
        try (WorkerClient worker = new WorkerClient()) {
            CountDownLatch started = new CountDownLatch(1);
            var future = worker.run(loop, "Loop", List.of(), false, e -> started.countDown());
            assertTrue(started.await(8, TimeUnit.SECONDS));
            long before = System.nanoTime();
            worker.stop();
            assertTrue(
                    System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(200),
                    "Stop must not wait for process exit");
            assertEquals(130, future.get(5, TimeUnit.SECONDS).exitCode());
            var normal =
                    compile("Ok", "public class Ok { public static void main(String[] a) {} }");
            assertEquals(0, worker.run(normal, "Ok", List.of(), false, e -> {}).get().exitCode());
        }
    }

    @Test
    void systemExitIsReportedAndAutomaticallyRestarts() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            var exit =
                    compile(
                            "Exit",
                            "public class Exit { public static void main(String[] a) {"
                                    + " System.exit(7); } }");
            var result = worker.run(exit, "Exit", List.of(), false, e -> {}).get();
            assertEquals(7, result.exitCode());
            assertTrue(result.detail().contains("exited"));
            var normal =
                    compile("Ok", "public class Ok { public static void main(String[] a) {} }");
            assertEquals(0, worker.run(normal, "Ok", List.of(), false, e -> {}).get().exitCode());
        }
    }

    @Test
    void thrownExceptionIsRealAndStderrIsDistinguished() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            StringBuffer errors = new StringBuffer();
            var classes =
                    compile(
                            "Bad",
                            "public class Bad { public static void main(String[] a) { throw new"
                                    + " IllegalStateException(\"real failure\"); } }");
            var result =
                    worker.run(
                                    classes,
                                    "Bad",
                                    List.of(),
                                    false,
                                    e -> {
                                        if (e.stderr()) errors.append(e.text());
                                    })
                            .get();
            assertEquals(1, result.exitCode());
            assertTrue(errors.toString().contains("IllegalStateException: real failure"));
            assertTrue(errors.toString().contains("Bad.main"));
        }
    }

    @Test
    void unicodeSplitAcrossByteWritesIsPreserved() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            StringBuffer output = new StringBuffer();
            var classes =
                    compile(
                            "Utf",
                            "public class Utf { public static void main(String[] a) throws"
                                    + " Exception { for(byte b : \"caffè ☕"
                                    + " 😀\".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {"
                                    + " System.out.write(b); System.out.flush(); } } }");
            assertEquals(
                    0,
                    worker.run(classes, "Utf", List.of(), false, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("caffè ☕ 😀", output.toString());
        }
    }

    @Test
    void outputIsBoundedAndIsolatedModeRetiresProcess() throws Exception {
        try (WorkerClient worker = new WorkerClient(Duration.ofSeconds(15), 4096, 64)) {
            StringBuffer output = new StringBuffer();
            var classes =
                    compile(
                            "Loud",
                            "public class Loud { public static void main(String[] a) { for(int"
                                + " i=0;i<20000;i++) System.out.println(\"01234567890123456789\");"
                                + " } }");
            assertEquals(
                    0,
                    worker.run(classes, "Loud", List.of(), true, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertTrue(output.length() < 4500);
            assertTrue(output.toString().contains("output limit reached"));
            assertTrue(worker.workerPid().isEmpty());
        }
    }

    @Test
    void timeoutKillsProgram() throws Exception {
        var classes =
                compile(
                        "Loop",
                        "public class Loop { public static void main(String[] a) { while(true) {} }"
                                + " }");
        try (WorkerClient worker = new WorkerClient(Duration.ofMillis(800), 4096, 64)) {
            worker.warmup().get(10, TimeUnit.SECONDS);
            var result =
                    worker.run(classes, "Loop", List.of(), false, e -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(124, result.exitCode());
            assertTrue(result.detail().contains("timed out"));
        }
    }

    @Test
    void globalStateMutationRetiresWorker() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            var classes =
                    compile(
                            "State",
                            "public class State { public static void main(String[] a) {"
                                + " System.setProperty(\"turboj.test.pollution\",\"true\"); } }");
            var result = worker.run(classes, "State", List.of(), false, e -> {}).get();
            assertEquals(0, result.exitCode());
            assertTrue(result.detail().contains("restart required"));
            assertTrue(worker.workerPid().isEmpty());
            assertNull(System.getProperty("turboj.test.pollution"));
        }
    }

    @Test
    void lingeringThreadRetiresWorker() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            var classes =
                    compile(
                            "Threads",
                            "public class Threads { public static void main(String[] a) { new"
                                    + " Thread(() -> { while(true) {} }).start(); } }");
            var result = worker.run(classes, "Threads", List.of(), false, e -> {}).get();
            assertEquals(0, result.exitCode());
            assertTrue(result.detail().contains("restart required"));
            assertTrue(worker.workerPid().isEmpty());
        }
    }

    @Test
    void java25CompactInstanceMainAndArgumentsWork() throws Exception {
        try (WorkerClient worker = new WorkerClient()) {
            StringBuffer output = new StringBuffer();
            var classes =
                    compile(
                            "Compact",
                            "void main(String[] args) { System.out.println(args[0]); } void main()"
                                    + " { System.out.println(\"wrong overload\"); }");
            assertEquals(
                    0,
                    worker.run(
                                    classes,
                                    "Compact",
                                    List.of("Java 25"),
                                    false,
                                    e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("Java 25\n", output.toString());
            output.setLength(0);
            classes = compile("Compact", "void main() { System.out.println(\"No arguments\"); }");
            assertEquals(
                    0,
                    worker.run(classes, "Compact", List.of(), false, e -> output.append(e.text()))
                            .get()
                            .exitCode());
            assertEquals("No arguments\n", output.toString());
        }
    }

    @Test
    void protocolRejectsMalformedLengthsAndUnknownMagic() throws Exception {
        byte[] invalidMagic = {0, 0, 0, 0};
        assertThrows(
                IOException.class,
                () -> Protocol.read(new DataInputStream(new ByteArrayInputStream(invalidMagic))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(Protocol.MAGIC);
        data.writeByte(Protocol.RUN);
        data.writeInt(Integer.MAX_VALUE);
        assertThrows(
                IOException.class,
                () ->
                        Protocol.read(
                                new DataInputStream(
                                        new ByteArrayInputStream(bytes.toByteArray()))));
    }

    private static Map<String, byte[]> compile(String name, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
        try (var standard =
                        compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
                var manager =
                        new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                            @Override
                            public JavaFileObject getJavaFileForOutput(
                                    Location location,
                                    String className,
                                    JavaFileObject.Kind kind,
                                    FileObject sibling) {
                                return new SimpleJavaFileObject(
                                        URI.create(
                                                "mem:///"
                                                        + className.replace('.', '/')
                                                        + kind.extension),
                                        kind) {
                                    @Override
                                    public OutputStream openOutputStream() {
                                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                        outputs.put(className, stream);
                                        return stream;
                                    }
                                };
                            }
                        }) {
            var unit =
                    new SimpleJavaFileObject(
                            URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreErrors) {
                            return source;
                        }
                    };
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            assertTrue(
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of("--release", "25", "-proc:none"),
                                    null,
                                    List.of(unit))
                            .call(),
                    diagnostics.getDiagnostics().toString());
        }
        Map<String, byte[]> classes = new HashMap<>();
        outputs.forEach((nameOfClass, bytes) -> classes.put(nameOfClass, bytes.toByteArray()));
        return classes;
    }
}
