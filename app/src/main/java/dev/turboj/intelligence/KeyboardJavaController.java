package dev.turboj.intelligence;

import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.EmbeddedOptionPane;
import dev.turboj.refactorj.api.JavaIntelligence;
import dev.turboj.refactorj.api.JavaStructure;
import dev.turboj.refactorj.api.Requests;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.EditorComponent;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import javax.swing.*;

/** Keyboard Java requests share the existing serial semantic lane and reject stale buffers. */
public final class KeyboardJavaController {
    private final JFrame owner;
    private final EditorComponent editor;
    private final IntelligenceController intelligence;
    private final Supplier<IntelligenceController.Snapshot> snapshots;
    private final Consumer<JavaIntelligence.Declaration> navigate;
    private final Consumer<String> notice;
    private final Deque<EditorComponent.Location> selections = new ArrayDeque<>();
    private EditorComponent.Location expanded;
    private long selectionVersion = -1, issued;
    private Path selectionFile;
    private boolean closed;
    private EmbeddedDialog surface;
    private Consumer<Requests.Request> refactoring = request -> noticeUnavailable();
    private record Located(List<JavaStructure.Target> targets, Map<Path, String> sources) {}
    private record Prepared(JavaStructure.EditResult result, Requests.Request refactoring) {}

    public KeyboardJavaController(JFrame owner, EditorComponent editor, IntelligenceController intelligence,
            Supplier<IntelligenceController.Snapshot> snapshots, Consumer<JavaIntelligence.Declaration> navigate,
            Consumer<String> notice) {
        this.owner = owner; this.editor = editor; this.intelligence = intelligence;
        this.snapshots = snapshots; this.navigate = navigate; this.notice = notice;
    }
    private void noticeUnavailable() { notice.accept("Change Signature is not connected to this editor."); }
    public void setRefactoring(Consumer<Requests.Request> handler) { refactoring = Objects.requireNonNull(handler); }
    public void changed() { ++issued; selections.clear(); expanded = null; }
    public void select(JavaStructure.Selection action) {
        if (editor.selections().size() > 1) { notice.accept("Structural selection requires one caret; remove secondary carets first."); return; }
        var snapshot = snapshots.get(); var before = editor.captureLocation(); long ticket = ++issued;
        intelligence.submitSemantic(snapshot, (service, context) -> service.selectStructure(context,
                Math.min(before.caret(), before.anchor()), Math.max(before.caret(), before.anchor()), action))
                .whenComplete((range, failure) -> SwingUtilities.invokeLater(() -> {
                    if (!current(ticket, snapshot) || !sameSelection(before, editor.captureLocation())) return;
                    if (failure != null) { failed(failure); return; }
                    if (range == null) { notice.accept("No enclosing Java structure at this selection."); return; }
                    if (selectionVersion != editor.version() || !snapshot.file().equals(selectionFile) || expanded == null || !sameSelection(expanded, before)) selections.clear();
                    selections.push(before); while (selections.size() > 100) selections.removeLast();
                    boolean reverse = before.caret() < before.anchor();
                    editor.setSelection(reverse ? range.end() : range.start(), reverse ? range.start() : range.end());
                    expanded = editor.captureLocation(); selectionVersion = editor.version(); selectionFile = snapshot.file();
                }));
    }
    public void shrinkSelection() {
        ++issued;
        var snapshot = snapshots.get();
        if (selectionVersion != editor.version() || !Objects.equals(snapshot.file(), selectionFile) || expanded == null || !sameSelection(expanded, editor.captureLocation()) || selections.isEmpty()) {
            selections.clear(); notice.accept("No structural selection to shrink at this caret."); return;
        }
        var previous = selections.pop(); editor.restoreLocation(previous); expanded = previous;
    }
    public void edit(JavaStructure.Edit action) {
        if (editor.isReadOnly()) { notice.accept("This source is read-only."); return; }
        if (editor.selections().size() > 1) { notice.accept("A semantic Java change requires one selection; remove secondary carets first."); return; }
        var snapshot = snapshots.get(); var location = editor.captureLocation(); long ticket = ++issued;
        notice.accept("Analyzing Java structure…");
        intelligence.submitSemantic(snapshot, (service, context) -> {
            if (action == JavaStructure.Edit.MOVE_ELEMENT_LEFT || action == JavaStructure.Edit.MOVE_ELEMENT_RIGHT) {
                var request = service.parameterMove(context, location.caret(), action == JavaStructure.Edit.MOVE_ELEMENT_LEFT ? -1 : 1);
                if (request != null) return new Prepared(null, request);
            }
            return new Prepared(service.editStructure(context, location.caret(), location.anchor(), action), null);
        }).whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
                    if (!current(ticket, snapshot)) return;
                    if (!sameSelection(location, editor.captureLocation())) { notice.accept("Selection changed; request the Java action again."); return; }
                    if (failure != null) { failed(failure); return; }
                    if (prepared.refactoring() != null) { refactoring.accept(prepared.refactoring()); return; }
                    var result = prepared.result();
                    if (!result.applicable()) { notice.accept(result.explanation()); return; }
                    // Moves and the one-semicolon Complete Statement apply at once, like typing; generation previews first.
                    boolean direct = action.name().startsWith("MOVE_") || action == JavaStructure.Edit.COMPLETE_STATEMENT;
                    if (direct && result.warnings().isEmpty()) apply(snapshot, result);
                    else preview(snapshot, result);
                }));
    }
    private void apply(IntelligenceController.Snapshot snapshot, JavaStructure.EditResult result) {
        if (!sameSource(snapshot)) { notice.accept("Source changed; prepare the Java action again."); return; }
        boolean applied = editor.applySemanticEdits(snapshot.source().version(), result.edits().stream().map(e -> new EditorComponent.TextEdit(e.start(), e.end(), e.text())).toList(), result.caret(), result.anchor());
        notice.accept(applied ? result.explanation() : "Source changed; no edits applied.");
        editor.requestFocusInWindow();
    }
    private void preview(IntelligenceController.Snapshot snapshot, JavaStructure.EditResult result) {
        if (surface != null) surface.dispose();
        surface = new EmbeddedDialog(owner, "Java change preview", false);
        String original = snapshot.source().toString();
        StringBuilder after = new StringBuilder(); int offset = 0;
        for (var edit : result.edits()) { after.append(original, offset, edit.start()).append(edit.text()); offset = edit.end(); }
        after.append(original, offset, original.length());
        var text = new JTextArea(result.explanation() + "\n\n" + String.join("\n", result.warnings()) + "\n\nBEFORE\n" + original + "\n\nAFTER\n" + after, 20, 80);
        text.setEditable(false); text.setFont(AsciiChrome.font()); text.setCaretPosition(0);
        text.getAccessibleContext().setAccessibleName("Java change warnings and source preview");
        var buttons = new JPanel(); var apply = new JButton("Apply"); var cancel = new JButton("Cancel"); buttons.add(apply); buttons.add(cancel);
        EmbeddedDialog preview = surface;
        apply.addActionListener(e -> { preview.dispose(); apply(snapshot, result); });
        cancel.addActionListener(e -> preview.dispose());
        preview.add(new JScrollPane(text), BorderLayout.CENTER); preview.add(buttons, BorderLayout.SOUTH);
        preview.getRootPane().setDefaultButton(apply); escape(preview); preview.pack(); preview.setVisible(true); text.requestFocusInWindow();
    }
    public void navigate(JavaStructure.Navigation action) {
        String query = "";
        if (action == JavaStructure.Navigation.SYMBOLS || action == JavaStructure.Navigation.TYPES) {
            query = EmbeddedOptionPane.showInputDialog(owner, action == JavaStructure.Navigation.TYPES ? "Type name" : "Symbol name");
            if (query == null) return;
        }
        String search = query; var snapshot = snapshots.get(); int caret = editor.caretOffset(); long ticket = ++issued;
        intelligence.submitSemantic(snapshot, (service, context) -> {
            var targets = service.navigationTargets(context, caret, action, search);
            Map<Path,String> sources = new HashMap<>();
            for (var source : service.sharedRefactorJ(context).workspace().snapshot().sources()) sources.put(source.file(), source.text());
            return new Located(targets, sources);
        }).whenComplete((locations, failure) -> SwingUtilities.invokeLater(() -> {
            if (!current(ticket, snapshot)) return;
            if (failure != null) { failed(failure); return; }
            if (locations.targets().isEmpty()) { notice.accept("No source targets for " + action.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "."); return; }
            if (locations.targets().size() == 1) open(locations.targets().getFirst(), locations.sources());
            else choose(action, locations);
        }));
    }
    private void choose(JavaStructure.Navigation action, Located locations) {
        if (surface != null) surface.dispose();
        surface = new EmbeddedDialog(owner, action.name().toLowerCase(Locale.ROOT).replace('_', ' '), false);
        var list = new JList<>(locations.targets().toArray(JavaStructure.Target[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setSelectedIndex(0); list.setVisibleRowCount(Math.min(16, locations.targets().size()));
        list.getAccessibleContext().setAccessibleName("Java navigation results");
        list.setCellRenderer((view, value, index, selected, focus) -> {
            var label = new JLabel(value.label() + " — " + value.file().getFileName() + ":" + line(locations.sources().get(value.file()), value.start()));
            label.setOpaque(true); label.setFont(AsciiChrome.font()); label.setBackground(selected ? view.getSelectionBackground() : view.getBackground()); label.setForeground(selected ? view.getSelectionForeground() : view.getForeground()); return label;
        });
        var open = new JButton("Open"); var cancel = new JButton("Cancel"); var buttons = new JPanel(); buttons.add(open); buttons.add(cancel);
        EmbeddedDialog picker = surface;
        Runnable activate = () -> { var target = list.getSelectedValue(); if (target != null) { picker.dispose(); open(target, locations.sources()); } };
        open.addActionListener(e -> activate.run()); cancel.addActionListener(e -> picker.dispose());
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open"); list.getActionMap().put("open", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { activate.run(); } });
        list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) activate.run(); } });
        picker.add(new JScrollPane(list), BorderLayout.CENTER); picker.add(buttons, BorderLayout.SOUTH); picker.getRootPane().setDefaultButton(open); escape(picker); picker.pack(); picker.setVisible(true); list.requestFocusInWindow();
    }
    private static int line(String source, int offset) { if (source == null) return 1; int line = 1; for (int i = 0; i < Math.min(offset, source.length()); i++) if (source.charAt(i) == '\n') line++; return line; }
    private void open(JavaStructure.Target target, Map<Path,String> sources) {
        String source = sources.get(target.file());
        if (source == null) { notice.accept("The target has no writable workspace source."); return; }
        navigate.accept(new JavaIntelligence.Declaration(target.file(), source, target.start(), target.end() - target.start(), false, "Shared Java workspace"));
    }
    private void escape(EmbeddedDialog dialog) {
        dialog.getRootPane().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); editor.requestFocusInWindow(); } });
    }
    private boolean current(long ticket, IntelligenceController.Snapshot snapshot) { return !closed && ticket == issued && sameSource(snapshot); }
    private boolean sameSource(IntelligenceController.Snapshot expected) {
        var actual = snapshots.get();
        if (!expected.file().equals(actual.file()) || expected.source().version() != actual.source().version()
                || !expected.source().toString().equals(actual.source().toString())
                || !expected.openSources().keySet().equals(actual.openSources().keySet())) return false;
        for (var entry : expected.openSources().entrySet()) {
            var source = actual.openSources().get(entry.getKey());
            if (source.version() != entry.getValue().version() || !source.toString().equals(entry.getValue().toString())) return false;
        }
        return true;
    }
    private static boolean sameSelection(EditorComponent.Location a, EditorComponent.Location b) { return a.caret() == b.caret() && a.anchor() == b.anchor(); }
    private void failed(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        if (!(failure instanceof CancellationException)) notice.accept("Java action: " + Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
    }
    public void dispose() { closed = true; ++issued; selections.clear(); if (surface != null) surface.dispose(); }
}
