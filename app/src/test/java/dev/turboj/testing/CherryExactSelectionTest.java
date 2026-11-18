package dev.turboj.testing;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class CherryExactSelectionTest {
    @TempDir Path root;
    TestSelector selector(String runtime) {
        return new TestSelector("junit-method","demo.PriceTest::discount",sha256("manifest"),runtime,"maven",root.resolve("pom.xml"),true,"");
    }
    @Test void requestTransportsTypedSelectorsAsDataWithoutAddingThemToCommandArguments() {
        TestSelector selected = selector("jvm");
        var request = new CherryTestProcess.Request(root,"cherry-test",root.resolve("events"),List.of(),"configured",List.of(selected,selected));
        String encoded = CherryTestProcess.selection(request.selectedTests());
        var json = JsonParser.parseString(encoded).getAsJsonObject();
        assertEquals(1,json.getAsJsonArray("selectors").size());
        assertEquals("demo.PriceTest::discount",json.getAsJsonArray("selectors").get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(CherryTestProcess.command(request).contains(selected.value()));
        assertEquals(2,JsonParser.parseString(CherryTestProcess.selection(List.of(selected,selector("another-runtime")))).getAsJsonObject().getAsJsonArray("selectors").size());
    }
    @Test void anOlderCliCannotSilentlyTurnAnExactRerunIntoACompleteSuite() throws Exception {
        var request = new CherryTestProcess.Request(root,"cherry-test",root.resolve("events"),List.of(),"configured",List.of(selector("jvm")));
        Run old = new Run("run",root,RunState.COMPLETED,List.of(),Map.of(),Map.of(),"passed",null,1);
        assertThrows(java.io.IOException.class,() -> CherryTestProcess.verifySelection(request,old));
        var context = new ExecutionContext(List.of(),Map.of("selectionSHA256",sha256(CherryTestProcess.selection(request.selectedTests()))));
        Run exact = new Run("run",root,RunState.COMPLETED,List.of(),Map.of(),Map.of(),"passed",null,1,Availability.UNKNOWN,List.of(),context);
        assertDoesNotThrow(() -> CherryTestProcess.verifySelection(request,exact));
        var different = new CherryTestProcess.Request(root,"cherry-test",root.resolve("events"),List.of(),"configured",List.of(selector("other")));
        assertThrows(java.io.IOException.class,() -> CherryTestProcess.verifySelection(different,exact));
    }
    @Test void unsupportedSelectorsExplainTheBroaderFallbackAndMissingIdentityIsRejected() {
        TestResult test = new TestResult("id","run","suite","test","p","m","pkg","unit","runtime","invocation",TestStatus.FAILED,Freshness.UNKNOWN,0,"",List.of());
        assertTrue(LineIntelligenceDialogs.selectionProblem(List.of(test)).contains("capability is unknown"));
        assertThrows(IllegalArgumentException.class,() -> new TestSelector("junit-method","method","bad","runtime","maven",root.resolve("pom.xml"),true,""));
        assertThrows(IllegalArgumentException.class,() -> new CherryTestProcess.Request(root,"cherry-test",root.resolve("events"),List.of(),"configured",List.of(new TestSelector("","","","","",null,false,"unsupported"))));
    }
    @Test void outsideRepositoryMetadataIsTrustedOnlyWhenItsRetainedStreamMatches() throws Exception {
        Path events = Files.writeString(root.resolve("events.jsonl"),"actual retained stream\n");
        Run run = new Run("run",root,RunState.COMPLETED,List.of(),Map.of(),Map.of(),"complete",null,1);
        assertFalse(CherryExecutionReceipt.matchesRetainedReceipt(events));
        CherryExecutionReceipt.write(events,run); assertTrue(CherryExecutionReceipt.matchesRetainedReceipt(events));
        Files.writeString(events,"changed\n",StandardOpenOption.APPEND);
        assertFalse(CherryExecutionReceipt.matchesRetainedReceipt(events));
    }
}
