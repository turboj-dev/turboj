package dev.javafmt.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import java.awt.GridLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.jetbrains.annotations.NotNull;

public final class JavafmtConfigurable implements Configurable {
    private final Project project;
    private JCheckBox enabled;
    private JTextField javaExecutable;
    private JTextField engineJar;

    public JavafmtConfigurable(Project project) {
        this.project = project;
    }

    @Override public @NotNull String getDisplayName() { return "javafmt"; }

    @Override
    public JComponent createComponent() {
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        enabled = new JCheckBox("Use JavaFMT Style 1 for Java files in this project");
        javaExecutable = new JTextField();
        engineJar = new JTextField();
        form.add(enabled);
        JLabel javaLabel = new JLabel("Java 25 executable (absolute path, or java on PATH)");
        javaLabel.setLabelFor(javaExecutable);
        form.add(javaLabel);
        form.add(javaExecutable);
        JLabel jarLabel = new JLabel("Engine JAR (leave empty to use the bundled engine)");
        jarLabel.setLabelFor(engineJar);
        form.add(jarLabel);
        form.add(engineJar);
        form.add(new JLabel("Use Code → Reformat Code for the document or selection."));
        form.add(new JLabel("Optional format on save: Tools → Actions on Save → Reformat code."));
        form.add(new JLabel("Java source stays local. The engine is reused until settings change or the project closes."));
        reset();
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.add(form, java.awt.BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        var state = JavafmtSettings.get(project).getState();
        return enabled != null && (enabled.isSelected() != state.enabled
                || !javaExecutable.getText().trim().equals(state.javaExecutable)
                || !engineJar.getText().trim().equals(state.engineJar));
    }

    @Override
    public void apply() throws ConfigurationException {
        if (javaExecutable.getText().isBlank()) {
            throw new ConfigurationException("Choose a Java 25 executable.");
        }
        var state = new JavafmtSettings.Options();
        state.enabled = enabled.isSelected();
        state.javaExecutable = javaExecutable.getText().trim();
        state.engineJar = engineJar.getText().trim();
        JavafmtSettings.get(project).loadState(state);
        project.getService(FormatterBackend.class).reset();
    }

    @Override
    public void reset() {
        if (enabled == null) return;
        var state = JavafmtSettings.get(project).getState();
        enabled.setSelected(state.enabled);
        javaExecutable.setText(state.javaExecutable);
        engineJar.setText(state.engineJar);
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
        javaExecutable = null;
        engineJar = null;
    }
}
