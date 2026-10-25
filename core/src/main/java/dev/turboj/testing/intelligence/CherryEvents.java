package dev.turboj.testing.intelligence;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

/** Versioned Cherry Test transport, independent of its console and HTML presentation. */
public final class CherryEvents {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;
    private final Path root;
    private String runId = "";
    private long sequence = -1;
    private RunState state = RunState.PENDING;
    private final Map<String,List<TestResult>> phases = new LinkedHashMap<>();
    private final Map<String,Map<Path,Map<Integer,Coverage>>> phaseCoverage = new LinkedHashMap<>();
    private final Map<Path,String> before = new LinkedHashMap<>();
    private Map<Path,String> after = Map.of();
    private String message = "Waiting for Cherry Test", scope = "", compiledIdentity = "unknown";
    private Availability coverageAvailability = Availability.UNKNOWN;
    private Path htmlReport;
    private boolean terminal;

    public CherryEvents(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** Returns false for a duplicate/out-of-order event; mismatched runs and schemas fail closed. */
    public boolean accept(String json) throws IOException {
        if (json.length() > MAX_EVENT_BYTES) throw new IOException("Cherry event exceeds size limit");
        try {
            JsonObject event = JsonParser.parseString(json).getAsJsonObject();
            if (number(event,"schemaVersion",-1) != SCHEMA_VERSION) throw new IOException("Unsupported Cherry IDE schema");
            String id = string(event,"runId");
            long next = number(event,"sequence",-1);
            if (id.isBlank() || next < 0) throw new IOException("Missing Cherry event identity");
            String kind = string(event,"event");
            if (runId.isEmpty()) {
                if (!kind.equals("run.started")) throw new IOException("Expected run.started");
                Path reportedRoot = Path.of(string(event,"workspaceRoot")).toAbsolutePath().normalize();
                if (!reportedRoot.equals(root)) throw new IOException("Cherry workspace does not match requested project");
                runId = id;
            }
            if (!runId.equals(id)) throw new IOException("Mixed Cherry run identities");
            if (next <= sequence || terminal) return false;
            sequence = next;
            switch (kind) {
                case "run.started" -> {
                    state = RunState.RUNNING;
                    before.putAll(hashes(event,"sourceBefore"));
                    scope = event.has("scope") && event.get("scope").isJsonArray()
                            ? event.get("scope").toString() : string(event,"scope");
                    coverageAvailability = event.has("coverageEnabled") && !event.get("coverageEnabled").isJsonNull()
                            && !event.get("coverageEnabled").getAsBoolean() ? Availability.DISABLED : Availability.MISSING;
                    message = "Cherry Test running against saved sources";
                }
                case "phase.started" -> { phaseKey(event); message = "Running " + phaseLabel(event); }
                case "phase.completed" -> phase(event);
                case "run.completed" -> {
                    terminal = true;
                    after = hashes(event,"sourceAfter");
                    compiledIdentity = string(event,"compiledIdentity");
                    String outcome = string(event,"outcome");
                    boolean complete = event.has("complete") && event.get("complete").getAsBoolean();
                    state = outcome.equals("cancelled") ? RunState.CANCELLED
                            : !complete ? RunState.INCOMPLETE
                            : outcome.equals("passed") ? RunState.COMPLETED : RunState.FAILED;
                    message = "Cherry Test: " + outcome + (complete ? "" : " (incomplete)")
                            + (string(event,"rawFailure").isBlank() ? "" : " — " + string(event,"rawFailure"));
                    htmlReport = localPath(string(event,"htmlReport"));
                }
                default -> throw new IOException("Unknown Cherry IDE event: " + kind);
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException error) {
            throw new IOException("Malformed Cherry IDE event: " + error.getMessage(), error);
        }
    }

    private void phase(JsonObject event) throws IOException {
        String phase = phaseKey(event);
        List<TestResult> results = new ArrayList<>();
        for (JsonElement entry : array(event,"tests")) {
            JsonObject test = entry.getAsJsonObject();
            String localId = string(test,"id");
            if (localId.isBlank()) localId = string(test,"name") + "#" + results.size();
            Path source = localPath(string(test,"source"));
            int line = Math.toIntExact(number(test,"line",0));
            Attribution role = switch (string(test,"locationRole")) {
                case "test-declaration" -> Attribution.TEST_DECLARATION;
                case "failure-location" -> Attribution.FAILURE_LOCATION;
                default -> Attribution.REPORTED_TEST_LOCATION;
            };
            List<Association> locations = source != null && line > 0
                    ? List.of(new Association(source,line,role,"Adapter-reported test/failure location; not production execution coverage")) : List.of();
            TestStatus status;
            try { status = TestStatus.valueOf(string(test,"status").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { status = TestStatus.UNKNOWN; }
            results.add(new TestResult(runId + "/" + phase + "/" + localId,runId,
                    string(event,"packageId"),string(test,"name"),string(event,"projectId"),
                    string(event,"moduleId"),string(event,"packageId"),string(event,"phaseId"),
                    string(event,"runtimeId"),phase,status,Freshness.UNKNOWN,
                    Math.max(0,number(test,"durationMs",0)),string(test,"message"),locations));
        }
        phases.put(phase,List.copyOf(results));
        Map<Path,Map<Integer,Coverage>> coverage = new LinkedHashMap<>();
        Map<Path,Map<Path,Map<Integer,Coverage>>> collections = new LinkedHashMap<>();
        boolean incomplete = !string(event,"completeness").equals("complete");
        for (JsonElement entry : array(event,"coverage")) {
            JsonObject artifact = entry.getAsJsonObject();
            Path path = localPath(string(artifact,"path"));
            if (path == null || !Files.isRegularFile(path)) continue;
            if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("Coverage artifact exceeds size limit");
            byte[] bytes = Files.readAllBytes(path);
            String expected = string(artifact,"sha256");
            if (expected.isBlank() || !hash(bytes).equals(expected)) throw new IOException("Coverage artifact hash mismatch");
            if (string(artifact,"format").equalsIgnoreCase("lcov") || path.toString().endsWith(".lcov"))
                readLcov(new String(bytes,StandardCharsets.UTF_8),collections.computeIfAbsent(path,key -> new LinkedHashMap<>()),incomplete);
        }
        for (JsonElement entry : array(event,"coveragePrecisionArtifacts")) {
            JsonObject artifact = entry.getAsJsonObject();
            Path path = localPath(string(artifact,"path"));
            if (path == null || !Files.isRegularFile(path)) continue;
            if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("Coverage precision artifact exceeds size limit");
            byte[] bytes = Files.readAllBytes(path);
            if (!hash(bytes).equals(string(artifact,"sha256"))) throw new IOException("Coverage precision artifact hash mismatch");
            if (string(artifact,"format").equals("jacoco-lines-v1")) {
                Path collection = path.toString().endsWith(".native.json")
                        ? Path.of(path.toString().substring(0,path.toString().length() - ".native.json".length())) : path;
                readCounters(JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(),
                        collections.computeIfAbsent(collection,key -> new LinkedHashMap<>()),incomplete);
            }
        }
        collections.values().forEach(files -> files.forEach((file,lines) -> lines.forEach((line,value) ->
                coverage.computeIfAbsent(file,key -> new LinkedHashMap<>()).merge(line,value,CherryEvents::merge))));
        if (!coverage.isEmpty()) coverageAvailability = incomplete ? Availability.INCOMPLETE : Availability.AVAILABLE;
        phaseCoverage.put(phase,coverage);
        message = "Finished " + phaseLabel(event) + ": " + results.size() + " test results"
                + (event.has("success") && !event.get("success").getAsBoolean() ? " — failure" : "")
                + (array(event,"diagnostics").isEmpty() ? "" : "\n" + array(event,"diagnostics"));
    }

    private void readCounters(JsonObject artifact,Map<Path,Map<Integer,Coverage>> destination,boolean incomplete) throws IOException {
        if (number(artifact,"schemaVersion",-1) != 1 || !string(artifact,"format").equals("jacoco-lines-v1"))
            throw new IOException("Unsupported JaCoCo precision schema");
        for (JsonElement entry : array(artifact,"files")) {
            JsonObject file = entry.getAsJsonObject(); Path source = localPath(string(file,"source"));
            if (source == null) continue;
            for (JsonElement element : array(file,"lines")) {
                JsonObject counters = element.getAsJsonObject();
                int line = Math.toIntExact(number(counters,"line",0)); if (line < 1) continue;
                long ci = number(counters,"ci",-1), mi = number(counters,"mi",-1),
                        cb = number(counters,"cb",-1), mb = number(counters,"mb",-1);
                Coverage value;
                if (incomplete) value = Coverage.unknown(Availability.INCOMPLETE,"Collection did not complete");
                else if (ci < 0 || mi < 0) value = Coverage.unknown(Availability.UNKNOWN,"Instruction counters not supplied by provider");
                else {
                    CoverageState state = ci + mi == 0 ? CoverageState.NOT_APPLICABLE : ci == 0 ? CoverageState.UNCOVERED
                            : mi > 0 || mb > 0 ? CoverageState.PARTIAL : CoverageState.COVERED;
                    value = new Coverage(state,Freshness.UNKNOWN,Availability.AVAILABLE,
                            cb >= 0 && mb >= 0 ? Precision.INSTRUCTIONS_AND_BRANCHES : Precision.INSTRUCTIONS,
                            ci,mi,cb,mb,"JaCoCo",scope,runId,"",before.getOrDefault(source,""),"",
                            "Actual aggregate counters; executed class/source identity is not available in JaCoCo XML");
                }
                destination.computeIfAbsent(source,key -> new LinkedHashMap<>()).merge(line,value,(existing, precise) ->
                        existing.precision() == Precision.BINARY_LINE ? precise : merge(existing,precise));
            }
        }
    }

    private void readLcov(String text,Map<Path,Map<Integer,Coverage>> destination,boolean incomplete) {
        Path source = null;
        for (String record : text.split("\\R")) {
            if (record.startsWith("SF:")) source = localPath(record.substring(3));
            else if (source != null && record.startsWith("DA:")) {
                String[] counts = record.substring(3).split(",");
                if (counts.length < 2) continue;
                int line = Integer.parseInt(counts[0]);
                if (line < 1) continue;
                long hits = Long.parseLong(counts[1]);
                if (hits < 0) continue;
                var coverage = incomplete ? Coverage.unknown(Availability.INCOMPLETE,"Collection did not complete")
                        : new Coverage(hits > 0 ? CoverageState.COVERED : CoverageState.UNCOVERED,
                        Freshness.UNKNOWN,Availability.AVAILABLE,Precision.BINARY_LINE,-1,-1,-1,-1,
                        "LCOV",scope,runId,"",before.getOrDefault(source,""),"",
                        "Binary line hits; instrumented bytecode/source identity unavailable");
                destination.computeIfAbsent(source,key -> new LinkedHashMap<>()).merge(line,coverage,CherryEvents::merge);
            }
        }
    }

    public Run snapshot() {
        List<TestResult> results = phases.values().stream().flatMap(Collection::stream).map(test -> {
            boolean changed = test.associations().stream().anyMatch(a -> before.containsKey(a.file())
                    && after.containsKey(a.file()) && !before.get(a.file()).equals(after.get(a.file())));
            return test.withFreshness(changed ? Freshness.STALE : Freshness.UNKNOWN);
        }).toList();
        Map<Path,Map<Integer,Coverage>> coverage = new LinkedHashMap<>();
        phaseCoverage.values().forEach(files -> files.forEach((file,lines) -> lines.forEach((line,value) -> {
            Coverage result = value;
            if (terminal && (!after.containsKey(file) || !Objects.equals(before.get(file),after.get(file))))
                result = value.withFreshness(Freshness.STALE);
            coverage.computeIfAbsent(file,key -> new LinkedHashMap<>()).merge(line,result,CherryEvents::merge);
        })));
        return new Run(runId,root,state,results,coverage,before,message,htmlReport,sequence,coverageAvailability);
    }

    private static Coverage merge(Coverage first,Coverage second) {
        if (first.availability() != Availability.AVAILABLE || second.availability() != Availability.AVAILABLE)
            return Coverage.unknown(Availability.INCOMPLETE,"Not all coverage collections completed");
        if (first.precision() != Precision.BINARY_LINE || second.precision() != Precision.BINARY_LINE)
            return Coverage.unknown(Availability.UNKNOWN,"Multiple counter sets require provider-level probe merging");
        boolean covered = first.state() == CoverageState.COVERED || second.state() == CoverageState.COVERED;
        return new Coverage(covered ? CoverageState.COVERED : CoverageState.UNCOVERED,
                first.freshness() == Freshness.STALE || second.freshness() == Freshness.STALE ? Freshness.STALE : Freshness.UNKNOWN,
                Availability.AVAILABLE,Precision.BINARY_LINE,-1,-1,-1,-1,"LCOV",first.scope(),first.runId(),"",first.sourceHash(),"",
                "Union of aggregate line hits; no per-test attribution");
    }

    public Availability coverageAvailability() { return coverageAvailability; }
    public boolean terminal() { return terminal; }
    public String compiledIdentity() { return compiledIdentity; }
    public Run interrupted(boolean cancelled,String reason) {
        Run run = snapshot(); terminal = true;
        return new Run(run.id(),root,cancelled ? RunState.CANCELLED : RunState.INCOMPLETE,run.tests(),
                run.coverage(),run.sourceHashes(),reason,run.htmlReport(),sequence,run.coverageAvailability());
    }
    private Map<Path,String> hashes(JsonObject event,String field) {
        Map<Path,String> result = new LinkedHashMap<>();
        if (event.has(field) && event.get(field).isJsonObject()) event.getAsJsonObject(field).entrySet().forEach(entry -> {
            Path path = localPath(entry.getKey());
            String value = entry.getValue().getAsString();
            if (path != null && value.matches("[a-fA-F0-9]{64}")) result.put(path,value.toLowerCase(Locale.ROOT));
        });
        return result;
    }
    private Path localPath(String value) {
        if (value.isBlank()) return null;
        Path path = root.resolve(value).toAbsolutePath().normalize();
        return path.startsWith(root) ? path : null;
    }
    private static String phaseKey(JsonObject event) throws IOException {
        if (event.has("phaseKey")) {
            JsonElement emitted = event.get("phaseKey");
            if (!emitted.isJsonPrimitive() || !emitted.getAsJsonPrimitive().isString()
                    || !emitted.getAsString().matches("[a-fA-F0-9]{64}"))
                throw new IOException("Invalid Cherry phase identity: expected a SHA-256 manifest key");
            JsonElement runtimeValue = event.get("runtimeId");
            if (runtimeValue == null || !runtimeValue.isJsonPrimitive() || !runtimeValue.getAsJsonPrimitive().isString()
                    || runtimeValue.getAsString().isBlank()) throw new IOException("Missing or invalid runtime for Cherry phase identity");
            String runtime = runtimeValue.getAsString();
            // The manifest hash distinguishes identical display labels in separate configurations.
            // A length-delimited runtime also keeps runtime variants distinct without delimiter collisions.
            return "manifest:" + emitted.getAsString().toLowerCase(Locale.ROOT) + ":" + runtime.length() + ":" + runtime;
        }
        // Historical schema-v1 controls did not emit a manifest key. Preserve their full tuple,
        // serialized unambiguously so slashes in labels cannot merge different configurations.
        JsonArray tuple = new JsonArray();
        for (String field : List.of("projectId","moduleId","packageId","phaseId","runtimeId")) tuple.add(string(event,field));
        return "legacy:" + tuple;
    }
    private static String phaseLabel(JsonObject event) {
        return string(event,"projectId") + "/" + string(event,"moduleId") + "/" + string(event,"packageId")
                + "/" + string(event,"phaseId") + "/" + string(event,"runtimeId");
    }
    private static JsonArray array(JsonObject object,String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }
    private static String string(JsonObject object,String name) {
        return !object.has(name) || object.get(name).isJsonNull() ? "" : object.get(name).getAsString();
    }
    private static long number(JsonObject object,String name,long fallback) {
        return !object.has(name) || object.get(name).isJsonNull() ? fallback : object.get(name).getAsLong();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
