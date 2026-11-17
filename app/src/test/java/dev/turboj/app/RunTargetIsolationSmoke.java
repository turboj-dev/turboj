package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.app.KeyboardActionEffectsSmoke.*;

/** Real plain-project compilation/processes; fixtures do not depend on personal examples. */
public final class RunTargetIsolationSmoke {
    public static void main(String[] args) throws Exception {
        output = Files.createDirectories(Path.of("app/target/run-target-isolation"));
        work = Files.createTempDirectory("turboj-run-targets-").toRealPath();
        System.setProperty("turboj.config", work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path first = project("first", true), second = project("second", true), empty = project("empty", false);
        settings = new Settings();
        frame = edt(() -> { var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront();
            Desktop.getDesktop().requestForeground(true); return f; });
        try {
            open(first.resolve("Helper.java")); reject("ambiguous target is refused");
            check(!edt(() -> frame.console().text().contains("EXECUTED_")), "ambiguous run must not choose a main");
            pass("run", "multiple mains require an explicit choice, no arbitrary execution");
            open(first.resolve("Main.java")); run("EXECUTED_first_Main");
            open(first.resolve("Helper.java")); run("EXECUTED_first_Main");
            pass("run", "non-main file reuses the selected main inside the same project");
            open(second.resolve("Helper.java")); reject("previous project's main must not select this project's main");
            check(!edt(() -> frame.console().text().contains("EXECUTED_second_")), "same class name in another root is not a prior launch");
            pass("run", "two projects with identical main class names keep their target selection separate");
            open(second.resolve("Other.java")); run("EXECUTED_second_Other");
            open(second.resolve("Helper.java")); run("EXECUTED_second_Other");
            open(empty.resolve("Helper.java")); reject("no-main project");
            pass("run", "no-main project reports the problem without running a previous program");
            open(first.resolve("Main.java"));
            // Ordered EDT actions force cancellation/edit before the asynchronous compilation result returns.
            for (String id : List.of("run", "run.context")) {
                edt(() -> { var ctx = CommandContext.of("workspace", "editor", "editable");
                    frame.commandRegistry().execute(id, ctx); frame.commandRegistry().execute("stop", ctx); return null; });
                Thread.sleep(1800);
                check(!edt(() -> frame.isRunning() || frame.console().text().contains("EXECUTED_")), "stop during " + id + " preparation must prevent launch");
                pass("stop", "cancelled " + id + " preparation cannot launch its program later");
            }
            edt(() -> { frame.commandRegistry().execute("run", CommandContext.of("workspace", "editor", "editable"));
                frame.editor().replaceSelection(" // unsaved\n"); return null; });
            Thread.sleep(1800);
            check(!edt(() -> frame.isRunning() || frame.console().text().contains("EXECUTED_")), "obsolete compilation must not launch");
            pass("run", "source edit during compilation prevents stale execution");
        } finally {
            Files.writeString(output.resolve("effects.tsv"), report);
            Files.writeString(output.resolve("console.txt"), edt(() -> frame.console().text()));
            edt(() -> { for (var dialog : List.copyOf(OverlayHost.dialogs(frame))) dialog.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }
    private static Path project(String name, boolean mains) throws Exception {
        Path root = Files.createDirectories(work.resolve(name));
        Files.createDirectories(root.resolve(".turboj"));
        Files.writeString(root.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=" + name + "\n");
        Path src = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(src.resolve("Helper.java"), "public class Helper {}\n");
        if (mains) for (String type : List.of("Main", "Other")) Files.writeString(src.resolve(type + ".java"),
                "public class " + type + " { public static void main(String[] args) { System.out.println(\"EXECUTED_" + name + "_" + type + "\"); } }\n");
        return src;
    }
    private static void open(Path file) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await(() -> file.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "open " + file);
        action("clear"); action("view.focusEditor");
    }
    private static void reject(String reason) throws Exception {
        int before = edt(() -> frame.statusMessages().split("No main method", -1).length);
        action("run");
        await(() -> frame.statusMessages().split("No main method", -1).length > before, reason);
    }
    private static void run(String marker) throws Exception {
        action("run"); await(() -> frame.console().text().contains(marker) && !frame.isRunning(), marker);
    }
}
