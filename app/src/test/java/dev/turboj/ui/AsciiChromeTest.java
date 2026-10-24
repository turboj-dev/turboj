package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiBorder;
import dev.turboj.commandj.swing.AsciiStyle;
import dev.turboj.ui.themes.Theme;
import java.awt.Font;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiChromeTest {
    @Test void interfaceZoomScalesMenusOutputFormsAndTextCellBordersBothWays() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int previous = AsciiChrome.editorFontSize();
            try {
                var menu = new JMenu("File");
                var item = new JMenuItem("Save");
                item.setAccelerator(KeyStroke.getKeyStroke("meta S")); menu.add(item);
                var console = new ConsolePane(ignored -> {});
                console.setInlineInput(true);
                var field = new JTextField("retain my text"); field.setCaretPosition(4);
                var table = new JTable(2, 2);
                var form = new JPanel(); form.add(field); form.add(table);
                form.setBorder(new AsciiBorder("Details", AsciiChrome.editor(Theme.bundled("classic"))));
                int smallRow = 0;
                for (int size : new int[] {18, 28, 12, 18}) {
                    AsciiChrome.setEditorFontSize(size);
                    for (String id : Theme.bundledResources().keySet()) {
                        Theme theme = Theme.bundled(id);
                        MenuStyler.install(theme); MenuStyler.style(menu, theme);
                        console.setTheme(theme); AsciiStyle.apply(form, AsciiChrome.editor(theme));
                        int expected = Math.round(size * 14f / 18);
                        assertEquals(expected, item.getFont().getSize(), id + " menu");
                        assertEquals(expected, UIManager.getFont("MenuItem.acceleratorFont").getSize(), id + " shortcut");
                        assertEquals(expected, console.inputComponent().getFont().getSize(), id + " output");
                        assertEquals(expected, field.getFont().getSize(), id + " input");
                        assertEquals(expected, ((AsciiBorder) form.getBorder()).palette().font().getSize(), id + " border");
                        assertEquals(table.getFontMetrics(table.getFont()).getHeight() + 4, table.getRowHeight());
                        assertEquals("retain my text", field.getText()); assertEquals(4, field.getCaretPosition());
                    }
                    if (size == 12) smallRow = table.getRowHeight();
                }
                assertTrue(table.getRowHeight() > smallRow, "row geometry also follows zooming back in");
            } finally {
                AsciiChrome.setEditorFontSize(previous);
                MenuStyler.install(Theme.bundled("classic"));
            }
        });
    }

    @Test void everyCatalogThemeKeepsEditorAndOutputSurfacesReadableAndDistinct() {
        for (String id : Theme.bundledResources().keySet()) {
            Theme theme = Theme.bundled(id);
            AsciiPalette editor = AsciiChrome.editor(theme), output = AsciiChrome.output(theme);
            assertEquals(theme.background(), editor.background(), id + " editor surface");
            assertEquals(theme.messagesBackground(), output.background(), id + " output surface");
            for (AsciiPalette palette : new AsciiPalette[] {editor, output}) {
                assertTrue(MenuStyler.contrast(palette.foreground(), palette.background()) >= 4.5, id + " text");
                assertTrue(MenuStyler.contrast(palette.selectionForeground(), palette.selectionBackground()) >= 4.5, id + " selected text");
                assertTrue(MenuStyler.contrast(palette.muted(), palette.background()) >= 3, id + " inactive text");
                assertTrue(MenuStyler.contrast(palette.border(), palette.background()) >= 3, id + " pane boundary");
                assertEquals(Font.MONOSPACED, palette.font().getFamily(), id + " chrome grid");
            }
        }
    }

    @Test void changingChromeKeepsSplitPositionContentCaretAndResizeBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea source = new JTextArea("class Example { int value = 42; }"), output = new JTextArea("answer: 42\n");
            source.setCaretPosition(14);
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, source, output);
            split.setSize(900, 500); split.setDividerSize(7); split.setResizeWeight(.25);
            split.setDividerLocation(260); split.doLayout();
            for (String id : Theme.BUNDLED) {
                AsciiChrome.splitPane(split, AsciiChrome.editor(Theme.bundled(id)));
                split.doLayout();
                assertEquals(260, split.getDividerLocation(), id + " user layout preserved");
                assertEquals(7, split.getDividerSize()); assertEquals(.25, split.getResizeWeight());
                assertSame(source, split.getLeftComponent()); assertSame(output, split.getRightComponent());
                assertEquals("class Example { int value = 42; }", source.getText());
                assertEquals("answer: 42\n", output.getText()); assertEquals(14, source.getCaretPosition());
                split.setDividerLocation(310); split.doLayout();
                assertEquals(310, split.getDividerLocation(), id + " divider remains operable");
                split.setDividerLocation(260);
            }
        });
    }
}
