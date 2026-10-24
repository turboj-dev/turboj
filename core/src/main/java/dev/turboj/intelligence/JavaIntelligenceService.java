package dev.turboj.intelligence;

import dev.turboj.refactorj.api.JavaIntelligence;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.refactorj.api.RefactorJ;

/** Compatibility entry point; all semantic services run on the same serial workspace worker. */
public interface JavaIntelligenceService extends JavaIntelligence {
    default RefactorJ sharedRefactorJ(Context context) {
        throw new UnsupportedOperationException("This intelligence backend does not support embedded refactoring");
    }
    default RefactorJ sharedRefactorJ(Context context, WorkspaceSnapshot snapshot) {
        throw new UnsupportedOperationException("This intelligence backend does not support embedded refactoring");
    }
}
