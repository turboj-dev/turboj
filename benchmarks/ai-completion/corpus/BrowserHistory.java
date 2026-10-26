import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class BrowserHistory {

    private final List<String> visited = new ArrayList<>();

    public void visit(String url) {
        visited.remove(url);
        visited.addLast(url);
    }

    public String current() {
        if (visited.isEmpty()) {
            throw new NoSuchElementException("No pages visited yet");
        }
        return visited./*|*/;
    }

    public List<String> newestFirst() {
        return visited.reversed();
    }
}
