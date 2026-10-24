package dev.turboj.commandj.core;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Cooperative cancellation; callbacks are detached under lock and invoked once outside it. */
public final class CancellationToken {
    private volatile boolean cancelled;
    private final List<Runnable> listeners = new ArrayList<> ();

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled)
            throw new CancellationException();
    }

    public AutoCloseable onCancel(Runnable listener) {
        Objects.requireNonNull(listener);
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return () -> {
                    synchronized (this) {
                        listeners.remove(listener);
                    }
                };
            }
        }
        listener.run();
        return () -> {};
    }

    boolean cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled)
                return false;
            cancelled = true;
            pending = List.copyOf(listeners);
            listeners.clear();
        }
        for (Runnable listener : pending)
            try {
                listener.run();
            } catch (RuntimeException ignored) {}
        return true;
    }
}
