package dev.turboj.testing;

import dev.turboj.commandj.swing.*;
import dev.turboj.testing.intelligence.LineIntelligence;
import dev.turboj.ui.EditorComponent;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

public final class LineIntelligenceDialogs {
    private LineIntelligenceDialogs() {}
    public static void inspect(JFrame owner,EditorComponent editor,EditorComponent.LineInspection target,
            Runnable showTests,Runnable createTest) {
        var dialog = new EmbeddedDialog(owner,"Line Intelligence — " + target.line(),false);
        Object document = editor.document();
        var sections = new JList<>(new String[]{"Git","Tests","Coverage"});
        sections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextArea detail = text(editor);
        Runnable update = () -> {
            Lane lane = Lane.values()[Math.max(0,sections.getSelectedIndex())];
            String description = editor.lineIntelligenceDescription(target.line(),lane);
            Snapshot snapshot = editor.lineIntelligence();
            if (snapshot != null && lane == Lane.GIT && target.deletionAnchor() != null) {
                description += "\nDeletion boundary " + target.deletionAnchor() + " (not current source):\n";
                for (Deletion deletion : snapshot.deletions()) if (deletion.anchorLine() == target.deletionAnchor())
                    description += deletion.text();
            }
            detail.setText(description + (snapshot == null ? "" : "\n\n" + snapshot.message())); detail.setCaretPosition(0);
        };
        sections.addListSelectionListener(event -> update.run());
        sections.setSelectedIndex(target.lane() == null ? 0 : target.lane().ordinal());
        sections.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"detail");
        sections.getActionMap().put("detail",new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { detail.requestFocusInWindow(); }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton tests = new JButton("Test results"), create = new JButton("Quick Fix at line " + target.line() + "…"), close = new JButton("Close");
        tests.addActionListener(event -> { dialog.dispose(); showTests.run(); });
        create.addActionListener(event -> {
            if (editor.document() != document) { detail.setText("The source document changed. Inspect the current source again."); return; }
            dialog.dispose(); editor.gotoLine(target.line()); createTest.run();
        });
        close.addActionListener(event -> dialog.dispose());
        actions.add(tests); actions.add(create); actions.add(close);
        dialog.setLayout(new BorderLayout(8,8)); dialog.add(new JScrollPane(sections),BorderLayout.WEST);
        dialog.add(new JScrollPane(detail),BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH);
        finish(dialog,editor,sections); update.run();
    }

    public static Consumer<Run> results(JFrame owner,EditorComponent editor,Run run,Consumer<Association> navigate,
            Consumer<Run> rerun,Consumer<Run> cancel,Consumer<Run> report) {
        if (run == null) return ignored -> {};
        Run[] current = {run};
        var dialog = new EmbeddedDialog(owner,"Cherry Test — " + run.state(),false);
        var list = new JList<>(run.tests().toArray(TestResult[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view,test,index,selected,focus) -> {
            JLabel label = new JLabel("[" + test.status() + "] " + test.name() + " · " + test.runtime() + " / " + test.phase());
            label.setOpaque(true); label.setFont(editor.getFont());
            label.setForeground(selected ? view.getSelectionForeground() : view.getForeground());
            label.setBackground(selected ? view.getSelectionBackground() : view.getBackground());
            return label;
        });
        JTextArea details = text(editor);
        list.addListSelectionListener(event -> {
            TestResult test = list.getSelectedValue();
            details.setText(test == null ? current[0].message() : describe(test)); details.setCaretPosition(0);
        });
        Runnable open = () -> {
            TestResult test = list.getSelectedValue();
            if (test == null) return;
            var location = test.associations().stream().filter(Association::verified).findFirst();
            if (location.isEmpty()) { details.setText(describe(test) + "\nNo source location supplied by this adapter."); return; }
            dialog.dispose(); navigate.accept(location.get());
        };
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"open");
        list.getActionMap().put("open",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { open.run(); } });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        button(actions,"Open source",open); button(actions,"Rerun configured suites",() -> { dialog.dispose(); rerun.accept(current[0]); });
        button(actions,"Cancel execution",() -> cancel.accept(current[0])); button(actions,"Full report",() -> report.accept(current[0])); button(actions,"Close",dialog::dispose);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),new JScrollPane(details));
        split.setResizeWeight(.5);
        JLabel progress = new JLabel(run.message());
        dialog.setLayout(new BorderLayout()); dialog.add(progress,BorderLayout.NORTH);
        dialog.add(split,BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH);
        if (!run.tests().isEmpty()) list.setSelectedIndex(0); else details.setText(run.message());
        finish(dialog,editor,list);
        return update -> {
            if (dialog.isClosed()) return;
            if (!Objects.equals(update.root(),current[0].root())) { dialog.dispose(); return; }
            current[0] = update;
            String selected = list.getSelectedValue() == null ? "" : list.getSelectedValue().id();
            list.setListData(update.tests().toArray(TestResult[]::new));
            int selectedIndex = 0;
            for (int i = 0; i < update.tests().size(); i++) if (update.tests().get(i).id().equals(selected)) selectedIndex = i;
            if (!update.tests().isEmpty()) list.setSelectedIndex(selectedIndex); else details.setText(update.message());
            progress.setText(update.message()); dialog.setTitle("Cherry Test — " + update.state());
        };
    }
    public static String describe(TestResult test) {
        return test.name() + "\nStatus: " + test.status() + "\nRun: " + test.runId() + "\nIdentity: " + test.id()
                + "\nProject/module: " + test.project() + " / " + test.module() + "\nRuntime: " + test.runtime()
                + "\nPhase: " + test.phase() + "\nDuration: " + test.durationMillis() + " ms\nSource validity: "
                + test.freshness() + "\n" + test.failure() + "\nLocations: " + test.associations();
    }
    private static JTextArea text(EditorComponent editor) {
        var text = new JTextArea(); text.setEditable(false); text.setFont(editor.getFont()); return text;
    }
    private static void button(JPanel panel,String title,Runnable action) {
        JButton button = new JButton(title); button.addActionListener(event -> action.run()); panel.add(button);
    }
    private static void finish(EmbeddedDialog dialog,EditorComponent editor,JComponent initial) {
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.onClosed(editor::requestFocusInWindow);
        dialog.setSize(850,540); dialog.setLocationRelativeTo(editor); dialog.setVisible(true);
        SwingUtilities.invokeLater(initial::requestFocusInWindow);
    }
}
