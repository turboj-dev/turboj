package dev.turboj.testing.intelligence;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class CherryRunHistoryTest {
    private Run run(String id,String root,long sequence,String message) {
        return new Run(id,Path.of(root),RunState.RUNNING,List.of(),Map.of(),Map.of(),message,null,sequence);
    }
    @Test void runtimesAndRepositoriesRemainSeparateAndOlderEventsCannotReplaceNewerOnes() {
        var history = new CherryRunHistory();
        history.accept(run("same","/one",1,"pending")); history.accept(run("same","/two",1,"other repository"));
        history.accept(run("same","/one",3,"current")); history.accept(run("same","/one",2,"late"));
        assertEquals(2,history.snapshot().size());
        assertEquals("current",history.snapshot().stream().filter(run -> run.root().equals(Path.of("/one"))).findFirst().orElseThrow().message());
        assertThrows(UnsupportedOperationException.class,() -> history.snapshot().clear());
    }
    @Test void completedRerunsDoNotOverwriteOriginalExecutionsAndRetentionIsBounded() {
        var history = new CherryRunHistory();
        for (int index = 0; index < 20; index++) history.accept(run("run-" + index,"/project",1,"result " + index));
        assertEquals(12,history.snapshot().size()); assertEquals("run-19",history.snapshot().getFirst().id());
        assertEquals("run-8",history.snapshot().getLast().id());
    }
}
