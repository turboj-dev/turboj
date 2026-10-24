package dev.turboj.refactorj.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;

public final class RefactorJConfigurable implements Configurable {
    private final Project project;
    private final JCheckBox resident = new JCheckBox("Use a resident backend for compatibility (default: embed on Java 25+)");
    private final JTextField java = new JTextField(), roots = new JTextField(),
            classpath = new JTextField();

    public RefactorJConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "RefactorJ";
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(resident);
        panel.add(new JLabel("Java 25 executable (resident local backend)"));
        panel.add(java);
        panel.add(new JLabel("Source roots (platform path separator; empty uses project)"));
        panel.add(roots);
        panel.add(new JLabel("Dependency classpath (platform path separator)"));
        panel.add(classpath);
        panel
                .add(new JLabel("Use Refactor → RefactorJ. Built-in IDEA refactoring remains available."));
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        var state = RefactorJSettings.get(project).getState();
        return resident.isSelected() != state.forceResident || !java.getText().equals(state.javaExecutable) || !roots.getText()
                .equals(state.sourceRoots) || !classpath.getText().equals(state.classpath);
    }

    @Override
    public void apply() {
        var state = RefactorJSettings.get(project).getState();
        state.forceResident = resident.isSelected();
        state.javaExecutable = java.getText().trim();
        state.sourceRoots = roots.getText().trim();
        state.classpath = classpath.getText().trim();
        project.getService(RefactorJBackend.class).reset();
    }

    @Override
    public void reset() {
        var state = RefactorJSettings.get(project).getState();
        resident.setSelected(state.forceResident);
        java.setText(state.javaExecutable);
        roots.setText(state.sourceRoots);
        classpath.setText(state.classpath);
    }
}
