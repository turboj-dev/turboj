package dev.turboj.app;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.CommandKeyDispatcher;
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
                check(area.getText().equals("xa"),
                        "printable key that cancels a pending chord is retained");
                maps.replaceBindings("test.action", List.of(KeyBinding.of("Q")));
                send(area, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 'q');
                send(area, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'q');
                send(area, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q, 'q');
                check(area.getText().equals("xa"),
                        "typed event belonging to the handled command remains suppressed");
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
