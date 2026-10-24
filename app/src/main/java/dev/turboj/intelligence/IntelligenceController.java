package dev.turboj.intelligence;

import dev.turboj.editor.document.PieceTable;
import dev.turboj.refactorj.api.JavaIntelligence.*;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

import javax.swing.*;

/** EDT-facing coordinator. The bounded, serial mailbox owns the shared JDT backend. */
public final class IntelligenceController implements AutoCloseable {
    public record Snapshot(
            Path file, PieceTable.Snapshot source, Map<Path, PieceTable.Snapshot> openSources) {}

    private record Ticket(Snapshot snapshot, int offset, long epoch) {}

    private enum Lane {
        NAVIGATE,
        REFACTOR,
        DOCUMENTATION,
        LINK,
        COMPLETION
    }

    private final EditorComponent editor;
    private final CompletionCoordinator completions;
    private final Supplier<Snapshot> snapshots;
    private final Consumer<Declaration> navigate;
    private final Consumer<String> notice;
    private JavaIntelligenceService service;

    private JavaIntelligenceService service() {
        if (service == null) service = new JdtJavaIntelligenceService();
        return service;
    }

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "turboj-java-intelligence");
                        t.setDaemon(true);
                        return t;
                    });
    private final EnumMap<Lane, Runnable> pending = new EnumMap<>(Lane.class);
    private boolean draining;
    private volatile boolean closed;
    private long epoch;
    private CompletableFuture<?> semanticRequest;
    private int hoverOffset = -1;
    private boolean hoverCommand;
    private final javax.swing.Timer completionTimer, hoverTimer, dismissTimer;
    private final InlinePopup completionWindow, documentationWindow, signatureWindow;
    private final JList<Completion> choices = new JList<>();
    private final JTextArea documentationText = new JTextArea();
    private final JPanel signatures = new JPanel();
    private final JButton openDeclaration = new JButton("Open declaration");
    private final JLabel docOrigin = new JLabel();
    private Theme theme;
    private KeyStroke acceptKey = KeyStroke.getKeyStroke("TAB"),
            dismissKey = KeyStroke.getKeyStroke("ESCAPE");
    private Ticket completionTicket, documentationTicket;
    private Declaration documentationTarget;
    private Symbol documentationSymbol;
    private Context documentationContext;
    private CompletionResult lastCompletion;
    private Documentation lastDocumentation;
    private SignatureHelp lastSignature;
    private final ArrayDeque<Long> completionTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> documentationTimes = new ArrayDeque<>();

    public IntelligenceController(
            JFrame owner,
            EditorComponent editor,
            Supplier<Snapshot> snapshots,
            Consumer<Declaration> navigate,
            Consumer<String> notice) {
        this.editor = editor;
        this.completions = new CompletionCoordinator(editor);
        this.snapshots = snapshots;
        this.navigate = navigate;
        this.notice = notice;
        completionWindow = new InlinePopup(owner);
        completionWindow.setFocusableWindowState(false);
        choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        choices.setCellRenderer(
                (list, value, index, selected, focus) -> {
                    JLabel row = new JLabel(" " + value.display() + "  " + value.detail() + " ");
                    row.setOpaque(true);
                    row.setFont(editor.getFont().deriveFont(13f));
                    if (theme != null) {
                        row.setBackground(
                                selected
                                        ? theme.color("intelligence", "selectionBackground")
                                        : theme.color("documentation", "background"));
                        row.setForeground(
                                selected
                                        ? theme.color("intelligence", "selectionForeground")
                                        : theme.color("documentation", "foreground"));
                    }
                    return row;
                });
        choices.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        acceptChoice();
                    }
                });
        completionWindow.getContentPane().add(new JScrollPane(choices));
        documentationWindow = new InlinePopup(owner);
        documentationWindow.setFocusableWindowState(false);
        documentationText.setEditable(false);
        documentationText.setLineWrap(true);
        documentationText.setWrapStyleWord(true);
        documentationText.setMargin(new Insets(8, 10, 8, 10));
        documentationText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        documentationText.getAccessibleContext().setAccessibleName("Java documentation");
        JPanel doc = new JPanel(new BorderLayout(0, 4));
        doc.add(new JScrollPane(documentationText), BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.add(docOrigin, BorderLayout.CENTER);
        actions.add(openDeclaration, BorderLayout.EAST);
        doc.add(actions, BorderLayout.SOUTH);
        documentationWindow.getContentPane().add(doc);
        openDeclaration.addActionListener(
                e -> {
                    if (documentationTarget != null && documentationTarget.source() != null) {
                        Declaration target = documentationTarget;
                        navigateTo(target);
                    } else if (documentationTicket != null && documentationSymbol != null) {
                        Ticket ticket = documentationTicket;
                        Symbol symbol = documentationSymbol;
                        Context context = documentationContext;
                        enqueue(
                                Lane.NAVIGATE,
                                () -> {
                                    Declaration target = service().findDeclaration(context, symbol);
                                    SwingUtilities.invokeLater(
                                            () -> {
                                                if (!valid(ticket)) return;
                                                if (target != null && target.source() != null) {
                                                    navigateTo(target);
                                                } else
                                                    notice.accept(
                                                            "Source is not attached. The resolved"
                                                                    + " signature is shown in Quick"
                                                                    + " Documentation.");
                                            });
                                });
                    }
                });
        documentationWindow
                .getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        documentationWindow
                .getRootPane()
                .getActionMap()
                .put(
                        "close",
                        new AbstractAction() {
                            public void actionPerformed(ActionEvent e) {
                                completionTimer.stop();
                                hoverTimer.stop();
                                dismissTimer.stop();
                                epoch++;
                                hoverOffset = -1;
                                dismiss();
                                focusEditor();
                            }
                        });
        // Bind inside the popup as well as its focused text component.
        documentationWindow
                .getRootPane()
                .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        documentationText
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "turboj.closeDocumentation");
        documentationText
                .getActionMap()
                .put(
                        "turboj.closeDocumentation",
                        documentationWindow.getRootPane().getActionMap().get("close"));
        signatureWindow = new InlinePopup(owner);
        signatureWindow.setFocusableWindowState(false);
        signatures.setLayout(new BoxLayout(signatures, BoxLayout.Y_AXIS));
        signatureWindow.getContentPane().add(signatures);
        completionTimer = new javax.swing.Timer(55, e -> complete(false));
        completionTimer.setRepeats(false);
        owner.getRootPane().addPropertyChangeListener("commandj.overlay.active", event -> {
            if (Boolean.TRUE.equals(event.getNewValue())) {
                epoch++;
                completionTimer.stop();
                dismiss();
            }
        });
        hoverTimer =
                new javax.swing.Timer(
                        350,
                        e -> {
                            if (hoverOffset >= 0 && !hoverCommand) document(hoverOffset, false);
                        });
        hoverTimer.setRepeats(false);
        dismissTimer =
                new javax.swing.Timer(
                        450,
                        e -> {
                            if (!documentationWindow.isVisible()) return;
                            PointerInfo p = MouseInfo.getPointerInfo();
                            if (!documentationWindow.isFocused()
                                    && (p == null
                                            || !documentationWindow.containsScreenPoint(p.getLocation()))) {
                                documentationWindow.setVisible(false);
                            }
                        });
        dismissTimer.setRepeats(false);
        trackDocumentationPointer(doc);
        editor.setOnSymbolHover(this::hover);
        editor.setOnGoDeclaration(this::goToDeclaration);
        editor.addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        completionWindow.setVisible(false);
                        signatureWindow.setVisible(false);
                    }
                });
    }

    private void focusEditor() {
        Window owner = SwingUtilities.getWindowAncestor(editor);
        if (owner != null) {
            owner.toFront();
            owner.requestFocus();
        }
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
    }

    private void navigateTo(Declaration declaration) {
        completionTimer.stop();
        hoverTimer.stop();
        epoch++;
        dismiss();
        focusEditor();
        navigate.accept(declaration);
    }

    private void trackDocumentationPointer(Component component) {
        component.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        dismissTimer.stop();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        dismissTimer.restart();
                    }
                });
        if (component instanceof Container container)
            for (Component child : container.getComponents()) trackDocumentationPointer(child);
    }

    public void setCompletionKeys(KeyStroke accept, KeyStroke dismiss) {
        acceptKey = accept;
        dismissKey = dismiss;
        editor.setCompletionKeys(accept, dismiss);
    }

    public CompletionCoordinator completions() {
        return completions;
    }

    public CompletionResult lastCompletion() {
        return lastCompletion;
    }

    public Documentation lastDocumentation() {
        return lastDocumentation;
    }

    public SignatureHelp lastSignature() {
        return lastSignature;
    }

    public boolean documentationVisible() {
        return documentationWindow.isVisible();
    }

    public boolean alternativesVisible() {
        return completionWindow.isVisible();
    }

    public String metrics() {
        return "Semantic completion "
                + percentiles(completionTimes)
                + "\nDocumentation lookup "
                + percentiles(documentationTimes)
                + "\nTimes include context/source discovery; hover delay excluded.";
    }

    private static String percentiles(ArrayDeque<Long> values) {
        if (values.isEmpty()) return "— no samples";
        long[] a = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(
                Locale.ROOT,
                "n=%d, p50 %.2f ms, p95 %.2f ms, p99 %.2f ms",
                a.length,
                a[(int) Math.ceil(a.length * .5) - 1] / 1e6,
                a[(int) Math.ceil(a.length * .95) - 1] / 1e6,
                a[(int) Math.ceil(a.length * .99) - 1] / 1e6);
    }

    private static void sample(ArrayDeque<Long> values, long nanos) {
        if (values.size() == 512) values.removeFirst();
        values.addLast(nanos);
    }

    public void changed() {
        epoch++;
        hoverTimer.stop();
        hoverOffset = -1;
        dismiss();
        editor.clearNavigableRange();
        lastCompletion = null;
        lastSignature = null;
        if (!closed && !editor.isReadOnly()) completionTimer.restart();
    }

    public void dismiss() {
        completions.clear();
        completionWindow.setVisible(false);
        documentationWindow.setVisible(false);
        signatureWindow.setVisible(false);
    }

    public void setTheme(Theme value) {
        theme = value;
        for (InlinePopup w : List.of(completionWindow, documentationWindow, signatureWindow)) {
            w.getRootPane()
                    .setBorder(
                            BorderFactory.createLineBorder(value.color("documentation", "border")));
            color(w.getContentPane(), value);
        }
        choices.repaint();
        documentationText.setSelectionColor(value.color("intelligence", "selectionBackground"));
        documentationText.setSelectedTextColor(value.color("intelligence", "selectionForeground"));
        for (Component component : signatures.getComponents()) {
            if (component instanceof JLabel label
                    && label.getClientProperty("turboj.signature") instanceof String markup)
                label.setText(highlightParameter(markup, value));
        }
    }

    private void color(Component c, Theme t) {
        c.setBackground(t.color("documentation", "background"));
        c.setForeground(t.color("documentation", "foreground"));
        if (c instanceof Container container)
            for (Component child : container.getComponents()) color(child, t);
    }

    private Ticket ticket(int offset) {
        return new Ticket(snapshots.get(), offset, epoch);
    }

    private boolean valid(Ticket t) {
        return !closed
                && t.epoch == epoch
                && t.snapshot.source.version() == editor.version()
                && t.snapshot.file.equals(snapshots.get().file);
    }

    private Context context(Ticket ticket) throws Exception {
        String source = ticket.snapshot.source.toString();
        Map<Path, String> open = new LinkedHashMap<>();
        ticket.snapshot.openSources.forEach(
                (path, snapshot) -> open.put(path, snapshot.toString()));
        ProjectContext.Config project = ProjectContext.detect(ticket.snapshot.file, source);
        // Empty roots explicitly mean an isolated, unsaved source snapshot. Retained
        // buffers from unrelated projects do not become its dependencies by accident.
        if (project.sourceRoots().isEmpty()) open.clear();
        return new Context(
                ticket.snapshot.file,
                source,
                ticket.snapshot.source.version(),
                open,
                project.sourceRoots(),
                project.classPath(),
                project.revision(),
                project.sourceArchives());
    }

    @FunctionalInterface
    public interface SemanticTask<T> {
        T run(JavaIntelligenceService intelligence, Context context) throws Exception;
    }

    /**
     * Runs a native refactoring/navigation operation on the same serial worker and backend
     * used by completion. Call on the EDT with an immutable buffer snapshot. The caller
     * must recheck every affected source version before publishing or applying its result.
     * Replacing a pending request completes its future exceptionally instead of leaking it.
     */
    public <T> CompletableFuture<T> submitSemantic(Snapshot snapshot, SemanticTask<T> task) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture editor state on the EDT");
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) { result.cancel(false); return result; }
        if (semanticRequest != null && !semanticRequest.isDone()) semanticRequest.cancel(false);
        semanticRequest = result;
        Ticket ticket = new Ticket(snapshot, 0, epoch);
        enqueue(Lane.REFACTOR, () -> {
            if (result.isDone() || closed) { result.cancel(false); return; }
            try {
                T value = task.run(service(), context(ticket));
                result.complete(value);
            } catch (Exception | LinkageError error) { result.completeExceptionally(error); }
        });
        return result;
    }

    /** At most one pending operation per lane. Obsolete tasks are replaced, never accumulated. */
    private void enqueue(Lane lane, Runnable operation) {
        long issuedEpoch = epoch;
        synchronized (pending) {
            if (closed) return;
            pending.put(
                    lane,
                    () -> {
                        try {
                            operation.run();
                        } catch (Exception | LinkageError e) {
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (!closed && epoch == issuedEpoch) {
                                            dismiss();
                                            notice.accept("Java intelligence: " + e.getMessage());
                                        }
                                    });
                        }
                    });
            if (draining) return;
            draining = true;
        }
        executor.execute(
                () -> {
                    while (true) {
                        Runnable task;
                        synchronized (pending) {
                            if (pending.isEmpty() || closed) {
                                draining = false;
                                return;
                            }
                            Lane first = pending.keySet().iterator().next();
                            task = pending.remove(first);
                        }
                        task.run();
                    }
                });
    }

    public void complete(boolean alternatives) {
        completionTimer.stop();
        if (closed || editor.isReadOnly() || !editor.getSelectedText().isEmpty()) return;
        Ticket t = ticket(editor.caretOffset());
        enqueue(
                Lane.COMPLETION,
                () -> {
                    long start = System.nanoTime();
                    try {
                        Context context = context(t);
                        CompletionResult result = service().complete(context, t.offset);
                        SignatureHelp help = service().signatureHelp(context, t.offset);
                        long duration = System.nanoTime() - start;
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!valid(t)
                                            || editor.caretOffset() != t.offset
                                            || !editor.hasFocus()
                                            || documentationWindow.isVisible()) return;
                                    sample(completionTimes, duration);
                                    lastCompletion = result;
                                    lastSignature = help;
                                    completionTicket = t;
                                    if (!result.proposals().isEmpty()) {
                                        completions.semantic(
                                                inline(t, result.proposals().getFirst()),
                                                alternatives);
                                        if (alternatives) showChoices(result.proposals());
                                    } else if (alternatives)
                                        notice.accept("No semantic completions at this position.");
                                    showSignatures(help);
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
    }

    private EditorComponent.InlineCompletion inline(Ticket t, Completion p) {
        return new EditorComponent.InlineCompletion(
                t.snapshot.source.version(),
                t.offset,
                p.start(),
                p.end(),
                p.text(),
                p.caret(),
                p.additionalEdits().stream()
                        .map(e -> new EditorComponent.TextEdit(e.start(), e.end(), e.text()))
                        .toList());
    }

    private void showChoices(List<Completion> proposals) {
        completions.alternatives(true);
        choices.setListData(proposals.toArray(Completion[]::new));
        choices.setSelectedIndex(0);
        completionWindow.setSize(Math.min(700, Math.max(360, editor.getWidth() - 30)), 180);
        place(completionWindow, editor.caretOffset());
    }

    private void acceptChoice() {
        Completion p = choices.getSelectedValue();
        if (p != null && completionTicket != null && valid(completionTicket)) {
            editor.applyCompletion(inline(completionTicket, p));
        }
        completionWindow.setVisible(false);
        completions.alternatives(false);
        editor.requestFocusInWindow();
    }

    /**
     * Called before the editor's ordinary keys. No global operating-system shortcuts are
     * intercepted.
     */
    public boolean handleKey(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        if (dismissKey.equals(KeyStroke.getKeyStrokeForEvent(e))) {
            boolean visible =
                    alternativesVisible()
                            || documentationVisible()
                            || signatureWindow.isVisible()
                            || editor.inlineCompletion() != null;
            completionTimer.stop();
            epoch++;
            dismiss();
            return visible;
        }
        if (!completionWindow.isVisible()) return false;
        if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN) {
            int index =
                    Math.clamp(
                            choices.getSelectedIndex()
                                    + (e.getKeyCode() == KeyEvent.VK_UP ? -1 : 1),
                            0,
                            choices.getModel().getSize() - 1);
            choices.setSelectedIndex(index);
            choices.ensureIndexIsVisible(index);
            if (completionTicket != null && valid(completionTicket))
                completions.semantic(inline(completionTicket, choices.getSelectedValue()), true);
            return true;
        }
        if (e.getKeyCode() == KeyEvent.VK_ENTER
                || acceptKey.equals(KeyStroke.getKeyStrokeForEvent(e))) {
            acceptChoice();
            return true;
        }
        return false;
    }

    private void hover(EditorComponent.SymbolHover hover) {
        if (hover.offset() == hoverOffset && hover.commandDown() == hoverCommand) return;
        hoverTimer.stop();
        hoverOffset = hover.offset();
        hoverCommand = hover.commandDown();
        editor.clearNavigableRange();
        if (hoverOffset < 0) {
            dismissTimer.restart();
            return;
        }
        dismissTimer.stop();
        if (!hoverCommand) {
            hoverTimer.restart();
            return;
        }
        Ticket t = ticket(hoverOffset);
        enqueue(
                Lane.LINK,
                () -> {
                    try {
                        Symbol s = service().resolveSymbol(context(t), t.offset);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (valid(t)
                                            && hoverOffset == t.offset
                                            && hoverCommand
                                            && s != null)
                                        editor.setNavigableRange(
                                                t.snapshot.source.version(), s.start(), s.end());
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    public void quickDocumentation() {
        document(editor.caretOffset(), true);
    }

    private void document(int offset, boolean keyboard) {
        completionTimer.stop();
        Ticket t = ticket(offset);
        enqueue(
                Lane.DOCUMENTATION,
                () -> {
                    long start = System.nanoTime();
                    try {
                        Context context = context(t);
                        Symbol symbol = service().resolveSymbol(context, offset);
                        Documentation docs =
                                symbol == null ? null : service().documentation(context, symbol);
                        long duration = System.nanoTime() - start;
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!valid(t) || (!keyboard && hoverOffset != offset)) return;
                                    sample(documentationTimes, duration);
                                    if (docs == null) {
                                        if (keyboard)
                                            notice.accept("No resolved Java symbol at the caret.");
                                        return;
                                    }
                                    lastDocumentation = docs;
                                    documentationTicket = t;
                                    documentationSymbol = symbol;
                                    documentationContext = context;
                                    documentationTarget = null;
                                    documentationText.setText(
                                            docs.owner()
                                                    + "\n\n"
                                                    + docs.signature()
                                                    + "\n\n"
                                                    + docs.text());
                                    documentationText.setCaretPosition(0);
                                    docOrigin.setText(" " + docs.origin());
                                    openDeclaration.setEnabled(docs.sourceAvailable());
                                    openDeclaration.setToolTipText(
                                            docs.sourceAvailable()
                                                    ? "Open source declaration"
                                                    : "Source is not attached");
                                    documentationWindow.setFocusableWindowState(keyboard);
                                    documentationWindow.setSize(
                                            Math.min(650, Math.max(350, editor.getWidth() - 30)),
                                            300);
                                    completionWindow.setVisible(false);
                                    signatureWindow.setVisible(false);
                                    place(documentationWindow, offset);
                                    if (keyboard) documentationText.requestFocusInWindow();
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    public void goToDeclaration(int offset) {
        completionTimer.stop();
        hoverTimer.stop();
        Ticket t = ticket(offset);
        enqueue(
                Lane.NAVIGATE,
                () -> {
                    try {
                        Context context = context(t);
                        Symbol symbol = service().resolveSymbol(context, offset);
                        Declaration declaration =
                                symbol == null ? null : service().findDeclaration(context, symbol);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!valid(t)) return;
                                    if (declaration != null && declaration.source() != null) {
                                        navigateTo(declaration);
                                    } else if (symbol != null) {
                                        notice.accept(
                                                "Source is not attached; showing the resolved Java"
                                                        + " signature.");
                                        document(offset, true);
                                    } else
                                        notice.accept(
                                                "No unambiguous Java declaration at this"
                                                        + " position.");
                                });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private void showSignatures(SignatureHelp help) {
        signatures.removeAll();
        if (help == null
                || help.signatures().isEmpty()
                || completionWindow.isVisible()
                || documentationWindow.isVisible()
                || !editor.hasFocus()) {
            signatureWindow.setVisible(false);
            return;
        }
        JLabel header = new JLabel(" Parameter " + (help.activeParameter() + 1) + " ");
        header.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        signatures.add(header);
        for (String signature : help.signatures().stream().limit(8).toList()) {
            String markup = parameterMarkup(signature, help.activeParameter());
            JLabel label = new JLabel(theme == null ? markup : highlightParameter(markup, theme));
            label.putClientProperty("turboj.signature", markup);
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            signatures.add(label);
        }
        if (theme != null) color(signatures, theme);
        signatureWindow.pack();
        place(signatureWindow, editor.caretOffset());
    }

    static String parameterMarkup(String signature, int argument) {
        int open = signature.indexOf('('), close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return " " + signature + " ";
        List<String> parameters = new ArrayList<>();
        int depth = 0, start = open + 1;
        for (int i = start; i < close; i++) {
            char c = signature.charAt(i);
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                parameters.add(signature.substring(start, i));
                start = i + 1;
            }
        }
        parameters.add(signature.substring(start, close));
        int active = argument;
        if (active >= parameters.size() && parameters.getLast().contains("..."))
            active = parameters.size() - 1;
        StringBuilder html =
                new StringBuilder("<html>&nbsp;").append(escape(signature.substring(0, open + 1)));
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) html.append(',');
            if (i == active) html.append("<b><u>");
            html.append(escape(parameters.get(i)));
            if (i == active) html.append("</u></b>");
        }
        return html.append(escape(signature.substring(close))).append("&nbsp;</html>").toString();
    }

    private static String highlightParameter(String markup, Theme theme) {
        String color =
                String.format(
                        "#%06X",
                        theme.color("intelligence", "activeParameter").getRGB() & 0xffffff);
        return markup.replace("<b><u>", "<b><u><font color=\"" + color + "\">")
                .replace("</u></b>", "</font></u></b>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void place(InlinePopup window, int offset) {
        if (editor.isShowing()) window.showBelow(editor, editor.offsetBounds(offset));
    }

    @Override
    public void close() {
        closed = true;
        if (semanticRequest != null) semanticRequest.cancel(false);
        completionTimer.stop();
        hoverTimer.stop();
        dismissTimer.stop();
        synchronized (pending) {
            pending.clear();
        }
        completionWindow.dispose();
        documentationWindow.dispose();
        signatureWindow.dispose();
        executor.execute(
                () -> {
                    if (service != null) service.close();
                });
        executor.shutdown();
    }
}
