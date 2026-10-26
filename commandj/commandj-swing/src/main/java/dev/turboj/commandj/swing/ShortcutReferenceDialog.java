package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;

/** Searchable, exportable reference generated from the same registry used for execution. */
public final class ShortcutReferenceDialog extends EmbeddedDialog {
    private final CommandRegistry registry;
    private final KeymapManager maps;
    private final CommandContext context;
    private final JTextField search = new JTextField();
    private final JCheckBox compare = new JCheckBox("Compare all profiles");
    private final Rows rows = new Rows();
    private final JTable table = new JTable(rows);
    private final List<ShortcutReference.Row> all;

    public ShortcutReferenceDialog(Component owner, CommandRegistry registry, KeymapManager maps,
            CommandContext context) {
        super(owner, "Shortcut Reference", false);
        this.registry = registry; this.maps = maps; this.context = context;
        all = ShortcutReference.rows(registry, maps, context);
        setLayout(new BorderLayout(6, 6));
        var top = new JPanel(new BorderLayout(6, 6));
        top.add(new JLabel("Active profile: " + maps.selected().name()), BorderLayout.NORTH);
        search.getAccessibleContext().setAccessibleName("Search commands, shortcuts and contexts");
        top.add(search, BorderLayout.CENTER); top.add(compare, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName("Generated shortcut reference");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, KeyGlyphs.tableRenderer());
        add(new JScrollPane(table), BorderLayout.CENTER);
        var buttons = new JPanel();
        buttons.add(button("Shortcut diagnostics", this::diagnostics));
        buttons.add(button("Export Markdown…", this::export));
        buttons.add(button("Close", this::dispose));
        add(buttons, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        compare.addActionListener(e -> { rows.fireTableStructureChanged(); sizeColumns(); });
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "results");
        search.getActionMap().put("results", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { table.requestFocusInWindow(); }
        });
        onOpened(search::requestFocusInWindow);
        setSize(850, 450); refresh(); sizeColumns();
    }

    private static JButton button(String title, Runnable action) {
        var button = new JButton(title); button.addActionListener(e -> action.run()); return button;
    }

    private void refresh() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        rows.values = all.stream().filter(row -> (row.id() + " " + row.title() + " " + row.category()
                + " " + row.shortcuts() + " " + row.contexts() + " " + String.join(" ", row.aliases()))
                .toLowerCase(Locale.ROOT).contains(query)).toList();
        rows.fireTableDataChanged();
        if (!rows.values.isEmpty()) table.setRowSelectionInterval(0, 0);
    }

    private void sizeColumns() {
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i)
                .setPreferredWidth(i == 0 ? 230 : i == 1 ? 130 : 190);
    }

    private void diagnostics() {
        var diagnostics = ShortcutReference.audit(registry, maps);
        var text = new JTextArea(diagnostics.isEmpty() ? "No binding conflicts or migration warnings."
                : String.join("\n", diagnostics));
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setRows(12); text.setColumns(65);
        EmbeddedOptionPane.showMessageDialog(this, new JScrollPane(text), "Shortcut diagnostics",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void export() {
        var chooser = new EmbeddedFileChooser();
        chooser.setSelectedFile(new java.io.File(compare.isSelected() ? "keymap-comparison.md" : "shortcut-reference.md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && EmbeddedOptionPane.showConfirmDialog(this, "Replace selected file?",
                "Export shortcut reference", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String snapshot = compare.isSelected() ? ShortcutReference.comparisonMarkdown(registry, maps)
                : ShortcutReference.markdown(registry, maps, context);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                Files.writeString(path, snapshot, StandardCharsets.UTF_8); return null;
            }
            protected void done() {
                try { get(); } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    EmbeddedOptionPane.showMessageDialog(ShortcutReferenceDialog.this,
                            cause.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private final class Rows extends AbstractTableModel {
        List<ShortcutReference.Row> values = List.of();
        public int getRowCount() { return values.size(); }
        public int getColumnCount() { return compare.isSelected() ? maps.profiles().size() + 2 : 5; }
        public String getColumnName(int column) {
            if (column == 0) return "Command";
            if (column == 1) return "Category";
            if (compare.isSelected()) return maps.profiles().get(column - 2).name();
            return switch (column) { case 2 -> "Shortcut"; case 3 -> "Context"; default -> "Availability / warnings"; };
        }
        public Object getValueAt(int row, int column) {
            var value = values.get(row);
            if (column == 0) return value.title();
            if (column == 1) return value.category();
            if (compare.isSelected()) {
                var profile = maps.profiles().get(column - 2);
                var bindings = ShortcutReference.bindings(registry, maps.bindingsForProfile(profile.id()), value.id());
                return bindings.isEmpty() ? "Unassigned" : String.join(" / ", bindings.stream().map(b -> b.sequence().display()).toList());
            }
            return switch (column) {
                case 2 -> value.shortcuts();
                case 3 -> ShortcutReference.contexts(value);
                default -> (value.availability().enabled() ? "Available" : value.availability().reason())
                        + (value.warnings().isEmpty() ? "" : "; " + String.join("; ", value.warnings()));
            };
        }
    }

    public JTextField searchField() { return search; }
    public JTable commandsTable() { return table; }
}
