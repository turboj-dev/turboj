package dev.turboj.refactorj.benchmarks;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.Rename;
import dev.turboj.refactorj.runtime.RefactorJRuntime;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Run under /usr/bin/time -l on macOS to record actual process peak RSS, not allocation totals. */
public final class MemoryProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("refactorj-memory-").toRealPath();
        try {
            List<SourceSnapshot> sources = new ArrayList<>();
            for (int i = 0; i < 20; i++) sources.add(new SourceSnapshot(root.resolve("C" + i + ".java"),
                    "class C" + i + " { int calculate(int value) { int result = value + 1; return result; } }", 1, true));
            try (var runtime = new RefactorJRuntime(); var engine = runtime.openWorkspace(
                    new WorkspaceSnapshot(root, 1, sources, List.of(root), List.of()))) {
                var location = new Location(sources.getFirst().file(), sources.getFirst().text().indexOf("result"));
                for (int i = 0; i < 25; i++) {
                    Plan plan = engine.prepare(new Rename(location, "total"), Cancellation.NONE);
                    if (!plan.valid()) throw new IllegalStateException(plan.diagnostics().toString());
                }
                var memory = ManagementFactory.getMemoryMXBean();
                System.out.println("sources=20 prepareLocalRename=25 explicitGC=false");
                System.out.println("heapUsedBytes=" + memory.getHeapMemoryUsage().getUsed());
                System.out.println("heapCommittedBytes=" + memory.getHeapMemoryUsage().getCommitted());
                System.out.println("nonHeapUsedBytes=" + memory.getNonHeapMemoryUsage().getUsed());
            }
        } finally { Files.deleteIfExists(root); }
    }
}
