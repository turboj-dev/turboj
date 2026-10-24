package dev.turboj.mavenj.cli;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir
    Path root;

    @Test
    void actualCommandsInspectSharedModelWithoutLaunchingProjectWrapper() throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\ntouch WRAPPER_WAS_RUN\n");
        for (String command : List.of("inspect", "modules", "dependencies", "classpath", "sync")) {
            var bytes = new ByteArrayOutputStream();
            int code = Main.run(new String[] {command, root.toString(), "--json",
                    "--local-repository", root.resolve("repo").toString()}, new PrintStream(bytes),
                    System.err);
            var json = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals(0, code, json.toString());
            assertTrue(json.has("result"));
            assertTrue(json.get("resolved").getAsBoolean());
        }
        assertFalse(Files.exists(root.resolve("WRAPPER_WAS_RUN")));
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test
    void stableUsageErrorsAndNoAccidentalNetworkPermission() {
        var bytes = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"inspect", ".", "--online", "--json"},
                new PrintStream(bytes), System.err));
        assertEquals("USAGE", JsonParser.parseString(bytes.toString()).getAsJsonObject()
                .get("error").getAsString());
        bytes.reset();
        assertEquals(0, Main.run(new String[] {"--version"}, new PrintStream(bytes), System.err));
        assertTrue(bytes.toString().startsWith("mavenj 0.1.0"));
    }

    @Test
    void jsonNeverLeaksSecretPropertiesOrUrlCredentials() {
        String json = Json.report(Map.of("password", "secret-value", "repository",
                "https://user:pass@example.test/repo", "effectivePom",
                "<project><api.token>confidential</api.token></project>"));
        assertFalse(json.contains("secret-value"));
        assertFalse(json.contains("user:pass"));
        assertFalse(json.contains("confidential"));
        assertTrue(json.contains("redacted"));
    }
}
