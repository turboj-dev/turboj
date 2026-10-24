package dev.turboj.mavenj.workspace;

import dev.turboj.mavenj.api.MavenModels.Diagnostic;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit, cancellable real Maven execution. Never used by model import or source editing. */
public final class MavenLifecycle {
    private MavenLifecycle() {}

    private static final Semaphore SLOTS = new Semaphore(4);

    public record Request(Path root, List<String> goals, List<String> profiles, Path javaHome,
            Path mavenExecutable, boolean trusted, boolean offline, Duration timeout, Map<String,
            String> properties, Path userSettings, Path globalSettings, Path localRepository,
            List<String> inactiveProfiles) {
        public Request(Path root, List<String> goals, List<String> profiles, Path javaHome,
                Path mavenExecutable, boolean trusted, boolean offline, Duration timeout,
                Map<String, String> properties) {
            this(root, goals, profiles, javaHome, mavenExecutable, trusted, offline, timeout,
                    properties, null, null, null, List.of());
        }

        public Request {
            root = root.toAbsolutePath().normalize();
            goals = List.copyOf(goals);
            profiles = List.copyOf(profiles);
            properties = Map.copyOf(properties);
            inactiveProfiles = List.copyOf(inactiveProfiles);
            Objects.requireNonNull(timeout);
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1))
                    > 0)
                throw new IllegalArgumentException("Maven timeout must be positive and at most one day");
            if (goals.isEmpty())
                throw new IllegalArgumentException("Choose at least one Maven goal");
            for (String goal : goals)
                if (!goal.matches("[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){0,3}") || goal
                        .startsWith("-"))
                    throw new IllegalArgumentException("Invalid Maven goal: " + goal);
            for (String profile : profiles)
                if (!profile.matches("[!+-]?[A-Za-z0-9_.-]+"))
                    throw new IllegalArgumentException("Invalid Maven profile");
            for (String profile : inactiveProfiles)
                if (!profile.matches("[A-Za-z0-9_.-]+"))
                    throw new IllegalArgumentException("Invalid inactive Maven profile");
            properties.forEach((k, v) -> {
                if (!k.matches("[A-Za-z0-9_.-]+") || v.indexOf('\0') >= 0 || v.contains("\n") || v
                        .contains("\r"))
                    throw new IllegalArgumentException("Invalid Maven property");
            });
        }
    }

    public record Description(List<String> command, Path workingDirectory, String distribution,
            Path javaHome, List<String> profiles, List<Diagnostic> diagnostics) {
        public Description {
            command = List.copyOf(command);
            profiles = List.copyOf(profiles);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Result(int exitCode, boolean cancelled, boolean timedOut, Duration elapsed,
            Description description) {
        public boolean success() {
            return exitCode == 0 && !cancelled && !timedOut;
        }
    }

    public static final class Execution implements AutoCloseable {
        private final List<String> command;
        private final CompletableFuture<Result> completion = new CompletableFuture<> ();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Process process;

        private Execution(List<String> command) {
            this.command = command;
        }

        public List<String> command() {
            return command;
        }

        public CompletableFuture<Result> completion() {
            return completion;
        }

        public void cancel() {
            cancelled.set(true);
            Process p = process;

            if (p != null)
                stop(p);
        }

        @Override
        public void close() {
            cancel();
        }
    }

    public static Description inspect(Request request) throws IOException {
        var diagnostics = new ArrayList<Diagnostic> ();

        if (!Files.isDirectory(request.root))
            throw new IOException("Maven project directory does not exist");
        Path wrapper = request.root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        String distribution;

        if (Files.isRegularFile(wrapper)) {
            distribution = "Project Maven Wrapper";
            Path properties = request.root.resolve(".mvn/wrapper/maven-wrapper.properties");

            if (Files.isRegularFile(properties)) {
                Properties values = new Properties();

                try (var input = Files.newInputStream(properties)) {
                    values.load(input);
                }
                String url = values.getProperty("distributionUrl", "");

                if (!url.isBlank())
                    distribution += " — " + redact(url, request.properties);
                String distributionHash = values.getProperty("distributionSha256Sum", "");

                if (distributionHash.isEmpty())
                    diagnostics.add(Diagnostic.warning("WRAPPER_UNPINNED",
                            "Wrapper distribution has no SHA-256 pin; the trusted wrapper controls its download",
                            properties));
                else if (!distributionHash.matches("[a-fA-F0-9]{64}"))
                    diagnostics.add(Diagnostic.error("WRAPPER_CHECKSUM",
                            "Invalid distributionSha256Sum", properties));
                String wrapperHash = values.getProperty("wrapperSha256Sum", "");
                Path jar = properties.resolveSibling("maven-wrapper.jar");

                if (!wrapperHash.isBlank()) {
                    if (!wrapperHash.matches("[a-fA-F0-9]{64}"))
                        diagnostics.add(Diagnostic.error("WRAPPER_CHECKSUM",
                                "Invalid wrapperSha256Sum", properties));
                    else if (Files.isRegularFile(jar) && !sha256(jar).equalsIgnoreCase(wrapperHash))
                        diagnostics.add(Diagnostic.error("WRAPPER_CHECKSUM",
                                "Local maven-wrapper.jar does not match wrapperSha256Sum", jar));
                }
                if (request.offline && !url.isBlank()) {
                    if (!cachedDistribution(request.root, values, url))
                        diagnostics.add(Diagnostic.error("WRAPPER_OFFLINE_BOOTSTRAP",
                                "The configured Maven distribution is not cached; prepare the wrapper online explicitly before an offline build",
                                properties));
                    String type = values.getProperty("distributionType", "");

                    if (!"only-script".equals(type) && (values.containsKey("wrapperUrl") || values
                            .containsKey("wrapperSha256Sum")) && !Files.isRegularFile(jar))
                        diagnostics.add(Diagnostic.error("WRAPPER_OFFLINE_BOOTSTRAP",
                                "The Maven Wrapper JAR is missing and cannot be downloaded in offline mode",
                                properties));
                }
            }
        } else
            distribution = request.mavenExecutable
                    == null ? "Installed Maven on PATH" : request.mavenExecutable.toAbsolutePath()
                    .toString();
        Path javaHome = request.javaHome == null ? Path.of(System
                .getProperty("java.home")) : request.javaHome.toAbsolutePath().normalize();

        if (!Files.isRegularFile(javaHome.resolve(isWindows() ? "bin/java.exe" : "bin/java")))
            diagnostics.add(Diagnostic.error("JDK_MISSING",
                    "Selected Java installation has no java executable", javaHome));
        if (!request.trusted)
            diagnostics.add(Diagnostic.warning("PROJECT_TRUST",
                    "Maven builds execute project-controlled code and require project trust",
                    request.root));
        return new Description(arguments(request).stream().map(s -> redact(s, request.properties))
                .toList(), request.root, distribution, javaHome, request.profiles, diagnostics);
    }

    public static Execution start(Request request, Consumer<String> output) throws IOException {
        Objects.requireNonNull(output);
        if (!request.trusted)
            throw new SecurityException("Explicit project trust is required to execute Maven");
        Description description = inspect(request);

        if (description.diagnostics.stream().anyMatch(d -> d.severity()
                == dev.turboj.mavenj.api.MavenModels.Severity.ERROR))
            throw new IOException(description.diagnostics.stream().filter(d -> d.severity()
                    == dev.turboj.mavenj.api.MavenModels.Severity.ERROR).findFirst().orElseThrow()
                    .message());
        if (!SLOTS.tryAcquire())
            throw new IOException("Four Maven builds are already running; cancel a build first");
        var execution = new Execution(description.command);
        long start = System.nanoTime();

        try {
            var builder = new ProcessBuilder(arguments(request)).directory(request.root.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("JAVA_HOME", description.javaHome.toString());
            builder.environment().put("PATH", description.javaHome.resolve("bin")
                    + File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
            execution.process = builder.start();
            execution.process.getOutputStream().close();
        } catch (IOException | RuntimeException failure) {
            SLOTS.release();
            throw failure;
        }
        Thread outputThread = Thread.ofVirtual().name("mavenj-lifecycle-output").start(() -> {
            try (var reader = new InputStreamReader(execution.process.getInputStream(),
                    StandardCharsets.UTF_8)) {
                var line = new StringBuilder();
                int c;

                while ((c = reader.read()) != -1) {
                    if (c == '\n' || line.length() >= 8192) {
                        deliver(output, redact(line.toString(), request.properties));
                        line.setLength(0);
                    }
                    if (c != '\n')
                        line.append((char) c);
                }
                if (!line.isEmpty())
                    deliver(output, redact(line.toString(), request.properties));
            } catch (IOException e) {
                if (!execution.cancelled.get())
                    deliver(output, "Maven output closed: " + e.getClass().getSimpleName());
            }
        });
        Thread.ofVirtual().name("mavenj-lifecycle-wait").start(() -> {
            boolean timedOut = false;

            try {
                if (!execution.process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                    stop(execution.process);
                }
                execution.process.waitFor(5, TimeUnit.SECONDS);
                int exit = execution.process.isAlive() ? -1 : execution.process.exitValue();
                outputThread.join(2000);
                execution.completion.complete(new Result(exit, execution.cancelled.get(), timedOut,
                        Duration.ofNanos(System.nanoTime() - start), description));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(execution.process);
                execution.completion.completeExceptionally(e);
            } finally {
                SLOTS.release();
            }
        });
        return execution;
    }

    private static boolean cachedDistribution(Path project, Properties configuration,
            String configuredUrl) {
        try {
            String url = configuredUrl;
            String mirror = System.getenv("MVNW_REPOURL");
            int marker = url.indexOf("/org/apache/maven/");

            if (mirror != null && !mirror.isBlank() && marker >= 0)
                url = mirror.replaceAll("/+$", "") + url.substring(marker);
            java.net.URI uri = java.net.URI.create(url);

            if ("file".equalsIgnoreCase(uri.getScheme()) && Files.isRegularFile(Path.of(uri)))
                return true;
            String filename = Path.of(uri.getPath()).getFileName().toString();
            String archive = filename.replaceFirst("\\.(zip|tar\\.gz)$", ""), distribution = archive
                    .replaceFirst("-bin$", "");
            var bases = new LinkedHashSet<Path> ();
            String configuredHome = System.getenv("MAVEN_USER_HOME");

            if (configuredHome != null && !configuredHome.isBlank())
                bases.add(Path.of(configuredHome));
            else {
                bases.add(Path.of(System.getProperty("user.home"), ".m2"));
                bases.add(project.resolve(".cache/maven"));
            }
            if ("PROJECT".equals(configuration.getProperty("distributionBase")))
                bases.add(project);
            String subpath = configuration.getProperty("distributionPath", "wrapper/dists");
            var hashes = List.of(Integer.toHexString(url.hashCode()), Integer.toHexString(uri
                    .hashCode()));

            for (Path base : bases)
                for (String name : List.of(archive, distribution))
                    for (String hash : hashes) {
                        Path directory = base.resolve(subpath).resolve(name).resolve(hash);

                        if (Files.isRegularFile(directory.resolve("bin/mvn")) || Files
                                .isRegularFile(directory.resolve(distribution).resolve("bin/mvn")))
                            return true;
                    }
            return false;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static List<String> arguments(Request request) throws IOException {
        var result = new ArrayList<String> ();
        Path wrapper = request.root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");

        if (Files.isRegularFile(wrapper)) {
            if (isWindows())
                throw new IOException("Windows Maven lifecycle execution requires a native launcher adapter; macOS and Unix are supported");
            if (!Files.isExecutable(wrapper))
                result.add("/bin/sh");
            result.add(wrapper.toString());
        } else {
            Path executable = request.mavenExecutable;

            if (executable == null)
                executable = findMaven();
            if (executable == null || !Files.isRegularFile(executable))
                throw new IOException("No project Maven Wrapper or installed Maven found; configure a Maven executable");
            result.add(executable.toAbsolutePath().normalize().toString());
        }
        result.add("--batch-mode");
        result.add("--no-transfer-progress");
        if (request.offline)
            result.add("--offline");
        var profiles = new ArrayList<> (request.profiles);
        request.inactiveProfiles.forEach(p -> profiles.add("!" + p));
        if (!profiles.isEmpty()) {
            result.add("-P");
            result.add(String.join(",", profiles));
        }
        if (request.userSettings != null) {
            result.add("--settings");
            result.add(request.userSettings.toAbsolutePath().toString());
        }
        if (request.globalSettings != null) {
            result.add("--global-settings");
            result.add(request.globalSettings.toAbsolutePath().toString());
        }
        if (request.localRepository != null)
            result.add("-Dmaven.repo.local=" + request.localRepository.toAbsolutePath());
        request.properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.add("-D" + e.getKey() + "=" + e.getValue()));
        result.addAll(request.goals);
        return List.copyOf(result);
    }

    private static Path findMaven() {
        String path = System.getenv().getOrDefault("PATH", "");
        var roots = new LinkedHashSet<> (List.of(path.split(java.util.regex.Pattern
                .quote(File.pathSeparator))));
        roots.add("/opt/homebrew/bin");
        roots.add("/usr/local/bin");
        String home = System.getenv("MAVEN_HOME");

        if (home != null)
            roots.add(Path.of(home).resolve("bin").toString());
        for (String root : roots) {
            if (root.isBlank())
                continue;
            Path candidate = Path.of(root).resolve(isWindows() ? "mvn.cmd" : "mvn");

            if (Files.isExecutable(candidate))
                return candidate;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void stop(Process process) {
        var children = process.descendants().toList();

        for (var child : children.reversed())
            child.destroy();
        process.destroy();
        for (var child : children.reversed())
            if (child.isAlive())
                child.destroyForcibly();
        if (process.isAlive())
            process.destroyForcibly();
    }

    private static void deliver(Consumer<String> output, String line) {
        try {
            output.accept(line);
        } catch (RuntimeException ignored) {
            /* A consumer cannot strand the process pipe. */
        }
    }

    public static String redact(String line, Map<String, String> properties) {
        String result = line.replaceAll("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@", "$1<redacted>@")
                .replaceAll("(?i)((?:password|passwd|token|secret|authorization|api[_-]?key)\\s*[=:]\\s*)[^\\s]+",
                "$1<redacted>");

        for (var property : properties.entrySet())
            if (property.getKey().toLowerCase(Locale.ROOT)
                    .matches(".*(password|passwd|token|secret|credential|api.?key).*")) {
                if (!property.getValue().isBlank())
                    result = result.replace(property.getValue(), "<redacted>");
            }
        return result;
    }

    private static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;

            while ((n = input.read(buffer)) != -1)
                hash.update(buffer, 0, n);
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
