package dev.turboj.testing.intelligence;

import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

/** Distinct executions remain inspectable; a rerun never upgrades older observations. */
public final class CherryRunHistory {
    private static final int MAX_RUNS = 12, MAX_TESTS = 50_000;
    private final LinkedHashMap<String,Run> runs = new LinkedHashMap<>();
    public void accept(Run run) {
        if (run.id().isBlank()) return;
        String key = run.root() + "\n" + run.id();
        Run previous = runs.get(key);
        if (previous != null && previous.sequence() > run.sequence()) return;
        runs.put(key,run);
        while (runs.size() > MAX_RUNS || runs.size() > 1 && runs.values().stream().mapToInt(value -> value.tests().size()).sum() > MAX_TESTS)
            runs.remove(runs.keySet().iterator().next());
    }
    public List<Run> snapshot() { return List.copyOf(runs.values()).reversed(); }
}
