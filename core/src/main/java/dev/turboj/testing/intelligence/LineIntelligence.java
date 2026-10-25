package dev.turboj.testing.intelligence;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Provider-neutral evidence. A test outcome never determines a coverage state. */
public final class LineIntelligence {
    private LineIntelligence() {}
    public enum Lane { GIT, TEST, COVERAGE }
    public enum TestStatus { PASSED, FAILED, SKIPPED, ABORTED, PENDING, UNKNOWN }
    public enum Freshness { CURRENT, STALE, UNKNOWN }
    public enum CoverageState { COVERED, UNCOVERED, PARTIAL, UNKNOWN, NOT_APPLICABLE }
    public enum Availability { AVAILABLE, DISABLED, MISSING, INCOMPLETE, EXCLUDED, BYTECODE_MISMATCH, UNKNOWN }
    public enum Precision { BINARY_LINE, INSTRUCTIONS, INSTRUCTIONS_AND_BRANCHES, UNKNOWN }
    public enum Attribution { TEST_DECLARATION, FAILURE_LOCATION, REPORTED_TEST_LOCATION, DECLARED_TARGET, DYNAMIC_EXECUTION, HEURISTIC }
    public enum RunState { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, INCOMPLETE, UNAVAILABLE }

    public record SourceIdentity(Path workspace, Path repository, String project, String module,
            Path file, String documentId, long version, String sourceHash, String compilationId) {
        public SourceIdentity {
            file = absolute(file); workspace = absolute(workspace); repository = absolute(repository);
            project = text(project); module = text(module); documentId = text(documentId);
            sourceHash = text(sourceHash); compilationId = text(compilationId);
        }
    }
    public record Association(Path file, int line, Attribution kind, String evidence) {
        public Association {
            file = absolute(file); Objects.requireNonNull(kind); evidence = text(evidence);
            if (line < 1) throw new IllegalArgumentException("Source lines are one-based");
        }
        public boolean verified() { return kind != Attribution.HEURISTIC && file != null; }
    }
    public record TestResult(String id, String runId, String suite, String name, String project,
            String module, String packageName, String phase, String runtime, String invocation,
            TestStatus status, Freshness freshness, long durationMillis, String failure,
            List<Association> associations) {
        public TestResult {
            id = text(id); runId = text(runId); suite = text(suite); name = text(name);
            project = text(project); module = text(module); packageName = text(packageName);
            phase = text(phase); runtime = text(runtime); invocation = text(invocation);
            status = Objects.requireNonNull(status); freshness = Objects.requireNonNull(freshness);
            failure = text(failure); associations = List.copyOf(associations);
            if (durationMillis < 0) throw new IllegalArgumentException("Negative test duration");
        }
        public TestResult withFreshness(Freshness value) {
            return new TestResult(id,runId,suite,name,project,module,packageName,phase,runtime,
                    invocation,status,value,durationMillis,failure,associations);
        }
    }
    /** Unknown counters are -1. Zero means a measured zero, never an absent report. */
    public record Coverage(CoverageState state, Freshness freshness, Availability availability,
            Precision precision, long coveredInstructions, long missedInstructions,
            long coveredBranches, long missedBranches, String provider, String scope,
            String runId, String processId, String sourceHash, String classIdentity, String reason) {
        public Coverage {
            Objects.requireNonNull(state); Objects.requireNonNull(freshness);
            Objects.requireNonNull(availability); Objects.requireNonNull(precision);
            provider = text(provider); scope = text(scope); runId = text(runId);
            processId = text(processId); sourceHash = text(sourceHash);
            classIdentity = text(classIdentity); reason = text(reason);
            if (coveredInstructions < -1 || missedInstructions < -1 || coveredBranches < -1 || missedBranches < -1)
                throw new IllegalArgumentException("Invalid coverage counters");
            if (availability != Availability.AVAILABLE && state != CoverageState.UNKNOWN && state != CoverageState.NOT_APPLICABLE)
                throw new IllegalArgumentException("Unavailable coverage cannot claim executed or missed code");
            if (state == CoverageState.PARTIAL && precision != Precision.INSTRUCTIONS && precision != Precision.INSTRUCTIONS_AND_BRANCHES)
                throw new IllegalArgumentException("Partial coverage requires counters");
        }
        public static Coverage unknown(Availability availability, String reason) {
            return new Coverage(availability == Availability.EXCLUDED ? CoverageState.NOT_APPLICABLE : CoverageState.UNKNOWN,
                    Freshness.UNKNOWN, availability, Precision.UNKNOWN, -1,-1,-1,-1,"","","","","","",reason);
        }
        public Coverage withFreshness(Freshness value) {
            return new Coverage(state,value,availability,precision,coveredInstructions,missedInstructions,
                    coveredBranches,missedBranches,provider,scope,runId,processId,sourceHash,classIdentity,reason);
        }
    }
    public record Line(boolean added, List<TestResult> tests, Coverage coverage) {
        public Line { tests = List.copyOf(tests); coverage = Objects.requireNonNull(coverage); }
        public TestStatus testStatus() {
            if (tests.stream().anyMatch(t -> t.freshness == Freshness.CURRENT && t.status == TestStatus.FAILED)) return TestStatus.FAILED;
            if (tests.stream().anyMatch(t -> t.status == TestStatus.PENDING)) return TestStatus.PENDING;
            if (tests.isEmpty() || tests.stream().anyMatch(t -> t.freshness != Freshness.CURRENT || t.status == TestStatus.UNKNOWN)) return TestStatus.UNKNOWN;
            if (tests.stream().anyMatch(t -> t.status == TestStatus.ABORTED)) return TestStatus.ABORTED;
            if (tests.stream().anyMatch(t -> t.status == TestStatus.SKIPPED)) return TestStatus.SKIPPED;
            return TestStatus.PASSED;
        }
        public Freshness testFreshness() {
            if (tests.isEmpty() || tests.stream().anyMatch(t -> t.freshness == Freshness.UNKNOWN)) return Freshness.UNKNOWN;
            return tests.stream().anyMatch(t -> t.freshness == Freshness.STALE) ? Freshness.STALE : Freshness.CURRENT;
        }
    }
    /** Gap zero is BOF; n is the boundary after source line n, never a fictitious code line. */
    public record Deletion(int anchorLine, String text) {
        public Deletion { if (anchorLine < 0) throw new IllegalArgumentException("Negative deletion boundary"); text = LineIntelligence.text(text); }
        public int removedLines() { return text.isEmpty() ? 0 : text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0); }
    }
    public record Snapshot(SourceIdentity source, Map<Integer, Line> lines, List<Deletion> deletions,
            boolean gitActive, boolean testingActive, String baseline, String message) {
        public Snapshot {
            Objects.requireNonNull(source); lines = Collections.unmodifiableMap(new TreeMap<>(lines));
            if (lines.keySet().stream().anyMatch(line -> line < 1)) throw new IllegalArgumentException("Invalid source line");
            deletions = List.copyOf(deletions); baseline = text(baseline); message = text(message);
        }
        public Line line(int number) { return lines.getOrDefault(number, EMPTY_LINE); }
    }
    public record Run(String id, Path root, RunState state, List<TestResult> tests,
            Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
            String message, Path htmlReport, long sequence, Availability coverageAvailability) {
        public Run(String id, Path root, RunState state, List<TestResult> tests,
                Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
                String message, Path htmlReport, long sequence) {
            this(id,root,state,tests,coverage,sourceHashes,message,htmlReport,sequence,Availability.UNKNOWN);
        }
        public Run {
            id = text(id); root = absolute(root); Objects.requireNonNull(state); tests = List.copyOf(tests);
            var immutable = new LinkedHashMap<Path,Map<Integer,Coverage>>();
            coverage.forEach((file, values) -> immutable.put(absolute(file), Map.copyOf(values)));
            coverage = Collections.unmodifiableMap(immutable); sourceHashes = Map.copyOf(sourceHashes);
            message = text(message); htmlReport = absolute(htmlReport); Objects.requireNonNull(coverageAvailability);
        }
    }
    private static final Line EMPTY_LINE = new Line(false,List.of(),Coverage.unknown(Availability.UNKNOWN,"No line evidence"));
    public static String sha256(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static Path absolute(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
}
