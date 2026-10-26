package dev.turboj.refactoring;

import dev.turboj.intelligence.IntelligenceController;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.Request;
import dev.turboj.refactorj.transactions.ChangeSet;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import dev.turboj.commandj.swing.*;

/** Native, preview-first refactoring on TurboJ's shared Java semantic worker. */
public final class RefactoringController implements AutoCloseable {
    public record Prepared(Plan plan, NativeWorkspaceCommitter.Capture capture) {}

    private record Inspection(List<Descriptor> descriptors, Symbol symbol) {}

    private record Invocation(Symbol symbol, List<Symbol> members) {}

    private final Window owner;
    private final Supplier<Point> renameAnchor;
    private final IntelligenceController intelligence;
    private final Supplier<IntelligenceController.Snapshot> snapshots;
    private final Supplier<Location> location;
    private final NativeWorkspaceCommitter.Host host;
    private final NativeWorkspaceCommitter committer;
    private final Consumer<String> notice;
    private final Runnable committed;
    private volatile boolean closed;
    private CompletableFuture<?> request;
    private EmbeddedDialog preview, rename;
    private long epoch;
    /** Renames a local variable or parameter in the editor itself; false leaves the Rename dialog to this controller. */
    private java.util.function.BiPredicate<Location, Symbol> inlineRename = (at, symbol) -> false;

    public void setInlineRename(java.util.function.BiPredicate<Location, Symbol> value) { inlineRename = Objects.requireNonNull(value); }

    public RefactoringController(Window owner, Supplier<Point> renameAnchor,
            IntelligenceController intelligence,
            Supplier<IntelligenceController.Snapshot> snapshots, Supplier<Location> location,
            NativeWorkspaceCommitter.Host host, Consumer<String> notice, Runnable committed) {
        this.owner = owner;
        this.renameAnchor = renameAnchor;
        this.intelligence = intelligence;
        this.snapshots = snapshots;
        this.location = location;
        this.host = host;
        this.notice = notice;
        this.committed = committed;
        this.committer = new NativeWorkspaceCommitter(host);
    }

    public boolean busy() {
        return committer.busy() || request != null && !request.isDone();
    }

    public boolean canUndo() {
        return committer.canUndo();
    }

    public boolean canRedo() {
        return committer.canRedo();
    }

    public CompletableFuture<Prepared> prepare(Request value) {
        requireEdt();
        cancelRequest();
        long issued = ++epoch;
        var capture = host.capture();
        var snapshot = snapshots.get();
        var future = intelligence.submitSemantic(snapshot, (service, context) -> {
            Cancellation cancel = () -> closed || issued != epoch || Thread.currentThread()
                    .isInterrupted();
            var engine = service.sharedRefactorJ(context);
            return new Prepared(new dev.turboj.refactorj.formatj.GeneratedCodeFormatter()
                    .format(engine.prepare(value, cancel)), capture);
        });
        request = future;
        return future;
    }

    public CompletableFuture<NativeWorkspaceCommitter.Result> apply(Prepared prepared) {
        requireEdt();
        if (!prepared.plan().valid())
            return CompletableFuture
                    .failedFuture(new IllegalArgumentException("The preview contains conflicts"));
        var future = committer.apply(prepared.plan(), prepared.capture());
        observe(future, "Refactoring applied");
        return future;
    }

    /** The workspace transaction the next Undo takes back, or null. */
    public String undoPlanId() { requireEdt(); return committer.peekUndoPlanId(); }

    /** Undo the last workspace transaction, for hosts that continue once it is done. */
    public CompletableFuture<NativeWorkspaceCommitter.Result> undoTransaction() {
        requireEdt();
        var future = committer.undo();
        observe(future, "Refactoring undone");
        return future;
    }

    public void undo() {
        requireEdt();
        observe(committer.undo(), "Refactoring undone");
    }

    public void redo() {
        requireEdt();
        observe(committer.redo(), "Refactoring redone");
    }

    private void observe(CompletableFuture<?> future, String success) {
        future.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (closed)
                return;
            if (error == null) {
                committed.run();
                notice.accept(success);
                if (result instanceof NativeWorkspaceCommitter.Result applied && !applied.warning()
                        .isBlank())
                    notice.accept(applied.warning());
            } else
                notice.accept(message(error));
        }));
    }

    public void invoke(Operation operation) {
        requireEdt();
        if (closed || committer.busy())
            return;
        var at = location.get();
        var capture = host.capture();
        long issued = ++epoch;
        var future = intelligence.submitSemantic(snapshots.get(), (service, context) -> {
            var engine = service.sharedRefactorJ(context);
            var resolved = engine.workspace().resolve(at, () -> closed || issued != epoch);
            List<Symbol> members = Set.of(Operation.EXTRACT_INTERFACE, Operation.PULL_UP,
                    Operation.PUSH_DOWN).contains(operation) ? engine.workspace().symbols("",
                    () -> closed || issued != epoch).stream().filter(member -> member.declaration()
                    != null && member.declaration().file().equals(at.file()) && Set.of("method",
                    "field").contains(member.kind())).toList() : List.of();
            return new Invocation(resolved, members);
        });
        request = future;
        future.whenComplete((invocation, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || issued != epoch)
                return;
            if (error != null) {
                notice.accept(message(error));
                return;
            }
            if (!sameCapture(capture, host.capture())) {
                notice.accept("Source changed; request the refactoring again");
                return;
            }
            Symbol symbol = invocation.symbol();

            if (operation == Operation.RENAME) {
                inlineRename(at, symbol, capture);
                return;
            }
            try {
                Request value = RefactoringForms.request(owner, operation, at, symbol, invocation
                        .members());

                if (value != null)
                    showPreparation(value);
            } catch (RuntimeException invalid) {
                notice.accept(message(invalid));
            }
        }));
    }

    public void choose(boolean quickFixesOnly) {
        choose(quickFixesOnly, Set.of());
    }

    public void chooseInline() {
        choose(false, Set.of(Operation.INLINE_VARIABLE, Operation.INLINE_METHOD));
    }

    private void choose(boolean quickFixesOnly, Set<Operation> permitted) {
        requireEdt();
        if (committer.busy())
            return;
        cancelRequest();
        long issued = ++epoch;
        Location at = location.get();
        var capture = host.capture();
        var future = intelligence.submitSemantic(snapshots.get(), (service, context) -> {
            var engine = service.sharedRefactorJ(context);
            return new Inspection(engine.available(at, () -> closed || issued != epoch), engine
                    .workspace().resolve(at, Cancellation.NONE));
        });
        request = future;
        future.whenComplete((inspection, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || issued != epoch)
                return;
            if (error != null) {
                notice.accept(message(error));
                return;
            }
            if (!sameCapture(capture, host.capture())) {
                notice.accept("Source changed; request actions again");
                return;
            }
            List<Descriptor> values = inspection.descriptors().stream().filter(d -> (!quickFixesOnly
                    || d.operation().ordinal() >= Operation.ORGANIZE_IMPORTS.ordinal())
                    && (permitted.isEmpty() || permitted.contains(d.operation()))).toList();
            JList<Descriptor> list = new JList<> (values.toArray(Descriptor[]::new));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setVisibleRowCount(Math.min(14, values.size()));
            list.setCellRenderer((view, value, index, selected, focus) -> {
                JLabel label = new JLabel(value.title() + (value
                        .applicable() ? "" : " — unavailable here"));
                label.setOpaque(true);
                label.setBackground(selected ? view.getSelectionBackground() : view
                        .getBackground());
                label.setForeground(selected ? view.getSelectionForeground() : value
                        .applicable() ? view.getForeground() : UIManager
                        .getColor("Label.disabledForeground"));
                label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                return label;
            });
            if (!values.isEmpty())
                list.setSelectedIndex(0);
            if (EmbeddedOptionPane.showConfirmDialog(owner, new JScrollPane(list),
                    quickFixesOnly ? "Context Actions" : "Refactor This",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION)
                return;
            var selected = list.getSelectedValue();

            if (selected == null)
                return;
            if (!selected.applicable()) {
                notice.accept(diagnostics(selected.diagnostics()));
                return;
            }
            invoke(selected.operation());
        }));
    }

    private void inlineRename(Location at, Symbol symbol,
            NativeWorkspaceCommitter.Capture original) {
        if (symbol == null) {
            notice.accept("No Java declaration resolves at the caret");
            return;
        }
        if (inlineRename.test(at, symbol)) return;
        renameWithDialog(at, symbol, original);
    }

    /** The Rename dialog: for symbols whose rename can reach other files, with its preview. */
    public void renameWithDialog(Location at, Symbol symbol, NativeWorkspaceCommitter.Capture original) {
        if (rename != null)
            rename.dispose();
        rename = new EmbeddedDialog(owner, "Rename " + symbol.name(), false);
        var field = new JTextField(symbol.name(), 24);
        field.selectAll();
        var go = new JButton("Preview");
        var cancel = new JButton("Cancel");
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(field, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(go);
        buttons.add(cancel);
        body.add(buttons, BorderLayout.SOUTH);
        go.addActionListener(e -> {
            rename.dispose();
            if (!original.equals(host.capture())) {
                notice.accept("Source changed while renaming; request Rename again");
                return;
            }
            showPreparation(new Requests.Rename(at, field.getText().strip()));
        });
        cancel.addActionListener(e -> rename.dispose());
        field.addActionListener(e -> go.doClick());
        rename.setContentPane(body);
        rename.getRootPane().setDefaultButton(go);
        escape(rename);
        rename.pack();
        Point anchor = renameAnchor.get();
        rename.setLocationOnScreen(anchor);
        rename.setVisible(true);
        field.requestFocusInWindow();
    }

    /** Keyboard structural actions reuse the same workspace preview and undo transaction. */
    public void previewRequest(Request value) { requireEdt(); if (!closed && !committer.busy()) showPreparation(value); }

    private void showPreparation(Request value) {
        notice.accept("Preparing " + value.operation().id() + "…");
        prepare(value).whenComplete((prepared, error) -> SwingUtilities.invokeLater(() -> {
            if (closed)
                return;
            if (error != null) {
                if (!(unwrap(error) instanceof CancellationException))
                    notice.accept(message(error));
                return;
            }
            if (!sameCapture(prepared.capture(), host.capture())) {
                notice.accept("Source changed while preparing; no edits applied");
                return;
            }
            if (unchanged(prepared.plan())) {
                notice.accept(prepared.plan().operation() == Operation.ORGANIZE_IMPORTS
                        ? "Imports are already organized" : "Nothing to change");
                return;
            }
            showPreview(prepared);
        }));
    }

    public void showPreview(Prepared prepared) {
        requireEdt();
        if (preview != null)
            preview.dispose();
        Plan plan = prepared.plan();
        preview = new EmbeddedDialog(owner, "RefactorJ — " + title(plan.operation()), false);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JTextArea before = sourceArea(), after = sourceArea();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(before),
                new JScrollPane(after));
        split.setResizeWeight(.5);
        List<ChangeSet.Transition> changes = plan.valid() ? ChangeSet.prepare(plan)
                .transitions() : List.of();
        JComboBox<String> paths = new JComboBox<> (changes.stream().map(t -> plan.before().root()
                .relativize(t.path()).toString()).toArray(String[]::new));
        Runnable update = () -> {
            int i = paths.getSelectedIndex();

            if (i < 0)
                return;
            var change = changes.get(i);
            before.setText(change.before() == null ? "[new file]" : change.before().text());
            after.setText(change.after() == null ? "[deleted / moved file]" : change.after()
                    .text());
            before.setCaretPosition(0);
            after.setCaretPosition(0);
        };
        paths.addActionListener(e -> update.run());
        update.run();
        body.add(paths, BorderLayout.NORTH);
        body.add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JTextArea status = new JTextArea(diagnostics(plan.diagnostics()), 4, 60);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        bottom.add(new JScrollPane(status), BorderLayout.CENTER);
        JButton apply = new JButton("Apply changes"), cancel = new JButton("Close");
        apply.setEnabled(plan.valid());
        apply.addActionListener(e -> {
            apply.setEnabled(false);
            apply(prepared).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                if (error == null)
                    preview.dispose();
                else
                    status.setText(message(error));
            }));
        });
        cancel.addActionListener(e -> preview.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(apply);
        bottom.add(actions, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);
        preview.setContentPane(body);
        preview.setSize(Math.min(1100, owner.getWidth()), Math.min(700, owner.getHeight()));
        preview.setLocationRelativeTo(owner);
        escape(preview);
        preview.setVisible(true);
    }

    public static String title(Operation operation) {
        StringBuilder text = new StringBuilder();

        for (String word : operation.id().split("-")) {
            if (!text.isEmpty())
                text.append(' ');
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    private static JTextArea sourceArea() {
        var text = new JTextArea();
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setTabSize(4);
        return text;
    }

    private static String diagnostics(List<Diagnostic> values) {
        return values
                .isEmpty() ? "Validated source changes. Preview does not modify files." : String
                .join("\n", values.stream().map(d -> d.severity() + ": " + d.message()).toList());
    }

    /** A request that changes nothing gets a notice, as Eclipse does, not an error preview. */
    private static boolean unchanged(Plan plan) {
        return plan.edits().isEmpty() && plan.fileOperations().isEmpty() && plan.diagnostics().stream()
                .allMatch(d -> d.code().equals("no.changes") || d.severity().ordinal() < Severity.ERROR.ordinal());
    }

    private static boolean sameCapture(NativeWorkspaceCommitter.Capture a,
            NativeWorkspaceCommitter.Capture b) {
        return a.equals(b);
    }

    private static void escape(EmbeddedDialog dialog) {
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke
                .getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    private static String message(Throwable error) {
        error = unwrap(error);
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Native refactoring UI requires EDT");
    }

    private void cancelRequest() {
        if (request != null && !request.isDone())
            request.cancel(false);
    }

    @Override
    public void close() {
        closed = true;
        epoch++;
        cancelRequest();
        committer.close();
        if (preview != null)
            preview.dispose();
        if (rename != null)
            rename.dispose();
    }
}
