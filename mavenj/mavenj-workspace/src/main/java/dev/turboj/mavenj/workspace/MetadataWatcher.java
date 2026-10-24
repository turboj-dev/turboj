package dev.turboj.mavenj.workspace;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded metadata polling, coalesced independently of ordinary Java document edits. */
public final class MetadataWatcher implements AutoCloseable {
    private record Stamp(boolean exists, long modified, long size, Object key) {}

    private final ScheduledExecutorService executor;
    private final Runnable changed;
    private final long debounceNanos;
    private Map<Path, Stamp> observed = Map.of();
    private long pendingSince;
    private volatile Throwable lastFailure;
    private boolean closed;

    public MetadataWatcher(Collection<Path> files, Duration debounce, Runnable changed) {
        if (debounce.isNegative())
            throw new IllegalArgumentException("Negative debounce");
        this.debounceNanos = debounce.toNanos();
        this.changed = Objects.requireNonNull(changed);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mavenj-metadata-watch");
            t.setDaemon(true);
            return t;
        });
        update(files);
        executor.scheduleWithFixedDelay(this::pollSafely, 150, 150, TimeUnit.MILLISECONDS);
    }

    public synchronized void update(Collection<Path> files) {
        if (closed)
            return;
        var next = new LinkedHashMap<Path, Stamp> ();

        for (Path path : files) {
            Path p = path.toAbsolutePath().normalize();
            next.put(p, stamp(p));
        }
        observed = next;
        pendingSince = 0;
    }

    public Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    private void pollSafely() {
        try {
            boolean notify = false;
            synchronized (this) {
                if (closed)
                    return;
                boolean dirty = false;

                for (var entry : observed.entrySet()) {
                    Stamp now = stamp(entry.getKey());

                    if (!now.equals(entry.getValue())) {
                        entry.setValue(now);
                        dirty = true;
                    }
                }
                long now = System.nanoTime();

                if (dirty)
                    pendingSince = now;
                if (pendingSince != 0 && now - pendingSince >= debounceNanos) {
                    pendingSince = 0;
                    notify = true;
                }
            }
            if (notify)
                changed.run();
        } catch (RuntimeException failure) {
            lastFailure = failure;
        }
    }

    private static Stamp stamp(Path path) {
        try {
            var a = Files.readAttributes(path, BasicFileAttributes.class);
            return new Stamp(true, a.lastModifiedTime().to(TimeUnit.NANOSECONDS), a.size(), a
                    .fileKey());
        } catch (java.io.IOException e) {
            return new Stamp(false, 0, 0, null);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        observed = Map.of();
    }
}
