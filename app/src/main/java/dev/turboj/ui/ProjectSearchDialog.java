package dev.turboj.ui;

import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.project.ProjectSearch;
import dev.turboj.project.ProjectSearch.Item;
import dev.turboj.project.ProjectSearch.Kind;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Search the project by name, by category; the query field keeps focus so the keyboard never leaves it. */
public final class ProjectSearchDialog extends EmbeddedDialog {
    public enum Tab {
        ALL("All", EnumSet.allOf(Kind.class)), CLASSES("Classes", EnumSet.of(Kind.CLASS)), FILES("Files", EnumSet.of(Kind.FILE)),
        METHODS("Methods", EnumSet.of(Kind.METHOD)), FIELDS("Fields", EnumSet.of(Kind.FIELD));
        final String title; final Set<Kind> kinds;
        Tab(String title, Set<Kind> kinds) { this.title = title; this.kinds = kinds; }
    }

    private static final int LIMIT = 200;
    private final JTextField query = new JTextField();
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel status = new JLabel(" ");
    private final Path current;
    private final String project;
    private final Consumer<Item> open;
    private List<Item> items;
    private String failure;
    private Tab tab;

    public ProjectSearchDialog(Component owner, Tab initial, String project, Path current,
            CompletableFuture<List<Item>> index, Consumer<Item> open) {
        super(owner, "Search " + project, false);
        this.tab = initial; this.project = project; this.current = current; this.open = open;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));
        query.setName("project-search-query"); query.getAccessibleContext().setAccessibleName("Search project");
        list.setName("project-search-results"); list.getAccessibleContext().setAccessibleName("Project search results");
        tabs.setName("project-search-tabs");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false); // Typing always goes to the query.
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                var label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focus);
                if (value instanceof Item item) label.setText(String.format(" %-6s %s   %s", tag(item.kind()), item.label(),
                        item.detail().isEmpty() ? "" : "· " + item.detail()));
                return label;
            }
        });
        var north = new JPanel(new BorderLayout(0, 4));
        north.add(tabs, BorderLayout.NORTH); north.add(query, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        query.setFocusTraversalKeysEnabled(false); // Tab switches category instead of moving focus.
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        bind("DOWN", () -> move(1)); bind("UP", () -> move(-1));
        bind("PAGE_DOWN", () -> move(10)); bind("PAGE_UP", () -> move(-10));
        bind("TAB", () -> select(Tab.values()[(tab.ordinal() + 1) % Tab.values().length]));
        bind("shift TAB", () -> select(Tab.values()[(tab.ordinal() + Tab.values().length - 1) % Tab.values().length]));
        bind("ENTER", this::activate);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) { if (event.getClickCount() == 2) activate(); }
        });
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(720, 440);
        setLocationRelativeTo(owner);
        renderTabs();
        update();
        index.whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) failure = "Cannot search " + project + ": " + Objects.requireNonNullElse(error.getMessage(), error.toString());
            else items = value;
            update();
        }));
        SwingUtilities.invokeLater(query::requestFocusInWindow);
    }

    public JTextField queryField() { return query; }
    public JList<Item> results() { return list; }
    public Tab tab() { return tab; }

    public void select(Tab next) { tab = next; renderTabs(); update(); }

    private void renderTabs() {
        tabs.removeAll();
        for (Tab value : Tab.values()) {
            var label = new JLabel(value == tab ? "[" + value.title + "]" : " " + value.title + " ");
            label.setName("project-search-tab-" + value.name().toLowerCase(Locale.ROOT));
            label.setOpaque(value == tab);
            if (value == tab) { label.setBackground(list.getSelectionBackground()); label.setForeground(list.getSelectionForeground()); }
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            label.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent event) { select(value); query.requestFocusInWindow(); }
            });
            tabs.add(label); tabs.add(Box.createHorizontalStrut(6));
        }
        tabs.revalidate(); tabs.repaint();
    }

    private void update() {
        model.clear();
        if (failure != null) { status.setText(" " + failure); return; }
        if (items == null) { status.setText(" Indexing " + project + "…"); return; }
        var found = ProjectSearch.search(items, tab.kinds, query.getText(), current, LIMIT);
        found.forEach(model::addElement);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        status.setText(" " + (found.isEmpty() ? "No matches" : found.size() + (found.size() == LIMIT ? "+" : "") + " results")
                + " · Tab next category · ↑↓ select · Enter open · Esc close");
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int index = Math.clamp(list.getSelectedIndex() + delta, 0, model.size() - 1);
        list.setSelectedIndex(index); list.ensureIndexIsVisible(index);
    }

    private void activate() {
        Item item = list.getSelectedValue();
        if (item == null) return;
        // As in the command palette, the editor receives focus only after this overlay has closed.
        OverlayHost.closeThen(this, () -> open.accept(item));
    }

    private void bind(String key, Runnable action) {
        query.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        query.getActionMap().put(key, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }

    private static String tag(Kind kind) {
        return switch (kind) { case CLASS -> "class"; case FILE -> "file"; case METHOD -> "method"; case FIELD -> "field"; };
    }
}
