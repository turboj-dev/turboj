package dev.turboj.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WorkspaceStatusTest {
    @Test void keepsBuildAndTestOutcomesWhileMovingTimingAndCountsToTheTooltip() {
        assertEquals("BUILD OK", TurboFrame.compactStatus("BUILD OK | 0 errors | 176.87 ms"));
        assertEquals("BUILD OK", TurboFrame.compactStatus("CACHE HIT | 0 errors | 0.1 ms"));
        assertEquals("BUILD FAILED", TurboFrame.compactStatus("BUILD FAILED | 12 errors"));
        assertEquals("NATIVE BUILD FAILED", TurboFrame.compactStatus("TURBOJ NATIVE · BUILD FAILED | 18 ms"));
        assertEquals("NATIVE COMPILING", TurboFrame.compactStatus("TURBOJ NATIVE · COMPILING"));
        assertEquals("TURBOTEST · FAILED", TurboFrame.compactStatus("TURBOTEST · FAILED"));
        assertEquals("TURBOTEST · PASSED", TurboFrame.compactStatus("TURBOTEST · PASSED"));
        assertEquals("CHECKING…", TurboFrame.compactStatus("CHECKING…"));
    }
}
