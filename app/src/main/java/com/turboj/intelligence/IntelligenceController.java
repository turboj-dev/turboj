package com.turboj.intelligence;

import com.turboj.editor.document.PieceTable;
import com.turboj.intelligence.JavaIntelligenceService.*;
import com.turboj.ui.EditorComponent;
import com.turboj.ui.themes.Theme;

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
        DOCUMENTATION,
        LINK,
        COMPLETION
    }

    private final EditorComponent editor;
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
    private int hoverOffset = -1;
    private boolean hoverCommand;
    private final javax.swing.Timer completionTimer, hoverTimer, dismissTimer;
    private final JWindow completionWindow, documentationWindow, signatureWindow;
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
        this.snapshots = snapshots;
        this.navigate = navigate;
        this.notice = notice;
        completionWindow = new JWindow(owner);
        completionWindow.setFocusableWindowState(false);
        choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        choices.setCellRenderer(
                (list, value, index, selected, focus) -> {
                    JLabel row = new JLabel(" " + value.display() + "  " + value.detail() + " ");
                    row.setOpaque(true);
                    row.setFont(editor.getFont().deriveFont(13f));
                    if (theme != null) {
                        row.setBackground(selected ? theme.selection() : theme.background());
                        row.setForeground(theme.foreground());
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
        completionWindow.add(new JScrollPane(choices));
        documentationWindow = new JWindow(owner);
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
        documentationWindow.add(doc);
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
        // A macOS JWindow can become focusable after hover presentation. Bind in the
        // focused component/ancestor chain, which does not depend on global window registration.
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
        signatureWindow = new JWindow(owner);
        signatureWindow.setFocusableWindowState(false);
        signatures.setLayout(new BoxLayout(signatures, BoxLayout.Y_AXIS));
        signatureWindow.add(signatures);
        completionTimer = new javax.swing.Timer(55, e -> complete(false));
        completionTimer.setRepeats(false);
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
                                            || !documentationWindow
                                                    .getBounds()
                                                    .contains(p.getLocation()))) {
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
        editor.clearInlineCompletion();
        completionWindow.setVisible(false);
        documentationWindow.setVisible(false);
        signatureWindow.setVisible(false);
    }

    public void setTheme(Theme value) {
        theme = value;
        for (JWindow w : List.of(completionWindow, documentationWindow, signatureWindow)) {
            w.getRootPane().setBorder(BorderFactory.createLineBorder(value.foreground()));
            color(w.getContentPane(), value);
        }
        choices.repaint();
    }

    private void color(Component c, Theme t) {
        c.setBackground(t.background());
        c.setForeground(t.foreground());
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
                                        editor.setInlineCompletion(
                                                inline(t, result.proposals().getFirst()));
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
                editor.setInlineCompletion(inline(completionTicket, choices.getSelectedValue()));
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
            JLabel label = new JLabel(parameterMarkup(signature, help.activeParameter()));
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

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void place(JWindow window, int offset) {
        if (!editor.isShowing()) return;
        Rectangle cell = editor.offsetBounds(offset);
        Point origin = new Point(cell.x, cell.y + cell.height + 3);
        SwingUtilities.convertPointToScreen(origin, editor);
        Rectangle screen = editor.getGraphicsConfiguration().getBounds();
        Insets insets =
                Toolkit.getDefaultToolkit().getScreenInsets(editor.getGraphicsConfiguration());
        screen =
                new Rectangle(
                        screen.x + insets.left,
                        screen.y + insets.top,
                        screen.width - insets.left - insets.right,
                        screen.height - insets.top - insets.bottom);
        int x = Math.max(screen.x, Math.min(origin.x, screen.x + screen.width - window.getWidth()));
        int y = origin.y;
        if (y + window.getHeight() > screen.y + screen.height)
            y = origin.y - cell.height - window.getHeight() - 6;
        window.setLocation(x, Math.max(screen.y, y));
        window.setVisible(true);
    }

    @Override
    public void close() {
        closed = true;
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
