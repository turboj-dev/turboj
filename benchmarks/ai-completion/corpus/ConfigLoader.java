import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigLoader {

    private final Path configFile;

    public ConfigLoader(Path configFile) {
        this.configFile = configFile;
    }

    public Map<String, String> load() {
        try {
            return parse(Files.readString(configFile));
        } catch (IOException e) {
            /*|*/
        }
    }

    static Map<String, String> parse(String text) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                entries.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return entries;
    }
}
