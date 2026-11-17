package dev.turboj.app;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.CommandKeyDispatcher;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Real AWT key rollover regression; shortcuts must not suppress another key's typed event. */
public final class CommandRolloverSmoke {
    public static void main(String[] args) throws Exception {
        var calls = new AtomicInteger();
        var registry = new CommandRegistry();
        var maps = new KeymapManager();
        registry.register(Command.action("test.action", "Action", "Test", calls::incrementAndGet));
        maps.replaceBindings("test.action", List.of(KeyBinding.of("F13"), KeyBinding
                .of("F14, F15")));
        JTextArea area = new JTextArea();
        JFrame frame = edt(() -> {
            JFrame value = new JFrame("CommandJ rollover regression");
            value.setContentPane(new JScrollPane(area));
            value.setSize(400, 180);
            value.setVisible(true);
            value.toFront();
            Desktop.getDesktop().requestForeground(true);
            area.requestFocusInWindow();
            return value;
        });
        var dispatcher = edt(() -> new CommandKeyDispatcher(frame, registry, maps,
                CommandContext::of, ignored -> {}));

        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while (!edt(() -> area.isFocusOwner() && frame.isActive())) {
                if (System.nanoTime() > end)
                    throw new AssertionError("Editor did not receive focus");
                Thread.sleep(10);
            }
            edt(() -> {
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_F13, KeyEvent.CHAR_UNDEFINED);
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_X, 'x');
                send(area, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'x');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_X, 'x');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_F13, KeyEvent.CHAR_UNDEFINED);
                check(calls.get() == 1, "held shortcut invokes exactly once");
                check(area.getText().equals("x"),
                        "new printable key survives held shortcut rollover; actual=" + area
                        .getText());
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a');
                send(area, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'a');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_A, 'a');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                check(area.getText().equals("x"),
                        "invalid chord suffix is consumed, never inserted into source");
                maps.replaceBindings("test.action", List.of(KeyBinding.of("Q")));
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 'q');
                send(area, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'q');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q, 'q');
                check(area.getText().equals("x"),
                        "typed event belonging to the handled command remains suppressed");
                return null;
            });
            JTextField field = new JTextField(20);
            EmbeddedDialog popup = edt(() -> {
                var dialog = new EmbeddedDialog(frame, "Shortcut-opened dialog", false);
                dialog.add(field); dialog.pack();
                registry.register(Command.action("test.dialog", "Dialog", "Test", () -> dialog.setVisible(true)));
                maps.replaceBindings("test.dialog", List.of(KeyBinding.of("F14, D")));
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 'd');
                return dialog;
            });
            long focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!edt(field::isFocusOwner)) {
                if (System.nanoTime() > focusDeadline) throw new AssertionError("Dialog did not receive focus");
                Thread.sleep(10);
            }
            edt(() -> {
                send(field, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'd');
                send(field, KeyEvent.KEY_RELEASED, KeyEvent.VK_D, 'd');
                check(field.getText().isEmpty(), "shortcut suffix cannot leak into the dialog it opens; actual=" + field.getText());
                send(field, KeyEvent.KEY_PRESSED, KeyEvent.VK_X, 'x');
                send(field, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'x');
                send(field, KeyEvent.KEY_RELEASED, KeyEvent.VK_X, 'x');
                check(field.getText().equals("x"), "fresh dialog typing remains available");
                popup.dispose();
                return null;
            });
            await(() -> area.isFocusOwner(), "source focus after dialog");
            JTextField modalInput = new JTextField(20);
            JTextField secondInput = new JTextField(20);
            EmbeddedDialog modal = edt(() -> {
                var dialog = new EmbeddedDialog(frame, "Modal shortcut", true);
                var fields = new JPanel(new GridLayout(0, 1));
                fields.add(modalInput); fields.add(secondInput);
                JButton confirm = new JButton("Confirm");
                confirm.addActionListener(e -> dialog.dispose());
                fields.add(confirm); dialog.add(fields); dialog.getRootPane().setDefaultButton(confirm); dialog.pack();
                registry.register(Command.action("test.modal", "Modal", "Test", () -> dialog.setVisible(true)));
                maps.replaceBindings("test.modal", List.of(KeyBinding.of("F14, M")));
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_F14, KeyEvent.CHAR_UNDEFINED);
                SwingUtilities.invokeLater(() -> send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_M, 'm'));
                return dialog;
            });
            await(modalInput::isFocusOwner, "modal input focus");
            int beforeModal = calls.get();
            edt(() -> {
                send(modalInput, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'm');
                send(modalInput, KeyEvent.KEY_RELEASED, KeyEvent.VK_M, 'm');
                check(modalInput.getText().isEmpty(), "modal secondary loop suppresses the opening shortcut letter");
                send(modalInput, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 'q');
                send(modalInput, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'q');
                send(modalInput, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q, 'q');
                check(modalInput.getText().equals("q") && calls.get() == beforeModal,
                        "modal input owns keys even when the same key is a global command");
                send(modalInput, KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t');
                send(modalInput, KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t');
                return null;
            });
            await(secondInput::isFocusOwner, "Tab reaches next modal field");
            edt(() -> {
                send(secondInput, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n');
                send(secondInput, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER, '\n');
                return null;
            });
            await(() -> !OverlayHost.active(frame) && area.isFocusOwner(), "Enter confirms and restores source focus");
            edt(() -> {
                send(area, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'z');
                check(area.getText().equals("xz"), "return from modal loop does not re-arm typed suppression");
                return null;
            });
        } finally {
            edt(() -> {
                dispatcher.close();
                frame.dispose();
                return null;
            });
            registry.close();
        }
    }

    private static void await(Callable<Boolean> ready, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(ready)) {
            if (System.nanoTime() > deadline) throw new AssertionError(label);
            Thread.sleep(10);
        }
        System.out.println("PASS " + label);
    }

    private static void send(Component source, int kind, int code, char character) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(source,
                kind, System.currentTimeMillis(), 0, code, character));
    }

    private static void check(boolean condition, String label) {
        if (!condition)
            throw new AssertionError(label);
        System.out.println("PASS " + label);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T> (action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
