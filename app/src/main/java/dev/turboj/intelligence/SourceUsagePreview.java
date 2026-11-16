package dev.turboj.intelligence;

import dev.turboj.ui.EditorComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Detached, read-only source snapshot using the same renderer as the main editor. */
final class SourceUsagePreview extends JPanel implements AutoCloseable {
    private final EditorComponent origin;
    private final EditorComponent code = new EditorComponent();
    private final JLabel location = new JLabel("No usage selected");
    private int start, end;
    private String source = "";
    private final PropertyChangeListener appearance = event -> {
        switch (event.getPropertyName()) {
            case "theme", "font", "tabWidth" -> syncAppearance();
            default -> { }
        }
    };

    SourceUsagePreview(EditorComponent origin, Runnable open) {
        super(new BorderLayout());
        this.origin = origin;
        code.setReadOnly(true);
        code.setFocusTraversalKeysEnabled(true);
        code.getAccessibleContext().setAccessibleName("Read-only source usage preview");
        code.setKeyHandler(event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ENTER && event.getModifiersEx() == 0) {
                open.run(); return true;
            }
            return false;
        });
        code.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { reveal(); }
        });
        add(location, BorderLayout.NORTH);
        add(code, BorderLayout.CENTER);
        setMinimumSize(new Dimension(160, 140));
        origin.addPropertyChangeListener(appearance);
        syncAppearance();
    }

    private void syncAppearance() {
        code.setTheme(origin.theme());
        code.setFontSize(origin.getFont().getSize());
        code.setTabWidth(origin.tabWidth());
        location.setFont(origin.getFont());
        location.setForeground(origin.theme().foreground());
        setBackground(origin.theme().background());
    }

    void showSource(String label, String text, int start, int end) {
        if (!source.equals(text)) { source = text; code.setText(text); }
        // setText intentionally resets readOnly in the editor; restore it on every snapshot.
        code.setReadOnly(true);
        this.start = start; this.end = end;
        location.setText(label + " — read-only");
        location.setToolTipText(label);
        reveal();
    }

    private void reveal() {
        int line = code.document().lineAt(start);
        // Re-evaluate horizontal visibility after a resize, including the first
        // layout after an initially zero-width component received its snapshot.
        code.restoreLocation(new EditorComponent.Location(start, start, Math.max(0, line - 3), 0));
        code.setSelection(start, end);
        var at = code.captureLocation();
        code.restoreLocation(new EditorComponent.Location(at.caret(), at.anchor(), Math.max(0, line - 3), at.leftColumn()));
    }

    EditorComponent editor() { return code; }

    @Override public void close() { origin.removePropertyChangeListener(appearance); }
}
