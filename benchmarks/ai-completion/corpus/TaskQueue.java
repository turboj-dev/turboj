import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class TaskQueue {

    private final Deque<Runnable> tasks = new ArrayDeque<>();

    public void submit(Runnable task) {
        tasks.addLast(task);
    }

    public Optional<Runnable> next() {
        if (tasks./*|*/()) {
            return Optional.empty();
        }
        return Optional.of(tasks.removeFirst());
    }

    public int pending() {
        return tasks.size();
    }
}
