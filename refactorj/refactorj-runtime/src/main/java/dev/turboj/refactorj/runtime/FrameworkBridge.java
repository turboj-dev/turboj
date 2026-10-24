package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.RefactorJ;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import java.nio.file.Path;
import java.util.List;

/** Internal bootstrap boundary; compiler/framework classes never cross this interface. */
public interface FrameworkBridge {
    void start(Path directory, List<Path> bundles) throws Exception;
    RefactorJ open(WorkspaceSnapshot snapshot) throws Exception;
    boolean stop() throws Exception;
}
