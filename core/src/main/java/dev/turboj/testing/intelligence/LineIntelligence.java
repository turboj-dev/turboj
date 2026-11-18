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

    public record TestSelector(String kind,String value,String phaseKey,String runtimeId,String adapterId,
            Path manifest,boolean exact,String reason) {
        public TestSelector {
            kind = text(kind); value = text(value); phaseKey = text(phaseKey); runtimeId = text(runtimeId);
            adapterId = text(adapterId); manifest = absolute(manifest); reason = text(reason);
            if (kind.length() > 128 || value.length() > 4096 || runtimeId.length() > 4096 || adapterId.length() > 256)
                throw new IllegalArgumentException("Oversized test selector");
            if (exact && (kind.isBlank() || value.isBlank() || !phaseKey.matches("[a-f0-9]{64}")
                    || runtimeId.isBlank() || adapterId.isBlank() || manifest == null))
                throw new IllegalArgumentException("Exact test selector requires complete execution identity");
        }
    }
    public record Repository(String id,Path root,String worktree) {
        public Repository { id = text(id); root = absolute(root); worktree = text(worktree); Objects.requireNonNull(root); }
    }
    public record PhaseExecution(String invocation,String project,String module,String phase,String runtime,
            RunState state,String inventoryCompleteness,int reportedTests) {
        public PhaseExecution {
            invocation = text(invocation); project = text(project); module = text(module); phase = text(phase); runtime = text(runtime);
            Objects.requireNonNull(state); inventoryCompleteness = text(inventoryCompleteness);
            if (reportedTests < 0) throw new IllegalArgumentException("Negative result count");
        }
    }
    public record ExecutionContext(List<Repository> repositories,Map<String,String> capabilities,Map<String,PhaseExecution> phases) {
        public ExecutionContext(List<Repository> repositories,Map<String,String> capabilities) { this(repositories,capabilities,Map.of()); }
        public ExecutionContext {
            repositories = List.copyOf(repositories); capabilities = Map.copyOf(capabilities);
            phases = Collections.unmodifiableMap(new LinkedHashMap<>(phases));
        }
        public ExecutionContext withPhase(PhaseExecution phase) {
            var updated = new LinkedHashMap<>(phases); updated.put(phase.invocation(),phase);
            return new ExecutionContext(repositories,capabilities,updated);
        }
        public List<Path> sourceRoots(Path workspace) {
            var roots = new LinkedHashSet<Path>(); roots.add(workspace); repositories.forEach(repository -> roots.add(repository.root()));
            return List.copyOf(roots);
        }
        public static ExecutionContext empty() { return new ExecutionContext(List.of(),Map.of()); }
    }

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
            List<Association> associations,TestSelector selector,Path moduleRoot,String repositoryId) {
        public TestResult(String id,String runId,String suite,String name,String project,String module,String packageName,
                String phase,String runtime,String invocation,TestStatus status,Freshness freshness,long durationMillis,
                String failure,List<Association> associations) {
            this(id,runId,suite,name,project,module,packageName,phase,runtime,invocation,status,freshness,durationMillis,
                    failure,associations,null,null,"");
        }
        public TestResult {
            id = text(id); runId = text(runId); suite = text(suite); name = text(name);
            project = text(project); module = text(module); packageName = text(packageName);
            phase = text(phase); runtime = text(runtime); invocation = text(invocation);
            status = Objects.requireNonNull(status); freshness = Objects.requireNonNull(freshness);
            failure = text(failure); associations = List.copyOf(associations);
            moduleRoot = absolute(moduleRoot); repositoryId = text(repositoryId);
            if (durationMillis < 0) throw new IllegalArgumentException("Negative test duration");
        }
        public TestResult withFreshness(Freshness value) {
            if (freshness == value) return this;
            return new TestResult(id,runId,suite,name,project,module,packageName,phase,runtime,
                    invocation,status,value,durationMillis,failure,associations,selector,moduleRoot,repositoryId);
        }
    }
    /** Unknown counters are -1. Zero means a measured zero, never an absent report. */
    public record Coverage(CoverageState state, Freshness freshness, Availability availability,
            Precision precision, long coveredInstructions, long missedInstructions,
            long coveredBranches, long missedBranches, String provider, String scope,
            String runId, String processId, String sourceHash, String classIdentity, String reason, CoverageProof proof) {
        public Coverage(CoverageState state, Freshness freshness, Availability availability,
                Precision precision, long coveredInstructions, long missedInstructions,
                long coveredBranches, long missedBranches, String provider, String scope,
                String runId, String processId, String sourceHash, String classIdentity, String reason) {
            this(state,freshness,availability,precision,coveredInstructions,missedInstructions,coveredBranches,missedBranches,
                    provider,scope,runId,processId,sourceHash,classIdentity,reason,null);
        }
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
            if (freshness == value) return this;
            return new Coverage(state,value,availability,precision,coveredInstructions,missedInstructions,
                    coveredBranches,missedBranches,provider,scope,runId,processId,sourceHash,classIdentity,reason,proof);
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
    /** One provider collection, retained independently from a provider-level merged result. */
    public record CoverageObservation(String id, String invocation, String project, String module,
            String packageName, String phase, String runtime, Path artifact,
            Map<Path,Map<Integer,Coverage>> coverage) {
        public CoverageObservation {
            id = text(id); invocation = text(invocation); project = text(project); module = text(module);
            packageName = text(packageName); phase = text(phase); runtime = text(runtime); artifact = absolute(artifact);
            coverage = immutableCoverage(coverage);
        }
        public CoverageObservation withCoverage(Map<Path,Map<Integer,Coverage>> values) {
            return new CoverageObservation(id,invocation,project,module,packageName,phase,runtime,artifact,values);
        }
        public CoverageObservation withFreshness(Freshness freshness) {
            var values = new LinkedHashMap<Path,Map<Integer,Coverage>>();
            coverage.forEach((file,lines) -> {
                var updated = new LinkedHashMap<Integer,Coverage>();
                lines.forEach((line,value) -> updated.put(line,value.withFreshness(freshness)));
                values.put(file,updated);
            });
            return withCoverage(values);
        }
    }
    public record Run(String id, Path root, RunState state, List<TestResult> tests,
            Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
            String message, Path htmlReport, long sequence, Availability coverageAvailability,
            List<CoverageObservation> collections,ExecutionContext context) {
        public Run(String id, Path root, RunState state, List<TestResult> tests,
                Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
                String message, Path htmlReport, long sequence, Availability coverageAvailability,List<CoverageObservation> collections) {
            this(id,root,state,tests,coverage,sourceHashes,message,htmlReport,sequence,coverageAvailability,collections,ExecutionContext.empty());
        }
        public Run(String id, Path root, RunState state, List<TestResult> tests,
                Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
                String message, Path htmlReport, long sequence, Availability coverageAvailability) {
            this(id,root,state,tests,coverage,sourceHashes,message,htmlReport,sequence,coverageAvailability,List.of());
        }
        public Run(String id, Path root, RunState state, List<TestResult> tests,
                Map<Path, Map<Integer, Coverage>> coverage, Map<Path,String> sourceHashes,
                String message, Path htmlReport, long sequence) {
            this(id,root,state,tests,coverage,sourceHashes,message,htmlReport,sequence,Availability.UNKNOWN);
        }
        public Run {
            id = text(id); root = absolute(root); Objects.requireNonNull(state); tests = List.copyOf(tests);
            var immutable = new LinkedHashMap<Path,Map<Integer,Coverage>>();
            coverage.forEach((file, values) -> {
                Map<Integer,Coverage> safe = new LinkedHashMap<>();
                values.forEach((line,value) -> safe.put(line,
                        state != RunState.COMPLETED && state != RunState.FAILED && value.freshness() == Freshness.CURRENT
                                ? value.withFreshness(Freshness.UNKNOWN) : value));
                immutable.put(absolute(file),Map.copyOf(safe));
            });
            coverage = Collections.unmodifiableMap(immutable); sourceHashes = Map.copyOf(sourceHashes);
            message = text(message); htmlReport = absolute(htmlReport); Objects.requireNonNull(coverageAvailability);
            Objects.requireNonNull(context);
            if (state == RunState.CANCELLED || state == RunState.INCOMPLETE || state == RunState.UNAVAILABLE) {
                for (PhaseExecution phase : List.copyOf(context.phases().values())) if (phase.state() == RunState.RUNNING)
                    context = context.withPhase(new PhaseExecution(phase.invocation(),phase.project(),phase.module(),phase.phase(),phase.runtime(),
                            state == RunState.CANCELLED ? RunState.CANCELLED : RunState.INCOMPLETE,phase.inventoryCompleteness(),phase.reportedTests()));
            }
            collections = collections.stream().map(collection -> state == RunState.COMPLETED || state == RunState.FAILED
                    ? collection : collection.withCoverage(pendingCoverage(collection.coverage()))).toList();
        }
    }
    private static Map<Path,Map<Integer,Coverage>> immutableCoverage(Map<Path,Map<Integer,Coverage>> values) {
        var immutable = new LinkedHashMap<Path,Map<Integer,Coverage>>();
        values.forEach((file,lines) -> immutable.put(absolute(file),Collections.unmodifiableMap(new TreeMap<>(lines))));
        return Collections.unmodifiableMap(immutable);
    }
    private static Map<Path,Map<Integer,Coverage>> pendingCoverage(Map<Path,Map<Integer,Coverage>> values) {
        var pending = new LinkedHashMap<Path,Map<Integer,Coverage>>();
        values.forEach((file,lines) -> {
            var updated = new LinkedHashMap<Integer,Coverage>();
            lines.forEach((line,value) -> updated.put(line,value.freshness() == Freshness.CURRENT
                    ? value.withFreshness(Freshness.UNKNOWN) : value));
            pending.put(file,updated);
        });
        return pending;
    }
    private static final Line EMPTY_LINE = new Line(false,List.of(),Coverage.unknown(Availability.UNKNOWN,"No line evidence"));
    public static String sha256(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static Path absolute(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
}
