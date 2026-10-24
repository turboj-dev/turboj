package com.turboj.ai;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nonblocking cancellation: potentially blocking stream-close operations never run on the caller.
 */
public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<AutoCloseable> resources = ConcurrentHashMap.newKeySet();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() throws InterruptedIOException {
        if (isCancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("AI request cancelled.");
    }

    public AutoCloseable register(AutoCloseable resource) {
        resources.add(resource);
        if (cancelled.get() && resources.remove(resource)) closeAsync(resource);
        return () -> resources.remove(resource);
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        var closing = new ArrayList<AutoCloseable>();
        for (AutoCloseable resource : resources)
            if (resources.remove(resource)) closing.add(resource);
        if (!closing.isEmpty())
            Thread.startVirtualThread(() -> closing.forEach(Cancellation::closeQuietly));
    }

    private static void closeAsync(AutoCloseable resource) {
        Thread.startVirtualThread(() -> closeQuietly(resource));
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
