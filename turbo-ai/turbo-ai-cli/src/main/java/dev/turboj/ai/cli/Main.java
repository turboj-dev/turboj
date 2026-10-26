package dev.turboj.ai.cli;

import dev.turboj.ai.AICompletionProvider;
import dev.turboj.ai.AIConfig;
import dev.turboj.ai.AIDiagnostics;
import dev.turboj.ai.AIException;
import dev.turboj.ai.AIProviders;
import dev.turboj.ai.Cancellation;
import dev.turboj.ai.CompletionProbe;
import dev.turboj.ai.CompletionValidation;
import dev.turboj.ai.java.CompletionContext;
import dev.turboj.ai.java.JavaSuggestionFilter;
import dev.turboj.ai.local.LocalModelManager;
import dev.turboj.ai.local.ModelCatalog;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** {@code turbo-ai}: the TurboAI runtime from a terminal or a script, outside TurboJ. */
public final class Main {
    private static final String USAGE = """
            usage: turbo-ai <command> [options]

              status    detect the local runtime, check and load the model
              models    installed models and the catalog of known FIM models
              pull      download --model through the local server (explicit consent)
              test      one completion of a synthetic snippet; no project source
              complete  --file F --offset N: suggest the middle at a UTF-16 offset
              serve     own the runtime in the foreground until Ctrl+C, then stop it

            options:
              --endpoint URL     local server (default http://127.0.0.1:11434)
              --model NAME       FIM model (default %s)
              --backend ollama|llama.cpp
              --runtime automatic|managed|external   (status, serve; default external, serve automatic)
              --cloud openai|anthropic|xai  --cloud-model NAME  --consent
                                 test/complete in the cloud; the key comes from TURBO_AI_API_KEY
              --java N           language level for complete (default 25)
            """.formatted(ModelCatalog.DEFAULT);

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        Map<String, String> options = options(args);
        String command = args[0];
        try {
            return switch (command) {
                case "status" -> status(config(options, "external"), out);
                case "models" -> models(config(options, "external"), out);
                case "pull" -> pull(config(options, "external"), out);
                case "test" -> test(config(options, "external"), out);
                case "complete" -> complete(config(options, "external"), options, out);
                case "serve" -> serve(config(options, "automatic"), out);
                default -> {
                    err.println("turbo-ai: unknown command '" + command + "'");
                    err.print(USAGE);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException invalid) {
            err.println("turbo-ai: " + invalid.getMessage());
            return 2;
        }
    }

    private static int status(AIConfig config, PrintStream out) throws Exception {
        try (var manager = manager(out, false)) {
            var status = manager.configure(config).get(3, TimeUnit.MINUTES);
            print(status, out);
            return status.ready() ? 0 : 1;
        }
    }

    private static int models(AIConfig config, PrintStream out) throws Exception {
        try (AICompletionProvider provider = AIProviders.create(config, null)) {
            out.println("Installed at " + config.localEndpoint() + ":");
            for (String name : provider.models(new Cancellation())) out.println("  " + name);
        } catch (AIException failure) {
            out.println("  (" + failure.getMessage() + ")");
        }
        out.println("Known FIM models:");
        for (var model : ModelCatalog.models())
            out.printf(Locale.ROOT, "  %-26s %-8s %-10s %s%n", model.name(), model.size(), model.license(), model.role());
        return 0;
    }

    private static int pull(AIConfig config, PrintStream out) throws Exception {
        try (var manager = manager(out, true)) {
            var status = manager.configure(config).get(3, TimeUnit.MINUTES);
            if (status.ready()) {
                out.println(config.localModel() + " is already installed.");
                return 0;
            }
            status = manager.download().get(2, TimeUnit.HOURS);
            print(status, out);
            return status.ready() ? 0 : 1;
        }
    }

    private static int test(AIConfig config, PrintStream out) {
        try (AICompletionProvider provider = AIProviders.create(config, () -> System.getenv("TURBO_AI_API_KEY"))) {
            var result = CompletionProbe.run(provider, new Cancellation());
            out.println(result.message());
            return result.valid() ? 0 : 1;
        }
    }

    private static int complete(AIConfig config, Map<String, String> options, PrintStream out) throws Exception {
        Path file = Path.of(required(options, "file"));
        int offset = Integer.parseInt(required(options, "offset"));
        int java = Integer.parseInt(options.getOrDefault("java", "25"));
        String source = Files.readString(file);
        var request = CompletionContext.build(file, source, 1, offset, config, false, new Cancellation());
        var last = new StringBuilder();
        try (AICompletionProvider provider = AIProviders.create(config, () -> System.getenv("TURBO_AI_API_KEY"))) {
            provider.stream(request, text -> last.replace(0, last.length(), text), new Cancellation());
        } catch (AIException failure) {
            out.println("error: " + failure.error() + ": " + failure.getMessage());
            return 1;
        }
        String usable = CompletionValidation.usable(request, last.toString(), true);
        var verdict = usable.isBlank() ? null : JavaSuggestionFilter.check(source, offset, usable, java);
        if (verdict != JavaSuggestionFilter.Verdict.ACCEPT) {
            out.println("no safe suggestion" + (verdict == null ? "" : " (" + verdict + ")"));
            return 1;
        }
        out.print(usable);
        if (!usable.endsWith("\n")) out.println();
        return 0;
    }

    private static int serve(AIConfig config, PrintStream out) throws Exception {
        var manager = manager(out, true);
        var stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.close();
            stop.countDown();
        }, "turbo-ai-stop"));
        manager.configure(config);
        out.println("Serving " + config.localModel() + " at " + config.localEndpoint() + "; Ctrl+C stops what TurboAI started.");
        stop.await();
        return 0;
    }

    private static LocalModelManager manager(PrintStream out, boolean progress) {
        Path home = Path.of(System.getenv().getOrDefault("TURBO_AI_HOME",
                Path.of(System.getProperty("user.home"), ".config", "turbo-ai").toString()));
        return new LocalModelManager(home, status -> {
            if (progress) out.println("  " + status.summary() + (status.detail().isEmpty() ? "" : " — " + status.detail()));
        });
    }

    private static void print(LocalModelManager.Status status, PrintStream out) {
        out.println(status.summary() + " · " + (status.runtime().isEmpty() ? "no runtime" : status.runtime()) + " · "
                + status.ownership().name().toLowerCase(Locale.ROOT) + " · " + status.model());
        if (!status.detail().isEmpty()) out.println("  " + status.detail());
        if (!status.installed().isEmpty()) out.println("  installed: " + String.join(", ", status.installed()));
        if (status.loadMillis() > 0) out.println("  load and warm-up: " + status.loadMillis() + " ms");
        if (status.error() != null) out.println("  error: " + status.error());
        var last = AIDiagnostics.global().last();
        if (last != null && status.error() != null) out.println("  last exchange: " + last.describe());
    }

    private static AIConfig config(Map<String, String> options, String runtime) {
        var backend = options.getOrDefault("backend", "ollama").equals("llama.cpp") ? AIConfig.LocalBackend.LLAMA_CPP : AIConfig.LocalBackend.OLLAMA;
        var policy = AIConfig.LocalRuntime.valueOf(options.getOrDefault("runtime", runtime).toUpperCase(Locale.ROOT));
        String endpoint = options.getOrDefault("endpoint", backend == AIConfig.LocalBackend.OLLAMA ? "http://127.0.0.1:11434" : "http://127.0.0.1:8080");
        AIConfig config = AIConfig.defaults().withMode(AIConfig.Mode.LOCAL)
                .withLocal(backend, endpoint, options.getOrDefault("model", ModelCatalog.DEFAULT), policy);
        if (options.containsKey("cloud")) {
            var provider = AIConfig.CloudProvider.valueOf(options.get("cloud").toUpperCase(Locale.ROOT));
            if (!options.containsKey("consent"))
                throw new IllegalArgumentException("--cloud sends source to " + provider.label() + "; add --consent to allow it.");
            config = config.withCloud(provider, options.getOrDefault("cloud-endpoint", provider.defaultEndpoint()),
                    options.getOrDefault("cloud-model", provider.defaultModel()), true).withMode(AIConfig.Mode.CLOUD);
        }
        config.validate();
        return config;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) throw new IllegalArgumentException("--" + name + " is required");
        return value;
    }

    private static Map<String, String> options(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument '" + args[i] + "'");
            String name = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            options.put(name, flag ? "true" : args[++i]);
        }
        return options;
    }
}
