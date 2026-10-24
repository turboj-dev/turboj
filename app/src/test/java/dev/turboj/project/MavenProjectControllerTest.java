package dev.turboj.project;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.mavenj.api.*;
import dev.turboj.mavenj.api.MavenModels.*;
import java.awt.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectControllerTest {
    @TempDir Path directory;

    @Test void importStaysOffEdtAndOlderResultsCannotReplaceANewProject() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.writeString(first.resolve("pom.xml"), "<project/>"); Files.writeString(second.resolve("pom.xml"), "<project/>");
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), published = new CountDownLatch(1);
        AtomicReference<Workspace> current = new AtomicReference<>(); AtomicBoolean importedOnEdt = new AtomicBoolean();
        MavenJ engine = new MavenJ() {
            @Override public Workspace open(ImportRequest request, Cancellation cancellation) {
                importedOnEdt.set(importedOnEdt.get() || SwingUtilities.isEventDispatchThread());
                if (request.project().equals(first)) {
                    firstStarted.countDown();
                    try { releaseFirst.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                return model(request.project());
            }
            @Override public Attachment attachment(ImportRequest request, String moduleId, Coordinates coordinates, String classifier, Cancellation cancellation) { throw new UnsupportedOperationException(); }
            @Override public void close() {}
        };
        var controller = edt(() -> new MavenProjectController(new MavenProjectController.Host() {
            private final JPanel owner = new JPanel();
            @Override public Component owner() { return owner; }
            @Override public Path activeFile() { return null; }
            @Override public Map<Path, String> pomOverlays() { return Map.of(); }
            @Override public void openFile(Path file) {}
            @Override public void published(Workspace value) { current.set(value); if (value.root().equals(second)) published.countDown(); }
            @Override public void notice(String text, boolean error) {}
            @Override public void previewPom(String title, java.util.function.UnaryOperator<String> edit) {}
            @Override public void stateChanged() {}
        }, engine));
        try {
            edt(() -> { controller.open(first); return null; });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            AtomicBoolean responsive = new AtomicBoolean();
            edt(() -> { responsive.set(true); controller.open(second); return null; });
            assertTrue(responsive.get()); assertTrue(published.await(5, TimeUnit.SECONDS));
            releaseFirst.countDown();
            edt(() -> null);
            assertEquals(second, current.get().root());
            assertEquals(second, controller.workspace().root()); assertFalse(importedOnEdt.get());
            assertFalse(controller.trusted()); assertFalse(controller.importRequest().offline());
            assertTrue(controller.importRequest().allowedRepositoryUrls().contains(dev.turboj.mavenj.core.MavenImportDefaults.CENTRAL));
        } finally { releaseFirst.countDown(); edt(() -> { controller.close(); return null; }); }
    }

    @Test void missingModuleDependencyIncludesActualCauseAndOfflineRecoveryInOutput() throws Exception {
        Path project = Files.createDirectory(directory.resolve("missing-dependency"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>dev.turboj.fixture</groupId><artifactId>absent</artifactId><version>1</version></dependency></dependencies></project>
                """);
        var messages = new CopyOnWriteArrayList<String>();
        var finished = new CountDownLatch(1);
        var resolved = new AtomicReference<Workspace>();
        MavenJ engine = new MavenJ() {
            private final MavenJ actual = new dev.turboj.mavenj.core.DefaultMavenJ();
            public Workspace open(ImportRequest request, Cancellation cancellation) {
                return actual.open(new ImportRequest(project, null, null, directory.resolve("empty-repository"),
                        Set.of(), Set.of(), Map.of(), true, Set.of(), false, null, Map.of()), cancellation);
            }
            public Attachment attachment(ImportRequest request, String moduleId, Coordinates coordinates, String classifier, Cancellation cancellation) { throw new UnsupportedOperationException(); }
            public void close() { actual.close(); }
        };
        MavenProjectController controller = edt(() -> new MavenProjectController(new MavenProjectController.Host() {
            public Component owner() { return new JPanel(); }
            public Path activeFile() { return null; }
            public Map<Path, String> pomOverlays() { return Map.of(); }
            public void openFile(Path file) {}
            public void published(Workspace value) { resolved.set(value); }
            public void notice(String text, boolean error) { messages.add(text); if (text.contains("Refresh Dependencies")) finished.countDown(); }
            public void previewPom(String title, java.util.function.UnaryOperator<String> edit) {}
            public void stateChanged() {}
        }, engine));
        try {
            edt(() -> { controller.open(project); return null; });
            assertTrue(finished.await(10, TimeUnit.SECONDS), messages.toString());
            assertFalse(resolved.get().resolved());
            assertTrue(resolved.get().diagnostics().isEmpty(), "Dependency causes belong to module diagnostics");
            String text = String.join("", messages);
            assertTrue(text.contains("dependency.missing"), text);
            assertTrue(text.contains("dev.turboj.fixture:absent"), text);
            assertTrue(text.contains("while Maven is offline"), text);
        } finally { edt(() -> { controller.close(); return null; }); }
    }

    @Test void savedOfflineAndRepositoryPermissionsAreNotSilentlyChanged() throws Exception {
        Path project = Files.createDirectory(directory.resolve("offline-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        String previousConfig = System.getProperty("turboj.config");
        Path configuration = Files.createDirectory(directory.resolve("config"));
        System.setProperty("turboj.config", configuration.toString());
        String key = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(project.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path preferences = Files.createDirectory(configuration.resolve("maven-projects")).resolve(key + ".json");
        Files.writeString(preferences, "{\"offline\":true,\"repositories\":[],\"activeProfiles\":[\"local\"]}");
        var imported = new CompletableFuture<ImportRequest>();
        MavenJ engine = new MavenJ() {
            public Workspace open(ImportRequest request, Cancellation cancellation) { imported.complete(request); return model(project); }
            public Attachment attachment(ImportRequest request, String moduleId, Coordinates coordinates, String classifier, Cancellation cancellation) { throw new UnsupportedOperationException(); }
            public void close() {}
        };
        MavenProjectController controller = null;
        try {
            controller = edt(() -> new MavenProjectController(new MavenProjectController.Host() {
                public Component owner() { return new JPanel(); }
                public Path activeFile() { return null; }
                public Map<Path, String> pomOverlays() { return Map.of(); }
                public void openFile(Path file) {}
                public void published(Workspace value) {}
                public void notice(String text, boolean error) {}
                public void previewPom(String title, java.util.function.UnaryOperator<String> edit) {}
                public void stateChanged() {}
            }, engine));
            MavenProjectController active = controller;
            edt(() -> { active.open(project); return null; });
            ImportRequest request = imported.get(5, TimeUnit.SECONDS);
            assertTrue(request.offline());
            assertTrue(request.allowedRepositoryUrls().isEmpty());
            assertEquals(Set.of("local"), request.activeProfiles());
            assertFalse(controller.trusted());
        } finally {
            if (controller != null) { MavenProjectController active = controller; edt(() -> { active.close(); return null; }); }
            if (previousConfig == null) System.clearProperty("turboj.config"); else System.setProperty("turboj.config", previousConfig);
        }
    }

    private Workspace model(Path root) { return new Workspace(root, root.resolve("pom.xml"), root.getFileName().toString().equals("first") ? 1 : 2,
            List.of(), List.of(), List.of(root.resolve("pom.xml")), root.toString(), directory.resolve("repository"), List.of(), true, List.of(), 1, false); }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
