package dev.turboj.testing;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

/** Real process/receipt/progressive-callback control; no GUI or simulated adapter responses. */
public final class CherryProcessAcceptance {
    public static void main(String[] args) throws Exception {
        if(args.length<6)throw new IllegalArgumentException("cli root events expectedCount expectedState requirePending [selection.json]");
        Path root=Path.of(args[1]),events=Path.of(args[2]); List<TestSelector> selected=new ArrayList<>();
        if(args.length>6)for(var element:JsonParser.parseString(Files.readString(Path.of(args[6]))).getAsJsonObject().getAsJsonArray("selectors")) {
            var value=element.getAsJsonObject(); selected.add(new TestSelector(value.get("kind").getAsString(),value.get("value").getAsString(),
                    value.get("phaseKey").getAsString(),value.get("runtimeId").getAsString(),value.get("adapterId").getAsString(),
                    Path.of(value.get("manifest").getAsString()),value.get("exact").getAsBoolean(),""));
        }
        var updates=new CopyOnWriteArrayList<Run>(); var done=new CountDownLatch(1); StringBuilder log=new StringBuilder();
        try(var process=new CherryTestProcess()) {
            SwingUtilities.invokeAndWait(()->process.run(new CherryTestProcess.Request(root,args[0],events,List.of(),"local",selected),run->{
                updates.add(run); if(!process.busy())done.countDown();
            },log::append));
            if(!done.await(180,TimeUnit.SECONDS))throw new AssertionError("Actual Cherry process did not finish\n"+log);
            Run result=updates.getLast();
            Files.writeString(events.resolveSibling(events.getFileName()+".controller.log"),log);
            if(!result.state().name().equals(args[4])||result.tests().size()!=Integer.parseInt(args[3]))throw new AssertionError(result.message()+" tests="+result.tests().size()+"\n"+log);
            long pending=updates.stream().filter(run->run.tests().stream().anyMatch(test->test.status()==TestStatus.PENDING)).count();
            if(Boolean.parseBoolean(args[5])&&pending==0)throw new AssertionError("No genuine pending test callback was observed");
            if(!CherryExecutionReceipt.matchesRetainedReceipt(events))throw new AssertionError("Process receipt missing or mismatched");
            Files.writeString(events.resolveSibling(events.getFileName()+".controller.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "state",result.state().name(),"tests",result.tests().size(),"runId",result.id(),"progressUpdates",updates.size(),"pendingCallbacks",pending,
                    "exactSelectors",selected.size(),"receiptMatches",true)));
            CherryFeedbackAcceptance.main(new String[]{root.toString(),events.toString(),args[3]});
        }
    }
}
