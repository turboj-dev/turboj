package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiStyle;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.ChangeListener;

/** Menus, combo choices and tooltips share the main frame's layer; never a heavyweight Window. */
public final class InFramePopupFactory extends PopupFactory {
    private static final String REGISTERED = InFramePopupFactory.class.getName();
    private final PopupFactory fallback;

    private InFramePopupFactory(PopupFactory fallback) { this.fallback = fallback; }

    public static void install(JRootPane root) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Install popup host on EDT");
        root.putClientProperty(REGISTERED, Boolean.TRUE);
        if (!(PopupFactory.getSharedInstance() instanceof InFramePopupFactory))
            PopupFactory.setSharedInstance(new InFramePopupFactory(PopupFactory.getSharedInstance()));
    }

    @Override public Popup getPopup(Component owner, Component contents, int x, int y) {
        JRootPane root = registeredRoot(owner);
        if (root == null) return fallback.getPopup(owner, contents, x, y);
        return new LayerPopup(root.getLayeredPane(), owner, contents, x, y);
    }

    private static JRootPane registeredRoot(Component owner) {
        if (owner == null) owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (owner instanceof RootPaneContainer container && Boolean.TRUE.equals(container.getRootPane().getClientProperty(REGISTERED)))
            return container.getRootPane();
        for (Component c = owner; c != null; c = c.getParent())
            if (c instanceof JRootPane root && Boolean.TRUE.equals(root.getClientProperty(REGISTERED))) return root;
        return null;
    }

    private static final class LayerPopup extends Popup {
        private final JLayeredPane layer;
        private final Component contents;
        private final JPanel surface = new JPanel(new BorderLayout());
        private final JScrollPane scroll;
        private final Point position;
        private boolean shown;
        private final ComponentListener resize = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { constrain(); }
        };
        private final ChangeListener selection = event -> revealSelection();

        LayerPopup(JLayeredPane layer, Component owner, Component contents, int x, int y) {
            this.layer = layer; this.contents = contents;
            surface.setName("turboj-in-frame-popup"); surface.setOpaque(false);
            surface.setFocusable(false);
            if (contents instanceof JPopupMenu) {
                scroll = new JScrollPane(contents, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
                scroll.setBorder(BorderFactory.createEmptyBorder());
                scroll.setFocusable(false); scroll.getViewport().setBackground(contents.getBackground());
                scroll.getVerticalScrollBar().setUnitIncrement(24);
                var palette = contents instanceof javax.swing.plaf.basic.BasicComboPopup
                        ? AsciiStyle.palette(owner)
                        : new AsciiPalette(contents.getBackground(), contents.getForeground(),
                                color("MenuItem.selectionBackground", contents.getForeground()),
                                color("MenuItem.selectionForeground", contents.getBackground()),
                                color("MenuItem.disabledForeground", contents.getForeground()),
                                contents.getForeground(), AsciiChrome.font());
                AsciiStyle.apply(scroll, palette);
                surface.add(scroll);
            } else { scroll = null; surface.add(contents); }
            position = new Point(x, y);
            SwingUtilities.convertPointFromScreen(position, layer);
            constrain();
        }

        private static Color color(String key, Color fallback) {
            Color value = UIManager.getColor(key);
            return value == null ? fallback : value;
        }

        @Override public void show() {
            if (shown) return;
            shown = true; layer.add(surface, JLayeredPane.POPUP_LAYER, 0);
            layer.addComponentListener(resize);
            if (scroll != null) MenuSelectionManager.defaultManager().addChangeListener(selection);
            surface.setVisible(true); surface.revalidate(); layer.repaint(surface.getBounds());
        }

        @Override public void hide() {
            if (!shown) return;
            shown = false; Rectangle bounds = surface.getBounds();
            layer.removeComponentListener(resize);
            MenuSelectionManager.defaultManager().removeChangeListener(selection);
            layer.remove(surface); surface.removeAll();
            if (scroll != null) scroll.setViewportView(null);
            layer.revalidate(); layer.repaint(bounds);
        }

        private void constrain() {
            Dimension preferred = contents.getPreferredSize();
            int availableWidth = Math.max(1, layer.getWidth()), availableHeight = Math.max(1, layer.getHeight());
            int width = Math.min(availableWidth, preferred.width + (scroll != null && preferred.height > availableHeight ? scroll.getVerticalScrollBar().getPreferredSize().width : 0));
            int height = Math.min(availableHeight, preferred.height + (scroll != null && preferred.width > availableWidth ? scroll.getHorizontalScrollBar().getPreferredSize().height : 0));
            surface.setBounds(Math.clamp(position.x, 0, Math.max(0, availableWidth - width)),
                    Math.clamp(position.y, 0, Math.max(0, availableHeight - height)), width, height);
            surface.revalidate();
        }

        private void revealSelection() {
            if (scroll == null) return;
            for (MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
                Component target = element.getComponent();
                if (target != contents && SwingUtilities.isDescendingFrom(target, contents)) {
                    Rectangle bounds = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), contents);
                    ((JComponent) contents).scrollRectToVisible(bounds);
                }
            }
        }
    }
}
