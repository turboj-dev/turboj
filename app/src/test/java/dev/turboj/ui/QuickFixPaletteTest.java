package dev.turboj.ui;

import dev.turboj.ui.themes.Theme;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class QuickFixPaletteTest {
    @Test void swingForegroundTracksThePaintedEditorPaletteAcrossThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EditorComponent editor = new EditorComponent();
            for (String id : Theme.bundledResources().keySet()) {
                Theme theme = Theme.bundled(id);
                editor.setTheme(theme);
                assertEquals(theme.foreground(),editor.getForeground(),id + ": popup consumers must see the painted foreground");
                assertEquals(theme.background(),editor.getBackground(),id);
            }
        });
    }
}
