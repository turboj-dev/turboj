package dev.turboj.templates;

import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.AsciiStyle;
import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.intelligence.InlinePopup;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.themes.Theme;
import dev.turboj.editor.document.PieceTable;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.EditorComponent.Selection;
import dev.turboj.ui.EditorComponent.TextEdit;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;

/** Reuses the editor's actual linked fields and undo; all catalog I/O and Java parsing run off the EDT. */
public final class LiveTemplateController implements AutoCloseable {
    private final EditorComponent editor;
    private final Path file;
    private final Consumer<String> message;
    private final Supplier<String> fileName;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            r -> { var t = new Thread(r, "turboj-live-templates"); t.setDaemon(true); return t; });
    private Future<?> pendingContext;
    private volatile LiveTemplateCatalog catalog;
    private List<LiveTemplateCatalog.Template> builtins = List.of();
    private String userJson = LiveTemplateCatalog.EMPTY;
    private long request;
    private boolean closed, active;
    private Runnable rollback;
    private final javax.swing.Timer suggestionsTimer = new javax.swing.Timer(140, e -> suggest());
    private BooleanSupplier eligible = () -> false;
    private Runnable beforeExpansion = () -> {};
    private InlinePopup suggestions;
    private JList<LiveTemplateCatalog.Template> suggestionsList;
    private JTextArea suggestionPreview;
    private AsciiPalette palette = AsciiPalette.defaults();
    private record Offered(PieceTable document, long version, int caret, String source, int start, int end,
                           JavaTemplateContext context) {}
    private Offered offered;

    @FunctionalInterface public interface SemanticContext {
        CompletableFuture<dev.turboj.refactorj.api.JavaIntelligence.TemplateSemantics> inspect(int start, int end);
    }
    @FunctionalInterface public interface Postprocessor {
        CompletableFuture<LiveTemplateTransform.Result> prepare(LiveTemplateCatalog.Template template, int start, int end, int caret, int anchor);
    }
    private SemanticContext semanticContext;
    private Postprocessor postprocessor;
    private boolean finishing;
    private PieceTable finishingDocument;
    private long finishingVersion;
    private long finishRequest;
    private Runnable afterConfirm;
    public void configureSemantics(SemanticContext context, Postprocessor transform) {
        semanticContext = context; postprocessor = transform;
    }
    /** Queue Save/Format until import and format preparation has finished against the current snapshot. */
    public boolean confirmThen(Runnable command) {
        invalidateObsoleteFinish();
        if (!active && !finishing) return false;
        afterConfirm = command;
        if (active) editor.confirmTemplate();
        return true;
    }
    public void configureSuggestions(BooleanSupplier eligible, Runnable dismissCompetitors) {
        this.eligible = eligible; this.beforeExpansion = dismissCompetitors;
    }
    public void setTheme(Theme theme) {
        palette = AsciiChrome.editor(theme);
        if (suggestions != null) { AsciiStyle.apply(suggestions.getContentPane(), palette); suggestions.repaint(); }
    }
    public boolean suggestionsVisible() {
        return suggestions != null && suggestions.isVisible() && offered != null
                && offered.document() == editor.document() && offered.version() == editor.version()
                && offered.caret() == editor.caretOffset() && editor.getSelectedText().isEmpty();
    }
    public boolean dismissSuggestions() {
        boolean visible = suggestionsVisible();
        suggestionsTimer.stop(); ++request;
        if (suggestions != null) suggestions.setVisible(false);
        offered = null;
        if (pendingContext != null) pendingContext.cancel(false);
        worker.purge();
        return visible;
    }
    /** Debounced, prefix-indexed work. Java parsing happens only on the worker for a matching prefix. */
    public void changed() {
        invalidateObsoleteFinish();
        dismissSuggestions();
        if (eligible.getAsBoolean() && !editor.templateActive() && !editor.hasInputComposition()
                && editor.caretCount() == 1 && editor.getSelectedText().isEmpty() && !editor.isReadOnly())
            suggestionsTimer.restart();
    }
    private int abbreviationStart() {
        int start = editor.caretOffset(), count = 0;
        var document = editor.document();
        while (start > 0 && count++ < 64) {
            int cp = Character.codePointBefore(document, start);
            if (!Character.isJavaIdentifierPart(cp) && cp != '-' && cp != '.') break;
            start -= Character.charCount(cp);
        }
        return start;
    }
    private void suggest() {
        if (closed || catalog == null || !eligible.getAsBoolean() || !editor.isFocusOwner()
                || editor.templateActive() || editor.hasInputComposition() || editor.caretCount() != 1
                || !editor.getSelectedText().isEmpty() || editor.isReadOnly()) return;
        int end = editor.caretOffset(), start = abbreviationStart();
        if (end - start < 2 || end < editor.document().length() && Character.isJavaIdentifierPart(editor.document().charAt(end))) return;
        String prefix = editor.document().subSequence(start, end).toString();
        var candidates = catalog.startingWith(prefix);
        if (!candidates.isEmpty()) prepare(candidates, editor.getText(), start, end, false, null, true);
    }
    public boolean moveSuggestion(int delta) {
        if (!suggestionsVisible()) return false;
        int index = Math.clamp(suggestionsList.getSelectedIndex() + delta, 0, suggestionsList.getModel().getSize() - 1);
        suggestionsList.setSelectedIndex(index); suggestionsList.ensureIndexIsVisible(index); return true;
    }
    public boolean acceptSuggestion() {
        if (!suggestionsVisible()) return false;
        var template = suggestionsList.getSelectedValue(); var snapshot = offered;
        dismissSuggestions();
        if (template != null) apply(template, snapshot.context(), snapshot.source(), snapshot.start(), snapshot.end(), "");
        return true;
    }
    /** Tab can arrive before the popup/context worker. An exact abbreviation still wins over an AI ghost. */
    public boolean acceptOrExpand(Runnable fallback) {
        if (acceptSuggestion()) return true;
        return expand(false, fallback);
    }
    private void showSuggestions(List<LiveTemplateCatalog.Template> available, JavaTemplateContext context,
                                 String source, int start, int end) {
        beforeExpansion.run();
        if (suggestions == null) {
            suggestions = new InlinePopup((JFrame) SwingUtilities.getWindowAncestor(editor));
            suggestions.setName("live-template-suggestions");
            var panel = new JPanel(new BorderLayout(4, 4));
            panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            panel.add(new JLabel("Live templates · ↑↓ choose · Tab/Enter expand · Esc close"), BorderLayout.NORTH);
            suggestionsList = new JList<>(); suggestionsList.setName("live-template-suggestion-list");
            suggestionsList.setFocusable(false);
            suggestionsList.getAccessibleContext().setAccessibleName("Matching Java live templates");
            panel.add(new JScrollPane(suggestionsList));
            suggestionPreview = new JTextArea(3, 48); suggestionPreview.setEditable(false); suggestionPreview.setFocusable(false);
            suggestionPreview.getAccessibleContext().setAccessibleName("Selected template source");
            panel.add(new JScrollPane(suggestionPreview), BorderLayout.SOUTH);
            suggestionsList.addListSelectionListener(e -> {
                var value = suggestionsList.getSelectedValue(); suggestionPreview.setText(value == null ? "" : value.body());
                suggestionPreview.setCaretPosition(0);
            });
            suggestionsList.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) acceptSuggestion(); }
            });
            suggestions.getContentPane().add(panel);
        }
        offered = new Offered(editor.document(), editor.version(), editor.caretOffset(), source, start, end, context);
        suggestionsList.setListData(available.toArray(LiveTemplateCatalog.Template[]::new));
        suggestionsList.setVisibleRowCount(Math.min(6, available.size())); suggestionsList.setSelectedIndex(0);
        AsciiStyle.apply(suggestions.getContentPane(), palette); suggestions.pack();
        suggestions.showBelow(editor, editor.caretBounds());
    }

    public LiveTemplateController(EditorComponent editor, Path directory, Supplier<String> fileName, Consumer<String> message) {
        suggestionsTimer.setRepeats(false);
        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { dismissSuggestions(); }
        });
        this.editor = editor; this.file = directory.resolve("live-templates.json"); this.fileName = fileName; this.message = message;
        worker.execute(() -> {
            try {
                var defaults = LiveTemplateCatalog.builtins();
                String users = Files.exists(file) ? Files.readString(file) : LiveTemplateCatalog.EMPTY;
                List<LiveTemplateCatalog.Template> overrides;
                try { overrides = LiveTemplateCatalog.parse(users); }
                catch (RuntimeException invalid) { overrides = List.of(); tell("User templates were not loaded: " + invalid.getMessage()); }
                var loaded = new LiveTemplateCatalog(defaults, overrides); String json = users;
                SwingUtilities.invokeLater(() -> { if (!closed) { builtins = defaults; userJson = json; catalog = loaded; } });
            } catch (Exception failure) { tell("Live templates: " + failure.getMessage()); }
        });
    }
    private void invalidateObsoleteFinish() {
        if (finishing && (finishingDocument != editor.document() || finishingVersion != editor.version())) {
            ++finishRequest; finishing = false; afterConfirm = null;
        }
    }
    public boolean active() { invalidateObsoleteFinish(); return active || finishing; }
    public boolean ready() { return catalog != null; }
    public boolean cancel() {
        if (!active || rollback == null) return false;
        ++finishRequest; finishing = false; afterConfirm = null;
        Runnable action = rollback; editor.finishTemplate(); action.run(); message.accept("Live template cancelled"); return true;
    }
    public boolean expand(boolean explicit) { return expand(explicit, explicit ? null : () -> editor.indent(false)); }
    private boolean expand(boolean explicit, Runnable fallback) {
        invalidateObsoleteFinish();
        if (finishing || editor.templateActive() || editor.hasInputComposition() || editor.caretCount() != 1 || editor.isReadOnly()) return false;
        var current = catalog;
        if (current == null) { if (explicit) message.accept("Live templates are loading"); return false; }
        String source = editor.getText(); int end = editor.caretOffset(), start = end;
        if (!editor.getSelectedText().isEmpty()) return false;
        if (end < source.length() && Character.isJavaIdentifierPart(source.codePointAt(end))) return false;
        while (start > 0) {
            int cp = source.codePointBefore(start);
            if (!Character.isJavaIdentifierPart(cp) && cp != '-' && cp != '.') break;
            start -= Character.charCount(cp);
        }
        List<LiveTemplateCatalog.Template> candidates = current.matching(source.substring(start, end));
        if (candidates.isEmpty()) { if (explicit) message.accept("No live template abbreviation before the caret"); return false; }
        suggestionsTimer.stop();
        prepare(candidates, source, start, end, false, fallback, false);
        return true;
    }
    public void choose() {
        if (catalog == null) { message.accept("Live templates are loading"); return; }
        if (editor.templateActive()) { message.accept("Finish the active template first"); return; }
        int start = Math.min(editor.caretOffset(), editor.selections().getFirst().anchor()), end = Math.max(editor.caretOffset(), editor.selections().getFirst().anchor());
        prepare(catalog.templates(), editor.getText(), start, end, true, null, false);
    }
    private void prepare(List<LiveTemplateCatalog.Template> candidates, String source, int start, int end, boolean chooser, Runnable fallback, boolean suggest) {
        long ticket = ++request, version = editor.version(); var document = editor.document();
        int caret = editor.caretOffset(), anchor = editor.selections().getFirst().anchor(); String name = fileName.get();
        if (pendingContext != null) pendingContext.cancel(false);
        worker.purge(); // Keep at most the running context and the newest queued context.
        var semantic = semanticContext == null ? CompletableFuture.<dev.turboj.refactorj.api.JavaIntelligence.TemplateSemantics>completedFuture(null)
                : semanticContext.inspect(start, end);
        pendingContext = worker.submit(() -> {
            try {
                var context = JavaTemplateContext.inspect(source.substring(0, start) + source.substring(end), start, name).resolved(semantic.get());
                SwingUtilities.invokeLater(() -> {
                    if (closed || request != ticket || document != editor.document() || version != editor.version()
                            || caret != editor.caretOffset() || anchor != editor.selections().getFirst().anchor() || editor.templateActive() || !editor.isFocusOwner()) return;
                    var available = candidates.stream().filter(context::accepts).toList();
                    if (available.isEmpty()) {
                        if (!suggest) {
                            if (fallback != null) fallback.run();
                            else message.accept("No live template applies here (comments, literals and unsupported semantic contexts are excluded)");
                        }
                    } else if (suggest) showSuggestions(available, context, source, start, end);
                    else if (chooser || available.size() > 1) select(available, context, source, start, end, chooser);
                    else apply(available.getFirst(), context, source, start, end, "");
                });
            } catch (Exception failure) { tell("Cannot inspect template context: " + failure.getMessage()); }
        });
    }
    private void select(List<LiveTemplateCatalog.Template> available, JavaTemplateContext context, String source, int start, int end, boolean surround) {
        var dialog = new EmbeddedDialog(SwingUtilities.getWindowAncestor(editor), "Insert Live Template", true);
        var filter = new JTextField(); filter.setName("live-template-filter"); filter.getAccessibleContext().setAccessibleName("Filter live templates");
        var list = new JList<LiveTemplateCatalog.Template>(); list.setName("live-template-list"); list.getAccessibleContext().setAccessibleName("Applicable live templates");
        var preview = new JTextArea(9, 48); preview.setEditable(false); preview.getAccessibleContext().setAccessibleName("Template source");
        var panel = new JPanel(new BorderLayout(8, 8)); panel.add(filter, BorderLayout.NORTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(preview)); split.setResizeWeight(.35); panel.add(split);
        Runnable update = () -> {
            String query = filter.getText().toLowerCase(Locale.ROOT);
            list.setListData(available.stream().filter(t -> t.toString().toLowerCase(Locale.ROOT).contains(query)).toArray(LiveTemplateCatalog.Template[]::new));
            if (list.getModel().getSize() > 0) list.setSelectedIndex(0);
        };
        filter.getDocument().addDocumentListener(listener(update));
        list.addListSelectionListener(e -> preview.setText(list.getSelectedValue() == null ? "" : list.getSelectedValue().body()));
        var insert = new JButton("Insert"); insert.setName("live-template-insert");
        insert.addActionListener(e -> { var selected = list.getSelectedValue(); if (selected == null) return; dialog.dispose();
            apply(selected, context, source, start, end, surround ? source.substring(start, end) : ""); });
        for (int code : new int[]{KeyEvent.VK_UP, KeyEvent.VK_DOWN}) filter.getInputMap().put(KeyStroke.getKeyStroke(code, 0), "navigate-" + code);
        filter.getActionMap().put("navigate-" + KeyEvent.VK_UP, new AbstractAction() { public void actionPerformed(ActionEvent e) { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); } });
        filter.getActionMap().put("navigate-" + KeyEvent.VK_DOWN, new AbstractAction() { public void actionPerformed(ActionEvent e) { list.setSelectedIndex(Math.min(list.getModel().getSize() - 1, list.getSelectedIndex() + 1)); } });
        panel.add(buttons(dialog, insert), BorderLayout.SOUTH); update.run(); show(dialog, panel, insert, filter);
    }
    private void apply(LiveTemplateCatalog.Template template, JavaTemplateContext context, String source, int start, int end, String selection) {
        if (!source.equals(editor.getText()) || editor.isReadOnly()) return;
        dismissSuggestions(); beforeExpansion.run();
        int line = source.lastIndexOf('\n', Math.max(0, start - 1)) + 1, white = line;
        while (white < start && (source.charAt(white) == ' ' || source.charAt(white) == '\t')) white++;
        var expansion = LiveTemplateExpansion.expand(template, context, selection, source.substring(line, white), editor.tabWidth(), source.contains("\r\n") ? "\r\n" : "\n");
        var document = editor.document(); long checkpoint = document.peekUndoTransactionId();
        int originalCaret = editor.caretOffset(), originalAnchor = editor.selections().getFirst().anchor();
        var beforeLocation = editor.captureLocation();
        boolean[] finishAsTransaction = {false};
        int exit = start + expansion.exit();
        if (!editor.applyEdits(editor.version(), List.of(new TextEdit(start, end, expansion.text())), List.of(new Selection(exit, exit)))) return;
        List<Integer> editable = new ArrayList<>();
        for (int i = 0; i < expansion.names().size(); i++) if (expansion.editable().contains(expansion.names().get(i))) editable.add(i);
        if (!editable.isEmpty()) {
            active = true;
            rollback = () -> {
                while (editor.document() == document && document.canUndo() && document.peekUndoTransactionId() != checkpoint) editor.undo();
                if (editor.document() == document) editor.setSelection(originalAnchor, originalCaret);
            };
            var groups = expansion.fields().stream().map(g -> g.stream().map(r -> new Selection(start + r.caret(), start + r.anchor())).toList()).toList();
            editor.startTemplate(groups, new Selection(exit, exit), i -> {}, (index, next) -> {
                // Recompute derived values when leaving a field, using the edited values rather than the original defaults.
                var values = new LinkedHashMap<String, String>(); values.put("__body", template.body()); values.put("SELECTION", selection);
                for (int i = 0; i < expansion.names().size(); i++) values.put(expansion.names().get(i), editor.templateText(i));
                for (var variable : template.variables()) {
                    int i = expansion.names().indexOf(variable.name());
                    if (i < 0 || editable.contains(i) || editor.templateModified(i)) continue;
                    String value = LiveTemplateExpansion.evaluate(variable.expression(), context, values, variable.name());
                    if (value != null) { editor.replaceTemplateText(i, value); values.put(variable.name(), value); }
                }
                if (next == EditorComponent.TEMPLATE_CONFIRM) { finishAsTransaction[0] = true; return true; }
                if (!editor.templateTraversing()) return true;
                boolean reverse = editor.templateMovingBackward();
                int position = editable.indexOf(index), target = position + (reverse ? -1 : 1);
                if (target >= editable.size()) { finishAsTransaction[0] = true; editor.finishTemplateAtExit(); return false; }
                editor.selectTemplateStop(editable.get(Math.max(0, target))); return false;
            }, () -> {
                active = false; rollback = null;
                if (finishAsTransaction[0] && editor.document() == document) {
                    finishing = true; finishingDocument = document; finishingVersion = editor.version();
                    long queued = ++finishRequest;
                    String completed = editor.getText(); long completedVersion = editor.version();
                    SwingUtilities.invokeLater(() -> {
                        if (closed || queued != finishRequest) return;
                        if (!completed.equals(editor.getText()) || completedVersion != editor.version() || editor.document() != document) {
                            finishing = false; afterConfirm = null; return;
                        }
                        finish(template, source, start, end, document, checkpoint, beforeLocation);
                    });
                }
            });
            editor.selectTemplateStop(editable.getFirst());
        } else finish(template, source, start, end, document, checkpoint, beforeLocation);
        message.accept(expansion.unresolved().isEmpty() ? "Live template: " + template.abbreviation() + " — Tab: next, Enter: finish, Escape: cancel"
                : "Live template: fill unresolved values — " + String.join("; ", expansion.unresolved()));
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
    }

    private void finish(LiveTemplateCatalog.Template template, String original, int start, int oldEnd,
            PieceTable document, long checkpoint, EditorComponent.Location before) {
        long ticket = ++finishRequest, version = editor.version();
        int end = document.length() - (original.length() - oldEnd);
        var selected = editor.selections().getFirst();
        finishing = true; finishingDocument = document; finishingVersion = version;
        CompletableFuture<LiveTemplateTransform.Result> future = postprocessor == null || !template.reformat() && !template.shortenNames()
                ? CompletableFuture.completedFuture(new LiveTemplateTransform.Result(List.of(), selected.caret(), selected.anchor()))
                : postprocessor.prepare(template, start, end, selected.caret(), selected.anchor());
        future.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (closed || ticket != finishRequest) return;
            finishing = false;
            if (editor.document() != document || editor.version() != version || editor.isReadOnly()) {
                afterConfirm = null; message.accept("Template formatting skipped: source changed during preparation"); return;
            }
            if (failure == null && !result.edits().isEmpty())
                editor.applyEdits(version, result.edits(), List.of(new Selection(result.caret(), result.anchor())));
            if (document.canCoalesceSince(checkpoint)) {
                try { editor.coalesceEdits(checkpoint, original, before); } catch (RuntimeException ignored) { /* Preserve separate valid undo entries. */ }
            }
            if (failure != null) message.accept("Template kept; automatic imports/formatting unavailable: " + failure.getMessage());
            Runnable command = afterConfirm; afterConfirm = null; if (command != null) command.run();
        }));
    }

    public void configure() {
        if (catalog == null) { message.accept("Live templates are loading"); return; }
        dismissSuggestions();
        var dialog = new LiveTemplateSettingsDialog(editor, builtins, userJson, (json, completed) -> {
            worker.execute(() -> {
                try {
                    var users = LiveTemplateCatalog.parse(json); var next = new LiveTemplateCatalog(builtins, users);
                    Files.createDirectories(file.getParent()); dev.turboj.config.SafeFiles.atomicWrite(file, json);
                    SwingUtilities.invokeLater(() -> {
                        if (closed) return;
                        userJson = json; catalog = next; completed.accept(null); message.accept("Live templates saved");
                    });
                } catch (Exception invalid) { SwingUtilities.invokeLater(() -> completed.accept(invalid)); }
            });
        });
        dialog.setVisible(true);
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
    }
    private JPanel buttons(EmbeddedDialog dialog, JButton accept) {
        var panel = new JPanel(new FlowLayout(FlowLayout.TRAILING)); var cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose()); panel.add(cancel); panel.add(accept); return panel;
    }
    private void show(EmbeddedDialog dialog, JPanel panel, JButton accept, JComponent focus) {
        dialog.setContentPane(panel); dialog.getRootPane().setDefaultButton(accept);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.onOpened(() -> SwingUtilities.invokeLater(focus::requestFocusInWindow)); dialog.pack(); dialog.setLocationRelativeTo(editor);
        dialog.setVisible(true); SwingUtilities.invokeLater(editor::requestFocusInWindow);
    }
    private static DocumentListener listener(Runnable update) { return new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { update.run(); } public void removeUpdate(DocumentEvent e) { update.run(); } public void changedUpdate(DocumentEvent e) { update.run(); }
    }; }
    private void tell(String text) { SwingUtilities.invokeLater(() -> { if (!closed) message.accept(text); }); }
    @Override public void close() { closed = true; dismissSuggestions(); if (suggestions != null) suggestions.dispose(); worker.shutdownNow(); }
}
