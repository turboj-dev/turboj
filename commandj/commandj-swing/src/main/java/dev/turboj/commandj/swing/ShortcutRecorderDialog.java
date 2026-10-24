package dev.turboj.commandj.swing;

import dev.turboj.commandj.keymap.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;

/** Records up to four logical strokes. Dead keys/AltGraph are left to the input method. */
public final class ShortcutRecorderDialog extends EmbeddedDialog {
    private final java.util.List<KeyStrokeSpec> strokes = new ArrayList<> ();
    private final JTextField field = new JTextField();
    private final JLabel warning = new JLabel("Press a shortcut or a sequence of up to four strokes.");
    private KeySequence result;

    private ShortcutRecorderDialog(Component owner) {
        super(owner, "Record shortcut", true);
        setLayout(new BorderLayout(6, 6));
        field.setEditable(false);
        field.setFocusTraversalKeysEnabled(false);
        field.getAccessibleContext().setAccessibleName("Shortcut recorder");
        field.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && e.getModifiersEx() == 0) {
                    e.consume();
                    dispose();
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.getModifiersEx() == 0 && !strokes
                        .isEmpty()) {
                    e.consume();
                    result = new KeySequence(strokes);
                    dispose();
                    return;
                }
                var key = SwingKeys.fromEvent(e);

                if (key.isEmpty())
                    return;
                e.consume();
                addStroke(key.get());
            }
        });
        add(field, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        center.add(warning, BorderLayout.NORTH);
        JPanel special = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var keys = new JComboBox<> (new String[] {"ESCAPE", "ENTER", "TAB", "BACK_SPACE",
                "DELETE"});
        keys.getAccessibleContext().setAccessibleName("Special key to record");
        var add = new JButton("Add special key");
        add.addActionListener(event -> {
            addStroke(KeyStrokeSpec.parse((String) keys.getSelectedItem()));
            field.requestFocusInWindow();
        });
        special.add(keys);
        special.add(add);
        center.add(special, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        var buttons = new JPanel();
        var clear = new JButton("Clear");
        clear.addActionListener(e -> {
            strokes.clear();
            field.setText("");
            field.requestFocusInWindow();
        });
        buttons.add(clear);
        var use = new JButton("Use shortcut");
        use.addActionListener(e -> {
            if (!strokes.isEmpty()) {
                result = new KeySequence(strokes);
                dispose();
            }
        });
        buttons.add(use);
        var cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(590, 190);
        setLocationRelativeTo(owner);
        onOpened(() -> field.requestFocusInWindow());
    }

    private void addStroke(KeyStrokeSpec key) {
        if (strokes.size() == 4)
            strokes.clear();
        strokes.add(key);
        var sequence = new KeySequence(strokes);
        field.setText(sequence.display());
        warning.setText(ReservedShortcuts.warning(sequence).orElse(key.modifiers().isEmpty() && key
                .key().length()
                == 1 ? "A plain character shortcut can intercept text entry." : "Press Use shortcut to accept."));
    }

    public static KeySequence record(Component owner) {
        var dialog = new ShortcutRecorderDialog(owner);
        dialog.setVisible(true);
        return dialog.result;
    }
}
