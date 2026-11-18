package dev.turboj.testing;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class CoverageCollectionDetailsTest {
    @Test void independentObservationExplainsCountersAndDoesNotUpgradeUnsavedSource() {
        Path file = Path.of("/project/Price.java");
        Coverage coverage = new Coverage(CoverageState.PARTIAL,Freshness.CURRENT,Availability.AVAILABLE,
                Precision.INSTRUCTIONS_AND_BRANCHES,7,2,1,1,"JaCoCo","unit","run","process",sha256("old"),"class","measured");
        CoverageObservation observation = new CoverageObservation("collection","invocation","project","module","package",
                "unit","spring",Path.of("/project/coverage.lcov"),Map.of(file,Map.of(2,coverage)));
        Snapshot snapshot = new Snapshot(new SourceIdentity(file.getParent(),file.getParent(),"project","module",file,"document",1,
                sha256("new"),""),Map.of(),List.of(),false,true,"HEAD","");
        String details = LineIntelligenceDialogs.describe(observation,file,2,snapshot);
        assertTrue(details.contains("Freshness: STALE"));
        assertTrue(details.contains("Instructions covered/missed: 7/2"));
        assertTrue(details.contains("Branches covered/missed: 1/1"));
        assertTrue(details.contains("Runtime: spring"));
        assertTrue(details.contains("counters are never added"));
        assertFalse(details.contains("passed"));
    }
    @Test void absentCountersStayUnknownAndLargeCollectionsHaveBoundedText() {
        Path file = Path.of("/project/Price.java");
        Map<Integer,Coverage> lines = new LinkedHashMap<>();
        for (int line = 1; line <= 10_000; line++) lines.put(line,Coverage.unknown(Availability.MISSING,"not supplied"));
        CoverageObservation observation = new CoverageObservation("c","i","p","m","p","unit","jvm",null,Map.of(file,lines));
        String details = LineIntelligenceDialogs.describe(observation,null,0,null);
        assertTrue(details.contains("first 200 source rows"));
        assertTrue(details.contains("Instructions covered/missed: unknown/unknown"));
        assertEquals(200,details.split("Measured state:",-1).length - 1);
    }
}
