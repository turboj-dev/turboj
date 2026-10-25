package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.KeymapManager;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaletteDispatchTest {
    @Test void immediateDialogHandoffRetainsOriginWhileNativeFocusEventsLag() throws Exception {
        var old = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        var fake = new DefaultKeyboardFocusManager() {
            Component focus;
            @Override public Component getFocusOwner() { return focus; }
        };
        var sourceRequests = new AtomicInteger();
        var intermediateRequests = new AtomicInteger();
        try {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(fake);
            SwingUtilities.invokeAndWait(() -> {
                var root = new JRootPane(); root.setSize(800,600);
                var source = new JTextField() {
                    @Override public boolean isShowing() { return true; }
                    @Override public boolean requestFocusInWindow() { sourceRequests.incrementAndGet(); return true; }
                };
                var intermediate = new JTextField() {
                    @Override public boolean isShowing() { return true; }
                    @Override public boolean requestFocusInWindow() { intermediateRequests.incrementAndGet(); return true; }
                };
                root.getContentPane().add(source,BorderLayout.CENTER);
                root.getContentPane().add(intermediate,BorderLayout.SOUTH);
                fake.focus=source;
                var palette = new EmbeddedDialog(root,"Palette",false); palette.setVisible(true);
                OverlayHost.closeThen(palette,() -> {
                    // A still-pending native focus event exposes the frame's automatic target.
                    fake.focus=intermediate;
                    var next = new EmbeddedDialog(root,"Theme selector",false); next.setVisible(true);
                    next.dispose();
                });
            });
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(2,sourceRequests.get(),"both surfaces restore the original source component");
                assertEquals(0,intermediateRequests.get(),"automatic intermediate focus must not replace the command origin");
            });
        } finally { KeyboardFocusManager.setCurrentKeyboardFocusManager(old); }
    }

    @Test void actionRunsAfterOverlayDisposalAndItsQueuedFocusRestoration() throws Exception {
        var invoked = new AtomicBoolean();
        var restoreTurn = new AtomicBoolean();
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("focus.target", "Focus target", "View", () -> {
                assertTrue(restoreTurn.get(), "palette action must follow the queued close/restoration turn");
                invoked.set(true);
            }));
            SwingUtilities.invokeAndWait(() -> {
                var root = new JRootPane(); root.setSize(800,600);
                var palette = new CommandPaletteDialog(root,registry,new KeymapManager(),CommandContext.of(),r -> {});
                palette.setVisible(true);
                palette.onClosed(() -> SwingUtilities.invokeLater(() -> restoreTurn.set(true)));
                palette.searchField().setText("focus.target");
                palette.searchField().postActionEvent();
                assertTrue(palette.isClosed());
                assertFalse(invoked.get(), "synchronous dispatch lets deferred overlay focus steal action focus");
            });
            SwingUtilities.invokeAndWait(() -> assertTrue(invoked.get()));
        }
    }
}
