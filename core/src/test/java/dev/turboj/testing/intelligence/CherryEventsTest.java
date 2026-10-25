package dev.turboj.testing.intelligence;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class CherryEventsTest {
    @TempDir Path root;
    final Gson gson = new Gson();
    String source = "class Price { int price(boolean discount) { return discount ? 80 : 100; } }\n";
    JsonObject event(String kind,int sequence) {
        JsonObject event = new JsonObject(); event.addProperty("schemaVersion",1); event.addProperty("event",kind);
        event.addProperty("runId","run-a"); event.addProperty("sequence",sequence); return event;
    }
    CherryEvents start(boolean coverage) throws Exception {
        Files.writeString(root.resolve("Price.java"),source);
        CherryEvents reader = new CherryEvents(root);
        JsonObject start = event("run.started",0); start.addProperty("workspaceRoot",root.toString());
        start.addProperty("coverageEnabled",coverage); start.add("scope",gson.toJsonTree(List.of("global")));
        start.add("sourceBefore",gson.toJsonTree(Map.of("Price.java",sha256(source))));
        assertTrue(reader.accept(start.toString())); return reader;
    }
    JsonObject phase(int sequence,String runtime,boolean success,List<?> tests) {
        JsonObject value = event("phase.completed",sequence);
        value.addProperty("projectId","shop"); value.addProperty("moduleId","module");
        value.addProperty("packageId","price"); value.addProperty("phaseId","unit"); value.addProperty("runtimeId",runtime);
        value.addProperty("completeness","complete"); value.addProperty("success",success);
        value.add("tests",gson.toJsonTree(tests)); return value;
    }
    void artifact(JsonObject phase,String field,String name,String format,String content) throws Exception {
        Files.writeString(root.resolve(name),content);
        phase.add(field,gson.toJsonTree(List.of(Map.of("path",name,"format",format,"sha256",sha256(content)))));
    }
    void complete(CherryEvents reader,String content,boolean complete,String outcome) throws Exception {
        JsonObject end = event("run.completed",100); end.addProperty("complete",complete); end.addProperty("outcome",outcome);
        end.add("sourceAfter",gson.toJsonTree(Map.of("Price.java",sha256(content))));
        end.addProperty("compiledIdentity","unknown"); reader.accept(end.toString());
    }

    @Test void failedPackageDoesNotCreateProductionFailureAndZeroHitsRemainCoverageOnly() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",false,List.of(Map.of("name","fails","status","failed","durationMs",4)));
        artifact(phase,"coverage","coverage.lcov","lcov","SF:Price.java\nDA:1,0\nend_of_record\n");
        reader.accept(phase.toString()); complete(reader,source,true,"failed");
        var run = reader.snapshot();
        assertEquals(RunState.FAILED,run.state()); assertEquals(1,run.tests().size());
        assertTrue(run.tests().getFirst().associations().isEmpty());
        assertEquals(CoverageState.UNCOVERED,run.coverage().get(root.resolve("Price.java")).get(1).state());
        assertEquals(Freshness.UNKNOWN,run.coverage().get(root.resolve("Price.java")).get(1).freshness());
    }
    @Test void infrastructureFailureIsNotSynthesizedAsATest() throws Exception {
        var reader = start(false); reader.accept(phase(1,"http",false,List.of()).toString()); complete(reader,source,true,"failed");
        assertTrue(reader.snapshot().tests().isEmpty()); assertEquals(Availability.DISABLED,reader.snapshot().coverageAvailability());
    }
    @Test void missingCoverageIsNotMeasuredZero() throws Exception {
        var reader = start(true); complete(reader,source,true,"passed");
        assertTrue(reader.snapshot().coverage().isEmpty()); assertEquals(Availability.MISSING,reader.snapshot().coverageAvailability());
    }
    @Test void partialRequiresActualInstructionOrBranchCounters() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",true,List.of());
        String sidecar = gson.toJson(Map.of("schemaVersion",1,"format","jacoco-lines-v1","files",List.of(Map.of(
                "source","Price.java","lines",List.of(Map.of("line",1,"ci",7,"mi",2,"cb",1,"mb",1))))));
        artifact(phase,"coveragePrecisionArtifacts","coverage.native.json","jacoco-lines-v1",sidecar);
        reader.accept(phase.toString());
        Coverage value = reader.snapshot().coverage().get(root.resolve("Price.java")).get(1);
        assertEquals(CoverageState.PARTIAL,value.state()); assertEquals(Precision.INSTRUCTIONS_AND_BRANCHES,value.precision());
        assertEquals(7,value.coveredInstructions()); assertEquals(2,value.missedInstructions());
        assertEquals(1,value.coveredBranches()); assertEquals(1,value.missedBranches());
        assertEquals(Freshness.UNKNOWN,value.freshness(),"XML counters cannot prove bytecode/source identity");
    }
    @Test void binaryLcovNeverInventsPartial() throws Exception {
        var reader = start(true); var phase = phase(1,"rust",true,List.of());
        artifact(phase,"coverage","coverage.lcov","lcov","SF:Price.java\nDA:1,1\nBRDA:1,0,0,1\nBRDA:1,0,1,0\n");
        reader.accept(phase.toString()); var value = reader.snapshot().coverage().get(root.resolve("Price.java")).get(1);
        assertEquals(Precision.BINARY_LINE,value.precision()); assertEquals(CoverageState.COVERED,value.state());
        assertEquals(-1,value.coveredInstructions());
    }
    @Test void incompleteCollectionCannotClaimUncovered() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",false,List.of()); phase.addProperty("completeness","cancelled");
        artifact(phase,"coverage","coverage.lcov","lcov","SF:Price.java\nDA:1,0\n"); reader.accept(phase.toString());
        var value = reader.snapshot().coverage().get(root.resolve("Price.java")).get(1);
        assertEquals(Availability.INCOMPLETE,value.availability()); assertEquals(CoverageState.UNKNOWN,value.state());
    }
    @Test void variantsKeepIndependentTestIdentities() throws Exception {
        var reader = start(true);
        for (var variant : List.of("spring","quarkus")) {
            var phase = phase(variant.equals("spring") ? 1 : 2,variant,true,List.of(Map.of("id","same-test","name","contract","status","passed")));
            reader.accept(phase.toString());
        }
        assertEquals(2,reader.snapshot().tests().size());
        assertEquals(2,reader.snapshot().tests().stream().map(TestResult::id).distinct().count());
    }
    @Test void manifestIdentityKeepsIdenticalLabelsAndTestIdsFromOverwritingTestsOrCoverage() throws Exception {
        var reader = start(true);
        for (int i = 1; i <= 2; i++) {
            var phase = phase(i,"maven",i == 1,List.of(Map.of("id","same-test","name","contract","status",i == 1 ? "passed" : "failed")));
            phase.addProperty("phaseKey",sha256("full manifest configuration " + i));
            artifact(phase,"coverage","manifest-" + i + ".lcov","lcov","SF:Price.java\nDA:1," + (i == 1 ? 1 : 0) + "\n");
            reader.accept(phase.toString());
        }
        var snapshot = reader.snapshot();
        assertEquals(List.of(TestStatus.PASSED,TestStatus.FAILED),snapshot.tests().stream().map(TestResult::status).toList());
        assertEquals(2,snapshot.tests().stream().map(TestResult::id).distinct().count());
        assertEquals(2,snapshot.tests().stream().map(TestResult::invocation).distinct().count());
        assertEquals(CoverageState.COVERED,snapshot.coverage().get(root.resolve("Price.java")).get(1).state(),
                "The first manifest's line hits must survive the second manifest's zero-hit collection");
        assertTrue(snapshot.message().contains("shop/module/price/unit/maven"),"Display text retains readable labels");
        assertFalse(snapshot.message().contains("manifest:"));
    }
    @Test void emittedManifestIdentityStillSeparatesRuntimesAndNormalizesHashCase() throws Exception {
        var reader = start(false); String key = sha256("manifest");
        var first = phase(1,"spring",true,List.of(Map.of("id","same","name","contract","status","passed")));
        first.addProperty("phaseKey",key.toUpperCase(Locale.ROOT)); reader.accept(first.toString());
        var replacement = phase(2,"spring",false,List.of(Map.of("id","same","name","contract","status","failed")));
        replacement.addProperty("phaseKey",key); reader.accept(replacement.toString());
        var variant = phase(3,"quarkus",true,List.of(Map.of("id","same","name","contract","status","passed")));
        variant.addProperty("phaseKey",key); reader.accept(variant.toString());
        assertEquals(List.of(TestStatus.FAILED,TestStatus.PASSED),reader.snapshot().tests().stream().map(TestResult::status).toList());
        assertEquals(2,reader.snapshot().tests().stream().map(TestResult::id).distinct().count());
    }
    @Test void malformedEmittedIdentityCannotSilentlyFallBackToDisplayLabels() throws Exception {
        int sequence = 1; var reader = start(false);
        for (JsonElement key : List.of(JsonNull.INSTANCE,new JsonPrimitive(""),new JsonPrimitive("a".repeat(63)),
                new JsonPrimitive("g".repeat(64)),new JsonPrimitive(42),new JsonArray())) {
            var phase = phase(sequence++,"maven",true,List.of()); phase.add("phaseKey",key);
            assertThrows(java.io.IOException.class,() -> reader.accept(phase.toString()));
        }
        var noRuntime = phase(sequence,"",true,List.of()); noRuntime.addProperty("phaseKey",sha256("manifest"));
        assertThrows(java.io.IOException.class,() -> reader.accept(noRuntime.toString()));
        assertTrue(reader.snapshot().tests().isEmpty());
    }
    @Test void historicalIdentityTupleDoesNotCollideWhenLabelsContainSlashes() throws Exception {
        var reader = start(false);
        var first = phase(1,"maven",true,List.of(Map.of("name","first","status","passed")));
        first.addProperty("projectId","a/b"); first.addProperty("moduleId","c");
        var second = phase(2,"maven",true,List.of(Map.of("name","second","status","passed")));
        second.addProperty("projectId","a"); second.addProperty("moduleId","b/c");
        reader.accept(first.toString()); reader.accept(second.toString());
        assertEquals(2,reader.snapshot().tests().size());
        assertEquals(2,reader.snapshot().tests().stream().map(TestResult::invocation).distinct().count());
    }
    @Test void sourceLocationsRemainTestLocationsNotProductionExecution() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",false,List.of(Map.of("name","test","status","failed","source","PriceTest.java","line",5)));
        reader.accept(phase.toString()); var association = reader.snapshot().tests().getFirst().associations().getFirst();
        assertEquals(root.resolve("PriceTest.java"),association.file()); assertEquals(Attribution.REPORTED_TEST_LOCATION,association.kind());
        assertEquals(Freshness.UNKNOWN,reader.snapshot().tests().getFirst().freshness());
    }
    @Test void changedSourceIsStaleAndUndoDoesNotCertifyBytecode() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",true,List.of(Map.of("name","test","status","passed","source","Price.java","line",1)));
        artifact(phase,"coverage","coverage.lcov","lcov","SF:Price.java\nDA:1,1\n"); reader.accept(phase.toString());
        complete(reader,source + "// edit\n",true,"passed");
        assertEquals(Freshness.STALE,reader.snapshot().tests().getFirst().freshness());
        assertEquals(Freshness.STALE,reader.snapshot().coverage().get(root.resolve("Price.java")).get(1).freshness());
    }
    @Test void lateDuplicateAndMixedRunsCannotOverwriteCurrentRun() throws Exception {
        var reader = start(false); JsonObject phase = phase(2,"maven",true,List.of()); assertTrue(reader.accept(phase.toString()));
        phase.addProperty("sequence",1); assertFalse(reader.accept(phase.toString()));
        phase.addProperty("sequence",3); phase.addProperty("runId","other"); assertThrows(java.io.IOException.class,() -> reader.accept(phase.toString()));
        complete(reader,source,true,"passed"); phase.addProperty("runId","run-a"); phase.addProperty("sequence",101);
        assertFalse(reader.accept(phase.toString())); assertEquals(RunState.COMPLETED,reader.snapshot().state());
    }
    @Test void futureSchemaRejectedAndCorruptedArtifactNotTrusted() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",true,List.of()); phase.addProperty("schemaVersion",2);
        assertThrows(java.io.IOException.class,() -> reader.accept(phase.toString()));
        phase.addProperty("schemaVersion",1); artifact(phase,"coverage","coverage.lcov","lcov","SF:Price.java\nDA:1,0\n");
        Files.writeString(root.resolve("coverage.lcov"),"changed");
        assertThrows(java.io.IOException.class,() -> reader.accept(phase.toString()));
    }
    @Test void unavailableCountersCannotBecomePartialAndOutsidePathsAreNotImported() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new Coverage(CoverageState.PARTIAL,Freshness.CURRENT,
                Availability.AVAILABLE,Precision.BINARY_LINE,-1,-1,-1,-1,"","","","","","",""));
        var reader = start(true); var phase = phase(1,"maven",true,List.of(Map.of("name","test","status","passed","source","../Outside.java","line",1)));
        reader.accept(phase.toString()); assertTrue(reader.snapshot().tests().getFirst().associations().isEmpty());
    }
    @Test void cancellationCannotBeReportedAsCompleteSuccess() throws Exception {
        var reader = start(true); complete(reader,source,false,"cancelled"); assertEquals(RunState.CANCELLED,reader.snapshot().state());
        assertEquals(RunState.INCOMPLETE,start(true).interrupted(false,"missing terminal").state());
    }
    @Test void aggregationPreservesFailurePendingAndUnknownIndependentlyOfCoverage() {
        TestResult passed = test("pass",TestStatus.PASSED,Freshness.CURRENT), failed = test("fail",TestStatus.FAILED,Freshness.CURRENT), pending = test("pending",TestStatus.PENDING,Freshness.CURRENT);
        Coverage covered = new Coverage(CoverageState.COVERED,Freshness.CURRENT,Availability.AVAILABLE,Precision.BINARY_LINE,-1,-1,-1,-1,"","","","","","","");
        assertEquals(TestStatus.FAILED,new Line(true,List.of(passed,failed,pending),covered).testStatus());
        assertEquals(TestStatus.PENDING,new Line(true,List.of(passed,pending),covered).testStatus());
        assertEquals(TestStatus.UNKNOWN,new Line(true,List.of(passed.withFreshness(Freshness.STALE)),covered).testStatus());
        assertEquals(TestStatus.UNKNOWN,new Line(true,List.of(),covered).testStatus());
    }
    @Test void overlappingBinaryArtifactsUnionWithoutOrderDependentLoss() throws Exception {
        for (boolean reverse : List.of(false,true)) {
            var reader = start(true); var phase = phase(1,"rust",true,List.of());
            JsonArray artifacts = new JsonArray();
            for (int hit : reverse ? List.of(0,1) : List.of(1,0)) {
                String data = "SF:Price.java\nDA:1," + hit + "\n";
                String name = "part" + hit + ".lcov"; Files.writeString(root.resolve(name),data);
                artifacts.add(gson.toJsonTree(Map.of("path",name,"format","lcov","sha256",sha256(data))));
            }
            phase.add("coverage",artifacts); reader.accept(phase.toString());
            assertEquals(CoverageState.COVERED,reader.snapshot().coverage().get(root.resolve("Price.java")).get(1).state());
            assertEquals(Availability.AVAILABLE,reader.snapshot().coverageAvailability());
        }
    }
    @Test void equalCounterTotalsFromSeparateCollectionsDoNotProveEqualProbes() throws Exception {
        var reader = start(true); var phase = phase(1,"maven",true,List.of()); JsonArray artifacts = new JsonArray();
        String data = gson.toJson(Map.of("schemaVersion",1,"format","jacoco-lines-v1","files",List.of(Map.of(
                "source","Price.java","lines",List.of(Map.of("line",1,"ci",5,"mi",5,"cb",1,"mb",1))))));
        for (String name : List.of("jvm-a.native.json","jvm-b.native.json")) {
            Files.writeString(root.resolve(name),data);
            artifacts.add(gson.toJsonTree(Map.of("path",name,"format","jacoco-lines-v1","sha256",sha256(data))));
        }
        phase.add("coveragePrecisionArtifacts",artifacts); reader.accept(phase.toString());
        Coverage result = reader.snapshot().coverage().get(root.resolve("Price.java")).get(1);
        assertEquals(CoverageState.UNKNOWN,result.state()); assertTrue(result.reason().contains("probe merging"));
    }
    TestResult test(String id,TestStatus status,Freshness freshness) {
        return new TestResult(id,"run","suite",id,"project","module","package","unit","jvm","1",status,freshness,3,"",List.of());
    }
}
