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
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import static dev.turboj.testing.intelligence.LineIntelligence.*;

public final class LineIntelligenceDialogs {
    private LineIntelligenceDialogs() {}
    public static void inspect(JFrame owner,EditorComponent editor,EditorComponent.LineInspection target,
            Runnable showTests,Runnable createTest) {
        inspect(owner,editor,target,showTests,createTest,() -> null);
    }
    public static void inspect(JFrame owner,EditorComponent editor,EditorComponent.LineInspection target,
            Runnable showTests,Runnable createTest,Supplier<Run> currentRun) {
        var dialog = new EmbeddedDialog(owner,"Line Intelligence — " + target.line(),false);
        Object document = editor.document();
        var sections = new JList<>(new String[]{"Git","Tests","Coverage"});
        sections.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextArea detail = text(editor);
        Runnable update = () -> {
            Lane lane = Lane.values()[Math.max(0,sections.getSelectedIndex())];
            String description = editor.lineIntelligenceDescription(target.line(),lane);
            Snapshot snapshot = editor.lineIntelligence();
            if (snapshot != null && lane == Lane.COVERAGE) {
                Coverage coverage = snapshot.line(target.line()).coverage();
                var proof = coverage.proof();
                description += "\n\n" + coverage.reason()
                        + "\n\nCoverage legend: ■ covered (C), □ uncovered (U), ◧ partial (P)."
                        + "\nASCII letters are used when a symbol cannot fit the editor font."
                        + "\nBlue row: covered or partial (◧); yellow row: uncovered. No coverage underline."
                        + "\nCoverage backgrounds: " + (editor.isShowCoverageBackgrounds() ? "shown" : "hidden")
                        + " (View → Show Coverage Backgrounds)."
                        + "\n? unknown/unverified, ~ stale. Not-applicable coverage has no symbol. Neutral rows make no coverage claim."
                        + "\nCoverage does not establish that a test passed.";
                if (proof != null) description += "\nCompiler: " + proof.compilerIdentity()
                        + "\nCompilation: " + proof.compilationId() + "\nSource SHA-256: " + proof.sourceHash()
                        + "\nClass identity: " + proof.classIdentity() + "\nInstrumented process: " + proof.processId()
                        + "\nJaCoCo session: " + proof.sessionId() + "\nCollection: " + proof.collectionId()
                        + "\nCaptured class files: " + proof.classFiles().size()
                        + "\nAggregate execution evidence; no per-test production-line attribution.";
            }
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
        actions.add(tests); actions.add(create);
        button(actions,"Coverage collections…",() -> {
            Snapshot snapshot = editor.lineIntelligence();
            if (snapshot == null || editor.document() != document) {
                detail.setText("The source document changed. Inspect the current source again."); return;
            }
            collections(owner,editor,currentRun.get(),snapshot.source().file(),target.line());
        });
        actions.add(close);
        dialog.setLayout(new BorderLayout(8,8)); dialog.add(new JScrollPane(sections),BorderLayout.WEST);
        dialog.add(new JScrollPane(detail),BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH);
        finish(dialog,editor,sections); update.run();
    }

    public static void collections(JFrame owner,EditorComponent editor,Run run,java.nio.file.Path file,int line) {
        var dialog = new EmbeddedDialog(owner,"Cherry Test — Coverage collections",false);
        List<CoverageObservation> observations = run == null ? List.of() : run.collections().stream()
                .filter(collection -> file == null || collection.coverage().getOrDefault(file,Map.of()).containsKey(line)).toList();
        var list = new JList<>(observations.toArray(CoverageObservation[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view,value,index,selected,focus) -> {
            JLabel label = new JLabel(value.packageName() + " / " + value.phase() + " / " + value.runtime()
                    + " · " + (value.artifact() == null ? value.id() : value.artifact().getFileName()));
            label.setOpaque(true); label.setFont(editor.getFont());
            label.setForeground(selected ? view.getSelectionForeground() : view.getForeground());
            label.setBackground(selected ? view.getSelectionBackground() : view.getBackground()); return label;
        });
        JTextArea detail = text(editor);
        list.addListSelectionListener(event -> {
            CoverageObservation selected = list.getSelectedValue();
            Snapshot snapshot = editor.lineIntelligence();
            detail.setText(selected == null ? "No collection selected." : describe(selected,file,line,snapshot));
            detail.setCaretPosition(0);
        });
        if (observations.isEmpty()) detail.setText("No individual coverage collection is available for this location.\n"
                + "Missing data does not mean zero coverage. Legacy reports may contain only aggregate data.");
        else list.setSelectedIndex(0);
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"inspect");
        list.getActionMap().put("inspect",new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { detail.requestFocusInWindow(); }
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(list),new JScrollPane(detail));
        split.setResizeWeight(.35);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING)); button(actions,"Close",dialog::dispose);
        dialog.setLayout(new BorderLayout()); dialog.add(split,BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH);
        finish(dialog,editor,list);
    }

    public static String describe(CoverageObservation observation,java.nio.file.Path file,int line,Snapshot snapshot) {
        StringBuilder result = new StringBuilder("Independent collection; counters are never added across collections.\n")
                .append("Identity: ").append(observation.id()).append("\nInvocation: ").append(observation.invocation())
                .append("\nProject/module: ").append(observation.project()).append(" / ").append(observation.module())
                .append("\nRuntime: ").append(observation.runtime()).append("\nPhase: ").append(observation.phase())
                .append("\nArtifact: ").append(observation.artifact());
        var rows = observation.coverage().entrySet().stream().filter(entry -> file == null || file.equals(entry.getKey()))
                .flatMap(entry -> entry.getValue().entrySet().stream().filter(item -> file == null || item.getKey() == line)
                        .map(item -> new AbstractMap.SimpleImmutableEntry<>(entry.getKey(),item))).limit(201).toList();
        if (rows.size() > 200) result.append("\nDetails show the first 200 source rows; the complete artifact is identified above.");
        rows.stream().limit(200).forEach(row -> {
                var source = row.getKey(); int number = row.getValue().getKey(); var value = row.getValue().getValue();
                Freshness freshness = value.freshness();
                if (snapshot != null && snapshot.source().file().equals(source) && !value.sourceHash().isBlank()
                        && !snapshot.source().sourceHash().equals(value.sourceHash())) freshness = Freshness.STALE;
                result.append("\n\n").append(source).append(':').append(number).append("\nMeasured state: ").append(value.state())
                        .append("\nFreshness: ").append(freshness).append("\nAvailability: ").append(value.availability())
                        .append("\nPrecision: ").append(value.precision()).append("\nInstructions covered/missed: ")
                        .append(counter(value.coveredInstructions())).append('/').append(counter(value.missedInstructions()))
                        .append("\nBranches covered/missed: ").append(counter(value.coveredBranches())).append('/').append(counter(value.missedBranches()))
                        .append("\nInstrumented process: ").append(known(value.processId())).append("\nSource SHA-256: ").append(known(value.sourceHash()))
                        .append("\nClass identity: ").append(known(value.classIdentity())).append("\n").append(value.reason());
                if (value.proof() != null) result.append("\nCollection: ").append(value.proof().collectionId())
                        .append("\nCompilation: ").append(value.proof().compilationId()).append("\nSessions: ").append(value.proof().sessionId());
        });
        return result.toString();
    }
    private static String counter(long value) { return value < 0 ? "unknown" : Long.toString(value); }

    public static Consumer<Run> results(JFrame owner,EditorComponent editor,Run run,Consumer<Association> navigate,
            Consumer<Run> rerun,Consumer<Run> cancel,Consumer<Run> report) {
        return results(owner,editor,run,navigate,rerun,cancel,report,() -> List.of(run));
    }
    public static Consumer<Run> results(JFrame owner,EditorComponent editor,Run run,Consumer<Association> navigate,
            Consumer<Run> rerun,Consumer<Run> cancel,Consumer<Run> report,Supplier<List<Run>> history) {
        return results(owner,editor,run,navigate,rerun,cancel,report,history,null);
    }
    public static Consumer<Run> results(JFrame owner,EditorComponent editor,Run run,Consumer<Association> navigate,
            Consumer<Run> rerun,Consumer<Run> cancel,Consumer<Run> report,Supplier<List<Run>> history,
            BiConsumer<Run,List<TestResult>> selectedRerun) {
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
            details.setText((test == null ? "" : describe(test) + "\n\n") + describe(current[0])); details.setCaretPosition(0);
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
        JPanel actions = new JPanel(new GridLayout(0,3,6,6));
        button(actions,"Open source",open); button(actions,"Rerun configured suites",() -> { dialog.dispose(); rerun.accept(current[0]); });
        if (selectedRerun != null) {
            Consumer<List<TestResult>> selected = values -> {
                String reason = selectionProblem(values);
                if (reason != null) { details.setText(reason + "\nUse Rerun configured suites to run the complete safe scope."); details.setCaretPosition(0); return; }
                dialog.dispose(); selectedRerun.accept(current[0],values);
            };
            button(actions,"Rerun selected",() -> selected.accept(list.getSelectedValue() == null ? List.of() : List.of(list.getSelectedValue())));
            button(actions,"Rerun failed",() -> selected.accept(current[0].tests().stream().filter(test -> test.status() == TestStatus.FAILED).toList()));
        }
        button(actions,"Cancel execution",() -> cancel.accept(current[0])); button(actions,"Full report",() -> report.accept(current[0])); button(actions,"Close",dialog::dispose);
        button(actions,"Coverage collections…",() -> collections(owner,editor,current[0],null,0));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(list),new JScrollPane(details));
        split.setResizeWeight(.5);
        JLabel progress = new JLabel(run.message());
        JComboBox<Run> executions = new JComboBox<>(); executions.setName("cherry.executions");
        executions.getAccessibleContext().setAccessibleName("Cherry executions; each run remains independent");
        executions.setRenderer((view,value,index,selected,focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.id() + " · " + value.state() + " · " + value.root());
            label.setOpaque(true); label.setForeground(selected ? view.getSelectionForeground() : view.getForeground());
            label.setBackground(selected ? view.getSelectionBackground() : view.getBackground()); return label;
        });
        boolean[] refreshing = {false};
        boolean[] followCurrent = {true};
        Runnable reload = () -> {
            refreshing[0] = true;
            executions.removeAllItems();
            for (Run item : history.get()) if (Objects.equals(item.root(),run.root())) executions.addItem(item);
            if (executions.getItemCount() == 0) executions.addItem(current[0]);
            for (int i = 0; i < executions.getItemCount(); i++) if (executions.getItemAt(i).id().equals(current[0].id())) executions.setSelectedIndex(i);
            refreshing[0] = false;
        };
        Runnable display = () -> {
            String selected = list.getSelectedValue() == null ? "" : list.getSelectedValue().id();
            list.setListData(current[0].tests().toArray(TestResult[]::new));
            int selectedIndex = 0;
            for (int i = 0; i < current[0].tests().size(); i++) if (current[0].tests().get(i).id().equals(selected)) selectedIndex = i;
            if (!current[0].tests().isEmpty()) list.setSelectedIndex(selectedIndex); else details.setText(current[0].message());
            progress.setText(current[0].message()); dialog.setTitle("Cherry Test — " + current[0].state());
        };
        executions.addActionListener(event -> {
            if (refreshing[0] || executions.getSelectedItem() == null) return;
            current[0] = (Run) executions.getSelectedItem(); followCurrent[0] = executions.getSelectedIndex() == 0; display.run();
        });
        reload.run();
        JPanel heading = new JPanel(new BorderLayout()); heading.add(executions,BorderLayout.NORTH); heading.add(progress,BorderLayout.SOUTH);
        dialog.setLayout(new BorderLayout()); dialog.add(heading,BorderLayout.NORTH);
        dialog.add(split,BorderLayout.CENTER); dialog.add(actions,BorderLayout.SOUTH);
        if (!run.tests().isEmpty()) list.setSelectedIndex(0); else details.setText(run.message());
        finish(dialog,editor,list);
        return update -> {
            if (dialog.isClosed()) return;
            if (!Objects.equals(update.root(),current[0].root())) { dialog.dispose(); return; }
            boolean followsLatest = followCurrent[0] || current[0].id().equals(update.id());
            if (followsLatest) current[0] = update;
            reload.run(); if (followsLatest) display.run();
        };
    }
    public static String describe(TestResult test) {
        return test.name() + "\nStatus: " + test.status() + "\nRun: " + test.runId() + "\nIdentity: " + test.id()
                + "\nProject/module: " + test.project() + " / " + test.module() + "\nRuntime: " + test.runtime()
                + "\nPhase: " + test.phase() + "\nDuration: " + test.durationMillis() + " ms\nSource validity: "
                + test.freshness() + "\nRepository: " + known(test.repositoryId()) + "\nSource module: " + (test.moduleRoot() == null ? "unknown" : test.moduleRoot())
                + "\nExact rerun: " + (test.selector() != null && test.selector().exact() ? test.selector().kind() + " / " + test.selector().value()
                        : test.selector() == null ? "Unavailable: adapter did not supply a selector" : test.selector().reason())
                + "\n" + test.failure() + "\nLocations: " + test.associations();
    }
    public static String selectionProblem(List<TestResult> tests) {
        if (tests.isEmpty()) return "No matching test is selected.";
        if (tests.size() > 1000) return "Exact rerun supports up to 1000 selectors in one request.";
        for (TestResult test : tests) if (test.selector() == null || !test.selector().exact())
            return "Exact rerun is unavailable for " + test.name() + " (" + test.runtime() + "): "
                    + (test.selector() == null ? "adapter selection capability is unknown" : test.selector().reason());
        return null;
    }
    private static String known(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    public static String describe(Run run) {
        String progress = run.context().capabilities().getOrDefault("testProgress","unknown");
        String summary = "Execution: " + run.id() + "\nWorkspace: " + run.root() + "\nState: " + run.state()
                + "\nPer-test progress: " + (progress.equals("true") ? "supported by this producer"
                        : progress.equals("false") ? "unavailable; actual phase results are reported" : "unknown")
                + "\nProgress scope: " + known(run.context().capabilities().get("testProgressScope"))
                + "\nReported test results: " + run.tests().size() + "\nIndividual coverage collections: " + run.collections().size()
                + "\nComplete discovery inventory is not inferred from the number of reported results.\n" + run.message();
        for (PhaseExecution phase : run.context().phases().values()) summary += "\n" + phase.project() + " / " + phase.module()
                + " / " + phase.phase() + " / " + phase.runtime() + ": " + phase.state()
                + "; " + phase.reportedTests() + " results; inventory " + phase.inventoryCompleteness();
        return summary;
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
