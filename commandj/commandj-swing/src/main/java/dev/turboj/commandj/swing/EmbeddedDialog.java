package dev.turboj.commandj.swing;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.*;
import javax.swing.*;

/** A focus-scoped editor surface inside the application's frame; never a desktop window. */
public class EmbeddedDialog extends JPanel implements RootPaneContainer, WindowConstants {
    private final Component owner;
    private final boolean modal;
    private JRootPane root;
    private JButton close;
    private SecondaryLoop loop;
    private Point screenLocation;
    private boolean disposed, opened;
    private int closeOperation = DISPOSE_ON_CLOSE;
    private String title;
    private final List<Runnable> openedListeners = new ArrayList<>(), closingListeners = new ArrayList<>(), closedListeners = new ArrayList<>();

    public EmbeddedDialog(Component owner, String title, boolean modal) {
        super(null);
        this.owner = owner; this.title = title; this.modal = modal;
        root = new JRootPane(); root.getContentPane().setLayout(new BorderLayout());
        super.addImpl(root, null, -1);
        close = new JButton("x");
        close.getAccessibleContext().setAccessibleName("Close " + title);
        close.addActionListener(event -> doDefaultCloseAction());
        super.addImpl(close, null, -1);
        setFocusCycleRoot(true); setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
        getAccessibleContext().setAccessibleName(title);
        restyle(AsciiStyle.palette(this));
        super.setVisible(false);
    }

    public Component getOwner() { return owner; }
    public boolean isModal() { return modal; }
    public boolean isClosed() { return disposed; }
    public void toFront() { OverlayHost host = OverlayHost.existing(owner); if (host != null) host.activate(this); }
    public String getTitle() { return title; }
    public void setTitle(String value) {
        String previous = title; title = value;
        getAccessibleContext().setAccessibleName(value); close.getAccessibleContext().setAccessibleName("Close " + value);
        firePropertyChange("title", previous, value); restyle(AsciiStyle.palette(this));
    }
    public JButton closeButton() { return close; }
    public void onOpened(Runnable listener) { openedListeners.add(java.util.Objects.requireNonNull(listener)); }
    public void onClosing(Runnable listener) { closingListeners.add(java.util.Objects.requireNonNull(listener)); }
    public void onClosed(Runnable listener) { closedListeners.add(java.util.Objects.requireNonNull(listener)); }
    public void setDefaultCloseOperation(int operation) {
        if (operation != DO_NOTHING_ON_CLOSE && operation != HIDE_ON_CLOSE && operation != DISPOSE_ON_CLOSE)
            throw new IllegalArgumentException("Unsupported embedded-surface close operation");
        closeOperation = operation;
    }
    public int getDefaultCloseOperation() { return closeOperation; }
    public void doDefaultCloseAction() {
        for (Runnable listener : List.copyOf(closingListeners)) listener.run();
        if (disposed) return;
        if (closeOperation == DISPOSE_ON_CLOSE) dispose();
        else if (closeOperation == HIDE_ON_CLOSE) setVisible(false);
    }
    /** Surfaces resize with their owning frame; they have no independent window resize handles. */
    public void setResizable(boolean ignored) { }
    public void setLocationRelativeTo(Component ignored) { screenLocation = null; }
    public void setLocationOnScreen(Point point) { screenLocation = new Point(point); }
    Point requestedScreenLocation() { return screenLocation == null ? null : new Point(screenLocation); }

    @Override public JRootPane getRootPane() { return root; }
    @Override public Container getContentPane() { return root.getContentPane(); }
    @Override public void setContentPane(Container pane) { root.setContentPane(pane); }
    @Override public JLayeredPane getLayeredPane() { return root.getLayeredPane(); }
    @Override public void setLayeredPane(JLayeredPane pane) { root.setLayeredPane(pane); }
    @Override public Component getGlassPane() { return root.getGlassPane(); }
    @Override public void setGlassPane(Component pane) { root.setGlassPane(pane); }
    @Override public void setLayout(LayoutManager layout) {
        if (root == null) super.setLayout(layout); else root.getContentPane().setLayout(layout);
    }
    @Override protected void addImpl(Component component, Object constraints, int index) {
        if (root == null) super.addImpl(component, constraints, index);
        else root.getContentPane().add(component, constraints, index);
    }
    @Override public void doLayout() {
        Insets insets = getInsets();
        root.setBounds(insets.left, insets.top, Math.max(0, getWidth() - insets.left - insets.right), Math.max(0, getHeight() - insets.top - insets.bottom));
        Dimension button = close.getPreferredSize();
        close.setBounds(Math.max(0, getWidth() - button.width - 12), 0, button.width, insets.top);
        root.doLayout();
    }
    @Override public Dimension getPreferredSize() {
        if (root == null || isPreferredSizeSet()) return super.getPreferredSize();
        Dimension body = root.getPreferredSize(); Insets insets = getInsets();
        return new Dimension(Math.max(220, body.width + insets.left + insets.right), Math.max(100, body.height + insets.top + insets.bottom));
    }
    public void pack() { setSize(getPreferredSize()); doLayout(); }
    void restyle(AsciiPalette palette) {
        AsciiStyle.apply(this, palette);
        root.setBorder(BorderFactory.createEmptyBorder());
        setBorder(new AsciiBorder(title, palette, AsciiBorder.Line.DOUBLE, close.getPreferredSize().width + 16));
        revalidate(); repaint();
    }

    @Override public void setVisible(boolean visible) {
        if (root == null) { super.setVisible(visible); return; }
        if (!visible) {
            super.setVisible(false);
            OverlayHost host = OverlayHost.existing(owner); if (host != null) host.closed(this);
            if (loop != null) loop.exit();
            return;
        }
        if (disposed) return;
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Show embedded surfaces on EDT");
        OverlayHost.forOwner(owner).show(this);
        if (modal && isVisible()) {
            loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            loop.enter(); loop = null;
        }
    }
    void showInHost() {
        super.setVisible(true);
        if (!opened) { opened = true; for (Runnable listener : List.copyOf(openedListeners)) listener.run(); }
    }
    public void dispose() {
        if (disposed) return;
        disposed = true; super.setVisible(false);
        OverlayHost host = OverlayHost.existing(owner); if (host != null) host.closed(this);
        if (loop != null) loop.exit();
        for (Runnable listener : List.copyOf(closedListeners)) listener.run();
    }
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleSurface();
        return accessibleContext;
    }
    protected class AccessibleSurface extends AccessibleJPanel {
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.DIALOG; }
    }
}
