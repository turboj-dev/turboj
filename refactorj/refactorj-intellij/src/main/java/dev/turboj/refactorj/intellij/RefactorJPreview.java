package dev.turboj.refactorj.intellij;

import com.google.gson.*;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;
import java.util.*;

final class RefactorJPreview extends DialogWrapper {
    private final JPanel panel = new JPanel(new BorderLayout());

    RefactorJPreview(Project project, JsonObject prepared) {
        super(project);
        JsonObject plan = prepared.getAsJsonObject("plan");
        setTitle("RefactorJ — " + plan.get("description").getAsString());
        setOKButtonText("Apply all changes");
        JTabbedPane tabs = new JTabbedPane();
        Map<String, String> sources = new HashMap<> ();

        for (JsonElement element : plan.getAsJsonObject("before").getAsJsonArray("sources")) {
            var source = element.getAsJsonObject();
            sources.put(source.get("file").getAsString(), source.get("text").getAsString());
        }
        for (JsonElement element : plan.getAsJsonArray("edits")) {
            JsonObject change = element.getAsJsonObject();
            String path = change.get("file").getAsString(), before = sources.get(path),
                    after = before;
            JsonArray edits = change.getAsJsonArray("edits");

            for (int i = edits.size() - 1; i >= 0; i--) {
                JsonObject edit = edits.get(i).getAsJsonObject();
                after = after.substring(0, edit.get("start").getAsInt()) + edit.get("text")
                        .getAsString() + after.substring(edit.get("end").getAsInt());
            }
            diff(project, tabs, path, before, after);
        }
        for (JsonElement element : plan.getAsJsonArray("fileOperations")) {
            JsonObject op = element.getAsJsonObject();
            String path = op.get("path").getAsString(), kind = op.get("kind").getAsString();
            diff(project, tabs, kind + " " + path + (kind.equals("MOVE") ? " → " + op
                    .get("destination").getAsString() : ""), kind.equals("CREATE") ? "" : sources
                    .getOrDefault(path, ""), kind.equals("CREATE") ? op.get("content")
                    .getAsString() : kind.equals("DELETE") ? "" : sources.getOrDefault(path, ""));
        }
        JTextArea diagnostics = new JTextArea();
        diagnostics.setEditable(false);
        diagnostics.setLineWrap(true);
        diagnostics.setWrapStyleWord(true);
        for (JsonElement value : plan.getAsJsonArray("diagnostics")) {
            var diagnostic = value.getAsJsonObject();
            diagnostics.append(diagnostic.get("severity").getAsString() + ": " + diagnostic
                    .get("message").getAsString() + "\n");
        }
        panel.add(tabs, BorderLayout.CENTER);
        panel.add(new JScrollPane(diagnostics), BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(960, 620));
        init();
    }

    private void diff(Project project, JTabbedPane tabs, String title, String before,
            String after) {
        var factory = DiffContentFactory.getInstance();
        var request = new SimpleDiffRequest(title, factory.create(project, before), factory
                .create(project, after), "Before", "After");
        var viewer = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        viewer.setRequest(request);
        tabs.addTab(java.nio.file.Path.of(title).getFileName().toString(), viewer.getComponent());
    }

    @Override
    protected JComponent createCenterPanel() {
        return panel;
    }
}
