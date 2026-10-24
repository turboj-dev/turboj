package dev.turboj.refactorj.runtime;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.Rename;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Seeded generated scopes test binding identity, preview purity, stale rejection and transaction laws. */
class SemanticPropertiesIT {
    @TempDir Path root;
    @Test void generatedRenameScopesPreserveUnrelatedNamesAndRoundTrip()throws Exception{
        Random random=new Random(0x5245464143544f52L);
        try(var runtime=new RefactorJRuntime()){
            for(int iteration=0;iteration<12;iteration++){
                String field="value"+random.nextInt(10000),replacement="result"+random.nextInt(10000);
                String source="class C { int "+field+"; String marker=\"value\"; int f(int value){/* value */return value+this."+field+";} }";
                Path file=root.resolve("C.java");var snapshot=new WorkspaceSnapshot(root,iteration,List.of(new SourceSnapshot(file,source,iteration,true)),List.of(root),List.of());
                try(var engine=runtime.openWorkspace(snapshot)){
                    Plan plan=engine.prepare(new Rename(new Location(file,source.indexOf("value)")),replacement),Cancellation.NONE);
                    assertTrue(plan.valid(),plan.diagnostics().toString());assertEquals(snapshot,engine.workspace().snapshot());
                    String output=TextEdit.apply(source,plan.edits().getFirst().edits());assertTrue(output.contains("int "+replacement+")"));assertTrue(output.contains("return "+replacement+"+this."+field));assertTrue(output.contains("\"value\""));assertTrue(output.contains("/* value */"));
                    assertTrue(engine.validate(plan,Cancellation.NONE).valid());RefactoringAssertions.roundTrip(plan);
                    var changed=new WorkspaceSnapshot(root,iteration+1,List.of(new SourceSnapshot(file,source+"\n",iteration+1,true)),List.of(root),List.of());engine.workspace().synchronize(changed,Cancellation.NONE);assertFalse(engine.validate(plan,Cancellation.NONE).valid());
                }
            }
        }
    }
}
