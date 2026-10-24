package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiStyle;
import dev.turboj.ui.themes.Theme;
import java.awt.Font;
import javax.swing.JSplitPane;

/** The editor, output and embedded forms share one text-cell chrome geometry. */
public final class AsciiChrome {
    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    private AsciiChrome() {}

    public static Font font() { return FONT; }

    public static AsciiPalette editor(Theme theme) {
        return new AsciiPalette(theme.background(), theme.foreground(), theme.selection(),
                theme.color("editor", "selectionForeground"), theme.color("ui", "muted"),
                theme.color("chrome", "border"), FONT);
    }

    public static AsciiPalette output(Theme theme) {
        return new AsciiPalette(theme.messagesBackground(), theme.messagesForeground(),
                theme.selection(), theme.color("editor", "selectionForeground"),
                theme.color("ui", "muted"), theme.messagesForeground(), FONT);
    }

    /** Keep the resize handle functional without adding a second native frame around the panes. */
    public static void splitPane(JSplitPane split, AsciiPalette palette) {
        AsciiStyle.splitPane(split, palette);
    }
}
