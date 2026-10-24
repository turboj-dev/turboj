package dev.javafmt.intellij;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

@State(name = "JavafmtSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class JavafmtSettings implements PersistentStateComponent<JavafmtSettings.Options> {
    public static final class Options {
        public boolean enabled;
        public String javaExecutable = defaultJava();
        public String engineJar = "";
    }

    private volatile Options options = new Options();

    public static JavafmtSettings get(Project project) {
        return project.getService(JavafmtSettings.class);
    }

    @Override
    public @NotNull Options getState() {
        return options;
    }

    @Override
    public void loadState(@NotNull Options state) {
        options = state;
    }

    private static String defaultJava() {
        String home = System.getenv("JAVA_HOME");
        return home == null || home.isBlank() ? "java" : Path.of(home, "bin", "java").toString();
    }
}
