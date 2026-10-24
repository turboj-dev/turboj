package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiStyle;
import dev.turboj.ui.themes.Theme;
import java.awt.Font;
import javax.swing.JSplitPane;

/** The editor, output and embedded forms share one text-cell chrome geometry. */
public final class AsciiChrome {
    private static int editorFontSize = 18;
    private static Font font = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    private AsciiChrome() {}

    public static Font font() { return font; }

    /** Application-wide zoom, changed on the EDT; retain the editor/chrome size proportions. */
    public static void setEditorFontSize(int size) {
        editorFontSize = Math.clamp(size, 10, 48);
        font = new Font(Font.MONOSPACED, Font.PLAIN, scaled(14));
    }

    public static int editorFontSize() { return editorFontSize; }
    public static double scale() { return editorFontSize / 18.0; }
    public static int scaled(int value) { return Math.max(1, (int) Math.round(value * scale())); }

    public static AsciiPalette editor(Theme theme) {
        return new AsciiPalette(theme.background(), theme.foreground(), theme.selection(),
                theme.color("editor", "selectionForeground"), theme.color("ui", "muted"),
                theme.color("chrome", "border"), font);
    }

    public static AsciiPalette output(Theme theme) {
        return new AsciiPalette(theme.messagesBackground(), theme.messagesForeground(),
                theme.selection(), theme.color("editor", "selectionForeground"),
                theme.color("ui", "muted"), theme.messagesForeground(), font);
    }

    /** Keep the resize handle functional without adding a second native frame around the panes. */
    public static void splitPane(JSplitPane split, AsciiPalette palette) {
        AsciiStyle.splitPane(split, palette);
    }
}
