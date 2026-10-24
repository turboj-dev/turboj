package dev.turboj.turbotest;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import java.util.*;
import java.util.function.Consumer;

/**
 * Headless JUnit Platform integration. Call in a resident test worker, never on the UI thread.
 * The caller owns the context class loader and process isolation/cancellation boundary.
 */
public final class TestEngine {
    public record Request(long sourceVersion, List<String> classes, List<String> uniqueIds, List<String> methods) {
        public Request { classes = List.copyOf(classes); uniqueIds = List.copyOf(uniqueIds); methods = List.copyOf(methods); }
        public Request(long sourceVersion, List<String> classes, List<String> uniqueIds) { this(sourceVersion, classes, uniqueIds, List.of()); }
    }
    public enum Status { PASSED, FAILED, ABORTED, SKIPPED }
    public record Test(String id, String name, String className, String methodName, Status status,
                       long durationNanos, String failure, String trace) {}
    public record Report(long sourceVersion, long durationNanos, List<Test> tests, List<String> engineFailures) {
        public Report { tests = List.copyOf(tests); engineFailures = List.copyOf(engineFailures); }
        public long passed() { return tests.stream().filter(t -> t.status == Status.PASSED).count(); }
        public long failed() { return tests.stream().filter(t -> t.status == Status.FAILED).count(); }
        public boolean successful() { return failed() == 0 && engineFailures.isEmpty(); }
        public List<String> failedIds() { return tests.stream().filter(t -> t.status == Status.FAILED).map(Test::id).toList(); }
    }
    public record Event(String kind, long sourceVersion, String id, String name, Test test) {}

    public List<String> discover(Request request) {
        TestPlan plan = LauncherFactory.create().discover(discovery(request));
        return plan.getRoots().stream().flatMap(root -> plan.getDescendants(root).stream())
                .filter(TestIdentifier::isTest).map(TestIdentifier::getUniqueId).sorted().toList();
    }

    public Report execute(Request request, Consumer<Event> events) {
        Objects.requireNonNull(events);
        long started = System.nanoTime();
        Map<String, Long> starts = new HashMap<>();
        List<Test> tests = new ArrayList<>();
        List<String> engineFailures = new ArrayList<>();
        Launcher launcher = LauncherFactory.create();
        TestExecutionListener listener = new TestExecutionListener() {
            @Override public synchronized void executionStarted(TestIdentifier id) {
                if (id.isTest()) {
                    starts.put(id.getUniqueId(), System.nanoTime());
                    emit("started", id, null);
                }
            }
            @Override public synchronized void executionSkipped(TestIdentifier id, String reason) {
                if (id.isTest()) finish(id, Status.SKIPPED, reason, "");
            }
            @Override public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
                Throwable error = result.getThrowable().orElse(null);
                String message = error == null ? "" : error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "");
                String trace = error == null ? "" : trace(error);
                if (id.isTest()) finish(id, switch (result.getStatus()) {
                    case SUCCESSFUL -> Status.PASSED;
                    case ABORTED -> Status.ABORTED;
                    case FAILED -> Status.FAILED;
                }, message, trace);
                else if (result.getStatus() == TestExecutionResult.Status.FAILED) engineFailures.add(id.getDisplayName() + ": " + message);
            }
            private void finish(TestIdentifier id, Status status, String failure, String trace) {
                String className = "", method = "";
                if (id.getSource().orElse(null) instanceof MethodSource source) { className = source.getClassName(); method = source.getMethodName(); }
                else if (id.getSource().orElse(null) instanceof ClassSource source) className = source.getClassName();
                long duration = starts.containsKey(id.getUniqueId()) ? System.nanoTime() - starts.remove(id.getUniqueId()) : 0;
                Test test = new Test(id.getUniqueId(), id.getDisplayName(), className, method, status, duration, failure, trace);
                tests.add(test); emit("finished", id, test);
            }
            private void emit(String kind, TestIdentifier id, Test test) {
                events.accept(new Event(kind, request.sourceVersion(), id.getUniqueId(), id.getDisplayName(), test));
            }
        };
        launcher.execute(discovery(request), listener);
        // Failures are presented first without changing the test framework's execution semantics.
        tests.sort(Comparator.comparingInt((Test test) -> test.status == Status.FAILED ? 0 : 1).thenComparing(Test::id));
        return new Report(request.sourceVersion(), System.nanoTime() - started, tests, engineFailures);
    }

    private LauncherDiscoveryRequest discovery(Request request) {
        if (request.classes().isEmpty() && request.uniqueIds().isEmpty() && request.methods().isEmpty()) throw new IllegalArgumentException("No tests selected");
        var builder = LauncherDiscoveryRequestBuilder.request().enableImplicitConfigurationParameters(false)
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false");
        if (!request.uniqueIds().isEmpty()) builder.selectors(request.uniqueIds().stream().map(DiscoverySelectors::selectUniqueId).toList());
        else if (!request.methods().isEmpty()) builder.selectors(request.methods().stream().map(DiscoverySelectors::selectMethod).toList());
        else builder.selectors(request.classes().stream().distinct().sorted().map(DiscoverySelectors::selectClass).toList());
        return builder.build();
    }

    private static String trace(Throwable error) {
        var output = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(output));
        String text = output.toString();
        return text.length() > 32_768 ? text.substring(0, 32_768) : text;
    }
}
