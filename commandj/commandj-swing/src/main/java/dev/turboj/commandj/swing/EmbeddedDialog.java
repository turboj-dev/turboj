package dev.turboj.commandj.swing;

import java.awt.*;
import javax.swing.*;

/** A lightweight dialog contained by its application's frame; never creates a native window. */
public class EmbeddedDialog extends JInternalFrame {
    private final Component owner;
    private final boolean modal;
    private SecondaryLoop loop;
    private Point screenLocation;
    private boolean disposed;

    public EmbeddedDialog(Component owner, String title, boolean modal) {
        super(title, true, true, false, false);
        this.owner = owner;
        this.modal = modal;
        // Platform internal-frame delegates may draw the title in a native border. Our title
        // is a normal in-frame component and must retain its own layout on every platform.
        setUI(new javax.swing.plaf.basic.BasicInternalFrameUI(this));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setFocusCycleRoot(true);
        getAccessibleContext().setAccessibleName(title);
    }

    public Component getOwner() {
        return owner;
    }

    public boolean isModal() {
        return modal;
    }

    public void setLocationRelativeTo(Component ignored) {
        screenLocation = null;
    }

    public void setLocationOnScreen(Point point) {
        screenLocation = new Point(point);
    }

    Point requestedScreenLocation() {
        return screenLocation == null ? null : new Point(screenLocation);
    }

    @Override
    public void setVisible(boolean visible) {
        if (owner == null) {
            super.setVisible(visible);
            return;
        }
        if (!visible) {
            super.setVisible(false);
            if (!disposed) {
                OverlayHost host = OverlayHost.existing(owner);
                if (host != null) host.closed(this);
                if (loop != null) loop.exit();
            }
            return;
        }
        if (disposed)
            return;
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Show internal dialogs on EDT");
        OverlayHost.forOwner(owner).show(this);
        if (modal && isVisible()) {
            loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            // A secondary event loop keeps painting, input and async completion running.
            loop.enter();
            loop = null;
        }
    }

    void showInHost() {
        super.setVisible(true);
    }

    void setHeader(JComponent header) {
        boolean checking = isRootPaneCheckingEnabled();
        setRootPaneCheckingEnabled(false);
        try {
            ((javax.swing.plaf.basic.BasicInternalFrameUI) getUI()).setNorthPane(header);
        } finally {
            setRootPaneCheckingEnabled(checking);
        }
    }

    @Override
    public void dispose() {
        if (disposed)
            return;
        disposed = true;
        OverlayHost host = OverlayHost.existing(owner);
        super.dispose();
        if (host != null)
            host.closed(this);
        if (loop != null)
            loop.exit();
    }
}
