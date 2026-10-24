package dev.turboj.turbotest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.Gson;
import static org.junit.jupiter.api.Assertions.*;

class TestEngineTest {
    @TempDir Path directory;
    public static class Fixture {
        @Test void passes() { assertEquals(4, 2 + 2); }
        @Test void fails() { fail("expected fixture failure"); }
        @Test @Disabled("fixture") void skipped() {}
    }
    public static class BrokenContainer {
        @BeforeAll static void setup() { throw new IllegalStateException("broken setup"); }
        @Test void neverRuns() {}
    }
    @Test void reportsRealJUnitOutcomesAndVersions() {
        var engine = new TestEngine();
        var request = new TestEngine.Request(42, List.of(Fixture.class.getName()), List.of());
        assertEquals(3, engine.discover(request).size());
        List<TestEngine.Event> events = new ArrayList<>();
        var report = engine.execute(request, events::add);
        assertEquals(42, report.sourceVersion());
        assertEquals(1, report.failed()); assertEquals(1, report.passed());
        assertEquals(TestEngine.Status.FAILED, report.tests().getFirst().status());
        assertTrue(events.stream().allMatch(event -> event.sourceVersion() == 42));
        var rerun = engine.execute(new TestEngine.Request(43, List.of(), report.failedIds()), event -> {});
        assertEquals(1, rerun.tests().size()); assertEquals(1, rerun.failed());
    }
    @Test void containerFailureCannotAppearAsPassingRun() {
        var report = new TestEngine().execute(new TestEngine.Request(1, List.of(BrokenContainer.class.getName()), List.of()), event -> {});
        assertFalse(report.successful()); assertFalse(report.engineFailures().isEmpty());
    }
    @Test void workerEntrypointProducesVersionedPrivateReport() throws Exception {
        var request = new TestEngine.Request(17, List.of(Fixture.class.getName()), List.of());
        Path report = directory.resolve("result.json");
        String encoded = Base64.getEncoder().encodeToString(new Gson().toJson(request).getBytes(StandardCharsets.UTF_8));
        WorkerEntry.main(new String[] {encoded, report.toString()});
        var result = new Gson().fromJson(Files.readString(report), TestEngine.Report.class);
        assertEquals(17, result.sourceVersion()); assertEquals(1, result.failed());
        assertThrows(IllegalArgumentException.class, () -> WorkerEntry.main(new String[] {encoded, report.toString()}));
    }
}
