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
        this.search = new CommandSearch(registry);
        this.finished = finished;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));
        query.getAccessibleContext().setAccessibleName("Search commands");
        list.getAccessibleContext().setAccessibleName("Available commands");
        add(query, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(detail, BorderLayout.SOUTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean selected, boolean focus) {
                var label = (JLabel) super.getListCellRendererComponent(l, value, index, selected,
                        focus);
                var match = (CommandSearch.Match) value;
                label.setText(match.command().title() + "   [" + match.command().category() + "]   "
                        + maps.shortcutText(match.command().id()));
                if (!match.availability().enabled() && !selected)
                    label.setForeground(UIManager.getColor("Label.disabledForeground"));
                return label;
            }
        });
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
        dispose();
        registry.execute(selected.command().id(), context).stage()
                .thenAccept(result -> SwingUtilities.invokeLater(() -> finished.accept(result)));
    }

    public JTextField searchField() {
        return query;
    }

    public JList<CommandSearch.Match> results() {
        return list;
    }
}
