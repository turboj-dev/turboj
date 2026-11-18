package dev.turboj.testing.intelligence;

import com.google.gson.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

/** Controlled synthetic protocol workload, not evidence of executed tests or real coverage. */
public final class CherryReportBenchmark {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private record Workload(Path root,List<String> events,int tests,int rows,long bytes) {}
    private static volatile Object retained;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output JSON path required");
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("environment",Map.of("runtime",System.getProperty("java.runtime.version"),"os",System.getProperty("os.name"),
                "version",System.getProperty("os.version"),"architecture",System.getProperty("os.arch"),
                "processors",Runtime.getRuntime().availableProcessors(),"maximumHeapBytes",Runtime.getRuntime().maxMemory()));
        report.put("method","Synthetic schema-v1 phase/test-result events and LCOV line hits, generated before timing. "
                + "No test execution or coverage claim. Includes JSON parse, bounded artifact IO/hash checking and immutable snapshot publication "
                + "after every phase. Source parsing, Swing rendering and OS display latency are excluded. "
                + "Heap deltas follow requested GC and are approximate retained-heap observations, not an allocation profile.");
        var measurements = new ArrayList<Map<String,Object>>();
        Workload warmup = workload(1000,1000);
        for (int n=0;n<3;n++) ingest(warmup,false);
        for (int size : new int[]{10_000,100_000}) {
            Workload workload = workload(size,size);
            measurements.add(ingest(workload,true));
        }
        report.put("measurements",measurements); Files.writeString(output,JSON.toJson(report));
        System.out.println(JSON.toJson(report));
    }
    private static Map<String,Object> ingest(Workload workload,boolean measureMemory) throws Exception {
        retained = null; if (measureMemory) settle();
        long heapBefore = heap(), gcBefore = collections(), start = System.nanoTime();
        CherryEvents reader = new CherryEvents(workload.root());
        var eventTimes = new ArrayList<Double>(); var publications = new ArrayList<Double>();
        Run run = null;
        for (String event : workload.events()) {
            long operation = System.nanoTime(); reader.accept(event); eventTimes.add((System.nanoTime()-operation)/1e6);
            operation = System.nanoTime(); run = reader.snapshot(); publications.add((System.nanoTime()-operation)/1e6);
        }
        long end = System.nanoTime();
        if (run == null || run.tests().size()!=workload.tests() || run.state()!=RunState.COMPLETED
                || run.coverage().values().stream().mapToInt(Map::size).sum()!=workload.rows()) throw new AssertionError("Workload result mismatch");
        retained = List.of(reader,run); if (measureMemory) settle();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("testResults",workload.tests()); result.put("coverageRows",workload.rows());
        result.put("events",workload.events().size()); result.put("eventUtf8Bytes",workload.bytes());
        result.put("ingestAndPublishMs",(end-start)/1e6); result.put("eventImportMs",summary(eventTimes));
        result.put("snapshotPublishMs",summary(publications)); result.put("retainedHeapDeltaBytes",heap()-heapBefore);
        result.put("gcCollections",collections()-gcBefore); return result;
    }
    private static Workload workload(int tests,int rows) throws Exception {
        Path root = Files.createTempDirectory("turboj-cherry-report-benchmark-");
        String source = "// Synthetic report benchmark source\n".repeat(rows);
        Files.writeString(root.resolve("Source.java"),source);
        String sourceHash = sha256(source); var events = new ArrayList<String>(); int sequence=0;
        JsonObject start = event("run.started",sequence++); start.addProperty("workspaceRoot",root.toString()); start.addProperty("coverageEnabled",true);
        start.add("sourceBefore",new Gson().toJsonTree(Map.of("Source.java",sourceHash))); events.add(start.toString());
        StringBuilder lcov = new StringBuilder("SF:Source.java\n");
        for (int line=1;line<=rows;line++) lcov.append("DA:").append(line).append(',').append(line%3==0?0:1).append('\n');
        lcov.append("end_of_record\n"); String coverage = lcov.toString(); Files.writeString(root.resolve("coverage.lcov"),coverage);
        for (int first=0;first<tests;first+=1000) {
            JsonObject phase = event("phase.completed",sequence++); phase.addProperty("projectId","synthetic-benchmark");
            phase.addProperty("moduleId","module-" + first/1000); phase.addProperty("packageId","results");
            phase.addProperty("phaseId","unit"); phase.addProperty("runtimeId","synthetic"); phase.addProperty("success",true);
            phase.addProperty("completeness","complete"); JsonArray results = new JsonArray();
            for (int index=first;index<Math.min(tests,first+1000);index++) {
                JsonObject test = new JsonObject(); test.addProperty("id","test-"+index);
                test.addProperty("name","benchmark.Suite"+(index/100)+"::case"+index); test.addProperty("status","passed");
                test.addProperty("durationMs",1); results.add(test);
            }
            phase.add("tests",results);
            if (first+1000>=tests) phase.add("coverage",new Gson().toJsonTree(List.of(Map.of("path","coverage.lcov","format","lcov","sha256",sha256(coverage)))));
            events.add(phase.toString());
        }
        JsonObject end=event("run.completed",sequence); end.addProperty("complete",true); end.addProperty("outcome","passed");
        end.add("sourceAfter",new Gson().toJsonTree(Map.of("Source.java",sourceHash))); events.add(end.toString());
        return new Workload(root,List.copyOf(events),tests,rows,events.stream().mapToLong(text -> text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum());
    }
    private static JsonObject event(String kind,int sequence) {
        JsonObject event = new JsonObject(); event.addProperty("schemaVersion",1); event.addProperty("event",kind);
        event.addProperty("runId","synthetic-benchmark"); event.addProperty("sequence",sequence); return event;
    }
    private static Map<String,Double> summary(List<Double> values) {
        List<Double> sorted=values.stream().sorted().toList();
        return Map.of("minimum",sorted.getFirst(),"median",sorted.get(sorted.size()/2),"p95",sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*.95)-1)),"maximum",sorted.getLast());
    }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long collections() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0,bean.getCollectionCount())).sum(); }
    private static void settle() throws InterruptedException { System.gc(); Thread.sleep(150); }
}
