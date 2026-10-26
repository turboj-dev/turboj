import java.util.Map;
import java.util.TreeMap;

public class WordCounter {

    private final Map<String, Integer> counts = new TreeMap<>();

    public void addText(String text) {
        for (String raw : text.split("\\s+")) {
            String word = raw.toLowerCase().replaceAll("[^a-z']", "");
            if (word.isEmpty()) {
                continue;
            }
            /*|*/
        }
    }

    public int countOf(String word) {
        return counts.getOrDefault(word.toLowerCase(), 0);
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(counts);
    }
}
