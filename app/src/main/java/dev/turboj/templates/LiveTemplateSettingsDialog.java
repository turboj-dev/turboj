package dev.turboj.templates;

import com.google.gson.*;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.ui.EditorComponent;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;

/** Searchable draft editor. Save is the only operation that touches persistent configuration. */
final class LiveTemplateSettingsDialog extends EmbeddedDialog {
    @FunctionalInterface interface Saver { void save(String json, Consumer<Exception> completed); }
    private final LiveTemplateSettings model;
    private final Saver saver;
    private final EditorComponent origin;
    private final JTextField search = field("live-template-search", "Search templates");
    private final JList<String> list = new JList<>(new DefaultListModel<>());
    private final JTextField name = field("live-template-name", "Template name");
    private final JTextField abbreviation = field("live-template-abbreviation", "Template abbreviation");
    private final JTextField description = field("live-template-description", "Template description");
    private final JTextField identity = field("live-template-id", "Stable template ID");
    private final JTextArea body = new JTextArea(7, 35);
    private final JCheckBox enabled = new JCheckBox("Enabled");
    private final JCheckBox reformat = new JCheckBox("Format expanded code"), shortenNames = new JCheckBox("Shorten qualified names / add imports");
    private final Map<String, JCheckBox> contexts = new LinkedHashMap<>();
    private final Variables variables = new Variables();
    private final JTable table = new JTable(variables);
    private final LiveTemplatePreview preview;
    private final JLabel count = new JLabel(" "), error = new JLabel(" ");
    private final JButton save, cancel, remove, duplicate;
    private final JPanel form = new JPanel(new BorderLayout(6, 6));
    private final javax.swing.Timer previewTimer = new javax.swing.Timer(100, e -> refreshPreview());
    private boolean loading, saving, validLoaded = true;
    private String selected, originalJson;

    LiveTemplateSettingsDialog(EditorComponent origin, List<LiveTemplateCatalog.Template> defaults, String json, Saver saver) {
        super(SwingUtilities.getWindowAncestor(origin), "Live Templates", true);
        this.origin = origin; this.saver = saver; originalJson = json;
        LiveTemplateSettings draft;
        try { draft = new LiveTemplateSettings(defaults, json); }
        catch (RuntimeException failure) { draft = new LiveTemplateSettings(defaults, LiveTemplateCatalog.EMPTY); validLoaded = false; }
        model = draft; preview = new LiveTemplatePreview(origin);
        body.setName("live-template-body"); body.getAccessibleContext().setAccessibleName("Template code definition"); traverse(body);
        identity.setEditable(false); identity.setToolTipText("Stable ID; editing a built-in creates a local override.");
        enabled.setName("live-template-enabled"); error.setName("live-template-error");
        list.setName("live-template-list"); list.getAccessibleContext().setAccessibleName("Built-in and user templates");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setVisibleRowCount(12);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(l, value, index, selected, focused);
                var entry = model.definition((String) value);
                if (entry != null) {
                    String kind = model.builtin((String) value) ? model.overridden((String) value) ? "override" : "built-in" : "user";
                    String state = entry.has("disabled") && entry.get("disabled").getAsBoolean() ? ", disabled" : "";
                    setText(LiveTemplateSettings.text(entry, "abbreviation") + " — " + LiveTemplateSettings.displayName(entry) + " [" + kind + state + "]");
                    setToolTipText(LiveTemplateSettings.text(entry, "description"));
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!loading && !e.getValueIsAdjusting()) select(list.getSelectedValue()); });
        search.getDocument().addDocumentListener(changed(this::filter));
        bind(search, "DOWN", () -> move(1)); bind(search, "UP", () -> move(-1));
        bind(search, "ENTER", () -> { if (selected != null) name.requestFocusInWindow(); });
        bind(list, "ENTER", () -> { if (selected != null) name.requestFocusInWindow(); });
        var left = new JPanel(new BorderLayout(6, 6));
        var filter = new JPanel(new BorderLayout(0, 4)); filter.add(label("Search", search), BorderLayout.NORTH); filter.add(search);
        left.add(filter, BorderLayout.NORTH); left.add(new JScrollPane(list));
        var actions = new JPanel(new GridLayout(0, 1, 4, 4));
        actions.add(button("New", "live-template-new", () -> add(false)));
        duplicate = button("Duplicate", "live-template-duplicate", () -> add(true)); actions.add(duplicate);
        remove = button("Remove", "live-template-remove", this::remove); actions.add(remove); actions.add(count);
        left.add(actions, BorderLayout.SOUTH); left.setPreferredSize(new Dimension(260, 500)); left.setMinimumSize(new Dimension(140, 100));

        var fields = new JPanel(new GridBagLayout());
        row(fields, 0, "Name", name); row(fields, 1, "Abbreviation", abbreviation);
        row(fields, 2, "Description", description); row(fields, 3, "ID", identity);
        var metadata = new JPanel(new BorderLayout()); metadata.add(fields); metadata.add(enabled, BorderLayout.SOUTH); form.add(metadata, BorderLayout.NORTH);
        var code = new JPanel(new BorderLayout(4, 4));
        code.add(new JLabel("Definition: $NAME$ linked fields · $END$ caret · $$ literal $"), BorderLayout.NORTH);
        var example = new JPanel(new BorderLayout(0, 4));
        example.add(new JLabel("Expanded example · boxes: fields · solid caret: END"), BorderLayout.NORTH); example.add(preview);
        example.setToolTipText("Sample context: Example.example(int[] values); selection: selected();. No code is executed.");
        var codeSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(body), example); codeSplit.setResizeWeight(.45);
        code.add(codeSplit);
        var tabs = new JTabbedPane(); tabs.setName("live-template-tabs");
        tabs.addTab("Code & preview", code); tabs.addTab("Configuration", configuration()); form.add(tabs);
        // Select on arrow navigation regardless of the host LAF's selectionFollowsFocus policy.
        bind(tabs, "RIGHT", () -> tabs.setSelectedIndex(Math.min(tabs.getTabCount() - 1, tabs.getSelectedIndex() + 1)));
        bind(tabs, "LEFT", () -> tabs.setSelectedIndex(Math.max(0, tabs.getSelectedIndex() - 1)));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, form); split.setResizeWeight(.28);
        var panel = new JPanel(new BorderLayout(8, 8)); panel.add(split);
        var footer = new JPanel(new BorderLayout(4, 4)); footer.add(error, BorderLayout.NORTH);
        var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(button("Edit JSON…", "live-template-json", this::editJson));
        cancel = button("Cancel", "live-template-cancel", this::cancel); buttons.add(cancel);
        save = button("Save", "live-template-save", this::save); buttons.add(save); footer.add(buttons); panel.add(footer, BorderLayout.SOUTH);
        setContentPane(panel); getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> cancel(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); onClosing(this::cancel);
        for (var text : List.of(name, abbreviation, description, body)) text.getDocument().addDocumentListener(changed(this::edit));
        enabled.addActionListener(e -> edit()); reformat.addActionListener(e -> edit()); shortenNames.addActionListener(e -> edit()); variables.addTableModelListener(e -> edit());
        previewTimer.setRepeats(false);
        onClosed(() -> { previewTimer.stop(); preview.close(); });
        onOpened(() -> SwingUtilities.invokeLater(search::requestFocusInWindow));
        filter();
        if (!validLoaded) error.setText("Invalid user catalog: use Edit JSON to repair it. The original file is unchanged.");
        save.setEnabled(validLoaded);
        Window owner = SwingUtilities.getWindowAncestor(origin);
        setPreferredSize(new Dimension(Math.max(500, Math.min(1060, owner == null ? 1060 : owner.getWidth() - 40)),
                Math.max(400, Math.min(740, owner == null ? 740 : owner.getHeight() - 40))));
        pack(); setLocationRelativeTo(origin);
    }

    private JPanel configuration() {
        var panel = new JPanel(new BorderLayout(6, 6));
        var choices = new JPanel(new GridLayout(0, 1));
        reformat.setName("live-template-reformat"); shortenNames.setName("live-template-shorten-names");
        choices.add(reformat); choices.add(shortenNames);
        choices.add(new JLabel("Contexts · semantic and project-level checks apply"));
        LiveTemplateCatalog.CONTEXTS.stream().sorted().forEach(id -> {
            String title = switch (id) {
                case "JAVA_STATEMENT" -> "Statement"; case "JAVA_DECLARATION" -> "Declaration";
                case "JAVA_EXPRESSION" -> "Expression"; case "JAVA_CODE" -> "Java code";
                case "JAVA_ELSE_PLACE" -> "After if (else branch)";
                case "JAVA_CONSUMER" -> "Consumer (resolved target type)";
                case "JAVA_LANG_IO_STATEMENT" -> "Language IO (Java 25+)";
                case "JAVA_IMPLICIT_CLASS_DECLARATION" -> "Implicit class declaration";
                case "JAVA_NORMAL_CLASS_DECLARATION_WITH_INSTANCE_MAIN" -> "Class with instance main";
                case "JAVA_NORMAL_CLASS_DECLARATION_WITHOUT_INSTANCE_MAIN" -> "Class without instance main";
                case "JAVA_STATEMENT_WITH_STRUCTURED_CONCURRENCY" -> "Structured concurrency (compatible preview API)";
                default -> "Legacy structured concurrency (unsupported checked exception API)";
            };
            var choice = new JCheckBox(title); choice.setName("live-template-context-" + id); choice.setToolTipText(id);
            choice.addActionListener(e -> edit()); contexts.put(id, choice); choices.add(choice);
        });
        table.setName("live-template-variables"); table.getAccessibleContext().setAccessibleName("Template variables: name, expression, default value, stop");
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Set.of(KeyStroke.getKeyStroke("TAB")));
        table.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Set.of(KeyStroke.getKeyStroke("shift TAB")));
        table.setToolTipText("Arrows select cells; F2 edits; Enter confirms; Tab moves to the next control.");
        var variablePanel = new JPanel(new BorderLayout(4, 4));
        variablePanel.add(new JLabel("Variables · unresolved expressions remain editable fields"), BorderLayout.NORTH);
        variablePanel.add(new JScrollPane(table));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(button("Add variable", "live-template-variable-add", () -> {
            stopEditing(); String next = "VALUE"; int suffix = 2;
            var names = new HashSet<String>(); variables.rows.forEach(v -> names.add(LiveTemplateSettings.text(v, "name")));
            while (names.contains(next)) next = "VALUE" + suffix++;
            var v = new JsonObject(); v.addProperty("name", next); v.addProperty("expression", ""); v.addProperty("defaultValue", ""); v.addProperty("alwaysStop", true);
            variables.rows.add(v); variables.fireTableDataChanged(); table.setRowSelectionInterval(variables.getRowCount() - 1, variables.getRowCount() - 1); table.requestFocusInWindow();
        }));
        actions.add(button("Remove variable", "live-template-variable-remove", () -> {
            stopEditing(); int row = table.getSelectedRow();
            if (row >= 0) { variables.rows.remove(row); variables.fireTableDataChanged(); }
        }));
        variablePanel.add(actions, BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(choices), variablePanel); split.setResizeWeight(.5); panel.add(split);
        return panel;
    }
    private void stopEditing() { if (table.isEditing()) table.getCellEditor().stopCellEditing(); }
    private void filter() {
        stopEditing(); String keep = selected; loading = true;
        try {
            var ids = model.matching(search.getText()); var items = (DefaultListModel<String>) list.getModel();
            items.clear(); items.addAll(ids);
            if (keep != null && ids.contains(keep)) list.setSelectedValue(keep, true);
            else if (!ids.isEmpty()) list.setSelectedIndex(0);
            count.setText(ids.isEmpty() ? "No matching templates" : ids.size() + " templates");
        } finally { loading = false; }
        select(list.getSelectedValue());
    }
    private void move(int delta) {
        if (list.getModel().getSize() == 0) return;
        list.setSelectedIndex(Math.clamp(list.getSelectedIndex() + delta, 0, list.getModel().getSize() - 1));
        list.ensureIndexIsVisible(list.getSelectedIndex());
    }
    private void select(String id) {
        stopEditing(); loading = true;
        try {
            selected = id; var entry = id == null ? new JsonObject() : model.definition(id);
            name.setText(LiveTemplateSettings.displayName(entry)); abbreviation.setText(LiveTemplateSettings.text(entry, "abbreviation"));
            description.setText(LiveTemplateSettings.text(entry, "description")); identity.setText(id == null ? "" : id);
            body.setText(LiveTemplateSettings.text(entry, "body")); body.setCaretPosition(0);
            enabled.setSelected(!entry.has("disabled") || !entry.get("disabled").getAsBoolean());
            reformat.setSelected(entry.has("reformat") && entry.get("reformat").getAsBoolean());
            shortenNames.setSelected(entry.has("shortenNames") && entry.get("shortenNames").getAsBoolean());
            var chosen = new HashSet<String>(); if (entry.has("contexts")) entry.getAsJsonArray("contexts").forEach(c -> chosen.add(c.getAsString()));
            contexts.forEach((key, box) -> box.setSelected(chosen.contains(key)));
            variables.rows.clear(); if (entry.has("variables")) entry.getAsJsonArray("variables").forEach(v -> variables.rows.add(v.getAsJsonObject().deepCopy()));
            variables.fireTableDataChanged(); setEnabledTree(form, id != null);
            duplicate.setEnabled(id != null); remove.setText(id != null && model.builtin(id) ? "Restore default" : "Remove");
            remove.setEnabled(id != null && model.overridden(id));
        } finally { loading = false; }
        refreshPreview();
    }
    private void edit() {
        if (loading || selected == null || saving) return;
        var entry = model.definition(selected);
        entry.addProperty("name", name.getText()); entry.addProperty("abbreviation", abbreviation.getText());
        entry.addProperty("description", description.getText()); entry.addProperty("body", body.getText()); entry.addProperty("disabled", !enabled.isSelected());
        entry.addProperty("reformat", reformat.isSelected()); entry.addProperty("shortenNames", shortenNames.isSelected());
        var cs = new JsonArray(); contexts.forEach((id, box) -> { if (box.isSelected()) cs.add(id); }); entry.add("contexts", cs);
        var vs = new JsonArray(); variables.rows.forEach(v -> vs.add(v.deepCopy())); entry.add("variables", vs);
        model.put(selected, entry); remove.setEnabled(true); list.repaint(); previewTimer.restart();
    }
    private void refreshPreview() {
        previewTimer.stop();
        if (selected == null) { preview.clear("No template selected"); return; }
        try { preview.showTemplate(LiveTemplateSettings.template(model.definition(selected))); if (validLoaded) error.setText(" "); }
        catch (RuntimeException invalid) { preview.clear("Correct the definition to preview it"); error.setText(invalid.getMessage()); }
    }
    private void add(boolean copy) {
        stopEditing(); String id = model.add(copy && selected != null ? model.definition(selected) : null);
        search.setText(""); filter(); list.setSelectedValue(id, true); name.requestFocusInWindow(); name.selectAll();
    }
    private void remove() {
        if (selected == null) return;
        stopEditing(); model.remove(selected); filter(); list.requestFocusInWindow();
    }
    private void editJson() {
        stopEditing();
        var dialog = new EmbeddedDialog(getOwner(), "Template JSON", true);
        var definitions = new JTextArea(validLoaded ? model.json() : originalJson, 18, 62); traverse(definitions);
        definitions.setName("live-template-definitions"); definitions.getAccessibleContext().setAccessibleName("User template definitions JSON");
        var message = new JLabel("Advanced configuration. Apply updates this draft; Save writes the file."); message.setName("live-template-json-error");
        var panel = new JPanel(new BorderLayout(6, 6)); panel.add(message, BorderLayout.NORTH); panel.add(new JScrollPane(definitions));
        var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(button("Cancel", "live-template-json-cancel", dialog::dispose));
        var apply = button("Apply", "live-template-json-apply", () -> {
            try { model.load(definitions.getText()); originalJson = definitions.getText(); validLoaded = true; save.setEnabled(true); filter(); dialog.dispose(); }
            catch (RuntimeException invalid) { message.setText(invalid.getMessage()); }
        });
        buttons.add(apply); panel.add(buttons, BorderLayout.SOUTH); dialog.setContentPane(panel); dialog.getRootPane().setDefaultButton(apply);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.onOpened(() -> SwingUtilities.invokeLater(definitions::requestFocusInWindow)); dialog.pack(); dialog.setVisible(true);
    }
    private void save() {
        if (saving || !validLoaded) return;
        stopEditing(); String json = model.json();
        try { LiveTemplateCatalog.parse(json); }
        catch (RuntimeException invalid) { error.setText(invalid.getMessage()); return; }
        saving = true; previewTimer.stop();
        var state = new IdentityHashMap<Component, Boolean>(); remember(getContentPane(), state);
        setEnabledTree(getContentPane(), false); closeButton().setEnabled(false); error.setText("Saving templates…");
        saver.save(json, failure -> {
            if (isClosed()) return;
            saving = false;
            if (failure == null) dispose();
            else { state.forEach(Component::setEnabled); closeButton().setEnabled(true); error.setText("Save failed: " + failure.getMessage()); }
        });
    }
    private void cancel() { if (!saving) dispose(); }
    private static void remember(Component c, Map<Component, Boolean> state) {
        state.put(c, c.isEnabled()); if (c instanceof Container container) for (var child : container.getComponents()) remember(child, state);
    }
    private static void setEnabledTree(Component c, boolean enabled) {
        c.setEnabled(enabled); if (c instanceof Container container) for (var child : container.getComponents()) setEnabledTree(child, enabled);
    }
    private static JTextField field(String id, String title) {
        var field = new JTextField(20); field.setName(id); field.getAccessibleContext().setAccessibleName(title); return field;
    }
    private static JLabel label(String text, JComponent input) { var label = new JLabel(text); label.setLabelFor(input); return label; }
    private static void row(JPanel panel, int row, String title, JComponent field) {
        var c = new GridBagConstraints(); c.gridy = row; c.gridx = 0; c.anchor = GridBagConstraints.LINE_START; c.insets = new Insets(2, 2, 2, 6);
        panel.add(label(title, field), c); c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
    }
    private static JButton button(String title, String id, Runnable action) { var button = new JButton(title); button.setName(id); button.addActionListener(e -> action.run()); return button; }
    private static void traverse(JTextArea area) {
        area.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Set.of(KeyStroke.getKeyStroke("TAB")));
        area.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Set.of(KeyStroke.getKeyStroke("shift TAB")));
    }
    private static void bind(JComponent target, String key, Runnable action) {
        String id = "template-" + key; target.getInputMap().put(KeyStroke.getKeyStroke(key), id);
        target.getActionMap().put(id, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    private static DocumentListener changed(Runnable action) { return new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { action.run(); } public void removeUpdate(DocumentEvent e) { action.run(); } public void changedUpdate(DocumentEvent e) { action.run(); }
    }; }
    private static final class Variables extends AbstractTableModel {
        final List<JsonObject> rows = new ArrayList<>();
        private static final String[] KEYS = {"name", "expression", "defaultValue", "alwaysStop"};
        private static final String[] TITLES = {"Name", "Expression", "Default value", "Stop"};
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return 4; }
        public String getColumnName(int c) { return TITLES[c]; }
        public Class<?> getColumnClass(int c) { return c == 3 ? Boolean.class : String.class; }
        public boolean isCellEditable(int r, int c) { return true; }
        public Object getValueAt(int r, int c) { var v = rows.get(r); return c == 3 ? !v.has("alwaysStop") || v.get("alwaysStop").getAsBoolean() : LiveTemplateSettings.text(v, KEYS[c]); }
        public void setValueAt(Object value, int r, int c) { if (c == 3) rows.get(r).addProperty(KEYS[c], Boolean.TRUE.equals(value)); else rows.get(r).addProperty(KEYS[c], Objects.toString(value, "")); fireTableCellUpdated(r, c); }
    }
}
