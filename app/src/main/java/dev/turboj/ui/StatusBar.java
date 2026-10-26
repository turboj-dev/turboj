package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.KeyGlyphs;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * The state right now, in segments (docs/output-and-status.md): build state, tests and a fading message on the left;
 * caret, file, formatter, AI, keymap and project on the right. Each segment opens what it describes when clicked. Short UI
 * feedback goes to {@link #flash}, never into the output, and stays readable in the message history.
 */
public final class StatusBar extends JPanel {
    public enum Level { NORMAL, SUCCESS, WARNING, ERROR, BUSY }
    public enum Segment { STATE, TESTS, MESSAGE, CARET, FILE, FORMATTER, AI, KEYMAP, PROJECT }

    private static final int HISTORY = 50;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private final EnumMap<Segment, JLabel> labels = new EnumMap<>(Segment.class);
    private final EnumMap<Segment, Level> levels = new EnumMap<>(Segment.class);
    private final Deque<String> history = new ArrayDeque<>();
    private String held = "";
    private final javax.swing.Timer fade = new javax.swing.Timer(5000, event -> set(Segment.MESSAGE, held, Level.NORMAL));
    private Color foreground = Color.BLACK, success = new Color(0, 110, 0), warning = new Color(130, 90, 0), error = new Color(170, 0, 0);
    private Color separator = Color.GRAY;

    public StatusBar() {
        super(new BorderLayout());
        setName("workspace-status-line");
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        left.setOpaque(false); right.setOpaque(false);
        for (Segment segment : Segment.values()) {
            var label = new JLabel();
            label.setName("status-" + segment.name().toLowerCase(Locale.ROOT));
            label.getAccessibleContext().setAccessibleName("Status " + segment.name().toLowerCase(Locale.ROOT));
            labels.put(segment, label);
            levels.put(segment, Level.NORMAL);
            (segment.ordinal() <= Segment.MESSAGE.ordinal() ? left : right).add(label);
        }
        labels.get(Segment.STATE).setName("workspace-status");
        labels.get(Segment.CARET).setName("caret-status");
        add(left, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        fade.setRepeats(false);
        onClick(Segment.MESSAGE, this::showHistory);
        refresh();
    }

    /** The text a segment shows now. */
    public String text(Segment segment) { return labels.get(segment).getText().strip(); }

    public JLabel label(Segment segment) { return labels.get(segment); }

    public void set(Segment segment, String text, Level level) {
        var label = labels.get(segment);
        label.setText(text == null || text.isBlank() ? "" : " " + text + " ");
        levels.put(segment, level);
        refresh();
    }

    public void setTooltip(Segment segment, String text) { labels.get(segment).setToolTipText(text == null || text.isBlank() ? null : text); }

    /** UI feedback ("Saved HelloWorld.java"): shown for five seconds, then kept in the history the segment opens. */
    public void flash(String text, Level level) {
        String message = text == null ? "" : text.strip();
        if (message.isEmpty()) return;
        String single = message.lines().findFirst().orElse(message);
        history.addFirst(LocalTime.now().format(CLOCK) + "  " + message);
        while (history.size() > HISTORY) history.removeLast();
        // While a mode holds the segment, only problems show over its keys; routine feedback goes to the history.
        if (!held.isEmpty() && level != Level.WARNING && level != Level.ERROR) return;
        set(Segment.MESSAGE, single + (message.contains("\n") ? " …" : ""), level);
        setTooltip(Segment.MESSAGE, message);
        fade.restart();
    }

    /** A message that stays while a mode lasts, such as an inline template's keys; warnings and errors show over it, then it returns. */
    public void hold(String text) {
        held = text == null ? "" : text;
        fade.stop();
        set(Segment.MESSAGE, held, Level.NORMAL);
        setTooltip(Segment.MESSAGE, held);
    }

    public void release() {
        boolean showing = !held.isEmpty() && text(Segment.MESSAGE).equals(held.strip());
        held = "";
        if (showing) set(Segment.MESSAGE, "", Level.NORMAL);
    }

    /** The current message, or the last one after it faded. */
    public String lastMessage() { return history.isEmpty() ? "" : history.getFirst().substring(10); }

    public List<String> history() { return List.copyOf(history); }

    public void onClick(Segment segment, Runnable action) {
        var label = labels.get(segment);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (SwingUtilities.isLeftMouseButton(event)) action.run(); }
        });
    }

    public void setColors(Color background, Color foreground, Color success, Color warning, Color error) {
        setBackground(background);
        this.foreground = AsciiPalette.readable(foreground, background, 4.5);
        this.success = AsciiPalette.readable(success, background, 4.5);
        this.warning = AsciiPalette.readable(warning, background, 4.5);
        this.error = AsciiPalette.readable(error, background, 4.5);
        separator = blend(this.foreground, background);
        refresh();
    }

    @Override public void setFont(Font font) {
        super.setFont(font);
        if (labels != null) refresh();
    }

    private void refresh() {
        if (labels == null) return;
        Font font = getFont();
        Segment previousShown = null;
        for (Segment segment : Segment.values()) {
            var label = labels.get(segment);
            boolean shown = !label.getText().isBlank();
            label.setVisible(shown);
            label.setFont(font == null ? null : KeyGlyphs.shortcutFont(font));
            label.setForeground(switch (levels.get(segment)) {
                case SUCCESS -> success; case WARNING, BUSY -> warning; case ERROR -> error; case NORMAL -> foreground;
            });
            // A thin rule separates neighbouring segments on the same side.
            boolean sameSide = previousShown != null && (previousShown.ordinal() <= Segment.MESSAGE.ordinal())
                    == (segment.ordinal() <= Segment.MESSAGE.ordinal());
            label.setBorder(shown && sameSide ? BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, separator),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2)) : BorderFactory.createEmptyBorder(1, 2, 1, 2));
            if (shown) previousShown = segment;
        }
        revalidate();
        repaint();
    }

    private void showHistory() {
        if (history.isEmpty()) return;
        var popup = new JPopupMenu("Recent messages");
        for (String entry : history) {
            var item = new JMenuItem(entry.length() > 140 ? entry.substring(0, 140) + "…" : entry);
            item.setToolTipText(entry);
            popup.add(item);
        }
        var label = labels.get(Segment.MESSAGE);
        Component anchor = label.isShowing() ? label : this;
        popup.show(anchor, 0, -popup.getPreferredSize().height);
    }

    private static Color blend(Color a, Color b) {
        return new Color((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2, (a.getBlue() + b.getBlue()) / 2);
    }
}
