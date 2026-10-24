package dev.turboj.intelligence;

import dev.turboj.commandj.swing.OverlayHost;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** A transient editor surface clipped to its owning frame; never creates a native window. */
final class InlinePopup extends JPanel {
    private final JFrame owner;
    private final JRootPane root = new JRootPane();
    private final ComponentAdapter boundsListener;

    InlinePopup(JFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        root.getContentPane().setLayout(new BorderLayout());
        add(root);
        setVisible(false);
        owner.getLayeredPane().add(this, JLayeredPane.POPUP_LAYER);
        boundsListener = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { setVisible(false); }
        };
        owner.getLayeredPane().addComponentListener(boundsListener);
    }

    @Override public JRootPane getRootPane() { return root; }
    Container getContentPane() { return root.getContentPane(); }
    void pack() { setSize(getPreferredSize()); }
    void setFocusableWindowState(boolean enabled) { setFocusable(enabled); }
    boolean isFocused() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus != null && SwingUtilities.isDescendingFrom(focus, this);
    }
    boolean containsScreenPoint(Point point) {
        Point local = new Point(point);
        SwingUtilities.convertPointFromScreen(local, this);
        return contains(local);
    }
    void showBelow(Component anchor, Rectangle cell) {
        if (OverlayHost.active(owner)) return;
        JLayeredPane layer = owner.getLayeredPane();
        Rectangle allowed = SwingUtilities.convertRectangle(owner.getContentPane(),
                new Rectangle(0, 0, owner.getContentPane().getWidth(), owner.getContentPane().getHeight()), layer);
        Point origin = SwingUtilities.convertPoint(anchor, cell.x, cell.y + cell.height + 3, layer);
        setSize(Math.min(getWidth(), allowed.width), Math.min(getHeight(), allowed.height));
        int x = Math.clamp(origin.x, allowed.x, Math.max(allowed.x, allowed.x + allowed.width - getWidth()));
        int y = origin.y;
        if (y + getHeight() > allowed.y + allowed.height) y = origin.y - cell.height - getHeight() - 6;
        setLocation(x, Math.clamp(y, allowed.y, Math.max(allowed.y, allowed.y + allowed.height - getHeight())));
        setVisible(true);
        layer.moveToFront(this);
        revalidate(); repaint();
    }
    void dispose() {
        setVisible(false);
        owner.getLayeredPane().removeComponentListener(boundsListener);
        owner.getLayeredPane().remove(this);
    }
}
