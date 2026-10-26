package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.palette.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;

/** One searchable registry view: categories, aliases, current shortcuts and disabled reasons. */
public final class CommandPaletteDialog extends EmbeddedDialog {
    private final CommandRegistry registry;
    private final KeymapManager maps;
    private final CommandContext context;
    private final CommandSearch search;
    private final JTextField query = new JTextField();
    private final DefaultListModel<CommandSearch.Match> model = new DefaultListModel<> ();
    private final JList<CommandSearch.Match> list = new JList<> (model);
    private final JLabel detail = new JLabel(" ");
    private final Consumer<CommandResult> finished;

    public CommandPaletteDialog(Component owner, CommandRegistry registry, KeymapManager maps,
            CommandContext context, Consumer<CommandResult> finished) {
        super(owner, "Find Action", false);
        this.registry = registry;
        this.maps = maps;
        this.context = context;
        this.search = new CommandSearch(registry, maps::revision, id -> maps.commandBindings(registry, id).stream()
                .flatMap(b -> java.util.stream.Stream.of(b.sequence().display(), b.sequence().words(), b.sequence().external()))
                .toList());
        this.finished = finished;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));
        query.getAccessibleContext().setAccessibleName("Search commands");
        list.getAccessibleContext().setAccessibleName("Available commands");
        add(query, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(detail, BorderLayout.SOUTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row(menus(owner)));
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            public void removeUpdate(DocumentEvent e) {
                update();
            }

            public void changedUpdate(DocumentEvent e) {
                update();
            }
        });
        list.addListSelectionListener(e -> {
            var m = list.getSelectedValue();
            detail.setText(m == null ? " " : m.availability().enabled() ? m.command()
                    .description() : m.availability().reason());
        });
        query.addActionListener(e -> execute());
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2)
                    execute();
            }
        });
        bind(query, "DOWN", () -> move(1));
        bind(query, "UP", () -> move(-1));
        bind(list, "ENTER", this::execute);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(650, 400);
        setLocationRelativeTo(owner);
        update();
        SwingUtilities.invokeLater(query::requestFocusInWindow);
    }

    /** Like a menu item: the menu holding the command, its title, and its shortcut aligned on the right. */
    private final class Row extends JPanel implements ListCellRenderer<CommandSearch.Match> {
        private final java.util.Map<String, String> menus;
        private final JLabel menu = new JLabel(), title = new JLabel(), keys = new JLabel();
        private int menuWidth = -1;

        Row(java.util.Map<String, String> menus) {
            super(new BorderLayout(12, 0));
            this.menus = menus;
            setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            add(menu, BorderLayout.WEST); add(title, BorderLayout.CENTER); add(keys, BorderLayout.EAST);
        }

        private String menu(Command command) { return "[" + menus.getOrDefault(command.id(), command.category()) + "]"; }

        @Override public Component getListCellRendererComponent(JList<? extends CommandSearch.Match> l,
                CommandSearch.Match match, int index, boolean selected, boolean focus) {
            Font font = l.getFont();
            if (menuWidth < 0) {
                var metrics = l.getFontMetrics(font);
                menuWidth = registry.commands().stream().mapToInt(command -> metrics.stringWidth(menu(command))).max().orElse(0);
            }
            Color foreground = selected ? l.getSelectionForeground() : l.getForeground();
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            for (JLabel label : new JLabel[]{menu, title, keys}) { label.setFont(font); label.setForeground(foreground); }
            keys.setFont(KeyGlyphs.shortcutFont(font));
            if (!match.availability().enabled() && !selected) title.setForeground(UIManager.getColor("Label.disabledForeground"));
            menu.setText(menu(match.command()));
            menu.setPreferredSize(new Dimension(menuWidth, menu.getPreferredSize().height));
            title.setText(match.command().title());
            keys.setText(String.join(" / ", maps.commandBindings(registry, match.command().id()).stream()
                    .map(b -> b.sequence().display()).toList()));
            return this;
        }
    }

    /** The top-level menu that offers each command, read from the owner's menu bar. */
    private static java.util.Map<String, String> menus(Component owner) {
        var found = new java.util.HashMap<String, String>();
        JRootPane root = owner == null ? null : SwingUtilities.getRootPane(owner);
        JMenuBar bar = root == null ? null : root.getJMenuBar();
        if (bar != null) for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            // The accessible name spells out symbol titles: Turbo's ≡ menu reads "TurboJ".
            if (menu != null) collect(menu.getPopupMenu(), menu.getAccessibleContext().getAccessibleName(), found);
        }
        return found;
    }

    private static void collect(Container container, String menu, java.util.Map<String, String> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof JMenu nested) collect(nested.getPopupMenu(), menu, found);
            else if (child instanceof JMenuItem item && item.getActionCommand() != null) found.putIfAbsent(item.getActionCommand(), menu);
        }
    }

    private static void bind(JComponent c, String key, Runnable action) {
        c.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        c.getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void move(int delta) {
        if (model.isEmpty())
            return;
        list.setSelectedIndex(Math.floorMod(list.getSelectedIndex() + delta, model.size()));
        list.ensureIndexIsVisible(list.getSelectedIndex());
    }

    private void update() {
        String selected = list.getSelectedValue() == null ? null : list.getSelectedValue().command()
                .id();
        model.clear();
        for (var match : search.search(query.getText(), context, 200))
            model.addElement(match);
        int index = 0;

        for (int i = 0; i < model.size(); i++)
            if (model.get(i).command().id().equals(selected)) {
                index = i;
                break;
            }
        if (!model.isEmpty())
            list.setSelectedIndex(index);
    }

    private void execute() {
        var selected = list.getSelectedValue();

        if (selected == null)
            return;
        var availability = registry.availability(selected.command().id(), context);

        if (!availability.enabled()) {
            detail.setText(availability.reason());
            return;
        }
        // A native focus request may not have settled when the next modal surface opens.
        OverlayHost.closeThen(this, () -> registry.execute(selected.command().id(), context).stage()
                .thenAccept(result -> SwingUtilities.invokeLater(() -> finished.accept(result))));
    }

    public JTextField searchField() {
        return query;
    }

    public JList<CommandSearch.Match> results() {
        return list;
    }
}
