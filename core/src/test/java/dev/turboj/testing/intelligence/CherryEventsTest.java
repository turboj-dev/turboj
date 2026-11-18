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

    JsonObject provenance() throws Exception {
        Files.writeString(root.resolve("Price.class"),"compiled fixture");
        JsonObject proof = new JsonObject(); proof.addProperty("schemaVersion",1); proof.addProperty("status","verified");
        proof.addProperty("sourceHash",sha256(source)); proof.addProperty("classIdentity",sha256("class identity"));
        String collection = "11111111-1111-4111-8111-111111111111";
        proof.addProperty("compilationId","22222222-2222-4222-8222-222222222222");
        proof.addProperty("compilerIdentity","javac fixture"); proof.addProperty("collectionId",collection);
        proof.addProperty("processId","123@2026-11-15T10:00:00Z");
        proof.addProperty("sessionId","cherry:" + collection + ":33333333-3333-4333-8333-333333333333");
        proof.addProperty("executionHashes",sha256("exec fixture"));
        proof.add("classFiles",gson.toJsonTree(List.of(Map.of("path","Price.class","sha256",sha256("compiled fixture")))));
        return proof;
    }
    void precisePhase(CherryEvents reader,JsonObject proof) throws Exception {
        JsonObject phase = phase(1,"maven",true,List.of());
        JsonObject file = new JsonObject(); file.addProperty("source","Price.java"); file.add("provenance",proof);
        file.add("lines",gson.toJsonTree(List.of(Map.of("line",1,"ci",7,"mi",2,"cb",1,"mb",1))));
        JsonObject report = new JsonObject(); report.addProperty("schemaVersion",1); report.addProperty("format","jacoco-lines-v1");
        JsonArray files = new JsonArray(); files.add(file); report.add("files",files);
        artifact(phase,"coveragePrecisionArtifacts","proof.native.json","jacoco-lines-v1",report.toString());
        reader.accept(phase.toString());
    }
    Coverage first(CherryEvents reader) { return reader.snapshot().coverage().get(root.resolve("Price.java")).get(1); }

    @Test void providerProbeUnionReplacesConflictingPhaseCountersOnlyAtCompletion() throws Exception {
        var reader = start(true); JsonObject proof = provenance(); precisePhase(reader,proof);
        JsonObject secondPhase = phase(2,"server",true,List.of());
        artifact(secondPhase,"coverage","server.lcov","lcov","SF:Price.java\nDA:1,1\n");
        reader.accept(secondPhase.toString());
        assertEquals(Freshness.UNKNOWN,first(reader).freshness());
        proof.add("collectionIds",gson.toJsonTree(List.of(proof.get("collectionId").getAsString())));
        proof.addProperty("collectionId","44444444-4444-4444-8444-444444444444");
        var union = gson.toJsonTree(Map.of("schemaVersion",1,"format","jacoco-lines-v1","files",List.of(Map.of(
                "source","Price.java","provenance",proof,"lines",List.of(Map.of("line",1,"ci",9,"mi",0,"cb",2,"mb",0)))))).getAsJsonObject();
        JsonObject end = event("run.completed",100); end.addProperty("complete",true); end.addProperty("outcome","passed");
        end.add("sourceAfter",gson.toJsonTree(Map.of("Price.java",sha256(source))));
        artifact(end,"coverageUnion","union.json","jacoco-lines-v1",union.toString());
        reader.accept(end.toString());
        assertEquals(Freshness.CURRENT,first(reader).freshness());
        assertEquals(9,first(reader).coveredInstructions());
        assertEquals(2,first(reader).coveredBranches());
        assertEquals(CoverageState.COVERED,first(reader).state());
        assertEquals(Freshness.UNKNOWN,reader.interrupted(true,"cancelled").coverage().get(root.resolve("Price.java")).get(1).freshness());
        proof.add("collectionIds",gson.toJsonTree(List.of("55555555-5555-4555-8555-555555555555")));
        assertThrows(java.io.IOException.class,() -> CoverageProof.read(proof,root));
    }

    @Test void verifiedProvenanceRequiresCompletedRunAndMatchingSourcesAndClasses() throws Exception {
        var reader = start(true); precisePhase(reader,provenance());
        assertEquals(Freshness.UNKNOWN,first(reader).freshness(),"No current coverage while collection is running");
        complete(reader,source,true,"passed");
        assertEquals(Freshness.CURRENT,first(reader).freshness());
        assertEquals(CoverageState.PARTIAL,first(reader).state());
        assertNotNull(first(reader).proof());
        assertEquals("123@2026-11-15T10:00:00Z",first(reader).processId());
        assertTrue(reader.snapshot().tests().isEmpty(),"Coverage does not invent passing per-line tests");
    }
    @Test void failingTestsCanHaveVerifiedCurrentCoverageWithoutInventingAttribution() throws Exception {
        var reader = start(true); precisePhase(reader,provenance()); complete(reader,source,true,"failed");
        assertEquals(Freshness.CURRENT,first(reader).freshness());
        var incomplete = reader.interrupted(false,"CLI failed after report");
        assertEquals(Freshness.UNKNOWN,incomplete.coverage().get(root.resolve("Price.java")).get(1).freshness());
    }
    @Test void changedSourceAndMismatchedBytecodeCannotUseCurrentDecorations() throws Exception {
        var reader = start(true); precisePhase(reader,provenance()); complete(reader,source + " ",true,"passed");
        assertEquals(Freshness.STALE,first(reader).freshness());
        reader = start(true); var proof = provenance(); Files.writeString(root.resolve("Price.class"),"different build");
        precisePhase(reader,proof); complete(reader,source,true,"passed");
        assertEquals(Availability.BYTECODE_MISMATCH,first(reader).availability());
        assertEquals(CoverageState.UNKNOWN,first(reader).state());
    }
    @Test void malformedProvenanceCannotUpgradeLegacyCounters() throws Exception {
        var reader = start(true); var proof = provenance(); proof.remove("schemaVersion");
        assertThrows(java.io.IOException.class,() -> precisePhase(reader,proof));
        var invalidReader = start(true); var invalidProof = provenance(); invalidProof.addProperty("sourceHash","bad");
        assertThrows(java.io.IOException.class,() -> precisePhase(invalidReader,invalidProof));
        var nullReader = start(true);
        assertThrows(java.io.IOException.class,() -> precisePhase(nullReader,null));
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
    @Test void realProgressRequiresStableIdentityAndIncompletePendingCannotBecomeCompleteSuccess() throws Exception {
        var reader = start(false);
        JsonObject pending = phase(1,"junit",true,List.of()); pending.addProperty("event","test.started");
        pending.add("test",gson.toJsonTree(Map.of("id","one","name","PriceTest::discount")));
        reader.accept(pending.toString());
        assertEquals(TestStatus.PENDING,reader.snapshot().tests().getFirst().status());
        JsonObject done = pending.deepCopy(); done.addProperty("event","test.completed"); done.addProperty("sequence",2);
        done.getAsJsonObject("test").addProperty("status","failed"); reader.accept(done.toString());
        assertEquals(1,reader.snapshot().tests().size()); assertEquals(TestStatus.FAILED,reader.snapshot().tests().getFirst().status());
        pending.addProperty("sequence",3); pending.getAsJsonObject("test").addProperty("id","two"); reader.accept(pending.toString());
        complete(reader,source,true,"passed");
        assertEquals(RunState.INCOMPLETE,reader.snapshot().state());
        assertEquals(1,reader.snapshot().tests().stream().filter(test -> test.status() == TestStatus.PENDING).count());
    }
    @Test void concurrentPhaseProgressDoesNotInventPendingTestsOrHideAnotherRunningPhase() throws Exception {
        var reader = start(false);
        var one = phase(1,"spring",true,List.of()); one.addProperty("event","phase.started"); reader.accept(one.toString());
        var two = phase(2,"quarkus",true,List.of()); two.addProperty("event","phase.started"); reader.accept(two.toString());
        reader.accept(phase(3,"spring",true,List.of()).toString());
        var progress = reader.snapshot().context().phases();
        assertEquals(2,progress.size());
        assertEquals(1,progress.values().stream().filter(phase -> phase.state() == RunState.RUNNING).count());
        assertEquals(1,progress.values().stream().filter(phase -> phase.state() == RunState.COMPLETED).count());
        assertTrue(reader.snapshot().tests().isEmpty(),"Phase execution is not an invented test");
        assertTrue(progress.values().stream().allMatch(phase -> phase.inventoryCompleteness().equals("unknown")));
        complete(reader,source,true,"passed");
        assertEquals(RunState.INCOMPLETE,reader.snapshot().state());
        assertTrue(reader.snapshot().context().phases().values().stream().noneMatch(phase -> phase.state() == RunState.RUNNING));
    }
    @Test void finalPhaseReplacesMatchingProgressAndSelectorsRetainExactInvocation() throws Exception {
        var reader = start(false); JsonObject pending = phase(1,"junit",true,List.of());
        pending.addProperty("event","test.started"); pending.addProperty("phaseKey",sha256("manifest"));
        var selector = Map.of("kind","junit-method","value","demo.PriceTest#discount","phaseKey",sha256("manifest"),
                "runtimeId","junit","adapterId","maven","manifest","cherry-test.yaml","exact",true);
        pending.add("test",gson.toJsonTree(Map.of("id","one","name","demo.PriceTest::discount","selector",selector)));
        reader.accept(pending.toString());
        var complete = phase(2,"junit",true,List.of(Map.of("id","one","name","demo.PriceTest::discount","status","passed","selector",selector)));
        complete.addProperty("phaseKey",sha256("manifest")); reader.accept(complete.toString()); complete(reader,source,true,"passed");
        var test = reader.snapshot().tests().getFirst();
        assertEquals(1,reader.snapshot().tests().size()); assertEquals(TestStatus.PASSED,test.status());
        assertEquals("demo.PriceTest#discount",test.selector().value()); assertTrue(test.withFreshness(Freshness.STALE).selector().exact());
        assertEquals(root.resolve("cherry-test.yaml"),test.selector().manifest());
    }
    @Test void externalRepositoriesRequireExplicitTrustAndKeepSourcePathsDistinct() throws Exception {
        Path other = Files.createDirectory(root.resolveSibling(root.getFileName() + "-repository"));
        try {
            Files.writeString(other.resolve("Price.java"),source);
            JsonObject started = event("run.started",0); started.addProperty("workspaceRoot",root.toString()); started.addProperty("coverageEnabled",true);
            started.add("repositories",gson.toJsonTree(List.of(Map.of("id","other-worktree","root",other.toString(),"worktree","other"))));
            started.add("sourceBefore",gson.toJsonTree(Map.of(other.resolve("Price.java").toString(),sha256(source))));
            var untrusted = new CherryEvents(root); untrusted.accept(started.toString());
            assertTrue(untrusted.snapshot().sourceHashes().isEmpty()); assertTrue(untrusted.snapshot().context().repositories().isEmpty());
            var outside = phase(1,"junit",true,List.of(Map.of("id","outside","name","test","status","passed","selector",Map.of(
                    "kind","junit-method","value","T::test","phaseKey",sha256("outside"),"runtimeId","junit","adapterId","maven",
                    "manifest",other.resolve("pom.xml").toString(),"exact",true))));
            outside.addProperty("phaseKey",sha256("outside")); untrusted.accept(outside.toString());
            assertFalse(untrusted.snapshot().tests().getFirst().selector().exact());
            assertTrue(untrusted.snapshot().tests().getFirst().selector().reason().contains("trusted source roots"));
            var trusted = new CherryEvents(root,true); trusted.accept(started.toString());
            JsonObject phase = phase(1,"junit",true,List.of(Map.of("id","one","name","test","status","passed","source",other.resolve("Price.java").toString(),"line",1)));
            phase.addProperty("moduleRoot",other.toString()); phase.addProperty("repositoryId","other-worktree");
            artifact(phase,"coverage","external.lcov","lcov","SF:" + other.resolve("Price.java") + "\nDA:1,1\n");
            trusted.accept(phase.toString());
            assertEquals(other,trusted.snapshot().tests().getFirst().moduleRoot());
            assertEquals("other-worktree",trusted.snapshot().tests().getFirst().repositoryId());
            assertTrue(trusted.snapshot().coverage().containsKey(other.resolve("Price.java")));
            Path escape = root.resolve("link"); Files.createSymbolicLink(escape,other);
            var escaping = phase(2,"junit",true,List.of());
            artifact(escaping,"coverage","escape.lcov","lcov","SF:link/Price.java\nDA:1,1\n");
            untrusted.accept(escaping.toString()); assertTrue(untrusted.snapshot().coverage().isEmpty(),"Symlinks do not expand trusted source roots");
        } finally { Files.deleteIfExists(other.resolve("Price.java")); Files.deleteIfExists(other); }
    }
    @Test void aRunCannotReplaceItsTrustedManifestOrSelectionAcknowledgementAfterStarting() throws Exception {
        var reader = start(false);
        JsonObject repeat = event("run.started",1); repeat.addProperty("workspaceRoot",root.toString());
        repeat.addProperty("selectionSHA256",sha256("another selection"));
        assertThrows(java.io.IOException.class,() -> reader.accept(repeat.toString()));
        assertEquals(0,reader.snapshot().sequence());
    }
    @Test void individualCollectionsSurviveConflictingCountersAndKeepRuntimeAndArtifactIdentities() throws Exception {
        var reader = start(true);
        for (int index = 1; index <= 2; index++) {
            var phase = phase(index,index == 1 ? "spring" : "quarkus",true,List.of());
            String json = gson.toJson(Map.of("schemaVersion",1,"format","jacoco-lines-v1","files",List.of(Map.of(
                    "source","Price.java","lines",List.of(Map.of("line",1,"ci",index,"mi",3-index,"cb",index,"mb",2-index))))));
            artifact(phase,"coveragePrecisionArtifacts","collection-" + index + ".native.json","jacoco-lines-v1",json);
            reader.accept(phase.toString());
        }
        complete(reader,source,true,"passed");
        var run = reader.snapshot();
        assertEquals(CoverageState.UNKNOWN,first(reader).state(),"Counters cannot be summed to fabricate a provider union");
        assertEquals(2,run.collections().size());
        assertEquals(List.of("spring","quarkus"),run.collections().stream().map(CoverageObservation::runtime).toList());
        assertEquals(2,run.collections().stream().map(CoverageObservation::id).distinct().count());
        assertTrue(run.collections().stream().allMatch(collection -> Files.isRegularFile(collection.artifact())),"Detail artifact paths reference the real precision file");
        assertEquals(1,run.collections().getFirst().coverage().get(root.resolve("Price.java")).get(1).coveredInstructions());
        assertEquals(2,run.collections().getLast().coverage().get(root.resolve("Price.java")).get(1).coveredInstructions());
        assertThrows(UnsupportedOperationException.class,() -> run.collections().clear());
        assertThrows(UnsupportedOperationException.class,() -> run.collections().getFirst().coverage().clear());
        assertEquals(2,reader.interrupted(true,"cancelled").collections().size());
    }
    @Test void collectionProofRemainsNeutralUntilCompletionAndStaleWhenSourceChanges() throws Exception {
        var reader = start(true); precisePhase(reader,provenance());
        assertEquals(Freshness.UNKNOWN,reader.snapshot().collections().getFirst().coverage().get(root.resolve("Price.java")).get(1).freshness());
        complete(reader,source,true,"passed");
        assertEquals(Freshness.CURRENT,reader.snapshot().collections().getFirst().coverage().get(root.resolve("Price.java")).get(1).freshness());
        assertEquals(Freshness.UNKNOWN,reader.interrupted(true,"cancelled").collections().getFirst().coverage().get(root.resolve("Price.java")).get(1).freshness());
        var stale = start(true); precisePhase(stale,provenance()); complete(stale,source + " ",true,"passed");
        assertEquals(Freshness.STALE,stale.snapshot().collections().getFirst().coverage().get(root.resolve("Price.java")).get(1).freshness());
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
