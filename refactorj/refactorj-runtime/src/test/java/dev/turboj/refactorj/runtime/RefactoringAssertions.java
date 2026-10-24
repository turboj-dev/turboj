package dev.turboj.refactorj.runtime;
import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class RefactoringAssertions {
    static void roundTrip(Plan plan){
        var memory=new InMemoryWorkspace(plan.before());var transactions=new WorkspaceTransactions(memory);
        Map<Path,String> original=texts(memory.snapshot());var applied=transactions.apply(plan,Cancellation.NONE);Map<Path,String> changed=texts(memory.snapshot());
        assertNotEquals(original,changed,"A valid refactoring must apply real changes");
        transactions.undo(applied.transactionId(),Cancellation.NONE);assertEquals(original,texts(memory.snapshot()),"Undo must restore every source and file identity");
        transactions.redo(applied.transactionId(),Cancellation.NONE);assertEquals(changed,texts(memory.snapshot()),"Redo must reproduce every source and file identity");
    }
    private static Map<Path,String> texts(WorkspaceSnapshot snapshot){var result=new TreeMap<Path,String>();for(var source:snapshot.sources())result.put(source.file(),source.text());return result;}
}
