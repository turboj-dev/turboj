package dev.turboj.testing;

import com.google.gson.*;
import dev.turboj.testing.intelligence.*;
import java.nio.file.*;
import java.util.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

/** Qualification of owned real CLI events; process exit is retained separately in the launch log. */
public final class CherryFeedbackAcceptance {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("workspace events expected-test-count");
        Path root=Path.of(args[0]).toRealPath(), events=Path.of(args[1]).toAbsolutePath();
        CherryEvents reader=new CherryEvents(root,true); int starts=0, completions=0;
        for(String line:Files.readAllLines(events)) {
            var event=JsonParser.parseString(line).getAsJsonObject();
            if(event.get("event").getAsString().equals("test.started")) starts++;
            if(event.get("event").getAsString().equals("test.completed")) completions++;
            reader.accept(line);
        }
        Run run=TestSourceResolver.resolve(reader.snapshot());
        require(reader.terminal(),"terminal event");
        require(run.state()==RunState.COMPLETED||run.state()==RunState.FAILED,"complete actual execution: "+run.message());
        require(run.tests().size()==Integer.parseInt(args[2]),"expected real test count");
        require(run.tests().stream().map(TestResult::id).distinct().count()==run.tests().size(),"test identities do not collide");
        for(TestResult test:run.tests()) {
            require(test.moduleRoot()!=null&&!test.repositoryId().isBlank(),"module and repository identity");
            require(test.selector()!=null,"explicit selector capability");
            require(!test.associations().isEmpty(),"fixture test declaration has a verified source location: " + test.name());
            require(test.associations().stream().allMatch(location->location.file().startsWith(test.moduleRoot())),"source location stays in actual module");
            require(test.associations().stream().noneMatch(location->location.kind()==Attribution.DYNAMIC_EXECUTION),"no invented dynamic per-test coverage");
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("runId",run.id()); report.put("outcome",run.state().name()); report.put("tests",run.tests().size());
        report.put("passed",run.tests().stream().filter(test->test.status()==TestStatus.PASSED).count());
        report.put("failed",run.tests().stream().filter(test->test.status()==TestStatus.FAILED).count());
        report.put("genuineStartedEvents",starts); report.put("genuineCompletedEvents",completions);
        report.put("repositories",run.context().repositories().stream().map(repository->Map.of("id",repository.id(),"root",repository.root().toString())).toList());
        report.put("collections",run.collections().size()); report.put("capabilities",run.context().capabilities());
        report.put("testDetails",run.tests().stream().map(test->Map.of("name",test.name(),"module",test.moduleRoot().toString(),
                "repository",test.repositoryId(),"selector",test.selector().kind(),"exact",test.selector().exact(),
                "locations",test.associations().stream().map(location->location.file()+":"+location.line()).toList())).toList());
        Map<String,Map<String,Long>> coverage=new TreeMap<>();
        for(var entry:run.coverage().entrySet()) {
            var states=new TreeMap<String,Long>();
            for(var value:entry.getValue().values()) states.merge(value.state()+"/"+value.freshness()+"/"+value.availability(),1L,Long::sum);
            coverage.put(entry.getKey().toString(),states);
            if(Files.isRegularFile(entry.getKey())) {
                String source=Files.readString(entry.getKey());
                var snapshot=LineIntelligenceController.snapshot(entry.getKey(),source+"\n// unsaved qualification\n",1,new Object(),root,
                        new GitBufferDiff.Result(null,"HEAD","",Map.of(),List.of(),"not part of this coverage check"),run);
                require(snapshot.lines().values().stream().noneMatch(value->value.coverage().availability()==Availability.AVAILABLE),"unsaved source invalidates current coverage");
            }
        }
        report.put("coverage",coverage);
        Files.writeString(events.resolveSibling(events.getFileName()+".validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        List<TestSelector> eligible=run.tests().stream().map(TestResult::selector).filter(TestSelector::exact).toList();
        if(!eligible.isEmpty()) Files.writeString(events.resolveSibling(events.getFileName()+".selected.json"),CherryTestProcess.selection(List.of(eligible.getFirst())));
        List<TestSelector> failed=run.tests().stream().filter(test->test.status()==TestStatus.FAILED).map(TestResult::selector).filter(TestSelector::exact).toList();
        if(!failed.isEmpty()) Files.writeString(events.resolveSibling(events.getFileName()+".failed.json"),CherryTestProcess.selection(failed));
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static void require(boolean condition,String label) { if(!condition)throw new AssertionError(label); }
}
