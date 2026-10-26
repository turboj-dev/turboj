package dev.turboj.intelligence;

import dev.turboj.refactoring.NativeWorkspaceCommitter;
import dev.turboj.refactoring.RefactoringController;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaQuickActions.*;
import dev.turboj.refactorj.api.Model.Location;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;
import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiStyle;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;

/** Contextual proposals on the existing semantic lane and CommandJ invocation route. */
public final class QuickFixController implements AutoCloseable {
    private final JFrame owner;
    private final EditorComponent editor;
    private final IntelligenceController intelligence;
    private final Supplier<IntelligenceController.Snapshot> snapshots;
    private final NativeWorkspaceCommitter.Host host;
    private final RefactoringController refactoring;
    private final Consumer<Prepared> navigate;
    private final BiConsumer<Inspection, Proposal> junit;
    private final Consumer<String> notice;
    private InlinePopup popup;
    private JTextField filter;
    private JList<Proposal> list;
    private JTextArea explanation;
    private JLabel heading;
    private JPanel body;
    private AsciiPalette palette = AsciiPalette.defaults();
    private Inspection inspection;
    private NativeWorkspaceCommitter.Capture capture;
    private EditorComponent.Location selection;
    private boolean assists;
    private volatile boolean closed;
    private volatile long epoch;
    private long navigationEpoch;

    public QuickFixController(JFrame owner, EditorComponent editor, IntelligenceController intelligence,
            Supplier<IntelligenceController.Snapshot> snapshots, NativeWorkspaceCommitter.Host host,
            RefactoringController refactoring, Consumer<Prepared> navigate,
            BiConsumer<Inspection, Proposal> junit, Consumer<String> notice) {
        this.owner = owner; this.editor = editor; this.intelligence = intelligence;
        this.snapshots = snapshots; this.host = host; this.refactoring = refactoring;
        this.navigate = navigate; this.junit = junit; this.notice = notice;
    }

    public boolean visible() { return popup != null && popup.isVisible(); }
    public boolean ownsFocus(Component component) {
        return visible() && component != null && SwingUtilities.isDescendingFrom(component, popup);
    }
    public void invoke() {
        if (closed || editor.isReadOnly() || editor.hasInputComposition() || refactoring.busy()) return;
        if (visible() && capture.equals(host.capture())) {
            assists = !assists; filter.setText(""); refresh(); filter.requestFocusInWindow(); return;
        }
        notice.accept("Finding Java fixes and assists…");
        inspectAtCaret(result -> { followProblem(result); inspection = result; assists = result.fixes().isEmpty(); show(); });
    }

    /**
     * Cmd+Enter: an import-like fix (a type, a static member, the JUnit library) applies at once, several are offered;
     * without one the caret stays put and fallback runs (completion, or Run).
     */
    public void importOrElse(Runnable fallback) {
        if (closed || editor.isReadOnly() || editor.hasInputComposition() || refactoring.busy()) { fallback.run(); return; }
        inspectAtCaret(result -> {
            var imports = result.fixes().stream().filter(proposal -> proposal.action() == JavaQuickActions.Action.ADD_IMPORT
                    || proposal.action() == JavaQuickActions.Action.ADD_JUNIT_LIBRARY).toList();
            if (imports.isEmpty()) { fallback.run(); return; }
            followProblem(result);
            inspection = new Inspection(result.snapshot(), result.location(), result.diagnostics(), imports);
            if (imports.size() == 1) apply(imports.getFirst()); else { assists = false; show(); }
        });
    }

    private void inspectAtCaret(java.util.function.Consumer<Inspection> found) {
        dismiss(false);
        if (editor.caretCount() != 1) { notice.accept("Quick Fix requires one caret."); return; }
        intelligence.dismiss(); editor.clearInlineCompletion();
        capture = host.capture(); selection = editor.captureLocation();
        var original = capture; var originalSelection = selection;
        var snapshot = snapshots.get(); var requestedDocument = editor.document(); long ticket = ++epoch;
        var location = new Location(snapshot.file(), Math.min(selection.caret(), selection.anchor()),
                Math.max(selection.caret(), selection.anchor()));
        intelligence.submitSemantic(snapshot, (service, context) -> service.sharedRefactorJ(context)
                .quickActions().inspect(location, () -> closed || ticket != epoch))
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != epoch) return;
                    if (error != null) { failed(error); return; }
                    var current = editor.captureLocation();
                    if (!original.equals(host.capture()) || requestedDocument != editor.document()
                            || !snapshot.file().equals(snapshots.get().file()) || current.caret() != originalSelection.caret()
                            || current.anchor() != originalSelection.anchor()) {
                        notice.accept("Source or caret changed; invoke Quick Fix again."); return;
                    }
                    found.accept(result);
                }));
    }

    /** The engine may have moved to the closest fixable problem on the line; the caret follows it, as in Eclipse. */
    private void followProblem(Inspection result) {
        if (result.location().start() == Math.min(selection.caret(), selection.anchor())
                && result.location().end() == Math.max(selection.caret(), selection.anchor())) return;
        editor.setSelection(result.location().start(), result.location().end());
        selection = editor.captureLocation();
    }

    private void show() {
        if (popup == null) {
            popup = new InlinePopup(owner); popup.setName("java-quick-actions");
            body = new JPanel(new BorderLayout(4, 4));
            body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(editor.getForeground()),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            heading = new JLabel();
            filter = new JTextField(38); filter.setName("quick-actions-filter");
            filter.getAccessibleContext().setAccessibleName("Filter Java fixes and assists");
            var top = new JPanel(new BorderLayout(2, 4)); top.add(heading, BorderLayout.NORTH); top.add(filter);
            body.add(top, BorderLayout.NORTH);
            list = new JList<>(); list.setName("quick-actions-proposals");
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.getAccessibleContext().setAccessibleName("Contextual Java proposals");
            list.setCellRenderer((view, value, index, selected, focus) -> {
                var label = new JLabel(value.title()); label.setOpaque(true); label.setFont(AsciiChrome.font());
                label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
                label.setBackground(selected ? view.getSelectionBackground() : view.getBackground());
                label.setForeground(selected ? view.getSelectionForeground() : view.getForeground()); return label;
            });
            body.add(new JScrollPane(list), BorderLayout.CENTER);
            explanation = new JTextArea(3, 38); explanation.setEditable(false); explanation.setLineWrap(true);
            explanation.setWrapStyleWord(true); explanation.setName("quick-actions-explanation");
            explanation.getAccessibleContext().setAccessibleName("Selected action explanation");
            body.add(explanation, BorderLayout.SOUTH);
            popup.getContentPane().add(body);
            filter.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { refresh(); }
                public void removeUpdate(DocumentEvent e) { refresh(); }
                public void changedUpdate(DocumentEvent e) { refresh(); }
            });
            list.addListSelectionListener(e -> describe());
            for (JComponent target : List.of(filter, list, explanation)) {
                key(target, "ESCAPE", () -> dismiss(true)); key(target, "ENTER", this::apply);
                target.setFocusTraversalKeysEnabled(false);
            }
            key(filter, "TAB", () -> list.requestFocusInWindow());
            key(list, "TAB", () -> explanation.requestFocusInWindow());
            key(explanation, "TAB", () -> filter.requestFocusInWindow());
            key(filter, "shift TAB", () -> explanation.requestFocusInWindow());
            key(list, "shift TAB", () -> filter.requestFocusInWindow());
            key(explanation, "shift TAB", () -> list.requestFocusInWindow());
            key(filter, "DOWN", () -> move(1)); key(filter, "UP", () -> move(-1));
            list.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) apply(); }
            });
        }
        filter.setText(""); refresh();
        theme(popup); popup.pack(); popup.showBelow(editor, editor.caretBounds()); filter.requestFocusInWindow();
    }

    private void theme(Component component) {
        AsciiStyle.apply(component, palette);
        body.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(palette.border()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    }
    public void setTheme(Theme theme) {
        palette = AsciiChrome.editor(theme);
        if (popup != null) { theme(popup); popup.pack(); popup.repaint(); }
    }
    private void refresh() {
        if (inspection == null || list == null) return;
        String query = filter.getText().strip().toLowerCase(java.util.Locale.ROOT);
        var values = (assists ? inspection.assists() : inspection.fixes()).stream()
                .filter(p -> (p.title() + " " + p.explanation()).toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
        heading.setText(assists ? "Quick Assists" : "Quick Fixes · invoke again for assists");
        list.setListData(values.toArray(Proposal[]::new)); list.setVisibleRowCount(Math.clamp(values.size(), 1, 8));
        if (!values.isEmpty()) list.setSelectedIndex(0);
        describe(); if (visible()) popup.pack();
    }
    private void describe() {
        var selected = list.getSelectedValue();
        explanation.setText(selected == null ? "No applicable " + (assists ? "assists" : "fixes")
                + " at this caret. Invoke again to switch fixes/assists; Escape returns to source." : selected.explanation());
        explanation.setCaretPosition(0);
    }
    private void move(int delta) {
        if (list.getModel().getSize() == 0) return;
        int index = Math.clamp(list.getSelectedIndex() + delta, 0, list.getModel().getSize() - 1);
        list.setSelectedIndex(index); list.ensureIndexIsVisible(index);
    }
    private void apply() {
        var proposal = list.getSelectedValue(); if (proposal != null) apply(proposal);
    }

    private void apply(Proposal proposal) {
        if (!capture.equals(host.capture())) { dismiss(true); notice.accept("Source changed; invoke Quick Fix again."); return; }
        var original = capture; var context = inspection;
        dismiss(true); editor.restoreLocation(selection);
        // JUnit actions change project configuration, which the host owns.
        if (proposal.action() == JavaQuickActions.Action.CREATE_JUNIT_TEST
                || proposal.action() == JavaQuickActions.Action.ADD_JUNIT_LIBRARY) { junit.accept(context, proposal); return; }
        if (proposal.action() == JavaQuickActions.Action.CREATE_CLASS
                && !dev.turboj.project.FirstLaunchForms.confirmQuickClass(owner, proposal)) return;
        if (proposal.action() == JavaQuickActions.Action.REFACTOR && !"true".equals(proposal.parameters().get("direct"))) {
            editor.setSelection(proposal.location().start(), proposal.location().end());
            refactoring.invoke(proposal.operation()); return;
        }
        long ticket = ++epoch;
        intelligence.submitSemantic(snapshots.get(), (service, source) -> service.sharedRefactorJ(source)
                .quickActions().prepare(proposal, () -> closed || ticket != epoch))
                .whenComplete((prepared, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != epoch) return;
                    if (error != null) { failed(error); return; }
                    if (!original.equals(host.capture())) { notice.accept("Source changed; no quick fix applied."); return; }
                    if (!prepared.plan().valid()) {
                        notice.accept(prepared.plan().diagnostics().stream().map(Model.Diagnostic::message)
                                .collect(java.util.stream.Collectors.joining("\n"))); return;
                    }
                    long navigationTicket = navigationEpoch;
                    var appliedDocument = editor.document();
                    refactoring.apply(new RefactoringController.Prepared(prepared.plan(), original))
                            .whenComplete((applied, failure) -> SwingUtilities.invokeLater(() -> {
                                if (closed) return;
                                if (failure != null) { failed(failure); return; }
                                if (navigationTicket == navigationEpoch && editor.document() == appliedDocument
                                        && editor.isFocusOwner()) navigate.accept(prepared);
                            }));
                }));
    }
    private static void key(JComponent component, String stroke, Runnable action) {
        String id = "quick-actions-" + stroke;
        component.getInputMap().put(KeyStroke.getKeyStroke(stroke), id);
        component.getActionMap().put(id, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }
    public void changed() {
        ++epoch;
        // Publication also refreshes the source. It must cancel stale inspections without
        // pretending the user dismissed the successful action's continuation.
        if (popup != null) popup.setVisible(false);
    }
    public void dismiss(boolean focusEditor) {
        ++epoch;
        ++navigationEpoch;
        if (popup != null) popup.setVisible(false);
        if (focusEditor) editor.requestFocusInWindow();
    }
    private void failed(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        if (!(failure instanceof CancellationException)) notice.accept("Quick Fix: "
                + Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
    }
    @Override public void close() { closed = true; ++epoch; if (popup != null) popup.dispose(); }
}
