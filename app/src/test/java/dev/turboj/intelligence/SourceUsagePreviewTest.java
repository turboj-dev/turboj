package dev.turboj.intelligence;

import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceUsagePreviewTest {
    @Test void fullSnapshotSelectsExactUsageWithoutEditingOrChangingOrigin() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var origin = new EditorComponent(); origin.setText("class Original {}"); origin.setSelection(6, 14);
            var before = origin.captureLocation();
            try (var preview = new SourceUsagePreview(origin, () -> {})) {
                String source = "// context\r\n".repeat(80) + "class Test { String s = \"😀\"; void test(){ service.run(); } }\r\n";
                int start = source.indexOf("service.run");
                preview.editor().setSize(500, 280);
                preview.showSource("Test.java:81", source, start, start + 11);
                assertEquals(source, preview.editor().getText());
                assertEquals("service.run", preview.editor().getSelectedText());
                assertEquals(77, preview.editor().captureLocation().topLine());
                preview.editor().setSize(1000, 280);
                preview.showSource("Test.java:81", source, start, start + 11);
                assertEquals(0, preview.editor().captureLocation().leftColumn());
                assertTrue(preview.editor().isReadOnly());
                preview.editor().replaceSelection("changed"); preview.editor().deleteLine();
                assertEquals(source, preview.editor().getText());
                preview.showSource("No usage selected", "", 0, 0);
                assertTrue(preview.editor().isReadOnly()); assertEquals("", preview.editor().getText());
                assertEquals("class Original {}", origin.getText()); assertEquals(before, origin.captureLocation());
            }
        });
    }

    @Test void tracksThemeZoomAndTabsAndReleasesListenerOnClose() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var origin = new EditorComponent(); int listeners = origin.getPropertyChangeListeners().length;
            var preview = new SourceUsagePreview(origin, () -> {});
            try {
                for (String id : new String[] {"vscode-monokai", "classic"}) {
                    origin.setTheme(Theme.bundled(id)); origin.setFontSize(24); origin.setTabWidth(8);
                    assertSame(origin.theme(), preview.editor().theme());
                    assertEquals(origin.getFont(), preview.editor().getFont());
                    assertEquals(8, preview.editor().tabWidth());
                    assertTrue(preview.editor().getFocusTraversalKeysEnabled());
                }
            } finally { preview.close(); }
            assertEquals(listeners, origin.getPropertyChangeListeners().length);
        });
    }
}
