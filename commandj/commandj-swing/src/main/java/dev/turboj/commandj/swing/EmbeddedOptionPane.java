package dev.turboj.commandj.swing;

import java.awt.Component;
import javax.swing.*;

/** Familiar Swing message/input API, presented only in the application's internal overlay. */
public final class EmbeddedOptionPane {
    private EmbeddedOptionPane() {}

    public static int showConfirmDialog(Component owner, Object message, String title,
            int options) {
        return showConfirmDialog(owner, message, title, options, JOptionPane.QUESTION_MESSAGE);
    }

    public static int showConfirmDialog(Component owner, Object message, String title, int options,
            int type) {
        var pane = new JOptionPane(message, type, options);
        show(owner, title, pane);
        return pane.getValue() instanceof Integer value ? value : JOptionPane.CLOSED_OPTION;
    }

    public static void showMessageDialog(Component owner, Object message, String title, int type) {
        show(owner, title, new JOptionPane(message, type, JOptionPane.DEFAULT_OPTION));
    }

    public static void showMessageDialog(Component owner, Object message) {
        showMessageDialog(owner, message, "Message", JOptionPane.INFORMATION_MESSAGE);
    }

    public static String showInputDialog(Component owner, Object message) {
        return showInputDialog(owner, message, "");
    }

    public static String showInputDialog(Component owner, Object message, Object initial) {
        Object value = showInputDialog(owner, message, "Input", JOptionPane.QUESTION_MESSAGE, null,
                null, initial);
        return value == null ? null : value.toString();
    }

    public static Object showInputDialog(Component owner, Object message, String title, int type,
            Icon icon, Object[] values, Object initial) {
        var pane = new JOptionPane(message, type, JOptionPane.OK_CANCEL_OPTION, icon);
        pane.setWantsInput(true);
        pane.setSelectionValues(values);
        pane.setInitialSelectionValue(initial);
        show(owner, title, pane);
        Object result = pane.getInputValue();
        return pane.getValue() instanceof Integer choice && choice == JOptionPane.OK_OPTION
                && result != JOptionPane.UNINITIALIZED_VALUE ? result : null;
    }

    private static void show(Component owner, String title, JOptionPane pane) {
        EmbeddedDialog dialog = new EmbeddedDialog(owner, title, true);
        dialog.setResizable(false);
        dialog.setContentPane(pane);
        pane.addPropertyChangeListener(event -> {
            if (JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName()) && pane.getValue()
                    != JOptionPane.UNINITIALIZED_VALUE && pane.getValue() != null)
                dialog.dispose();
        });
        dialog.getRootPane().registerKeyboardAction(event -> {
            pane.setValue(JOptionPane.CLOSED_OPTION);
            dialog.dispose();
        }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(pane::selectInitialValue);
        dialog.setVisible(true);
    }
}
