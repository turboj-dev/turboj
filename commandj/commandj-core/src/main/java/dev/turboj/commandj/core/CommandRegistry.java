package dev.turboj.commandj.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Thread-safe registry with bounded background work; caller handlers must never block. */
public final class CommandRegistry implements AutoCloseable {
    private final ConcurrentHashMap<String, Command> byId = new ConcurrentHashMap<> ();
    private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<> ();
    private volatile List<Command> snapshot = List.of();
    private final AtomicLong revision = new AtomicLong();
    private final ArrayDeque<String> recent = new ArrayDeque<> ();
    private final ConcurrentHashMap<Long, CancellationToken> active = new ConcurrentHashMap<> ();
    private final AtomicLong requests = new AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<> (64), r -> {
        Thread t = new Thread(r, "commandj-worker");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public synchronized void register(Command command) {
        Objects.requireNonNull(command);
        if (closed)
            throw new IllegalStateException("Registry is closed");
        if (aliases.containsKey(command.id()) || byId.putIfAbsent(command.id(), command) != null)
            throw new IllegalArgumentException("Duplicate command: " + command.id());
        var list = new ArrayList<> (snapshot);
        list.add(command);
        snapshot = List.copyOf(list);
        revision.incrementAndGet();
    }

    public synchronized void unregister(String id) {
        if (aliases.remove(id) != null) {
            revision.incrementAndGet();
            return;
        }
        byId.remove(id);
        aliases.entrySet().removeIf(entry -> entry.getValue().equals(id));
        snapshot = snapshot.stream().filter(c -> !c.id().equals(id)).toList();
        recent.remove(id);
        revision.incrementAndGet();
    }

    public Optional<Command> find(String id) {
        return Optional.ofNullable(byId.get(canonicalId(id)));
    }

    /** A persisted command ID may survive a rename without creating a second palette row. */
    public synchronized void alias(String alias, String target) {
        if (closed) throw new IllegalStateException("Registry is closed");
        if (alias == null || alias.isBlank() || byId.containsKey(alias) || aliases.containsKey(alias))
            throw new IllegalArgumentException("Duplicate/invalid alias: " + alias);
        String canonical = canonicalId(target);
        if (!byId.containsKey(canonical)) throw new IllegalArgumentException("Unknown target: " + target);
        aliases.put(alias, canonical);
        revision.incrementAndGet();
    }

    public String canonicalId(String id) { return aliases.getOrDefault(id, id); }

    public Map<String, String> aliases() { return Map.copyOf(aliases); }

    public List<Command> commands() {
        return snapshot;
    }

    public long revision() {
        return revision.get();
    }

    public Availability availability(String id, CommandContext context) {
        Command c = byId.get(canonicalId(id));
        return c == null ? Availability.disabled("Unknown command") : c.available(context);
    }

    public synchronized List<String> recentCommands() {
        return List.copyOf(recent);
    }

    public synchronized void restoreRecent(List<String> ids) {
        recent.clear();
        for (String id : ids)
            if (find(id).isPresent() && !recent.contains(canonicalId(id))) {
                recent.addLast(canonicalId(id));
                if (recent.size() == 64)
                    break;
            }
    }

    private synchronized void remember(String id) {
        recent.remove(id);
        recent.addFirst(id);
        while (recent.size() > 64)
            recent.removeLast();
    }

    public CommandExecution execute(String id, CommandContext context) {
        Objects.requireNonNull(context);
        Command command = byId.get(canonicalId(id));
        var token = new CancellationToken();
        var future = new CompletableFuture<CommandResult> ();
        var result = new CommandExecution(id, token, future, command != null && command
                .cancellable());
        token.onCancel(() -> future.complete(CommandResult.cancelled()));
        if (closed) {
            future.complete(CommandResult.failure("Registry is closed"));
            return result;
        }
        if (command == null) {
            future.complete(CommandResult.failure("Unknown command: " + id));
            return result;
        }
        Availability available = command.available(context);

        if (!available.enabled()) {
            future.complete(new CommandResult(CommandResult.Status.DISABLED, available.reason()));
            return result;
        }
        long request = requests.incrementAndGet();
        synchronized (active) {
            if (active.size() >= 128) {
                future.complete(CommandResult.failure("Command queue is full"));
                return result;
            }
            active.put(request, token);
        }
        Runnable run = () -> {
            try {
                token.throwIfCancelled();
                CompletionStage<CommandResult> stage = Objects.requireNonNull(command.handler()
                        .execute(context, token));
                stage.whenComplete((value, error) -> {
                    active.remove(request);
                    CommandResult outcome = token.isCancelled() ? CommandResult.cancelled() : error
                            != null ? CommandResult.failure(message(error)) : Objects
                            .requireNonNullElse(value, CommandResult
                            .failure("Command returned no result"));

                    if (outcome.status() == CommandResult.Status.SUCCEEDED)
                        remember(command.id());
                    future.complete(outcome);
                });
            } catch (Throwable error) {
                active.remove(request);
                future.complete(token.isCancelled()
                        || error instanceof CancellationException ? CommandResult
                        .cancelled() : CommandResult.failure(message(error)));
            }
        };

        try {
            if (command.execution() == Command.Execution.CALLER)
                run.run();
            else
                worker.execute(run);
        } catch (RejectedExecutionException full) {
            active.remove(request);
            future.complete(CommandResult.failure("Command queue is full"));
        }
        return result;
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error
                .getCause() : error;
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }

    @Override
    public void close() {
        closed = true;
        active.values().forEach(CancellationToken::cancel);
        worker.shutdownNow();
    }
}
