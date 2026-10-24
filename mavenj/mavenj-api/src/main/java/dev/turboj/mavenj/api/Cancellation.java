package dev.turboj.mavenj.api;

import java.util.concurrent.CancellationException;

@FunctionalInterface
public interface Cancellation {
    Cancellation NONE = () -> false;

    boolean cancelled();

    default void check() {
        if (cancelled() || Thread.currentThread().isInterrupted())
            throw new CancellationException();
    }
}
