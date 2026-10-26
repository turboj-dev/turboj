import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LineReader {

    private final Path path;

    public LineReader(Path path) {
        this.path = path;
    }

    public List<String> nonBlankLines() throws IOException {
        List<String> lines = new ArrayList<>();
        try (/*|*/) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line.strip());
                }
            }
        }
        return lines;
    }

    public long size() throws IOException {
        return Files.size(path);
    }
}
