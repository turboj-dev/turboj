package dev.turboj.testing;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class CherryExecutionReceiptTest {
    @TempDir Path directory;

    @Test void verifiedTerminalSuccessSurvivesReloadWithTheActualEvidence() throws Exception {
        Path events = events(); Run reported = reported(RunState.COMPLETED);
        CherryExecutionReceipt.write(events,reported);
        assertEquals(reported,CherryExecutionReceipt.restore(events,reported));
        try (var files = Files.list(directory)) { assertEquals(2,files.count(),"Atomic temporary receipt is cleaned up."); }
    }

    @Test void rejectedSuccessCancellationAndFailureRemainRejectedAfterReload() throws Exception {
        Path events = events(); Run reported = reported(RunState.COMPLETED);
        for (RunState state : List.of(RunState.INCOMPLETE,RunState.CANCELLED,RunState.FAILED)) {
            Run observed = outcome(reported,state,"Observed " + state);
            CherryExecutionReceipt.write(events,observed);
            Run restored = CherryExecutionReceipt.restore(events,reported);
            assertEquals(observed,restored);
            assertEquals(1,restored.tests().size());
            assertFalse(restored.coverage().isEmpty());
        }
    }

    @Test void missingCorruptChangedOrForeignReceiptsFailClosedWithoutDroppingResults() throws Exception {
        Path events = events(); Run reported = reported(RunState.COMPLETED);
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        Files.writeString(receipt(events),"not JSON");
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        CherryExecutionReceipt.write(events,reported);
        Files.writeString(events,"changed after completion\n",StandardOpenOption.APPEND);
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        CherryExecutionReceipt.write(events,reported);
        JsonObject value = JsonParser.parseString(Files.readString(receipt(events))).getAsJsonObject();
        value.addProperty("runId","another-run"); Files.writeString(receipt(events),value.toString());
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
    }

    @Test void boundedStrictReceiptCannotUpgradeAReportedFailureOrPendingExecution() throws Exception {
        Path events = events(); Run reported = reported(RunState.COMPLETED);
        CherryExecutionReceipt.write(events,reported);
        assertIncomplete(CherryExecutionReceipt.restore(events,outcome(reported,RunState.FAILED,"Reported failure")));
        JsonObject valid = JsonParser.parseString(Files.readString(receipt(events))).getAsJsonObject();
        for (String state : List.of("RUNNING","PENDING","NOT_A_STATE")) {
            JsonObject changed = valid.deepCopy(); changed.addProperty("state",state);
            Files.writeString(receipt(events),changed.toString());
            assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        }
        JsonObject changed = valid.deepCopy(); changed.addProperty("schemaVersion",1.5);
        Files.writeString(receipt(events),changed.toString());
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        Files.writeString(receipt(events)," ".repeat(65537));
        assertIncomplete(CherryExecutionReceipt.restore(events,reported));
        assertThrows(java.io.IOException.class,() -> CherryExecutionReceipt.write(events,outcome(reported,RunState.RUNNING,"Pending")));
    }

    private Path events() throws Exception { return Files.writeString(directory.resolve("events.jsonl"),"fixture event stream\n"); }
    private static Path receipt(Path events) { return events.resolveSibling(events.getFileName() + ".completion.json"); }
    private Run reported(RunState state) {
        Path file = directory.resolve("Price.java");
        var test = new TestResult("test","run","suite","price","project","module","package","unit","maven","invocation",
                TestStatus.PASSED,Freshness.UNKNOWN,1,"",List.of());
        return new Run("run",directory,state,List.of(test),Map.of(file,Map.of(2,Coverage.unknown(Availability.UNKNOWN,"Raw measurement"))),
                Map.of(file,sha256("source")),"Reported success",directory.resolve("report.html"),3,Availability.UNKNOWN);
    }
    private static Run outcome(Run run,RunState state,String message) {
        return new Run(run.id(),run.root(),state,run.tests(),run.coverage(),run.sourceHashes(),message,run.htmlReport(),run.sequence(),run.coverageAvailability());
    }
    private static void assertIncomplete(Run restored) {
        assertEquals(RunState.INCOMPLETE,restored.state());
        assertTrue(restored.message().contains("unverified"));
        assertEquals(1,restored.tests().size());
        assertFalse(restored.coverage().isEmpty());
    }
}
