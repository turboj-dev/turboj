package dev.turboj.refactorj.intellij;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;

@State(name = "RefactorJ", storages = @Storage("refactorj.xml"))
public final class RefactorJSettings implements PersistentStateComponent<RefactorJSettings.State> {
    public static final class State {
        public boolean forceResident;
        public String javaExecutable = "java";
        public String sourceRoots = "";
        public String classpath = "";
    }

    private State state = new State();

    public static RefactorJSettings get(Project project) {
        return project.getService(RefactorJSettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(State value) {
        state = value;
    }
}
