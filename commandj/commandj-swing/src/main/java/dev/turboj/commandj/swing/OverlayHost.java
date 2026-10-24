package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyVetoException;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** One internal window surface per RootPane, with stacking, focus restoration and resize bounds. */
public final class OverlayHost extends JDesktopPane {
    private static final String PROPERTY = OverlayHost.class.getName();
    private final JRootPane root;
    private final Deque<EmbeddedDialog> dialogs = new ArrayDeque<> ();
    private final Map<EmbeddedDialog, Component> previousFocus = new IdentityHashMap<> ();
    private Component backgroundFocus;
    private Color surface = new Color(0xAAAAAA), foreground = Color.BLACK, border = Color.BLACK;
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

            if (top == null || event.getID() != KeyEvent.KEY_PRESSED || event.getKeyCode()
                    != KeyEvent.VK_ESCAPE || event.getModifiersEx() != 0 || MenuSelectionManager
                    .defaultManager().getSelectedPath().length != 0)
                return false;
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (focus == null || SwingUtilities.getWindowAncestor(focus) != SwingUtilities
                    .getWindowAncestor(root))
                return false;
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
        }
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
        dialog.moveToFront();
        blockLowerDialogs();
        try {
            dialog.setSelected(true);
        } catch (PropertyVetoException ignored) {}
        SwingUtilities.invokeLater(this::focusTop);
        revalidate();
        repaint();
    }

    void closed(EmbeddedDialog dialog) {
        dialogs.remove(dialog);
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
        }
    }

    private void focusTop() {
        var top = dialogs.peekLast();

        if (top == null)
            return;
        top.moveToFront();
        try {
            top.setSelected(true);
        } catch (PropertyVetoException ignored) {}
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

        if (focus != null && SwingUtilities.isDescendingFrom(focus, top))
            return;
        FocusTraversalPolicy policy = top.getFocusTraversalPolicy();

        if (policy == null)
            policy = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .getDefaultFocusTraversalPolicy();
        Component target = policy.getDefaultComponent(top);

        if (target != null)
            target.requestFocusInWindow();
        else
            top.requestFocusInWindow();
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

    public void setTheme(Color surface, Color foreground, Color border) {
        this.surface = surface;
        this.foreground = foreground;
        this.border = border;
        dialogs.forEach(dialog -> {
            SwingUtilities.updateComponentTreeUI(dialog.getContentPane());
            style(dialog);
        });
        repaint();
    }

    private void style(EmbeddedDialog dialog) {
        dialog.setBorder(BorderFactory.createLineBorder(border, 1));
        styleTree(dialog.getContentPane());
        if (dialog.getUI() instanceof javax.swing.plaf.basic.BasicInternalFrameUI ui) {
            if (!(ui.getNorthPane() instanceof Header))
                dialog.setHeader(new Header(dialog));
            styleTree(ui.getNorthPane());
        }
    }

    private final class Header extends JPanel {
        Header(EmbeddedDialog dialog) {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 5));
            var title = new JLabel(dialog.getTitle());
            dialog.addPropertyChangeListener("title", event -> title.setText(dialog.getTitle()));
            add(title, BorderLayout.CENTER);
            var close = new JButton("×");
            close.setFocusable(false);
            close.setMargin(new Insets(0, 7, 0, 7));
            close.getAccessibleContext().setAccessibleName("Close " + dialog.getTitle());
            close.addActionListener(event -> dialog.doDefaultCloseAction());
            add(close, BorderLayout.EAST);
            MouseAdapter drag = new MouseAdapter() {
                Point start;

                public void mousePressed(MouseEvent event) {
                    start = SwingUtilities.convertPoint(Header.this, event.getPoint(),
                            OverlayHost.this);
                }

                public void mouseDragged(MouseEvent event) {
                    if (start == null)
                        return;
                    Point point = SwingUtilities.convertPoint(Header.this, event.getPoint(),
                            OverlayHost.this);
                    dialog.setLocation(Math.clamp(dialog.getX() + point.x - start.x, 0, Math.max(0,
                            getWidthInHost() - dialog.getWidth())), Math.clamp(dialog.getY()
                            + point.y - start.y, 0, Math.max(0, OverlayHost.this.getHeight()
                            - dialog.getHeight())));
                    start = point;
                }
            };
            addMouseListener(drag);
            addMouseMotionListener(drag);
        }

        private int getWidthInHost() {
            return OverlayHost.this.getWidth();
        }
    }

    private void styleTree(Component component) {
        component.setForeground(foreground);
        component.setBackground(surface);
        Color selected = border;
        Color selectedText = contrastText(selected);

        if (component instanceof javax.swing.text.JTextComponent text) {
            text.setCaretColor(foreground);
            text.setSelectionColor(selected);
            text.setSelectedTextColor(selectedText);
            text.setDisabledTextColor(foreground);
        }
        if (component instanceof JList<?> list) {
            list.setSelectionBackground(selected);
            list.setSelectionForeground(selectedText);
        }
        if (component instanceof JTable table) {
            table.setSelectionBackground(selected);
            table.setSelectionForeground(selectedText);
            table.setGridColor(border);
        }
        if (component instanceof JComponent swing)
            swing.putClientProperty("html.disable", Boolean.TRUE);
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                styleTree(child);
    }

    private static Color contrastText(Color value) {
        double luminance = linear(value.getRed()) * .2126 + linear(value.getGreen()) * .7152
                + linear(value.getBlue()) * .0722;
        return (luminance + .05) / .05 >= 1.05 / (luminance + .05) ? Color.BLACK : Color.WHITE;
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(new Color(0, 0, 0, 80));
        graphics.fillRect(0, 0, getWidth(), getHeight());
    }
}
