package dev.turboj.ui;

import dev.turboj.commandj.swing.EmbeddedDialog;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.*;

/** Find and replace in the current buffer, as in every editor's ⌥⌘F dialog: Enter finds the next match. */
public final class FindReplaceDialog extends EmbeddedDialog {
    private final EditorComponent editor;
    private final Consumer<String> remember;
    private final JTextField find = new JTextField(28), replace = new JTextField(28);
    private final JCheckBox matchCase = new JCheckBox("Match case"), wholeWord = new JCheckBox("Whole word");
    private final JLabel status = new JLabel(" ");

    public FindReplaceDialog(Component owner, EditorComponent editor, String query, Consumer<String> remember) {
        super(owner, "Find and Replace", false);
        this.editor = editor;
        this.remember = remember;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        find.setName("find-replace-find"); replace.setName("find-replace-replace");
        find.getAccessibleContext().setAccessibleName("Find"); replace.getAccessibleContext().setAccessibleName("Replace with");
        String selected = editor.getSelectedText();
        find.setText(!selected.isEmpty() && !selected.contains("\n") ? selected : query);

        var form = new JPanel(new GridBagLayout());
        var c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(label("Find:", find), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(find, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(label("Replace with:", replace), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(replace, c);
        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(matchCase); options.add(wholeWord);
        c.gridx = 1; c.gridy = 2; form.add(options, c);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(button("Replace All", "find-replace-all", this::replaceAll));
        buttons.add(button("Replace", "find-replace-one", this::replaceOne));
        buttons.add(button("Previous", "find-replace-previous", () -> next(false)));
        JButton next = button("Find Next", "find-replace-next", () -> next(true));
        buttons.add(next);
        buttons.add(button("Close", "find-replace-close", this::dispose));

        var body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(form, BorderLayout.NORTH);
        var south = new JPanel(new BorderLayout(6, 6));
        south.add(status, BorderLayout.WEST); south.add(buttons, BorderLayout.EAST);
        body.add(south, BorderLayout.SOUTH);
        setContentPane(body);
        getRootPane().setDefaultButton(next);
        // Enter finds the next match from either field; Shift+Enter the previous one; Escape closes.
        for (JTextField field : List.of(find, replace)) {
            field.addActionListener(event -> next(true));
            bind(field, "shift ENTER", () -> next(false));
        }
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> { find.requestFocusInWindow(); find.selectAll(); });
    }

    public JTextField findField() { return find; }
    public JTextField replaceField() { return replace; }
    public String status() { return status.getText().strip(); }

    /** Selects the next match after (or the previous before) the selection, wrapping around the buffer. */
    public boolean next(boolean forward) {
        List<int[]> matches = matches();
        remember();
        if (matches.isEmpty()) { status.setText("Not found"); return false; }
        int from = forward ? editor.selectionEnd() : editor.selectionStart();
        int[] chosen = forward ? matches.getFirst() : matches.getLast();
        if (forward) { for (int[] m : matches) if (m[0] >= from) { chosen = m; break; } }
        else for (int i = matches.size() - 1; i >= 0; i--) if (matches.get(i)[1] <= from) { chosen = matches.get(i); break; }
        editor.setSelection(chosen[0], chosen[1]);
        status.setText(matches.size() == 1 ? "1 match" : (matches.indexOf(chosen) + 1) + " of " + matches.size());
        return true;
    }

    /** Replaces the selected match, then selects the next one. */
    public void replaceOne() {
        if (editor.isReadOnly()) { status.setText("Read only"); return; }
        if (matches().stream().anyMatch(m -> m[0] == editor.selectionStart() && m[1] == editor.selectionEnd())) {
            editor.replaceSelection(replace.getText());
            if (!next(true)) status.setText("Replaced; no more matches");
        } else next(true);
    }

    /** Every match at once: one edit, so a single Undo restores the buffer. */
    public void replaceAll() {
        if (editor.isReadOnly()) { status.setText("Read only"); return; }
        List<int[]> matches = matches();
        remember();
        if (matches.isEmpty()) { status.setText("Not found"); return; }
        var selections = new ArrayList<EditorComponent.Selection>();
        for (int[] m : matches) selections.add(new EditorComponent.Selection(m[1], m[0]));
        editor.setSelections(selections);
        editor.replaceSelection(replace.getText());
        editor.removeSecondaryCarets();
        status.setText("Replaced " + matches.size() + (matches.size() == 1 ? " match" : " matches"));
    }

    private List<int[]> matches() {
        String query = find.getText();
        var found = new ArrayList<int[]>();
        if (query.isEmpty()) return found;
        String text = editor.getText();
        String haystack = matchCase.isSelected() ? text : text.toLowerCase(Locale.ROOT);
        String needle = matchCase.isSelected() ? query : query.toLowerCase(Locale.ROOT);
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + Math.max(1, needle.length()))) {
            int end = at + needle.length();
            if (wholeWord.isSelected() && (at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))
                    || end < text.length() && Character.isJavaIdentifierPart(text.charAt(end)))) continue;
            found.add(new int[]{at, end});
        }
        return found;
    }

    private void remember() { if (!find.getText().isEmpty()) remember.accept(find.getText()); }

    private static JLabel label(String text, JComponent target) {
        var label = new JLabel(text);
        label.setLabelFor(target);
        return label;
    }

    private static JButton button(String text, String name, Runnable action) {
        var button = new JButton(text);
        button.setName(name);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static void bind(JComponent c, String key, Runnable action) {
        c.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        c.getActionMap().put(key, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
}
