package dev.turboj.help;

import dev.turboj.app.TurboFrame;
import dev.turboj.commandj.keymap.BundledKeymaps;
import dev.turboj.config.Settings;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

/** Prints one cheat sheet per bundled keymap, with the bundled bindings: {@code CheatSheetExport [directory]}. */
public final class CheatSheetExport {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "docs/cheatsheets" : args[0]).toAbsolutePath();
        System.setProperty("turboj.config", Files.createTempDirectory("turboj-cheatsheets-").toString());
        var task = new FutureTask<Map<String, String>>(() -> {
            TurboFrame frame = new TurboFrame(new Settings(), Map.of());
            try {
                var pages = new LinkedHashMap<String, String>();
                for (var profile : BundledKeymaps.profiles())
                    pages.put("cheatsheet-" + profile.id(), CheatSheet.html(frame.commandRegistry(),
                            frame.commandKeymaps().bindingsForProfile(profile.id()), profile.id(), profile.name(), CheatSheet.Environment.GENERIC));
                return pages;
            } finally {
                frame.dispose();
            }
        });
        SwingUtilities.invokeAndWait(task);
        Map<String, String> pages;
        try { pages = task.get(); }
        catch (java.util.concurrent.ExecutionException failure) { failure.getCause().printStackTrace(); System.exit(1); return; }
        for (var page : pages.entrySet()) {
            var printed = CheatSheetPrinter.print(page.getValue(), output, page.getKey());
            // The PDF is the published sheet; its HTML source is regenerated on demand.
            if (printed.pdf()) Files.deleteIfExists(output.resolve(page.getKey() + ".html"));
            System.out.println(printed.file());
        }
        System.exit(0);
    }
}
