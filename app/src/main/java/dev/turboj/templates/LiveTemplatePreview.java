package dev.turboj.templates;

import dev.turboj.ui.EditorComponent;
import dev.turboj.commandj.swing.AsciiPalette;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.plaf.LayerUI;

/** Detached native editor, with non-editable field outlines and a permanent final-caret marker. */
final class LiveTemplatePreview extends JPanel implements AutoCloseable {
    final EditorComponent code = new EditorComponent();
    private final EditorComponent origin;
    private final JLabel details = new JLabel(" ");
    private LiveTemplateExpansion expansion;
    private Color ink;
    private final PropertyChangeListener appearance = e -> {
        if (Set.of("theme", "font", "tabWidth").contains(e.getPropertyName())) syncAppearance();
    };
    // A documented sample context; no project parsing or semantic inference is claimed by this view.
    static final JavaTemplateContext EXAMPLE = new JavaTemplateContext(Set.of("JAVA_STATEMENT"), "Example", "example",
            Map.of("values", "int[]"), List.of("values"), "Example.java");

    LiveTemplatePreview(EditorComponent origin) {
        super(new BorderLayout(0, 4)); this.origin = origin;
        code.setName("live-template-preview"); code.setReadOnly(true); code.setFocusTraversalKeysEnabled(true);
        code.getAccessibleContext().setAccessibleName("Read-only expanded template example");
        var layer = new JLayer<>(code, new LayerUI<EditorComponent>() {
            @Override public void paint(Graphics graphics, JComponent component) {
                super.paint(graphics, component);
                if (expansion == null) return;
                var g = (Graphics2D) graphics.create();
                int left = code.offsetBounds(0).x + code.captureLocation().leftColumn() * Math.max(1, code.getFontMetrics(code.getFont()).charWidth('M'));
                g.clipRect(left, 0, Math.max(0, code.getWidth() - left), code.getHeight());
                g.setColor(ink);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2, 2}, 0));
                for (var group : expansion.fields()) for (var range : group) {
                    int first = code.document().lineAt(range.start()), last = code.document().lineAt(range.end());
                    for (int line = first; line <= last; line++) {
                        Rectangle a = code.offsetBounds(Math.max(range.start(), code.document().lineStart(line)));
                        Rectangle b = code.offsetBounds(Math.min(range.end(), code.document().lineEnd(line)));
                        if (a.y + a.height < 0 || a.y >= code.getHeight()) continue;
                        g.drawRect(a.x, a.y + 1, Math.max(3, b.x - a.x), a.height - 3);
                    }
                }
                Rectangle end = code.offsetBounds(expansion.exit());
                g.setStroke(new BasicStroke(2f));
                g.drawLine(end.x, end.y + 2, end.x, end.y + end.height - 2);
                g.drawLine(end.x - 3, end.y + 2, end.x + 3, end.y + 2);
                g.dispose();
            }
        });
        add(layer); add(details, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(420, 180)); setMinimumSize(new Dimension(140, 100));
        origin.addPropertyChangeListener(appearance); syncAppearance();
    }
    private void syncAppearance() {
        code.setTheme(origin.theme()); code.setFontSize(origin.getFont().getSize()); code.setTabWidth(origin.tabWidth());
        ink = AsciiPalette.readable(origin.theme().foreground(), origin.theme().background(), 4.5);
        details.setFont(origin.getFont()); details.setForeground(origin.theme().foreground()); setBackground(origin.theme().background());
        repaint();
    }
    void showTemplate(LiveTemplateCatalog.Template template) {
        expansion = LiveTemplateExpansion.expand(template, EXAMPLE, "selected();", "", origin.tabWidth(), "\n");
        code.setText(expansion.text()); code.setReadOnly(true); code.setSelection(0, 0);
        int line = code.document().lineAt(expansion.exit());
        String label = "Fields: " + (expansion.names().isEmpty() ? "none" : String.join(", ", expansion.names()))
                + " · END " + (line + 1) + ":" + (expansion.exit() - code.document().lineStart(line) + 1);
        details.setText(label); details.setToolTipText(label);
        code.getAccessibleContext().setAccessibleDescription(label + ". Example context: Example.example(int[] values). "
                + "Dotted boxes mark linked fields; solid caret marks END. " + String.join("; ", expansion.unresolved()));
        repaint();
    }
    void clear(String reason) {
        expansion = null; code.setText(""); code.setReadOnly(true); details.setText(reason);
        code.getAccessibleContext().setAccessibleDescription(reason); repaint();
    }
    @Override public void close() { origin.removePropertyChangeListener(appearance); }
}
