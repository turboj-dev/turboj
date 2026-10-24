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
            assertFalse(controller.trusted()); assertTrue(controller.importRequest().offline());
            assertTrue(controller.importRequest().allowedRepositoryUrls().isEmpty());
        } finally { releaseFirst.countDown(); edt(() -> { controller.close(); return null; }); }
    }

    private Workspace model(Path root) { return new Workspace(root, root.resolve("pom.xml"), root.getFileName().toString().equals("first") ? 1 : 2,
            List.of(), List.of(), List.of(root.resolve("pom.xml")), root.toString(), directory.resolve("repository"), List.of(), true, List.of(), 1, false); }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
