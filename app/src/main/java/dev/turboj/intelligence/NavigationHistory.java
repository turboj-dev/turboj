package dev.turboj.intelligence;

import java.util.ArrayDeque;
import java.util.function.UnaryOperator;

/** Independent of edit undo. New jumps clear forward history; edits do not. */
public final class NavigationHistory<T> {
    private final ArrayDeque<T> backward = new ArrayDeque<>(), forward = new ArrayDeque<>();

    public void jumpedFrom(T previous) {
        if (backward.isEmpty() || !backward.peekLast().equals(previous)) push(backward, previous);
        forward.clear();
    }

    public T back(T current) {
        if (backward.isEmpty()) return null;
        push(forward, current);
        return backward.removeLast();
    }

    public T forward(T current) {
        if (forward.isEmpty()) return null;
        push(backward, current);
        return forward.removeLast();
    }

    public void remap(UnaryOperator<T> mapper) {
        var back = backward.stream().map(mapper).toList();
        var next = forward.stream().map(mapper).toList();
        backward.clear();
        backward.addAll(back);
        forward.clear();
        forward.addAll(next);
    }

    private static <T> void push(ArrayDeque<T> stack, T value) {
        if (stack.size() == 256) stack.removeFirst();
        stack.addLast(value);
    }
}
