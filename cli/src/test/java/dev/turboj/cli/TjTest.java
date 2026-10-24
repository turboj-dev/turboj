package dev.turboj.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Timeout(30)
class TjTest {
    @TempDir Path temporary;

    private record Result(int code, String output, String errors) {}

    private Result command(String input, String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(),
                errors = new ByteArrayOutputStream();
        int code =
                Tj.execute(
                        args,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(output, true, StandardCharsets.UTF_8),
                        new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(
                code,
                output.toString(StandardCharsets.UTF_8),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void evalAndPersistentReplProduceRealValues() {
        Result eval = command("", "eval", "1+2");
        assertEquals(0, eval.code(), eval.errors());
        assertEquals("3\n", eval.output());
        Result repl = command("int a=5;\nint b=7;\na*b\n/exit\n", "repl");
        assertEquals(0, repl.code(), repl.errors());
        assertEquals("5\n7\n35\n", repl.output());
    }

    @Test
    void checkCompileAndRunSourceWithArgumentsAndInput() throws Exception {
        Path source = temporary.resolve("Hello.java");
        Files.writeString(
                source,
                "public class Hello { public static void main(String[] args) { System.out.println(args[0]+\": \"+new java.util.Scanner(System.in).nextLine()); } }");
        assertEquals(0, command("", "check", source.toString()).code());
        Path classes = temporary.resolve("classes");
        Result compiled = command("", "compile", source.toString(), "-d", classes.toString());
        assertEquals(0, compiled.code(), compiled.errors());
        assertTrue(Files.exists(classes.resolve("Hello.class")));
        Result executed = command("caffè 😀\n", "run", source.toString(), "--", "Hello");
        assertEquals(0, executed.code(), executed.errors());
        assertEquals("Hello: caffè 😀\n", executed.output());
    }

    @Test
    void errorsReturnFailureAndNeverExecuteOldBytecode() throws Exception {
        Path source = temporary.resolve("Broken.java");
        Files.writeString(
                source, "class Broken { void main() { System.out.println(undefinedVariable); } }");
        Result result = command("", "run", source.toString());
        assertEquals(1, result.code());
        assertTrue(result.errors().contains("cannot find symbol"));
        assertEquals("", result.output());
        assertEquals(2, command("", "nonsense").code());
        assertEquals(1, command("", "eval", "missingVariable").code());
    }

    @Test
    void explicitDependencyClasspathIsSharedByCompilerAndWorker() throws Exception {
        Path library = temporary.resolve("Greeting.java");
        Files.writeString(
                library,
                "package library; public class Greeting { public static String text() { return \"dependency loaded\"; } }");
        Path dependencies = temporary.resolve("library-classes");
        Result built = command("", "compile", library.toString(), "-d", dependencies.toString());
        assertEquals(0, built.code(), built.errors());
        Path source = temporary.resolve("Main.java");
        Files.writeString(
                source,
                "import library.Greeting; class Main { void main() { System.out.println(Greeting.text()); } }");
        assertEquals(1, command("", "check", source.toString()).code());
        Result result =
                command("", "run", source.toString(), "--classpath", dependencies.toString());
        assertEquals(0, result.code(), result.errors());
        assertEquals("dependency loaded\n", result.output());
    }

    @Test
    void replHandlesMultilineAndIncompleteEndOfInput() {
        Result result =
                command("for (int i=0;i<2;i++) {\nSystem.out.println(i);\n}\n/exit\n", "repl");
        assertEquals(0, result.code(), result.errors());
        assertEquals("0\n1\n", result.output());
        result = command("for (;;) {\n", "repl");
        assertEquals(1, result.code());
        assertTrue(result.errors().contains("Incomplete"));
    }
}
