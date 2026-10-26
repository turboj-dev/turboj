import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class BoundedStack<T> {

    private final Deque<T> items = new ArrayDeque<>();
    private final int capacity;

    BoundedStack(int capacity) {
        this.capacity = capacity;
    }

    void push(T item) {
        if (items.size() == capacity) {
            throw new IllegalStateException("stack is full");
        }
        items.push(item);
    }

    T pop() {
        if (items.isEmpty()) {
            throw new NoSuchElementException("stack is empty");
        }
        return items.pop();
    }

    int size() {
        return items.size();
    }
}

public class BoundedStackTest {

    @Test
    void pushThenPopReturnsLastItem() {
        BoundedStack<String> stack = new BoundedStack<>(2);
        stack.push("a");
        stack.push("b");
        assertEquals("b", stack.pop());
        assertEquals(1, stack.size());
    }

    @Test
    void pushBeyondCapacityThrows() {
        BoundedStack<String> stack = new BoundedStack<>(1);
        stack.push("a");
        assertThrows(IllegalStateException.class, () -> stack.push("b"));
    }

    @Test
    void popOnEmptyStackThrows() {
        BoundedStack<String> stack = new BoundedStack<>(2);
        /*|*/
    }
}
