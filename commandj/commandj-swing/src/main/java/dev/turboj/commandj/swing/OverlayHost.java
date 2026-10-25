package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** One transparent editor overlay per root, with focus restoration and bounded surfaces. */
public final class OverlayHost extends JLayeredPane {
    private static final String PROPERTY = OverlayHost.class.getName();
    private final JRootPane root;
    private final Deque<EmbeddedDialog> dialogs = new ArrayDeque<> ();
    private final Map<EmbeddedDialog, Component> previousFocus = new IdentityHashMap<> ();
    private Component backgroundFocus;
    private Component actionFocusOrigin;
    private AsciiPalette palette = AsciiPalette.defaults();
    private final PropertyChangeListener focusGuard;
    private final KeyEventDispatcher keys;

    private OverlayHost(JRootPane root) {
        this.root = root;
        setOpaque(false);
        setVisible(false);
        root.getLayeredPane().add(this, JLayeredPane.MODAL_LAYER);
        root.getLayeredPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                resize();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                focusTop();
            }
        });
        setFocusTraversalPolicyProvider(true);
        focusGuard = event -> {
            var top = dialogs.peekLast();

            if (top != null && event.getNewValue() instanceof Component focus && SwingUtilities
                    .getWindowAncestor(focus) == SwingUtilities.getWindowAncestor(root)
                    && !SwingUtilities.isDescendingFrom(focus, top) && !belongsToPopup(focus, top))
                SwingUtilities.invokeLater(this::focusTop);
        };
        keys = event -> {
            var top = dialogs.peekLast();

            if (top == null || event.getID() != KeyEvent.KEY_PRESSED || MenuSelectionManager
                    .defaultManager().getSelectedPath().length != 0)
                return false;
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (focus == null || SwingUtilities.getWindowAncestor(focus) != SwingUtilities
                    .getWindowAncestor(root))
                return false;
            if (event.getKeyCode() == KeyEvent.VK_TAB && !event.isAltDown()
                    && !event.isMetaDown() && !event.isControlDown()) {
                if (event.isShiftDown()) focus.transferFocusBackward(); else focus.transferFocus();
                event.consume();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_ENTER && event.getModifiersEx() == 0
                    && focus instanceof AbstractButton button && button.isEnabled()) {
                button.doClick(0); event.consume(); return true;
            }
            if (event.getKeyCode() != KeyEvent.VK_ESCAPE || event.getModifiersEx() != 0) return false;
            top.doDefaultCloseAction();
            event.consume();
            return true;
        };
        root.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !root
                    .isDisplayable())
                for (var dialog : List.copyOf(dialogs))
                    dialog.dispose();
        });
        resize();
    }

    private static boolean belongsToPopup(Component component, EmbeddedDialog dialog) {
        for (Component at = component; at != null; at = at.getParent())
            if (at instanceof JPopupMenu popup && popup.getInvoker() != null)
                return SwingUtilities.isDescendingFrom(popup.getInvoker(), dialog);
        return false;
    }

    private static JRootPane applicationRoot(Component owner) {
        if (owner == null)
            return null;
        for (Component at = owner; at != null; at = at.getParent())
            if (at instanceof EmbeddedDialog dialog)
                return applicationRoot(dialog.getOwner());
        return owner instanceof RootPaneContainer container ? container
                .getRootPane() : SwingUtilities.getRootPane(owner);
    }

    public static OverlayHost forOwner(Component owner) {
        JRootPane root = applicationRoot(owner);

        if (root == null)
            throw new IllegalArgumentException("The dialog owner must belong to an application frame");
        var host = (OverlayHost) root.getClientProperty(PROPERTY);

        if (host == null) {
            host = new OverlayHost(root);
            root.putClientProperty(PROPERTY, host);
        }
        return host;
    }

    public static OverlayHost existing(Component owner) {
        if (owner == null)
            return null;
        JRootPane root = applicationRoot(owner);
        return root == null ? null : (OverlayHost) root.getClientProperty(PROPERTY);
    }

    public static boolean active(Component owner) {
        var host = existing(owner);
        return host != null && !host.dialogs.isEmpty();
    }

    public static List<EmbeddedDialog> dialogs(Component owner) {
        var host = existing(owner);
        return host == null ? List.of() : List.copyOf(host.dialogs);
    }

    /** Close a surface before running its action. Preserve the logical focus origin when the
     * action immediately opens another surface: native focus events may lag the EDT queue. */
    public static void closeThen(EmbeddedDialog dialog, Runnable action) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Close surfaces on EDT");
        Objects.requireNonNull(action);
        OverlayHost host = existing(dialog);
        Component captured = host == null ? null : host.previousFocus.get(dialog);
        Component origin = captured != null && captured.isShowing() ? captured : host == null ? null : host.backgroundFocus;
        dialog.dispose();
        SwingUtilities.invokeLater(() -> {
            if (host == null) { action.run(); return; }
            Component previous = host.actionFocusOrigin;
            host.actionFocusOrigin = origin;
            try { action.run(); }
            finally { host.actionFocusOrigin = previous; }
        });
    }

    void show(EmbeddedDialog dialog) {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        root.putClientProperty("commandj.overlay.active", true);
        if (dialogs.isEmpty()) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addPropertyChangeListener("permanentFocusOwner", focusGuard);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
        }
        if (!dialogs.contains(dialog)) {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (dialogs.isEmpty() && actionFocusOrigin != null && actionFocusOrigin.isShowing()
                    && SwingUtilities.isDescendingFrom(actionFocusOrigin, root)) focus = actionFocusOrigin;
            if (dialogs.isEmpty() && focus != null && focus.isShowing()
                    && !SwingUtilities.isDescendingFrom(focus, this)) backgroundFocus = focus;
            previousFocus.put(dialog, focus);
            dialogs.addLast(dialog);
            add(dialog, Integer.valueOf(dialogs.size() * 10));
        }
        resize();
        style(dialog);
        Dimension size = dialog.getSize();

        if (size.width <= 0 || size.height <= 0) {
            dialog.pack();
            size = dialog.getSize();
        } else {
            // Explicit dialog dimensions use the default 14-point text grid. Packed content
            // and reused surfaces record the font that already sized their dimensions.
            Object measured = dialog.getClientProperty("commandj.overlay.layoutFontSize");
            double base = measured instanceof Number number ? number.doubleValue() : 14.0;
            double scale = palette.font().getSize2D() / base;
            size = new Dimension((int) Math.round(size.width * scale), (int) Math.round(size.height * scale));
        }
        dialog.putClientProperty("commandj.overlay.layoutFontSize", palette.font().getSize());
        dialog.setSize(Math.min(Math.max(220, size.width), Math.max(220, getWidth() - 20)), Math
                .min(Math.max(100, size.height), Math.max(100, getHeight() - 20)));
        Point position = dialog.requestedScreenLocation();

        if (position != null)
            SwingUtilities.convertPointFromScreen(position, this);
        else
            position = new Point((getWidth() - dialog.getWidth()) / 2, (getHeight() - dialog
                    .getHeight()) / 2);
        dialog.setLocation(Math.clamp(position.x, 0, Math.max(0, getWidth() - dialog.getWidth())),
                Math.clamp(position.y, 0, Math.max(0, getHeight() - dialog.getHeight())));
        setVisible(true);
        dialog.showInHost();
        if (!dialogs.contains(dialog)) return;
        moveToFront(dialog);
        blockLowerDialogs();
        SwingUtilities.invokeLater(this::focusTop);
        revalidate();
        repaint();
    }

    void closed(EmbeddedDialog dialog) {
        if (!dialogs.remove(dialog)) return;
        remove(dialog);
        Component focus = previousFocus.remove(dialog);
        setVisible(!dialogs.isEmpty());
        revalidate();
        repaint();
        root.putClientProperty("commandj.overlay.active", !dialogs.isEmpty());
        if (dialogs.isEmpty()) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("permanentFocusOwner", focusGuard);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        }
        blockLowerDialogs();
        SwingUtilities.invokeLater(() -> {
            if (!dialogs.isEmpty())
                focusTop();
            else {
                Component target = focus != null && focus.isShowing() ? focus : backgroundFocus;
                if (target != null && target.isShowing()) target.requestFocusInWindow();
            }
        });
    }

    private void blockLowerDialogs() {
        for (var dialog : dialogs) {
            Component glass = dialog.getGlassPane();

            if (glass.getMouseListeners().length == 0)
                glass.addMouseListener(new MouseAdapter() {
                    public void mousePressed(MouseEvent event) {
                        focusTop();
                    }
                });
            glass.setVisible(dialog != dialogs.peekLast());
            dialog.closeButton().setEnabled(dialog == dialogs.peekLast());
        }
    }

    void activate(EmbeddedDialog dialog) {
        if (!dialogs.contains(dialog)) return;
        EmbeddedDialog current = dialogs.peekLast();
        if (current != dialog && current != null && current.isModal()) return;
        int highest = dialogs.stream().mapToInt(surface -> getLayer((Component) surface)).max().orElse(0);
        dialogs.remove(dialog); dialogs.addLast(dialog); setLayer(dialog, highest + 1);
        blockLowerDialogs(); focusTop();
    }

    private void focusTop() {
        var top = dialogs.peekLast();

        if (top == null)
            return;
        moveToFront(top);
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

        if (focus != null && SwingUtilities.isDescendingFrom(focus, top))
            return;
        FocusTraversalPolicy policy = top.getFocusTraversalPolicy();

        if (policy == null)
            policy = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalPolicy();
        Component target = firstTextInput(top.getContentPane());
        if (target == null) target = policy.getDefaultComponent(top);

        if (target != null)
            target.requestFocusInWindow();
        else
            top.requestFocusInWindow();
    }

    private static Component firstTextInput(Container container) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible() || !child.isEnabled()) continue;
            // The shortcut recorder deliberately uses a read-only text field as its key target.
            if (child instanceof JTextField && child.isFocusable()) return child;
            if (child instanceof Container nested) {
                Component found = firstTextInput(nested); if (found != null) return found;
            }
        }
        return null;
    }

    private void resize() {
        setBounds(0, 0, root.getLayeredPane().getWidth(), root.getLayeredPane().getHeight());
        for (var dialog : dialogs) {
            dialog.setSize(Math.min(dialog.getWidth(), Math.max(220, getWidth() - 20)), Math
                    .min(dialog.getHeight(), Math.max(100, getHeight() - 20)));
            dialog.setLocation(Math.clamp(dialog.getX(), 0, Math.max(0, getWidth() - dialog
                    .getWidth())), Math.clamp(dialog.getY(), 0, Math.max(0, getHeight() - dialog
                    .getHeight())));
        }
    }

    public void setTheme(AsciiPalette palette) {
        double scale = palette.font().getSize2D() / this.palette.font().getSize2D();
        this.palette = java.util.Objects.requireNonNull(palette);
        for (var dialog : dialogs) {
            if (scale != 1) {
                int width = (int) Math.round(dialog.getWidth() * scale);
                int height = (int) Math.round(dialog.getHeight() * scale);
                dialog.setLocation(dialog.getX() + (dialog.getWidth() - width) / 2,
                        dialog.getY() + (dialog.getHeight() - height) / 2);
                dialog.setSize(width, height);
            }
            dialog.putClientProperty("commandj.overlay.layoutFontSize", palette.font().getSize());
            style(dialog);
        }
        resize();
        revalidate();
        repaint();
    }

    /** Compatibility for embedders that have not yet supplied all editor tokens. */
    public void setTheme(Color surface, Color foreground, Color border) {
        Color selectedText = AsciiPalette.contrast(Color.BLACK, border) >= AsciiPalette.contrast(Color.WHITE, border) ? Color.BLACK : Color.WHITE;
        setTheme(new AsciiPalette(surface, foreground, border, selectedText, foreground, border,
                new Font(Font.MONOSPACED, Font.PLAIN, 14)));
    }

    private void style(EmbeddedDialog dialog) { dialog.restyle(palette); }
}
