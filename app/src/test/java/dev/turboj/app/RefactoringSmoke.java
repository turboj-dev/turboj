package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.refactoring.RefactoringController;
import dev.turboj.commandj.core.CommandContext;
import dev.turboj.refactorj.api.Model.Location;
import dev.turboj.refactorj.api.Requests.Rename;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual in-process LTK + native buffers, preview, typing, workspace undo/redo and disk moves. */
public final class RefactoringSmoke {
    private static final String MAIN = "public class Main { public int run() { return new Calculator().add(2, 3); } }\n";
    private static final String CALC = "public class Calculator { public int add(int a, int b) { return a + b; } }\n";
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/refactoring-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path project = Files.createTempDirectory(output, "project-");
        Files.createDirectories(project.resolve(".turboj"));
        Path main = project.resolve("Main.java"), calc = project.resolve("Calculator.java");
        Files.writeString(main, MAIN);
        Files.writeString(calc, CALC);
        System.setProperty("turboj.config", project.resolve(".config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            var f = new TurboFrame(new Settings(), Map.of());
            f.setVisible(true);
            return f;
        });

        try {
            open(frame, calc);
            edt(() -> {
                frame.editor().gotoOffset(frame.editor().getText().length());
                frame.editor().replaceSelection("// unsaved\n");
                return null;
            });
            open(frame, main);
            RefactoringController.Prepared prepared = prepare(frame, new Rename(new Location(main,
                    MAIN.indexOf("add")), "sum"));
            check(prepared.plan().valid(), "semantic cross-file Rename prepared: " + prepared.plan()
                    .diagnostics());
            check(Files.readString(main).equals(MAIN) && Files.readString(calc).equals(CALC),
                    "preparation leaves disk unchanged");
            edt(() -> {
                frame.refactoring().showPreview(prepared);
                return null;
            });
            check(edt(() -> frame.editor().getText().equals(MAIN)),
                    "preview leaves source unchanged");
            edt(() -> frame.refactoring().apply(prepared)).get(40, TimeUnit.SECONDS);
            waitFor(() -> !frame.refactoring().busy());
            check(edt(() -> frame.editor().getText().contains(".sum(2, 3)")),
                    "resolved call updated in active buffer");
            check(Files.readString(main).equals(MAIN) && Files.readString(calc).equals(CALC),
                    "open buffer edits are not autosaved");
            open(frame, calc);
            check(edt(() -> frame.editor().getText().contains("int sum(")) && edt(() -> frame
                    .editor().getText().contains("// unsaved")),
                    "unsaved target buffer preserved and updated");
            open(frame, main);
            edt(() -> {
                frame.editor().gotoOffset(frame.editor().getText().length());
                frame.editor().replaceSelection("// later\n");
                return null;
            });
            command(frame, "undo");
            check(edt(() -> frame.editor().getText().contains(".sum(") && !frame.editor().getText()
                    .contains("// later")), "first Undo removes only later typing");
            command(frame, "undo");
            waitFor(() -> !frame.refactoring().busy());
            check(edt(() -> frame.editor().getText().equals(MAIN)),
                    "second Undo restores workspace call");
            open(frame, calc);
            check(edt(() -> frame.editor().getText().equals(CALC + "// unsaved\n")),
                    "workspace Undo restores unsaved declaration");
            command(frame, "redo");
            waitFor(() -> !frame.refactoring().busy());
            open(frame, calc);
            check(edt(() -> frame.editor().getText().contains("int sum(")),
                    "workspace Redo reapplies declaration");
            var renameType = prepare(frame, new Rename(new Location(calc, CALC
                    .indexOf("Calculator")), "Arithmetic"));

            if (!renameType.plan().valid())
                System.out.println("TYPE PLAN " + renameType.plan().edits() + " FILES " + renameType
                        .plan().fileOperations());
            check(renameType.plan().valid(), "type Rename includes safe file move: " + renameType
                    .plan().diagnostics());
            edt(() -> frame.refactoring().apply(renameType)).get(40, TimeUnit.SECONDS);
            waitFor(() -> !frame.refactoring().busy());
            Path moved = project.resolve("Arithmetic.java");
            check(!Files.exists(calc) && Files.readString(moved).equals(CALC),
                    "file move preserves original saved bytes");
            check(edt(() -> moved.equals(frame.currentFile()) && frame.editor().getText()
                    .contains("class Arithmetic") && frame.editor().getText()
                    .contains("// unsaved")), "moved buffer retains unsaved source and identity");
            command(frame, "undo");
            waitFor(() -> !frame.refactoring().busy());
            check(Files.exists(calc) && !Files.exists(moved),
                    "workspace Undo restores moved disk file");
            check(edt(() -> calc.equals(frame.currentFile()) && frame.editor().getText()
                    .contains("class Calculator")), "workspace Undo restores buffer path");
            open(frame, main);
            var stale = prepare(frame, new Rename(new Location(main, MAIN.indexOf("add")),
                    "total"));
            // The method is sum after the first workspace redo; its source offset is unchanged.
            check(stale.plan().valid(), "next semantic preview prepared");
            edt(() -> {
                frame.editor().gotoOffset(frame.editor().getText().length());
                frame.editor().replaceSelection("// race\n");
                return null;
            });
            try {
                edt(() -> frame.refactoring().apply(stale)).get(40, TimeUnit.SECONDS);
                throw new AssertionError("Stale preview applied");
            } catch (ExecutionException expected) {
                check(edt(() -> frame.editor().getText().contains(".sum(") && frame.editor()
                        .getText().endsWith("// race\n")),
                        "stale preview rejected without overwriting typing");
            }
            note("PASS shared native semantic model, preview, cross-file buffers, workspace undo/redo, saved-file move and stale rejection");
        } finally {
            Files.writeString(output.resolve("report.txt"), report);
            edt(() -> {
                frame.shutdown();
                frame.dispose();
                return null;
            });
        }
    }

    private static RefactoringController.Prepared prepare(TurboFrame frame,
            Rename request) throws Exception {
        return edt(() -> frame.refactoring().prepare(request)).get(90, TimeUnit.SECONDS);
    }

    private static void open(TurboFrame frame, Path file) throws Exception {
        edt(() -> {
            frame.openPath(file);
            return null;
        });
        waitFor(() -> file.equals(frame.currentFile()));
    }

    private static void command(TurboFrame frame, String id) throws Exception {
        edt(() -> frame.commandRegistry().execute(id, CommandContext.of("editor", "editable")))
                .stage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void check(boolean value, String label) {
        if (!value)
            throw new AssertionError(label);
        note("PASS " + label);
    }

    private static void note(String value) {
        report.append(value).append('\n');
        System.out.println(value);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);

        while (System.nanoTime() < end) {
            if (edt(condition))
                return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T> (action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
