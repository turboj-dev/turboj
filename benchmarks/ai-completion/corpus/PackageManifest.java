import java.util.List;
import java.util.stream.Collectors;

public class PackageManifest {

    private final String name;
    private final String version;
    private final List<String> dependencies;

    public PackageManifest(String name, String version, List<String> dependencies) {
        this.name = name;
        this.version = version;
        this.dependencies = List.copyOf(dependencies);
    }

    public String toJson() {
        String deps = dependencies.stream()
                .map(dep -> "\"" + dep + "\"")
                .collect(Collectors.joining(", "));
        return """
                {
                  "name": "%s",
                  "version": "%s",
                  "dependencies": [%s]
                }
                """.formatted(/*|*/);
    }

    public boolean dependsOn(String artifact) {
        return dependencies.contains(artifact);
    }
}
