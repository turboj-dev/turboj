package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.palette.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;

/** Registry-backed keymap editor. Every mutation is conflict checked; file I/O runs off the EDT. */
public final class KeymapEditorDialog extends EmbeddedDialog {
    private final CommandRegistry registry;
    private final KeymapManager maps;
    private final Runnable changed;
    private final JComboBox<KeymapProfile> profiles = new JComboBox<> ();
    private final JTextField search = new JTextField();
    private final JTable table;
    private final Rows rows = new Rows();
    private final CommandSearch commandSearch;
    private boolean refreshing;

    public KeymapEditorDialog(Component owner, CommandRegistry registry, KeymapManager maps,
            Runnable changed) {
        super(owner, "Keymap", false);
        this.registry = registry;
        this.maps = maps;
        this.changed = changed;
        commandSearch = new CommandSearch(registry, maps::revision, id -> maps.commandBindings(registry, id).stream()
                .flatMap(b -> java.util.stream.Stream.of(b.sequence().display(), b.sequence().external())).toList());
        table = new JTable(rows);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName("Commands and keyboard shortcuts");
        setLayout(new BorderLayout(8, 8));
        var top = new JPanel(new BorderLayout(6, 6));
        var profileLine = new JPanel(new BorderLayout(6, 6));
        profiles.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean selected, boolean focus) {
                return super.getListCellRendererComponent(l, value instanceof KeymapProfile p ? p
                        .name() : value, index, selected, focus);
            }
        });
        profiles.getAccessibleContext().setAccessibleName("Keyboard profile");
        profileLine.add(profiles, BorderLayout.CENTER);
        profileLine.add(button("Duplicate…", this::duplicate), BorderLayout.EAST);
        top.add(profileLine, BorderLayout.NORTH);
        search.getAccessibleContext().setAccessibleName("Search commands or shortcuts");
        var searchLine = new JPanel(new BorderLayout(6, 0));
        var searchLabel = new JLabel("Search:"); searchLabel.setLabelFor(search);
        searchLine.add(searchLabel, BorderLayout.WEST); searchLine.add(search, BorderLayout.CENTER);
        top.add(searchLine, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        var buttons = new JPanel(new GridLayout(0, 4, 4, 4));
        buttons.add(button("Add shortcut…", () -> record(false)));
        buttons.add(button("Replace…", () -> record(true)));
        buttons.add(button("Remove…", this::remove));
        buttons.add(button("Reset command", this::resetCommand));
        buttons.add(button("Reset profile", this::resetProfile));
        buttons.add(button("Import JSON…", this::importJson));
        buttons.add(button("Export JSON…", this::exportJson));
        buttons.add(button("Reference / conflicts…", () -> new ShortcutReferenceDialog(this, registry,
                maps, CommandContext.of()).setVisible(true)));
        buttons.add(button("Close", this::dispose));
        add(buttons, BorderLayout.SOUTH);
        profiles.addActionListener(e -> {
            if (!refreshing && profiles.getSelectedItem() instanceof KeymapProfile p) {
                maps.select(p.id());
                notifyChanged();
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updateRows();
            }

            public void removeUpdate(DocumentEvent e) {
                updateRows();
            }

            public void changedUpdate(DocumentEvent e) {
                updateRows();
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "recordShortcut");
        table.getActionMap().put("recordShortcut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { record(false); }
        });
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "focusCommands");
        search.getActionMap().put("focusCommands", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { table.requestFocusInWindow(); }
        });
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 460);
        setMinimumSize(new Dimension(540, 340));
        setLocationRelativeTo(owner);
        refresh();
    }

    private JButton button(String text, Runnable action) {
        var b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private String command() {
        int selected = table.getSelectedRow();
        return selected < 0 ? null : rows.commands.get(selected).id();
    }

    private void refresh() {
        refreshing = true;
        profiles.removeAllItems();
        for (var p : maps.profiles()) {
            profiles.addItem(p);
            if (p.id().equals(maps.selectedId()))
                profiles.setSelectedItem(p);
        }
        refreshing = false;
        updateRows();
    }

    private void updateRows() {
        String selected = command();
        String q = search.getText().strip().toLowerCase(Locale.ROOT);
        var results = commandSearch.search(q, CommandContext.of(), 1000);
        var commands = new LinkedHashMap<String, Command> ();

        for (var result : results)
            commands.put(result.command().id(), result.command());
        if (!q.isEmpty())
            for (var c : registry.commands())
                if (shortcut(c.id()).toLowerCase(Locale.ROOT).contains(q))
                    commands.put(c.id(), c);
        rows.commands = List.copyOf(commands.values());
        rows.fireTableDataChanged();
        int index = 0;

        for (int i = 0; i < rows.commands.size(); i++)
            if (rows.commands.get(i).id().equals(selected)) {
                index = i;
                break;
            }
        if (!rows.commands.isEmpty())
            table.setRowSelectionInterval(index, index);
    }

    private void notifyChanged() {
        changed.run();
        refresh();
    }

    private void duplicate() {
        String name = EmbeddedOptionPane.showInputDialog(this,
                "Name of the copied keyboard profile:", maps.selected().name() + " Copy");

        if (name == null || name.isBlank())
            return;
        String id = "user-" + UUID.randomUUID();
        maps.duplicate(id, name.strip());
        notifyChanged();
    }

    private void record(boolean replace) {
        String id = command();

        if (id == null)
            return;
        KeySequence value = ShortcutRecorderDialog.record(this);

        if (value == null)
            return;
        var next = new ArrayList<> (replace ? List.<KeyBinding>of() : maps.commandBindings(registry, id));
        BindingContext context = maps.commandBindings(registry, id).isEmpty()
                ? new BindingContext(registry.find(id).orElseThrow().requiredContexts(), Set.of())
                : maps.commandBindings(registry, id).getFirst().context();
        Object scope = EmbeddedOptionPane.showInputDialog(this, "Shortcut context:", "Binding context",
                JOptionPane.PLAIN_MESSAGE, null, new String[]{"Keep command context", "Editor only", "Any application panel"},
                "Keep command context");
        if (scope == null) return;
        if (scope.equals("Editor only")) context = BindingContext.requiring("editor");
        else if (scope.equals("Any application panel")) context = BindingContext.any();
        next.add(new KeyBinding(value, context));
        var result = maps.replaceCommandBindings(registry, id, next, Set.of());

        if (!result.applied()) {
            var others = new TreeSet<String> ();

            for (var conflict : result.conflicts())
                others.add(conflict.command().equals(id) ? conflict.other() : conflict.command());
            if (others.contains(id)) {
                error("The shortcut duplicates or prefixes another binding for this command.");
                return;
            }
            String names = String.join(", ", others.stream().map(key -> registry.find(key)
                    .map(Command::title).orElse(key)).toList());
            int choice = EmbeddedOptionPane.showConfirmDialog(this, "Conflicts with: " + names
                    + "\nRemove only the conflicting bindings from these commands and assign this shortcut?",
                    "Resolve shortcut conflict", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (choice != JOptionPane.OK_OPTION)
                return;
            result = maps.replaceCommandBindings(registry, id, next, others);
            if (!result.applied()) {
                error("The keymap changed; resolve the current conflicts first.");
                return;
            }
        }
        notifyChanged();
    }

    private void remove() {
        String id = command();

        if (id == null)
            return;
        List<KeyBinding> bindings = maps.commandBindings(registry, id);

        if (bindings.isEmpty())
            return;
        String[] names = bindings.stream().map(b -> b.sequence().display()).toArray(String[]::new);
        Object selected = EmbeddedOptionPane.showInputDialog(this, "Remove shortcut:", "Remove",
                JOptionPane.PLAIN_MESSAGE, null, names, names[0]);

        if (selected == null)
            return;
        int index = Arrays.asList(names).indexOf(selected);
        var next = new ArrayList<> (bindings);
        next.remove(index);
        maps.replaceCommandBindings(registry, id, next, Set.of());
        notifyChanged();
    }

    private void resetCommand() {
        String id = command();

        if (id == null)
            return;
        var result = maps.resetCommand(registry, id);

        if (!result.applied()) {
            error("The default binding conflicts with a customization. Remove that conflicting shortcut first.");
            return;
        }
        notifyChanged();
    }

    private void resetProfile() {
        if (EmbeddedOptionPane.showConfirmDialog(this,
                "Reset all overrides in this keyboard profile?", "Reset profile",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            maps.resetProfile();
            notifyChanged();
        }
    }

    private void importJson() {
        var chooser = new EmbeddedFileChooser();

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        Path path = chooser.getSelectedFile().toPath();
        new SwingWorker<String, Void> () {
            protected String doInBackground() throws Exception {
                if (Files.size(path) > 1_048_576)
                    throw new IllegalArgumentException("Keymap exceeds 1 MiB");
                return Files.readString(path);
            }

            protected void done() {
                try {
                    KeymapJson.read(maps, get());
                    notifyChanged();
                } catch (Exception failure) {
                    error(message(failure));
                }
            }
        }.execute();
    }

    private void exportJson() {
        var chooser = new EmbeddedFileChooser();
        chooser.setSelectedFile(new java.io.File("commandj-keymap.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        Path path = chooser.getSelectedFile().toPath();

        if (Files.exists(path) && EmbeddedOptionPane.showConfirmDialog(this,
                "Replace the selected JSON file?", "Export", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION)
            return;
        String snapshot = KeymapJson.write(maps);
        new SwingWorker<Void, Void> () {
            protected Void doInBackground() throws Exception {
                KeymapJson.save(path, snapshot);
                return null;
            }

            protected void done() {
                try {
                    get();
                } catch (Exception failure) {
                    error(message(failure));
                }
            }
        }.execute();
    }

    private static String message(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private void error(String text) {
        EmbeddedOptionPane.showMessageDialog(this, text, "Keymap", JOptionPane.ERROR_MESSAGE);
    }

    private String shortcut(String id) {
        return String.join(" / ", maps.commandBindings(registry, id).stream().map(b -> b.sequence().display()).toList());
    }

    private final class Rows extends AbstractTableModel {
        List<Command> commands = List.of();

        public int getRowCount() {
            return commands.size();
        }

        public int getColumnCount() {
            return 3;
        }

        public String getColumnName(int i) {
            return switch (i) {
                case 0 -> "Command";
                case 1 -> "Category";
                default -> "Shortcut";
            };
        }

        public Object getValueAt(int row, int column) {
            Command c = commands.get(row);
            return switch (column) {
                case 0 -> c.title();
                case 1 -> c.category();
                default -> shortcut(c.id());
            };
        }
    }

    public JTextField searchField() {
        return search;
    }

    public JTable commandsTable() {
        return table;
    }
}
