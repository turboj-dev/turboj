package dev.turboj.help;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Prints the cheat sheet to PDF with an installed Chromium browser in headless mode; without one, the HTML prints itself. */
public final class CheatSheetPrinter {
    private CheatSheetPrinter() {}

    public record Printed(Path file, boolean pdf) {}

    /** Writes {@code name}.html into the directory, then {@code name}.pdf when a headless browser is available. */
    public static Printed print(String html, Path directory, String name) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path page = directory.resolve(name + ".html");
        Files.writeString(page, html, StandardCharsets.UTF_8);
        Optional<Path> browser = browser();
        if (browser.isEmpty()) return new Printed(page, false);
        Path pdf = directory.resolve(name + ".pdf");
        Path profile = Files.createTempDirectory("turboj-cheatsheet-browser-");
        try {
            Process process = new ProcessBuilder(browser.get().toString(), "--headless", "--disable-gpu", "--no-first-run",
                    "--no-default-browser-check", "--user-data-dir=" + profile, "--no-pdf-header-footer",
                    "--print-to-pdf-no-header", "--print-to-pdf=" + pdf, page.toUri().toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Printed(page, false);
            }
            return Files.isRegularFile(pdf) && Files.size(pdf) > 0 ? new Printed(pdf, true) : new Printed(page, false);
        } finally {
            try (var files = Files.walk(profile)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
            }
        }
    }

    /** Chrome, Chromium, Edge or Brave: the browsers that print to PDF from the command line. */
    static Optional<Path> browser() {
        String home = System.getProperty("user.home");
        var candidates = new ArrayList<Path>();
        for (String app : List.of("Google Chrome.app/Contents/MacOS/Google Chrome", "Chromium.app/Contents/MacOS/Chromium",
                "Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "Brave Browser.app/Contents/MacOS/Brave Browser")) {
            candidates.add(Path.of("/Applications", app));
            candidates.add(Path.of(home, "Applications", app));
        }
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            for (String binary : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge"))
                if (!directory.isBlank()) candidates.add(Path.of(directory, binary));
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }
}
