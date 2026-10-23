package com.turboj.ui;

import com.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.text.*;

/** Bounded output, with optional terminal-style editable input after the last output character. */
public final class ConsolePane extends JPanel {
    private final JTextPane output = new JTextPane();
    private final JTextField input = new JTextField();
    private final JPanel inputRow = new JPanel(new BorderLayout(8, 0));
    private final JLabel inputPrompt = new JLabel("Input >");
    private final JLabel inputHint = new JLabel("Enter submits");
    private final Consumer<String> send;
    private boolean echoInput;
    private boolean inlineInput;
    private boolean inputEnabled;
    private boolean writingOutput;

    /** UTF-16 boundary: only the suffix after this offset is user-editable. EDT-owned. */
    private int inputStart;

    private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
    private int pendingChars;
    private boolean scheduled;
    private boolean truncated;
    private final SimpleAttributeSet normal = new SimpleAttributeSet(),
            error = new SimpleAttributeSet();
    private static final String STDERR_ATTRIBUTE = "turboj.stderr";

    private record Chunk(String text, boolean error) {}

    public ConsolePane(Consumer<String> send) {
        super(new BorderLayout());
        normal.addAttribute(STDERR_ATTRIBUTE, false);
        error.addAttribute(STDERR_ATTRIBUTE, true);
        this.send = send;
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        ((AbstractDocument) output.getDocument()).setDocumentFilter(new InputFilter());
        var scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        input.setFont(output.getFont());
        input.setCaret(new BlockCaret());
        input.getCaret().setBlinkRate(0);
        inputPrompt.setFont(output.getFont());
        inputHint.setFont(output.getFont().deriveFont(12f));
        input.setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3));
        inputRow.add(inputPrompt, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(inputHint, BorderLayout.EAST);
        output.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)
                                && (inlineInput ? inputEnabled : input.isEnabled())
                                && output.getSelectionStart() == output.getSelectionEnd())
                            focusInput();
                    }
                });
        input.setToolTipText("Enter submits a line");
        input.addActionListener(e -> submitInput());
        add(inputRow, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(100, 150));
        setTheme(Theme.bundled("classic"));
    }

    /**
     * Program output and its pending stdin share one text surface. REPL keeps its own input row.
     */
    public void setInlineInput(boolean enabled) {
        inlineInput = enabled;
        inputRow.setVisible(!enabled);
        inputStart = output.getDocument().getLength();
        output.setEditable(enabled && inputEnabled);
        if (enabled) {
            output.setCaret(new BlockCaret());
            output.getCaret().setBlinkRate(0);
            output.setToolTipText(
                    "Type after the program prompt. Enter sends a line; F6 returns to the editor.");
            output.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "submit-stdin");
            output.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "submit-stdin");
            output.getActionMap()
                    .put(
                            "submit-stdin",
                            new AbstractAction() {
                                @Override
                                public void actionPerformed(java.awt.event.ActionEvent e) {
                                    submitInput();
                                }
                            });
        }
        revalidate();
    }

    public void setEchoInput(boolean enabled) {
        echoInput = enabled;
    }

    public void setProgramRunning(boolean running) {
        if (inlineInput) {
            mutateOutput(
                    () ->
                            output.getDocument()
                                    .remove(
                                            inputStart,
                                            output.getDocument().getLength() - inputStart));
            inputEnabled = running;
            output.setEditable(running);
            output.setCaretPosition(inputStart);
            output.getCaret().setVisible(running && output.isFocusOwner());
        } else {
            input.setText("");
            input.setEnabled(running);
            inputPrompt.setText(running ? "stdin >" : "Input");
            inputHint.setText(running ? "Enter sends · F6 editor" : "Run to enter input");
            input.getCaret().setVisible(running && input.isFocusOwner());
        }
    }

    public void setWaitingForInput(boolean waiting) {
        if (inlineInput) return;
        inputPrompt.setText("stdin >");
        inputHint.setText(waiting ? "Type a value · Enter" : "Enter sends · F6 editor");
    }

    public String inputText() {
        if (!inlineInput) return input.getText();
        try {
            return output.getDocument()
                    .getText(inputStart, output.getDocument().getLength() - inputStart);
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    public void setInputText(String text) {
        if (!inlineInput) input.setText(text);
        else if (inputEnabled) {
            output.select(inputStart, output.getDocument().getLength());
            output.replaceSelection(text);
        }
    }

    public void submitInput() {
        if (inlineInput) {
            if (!inputEnabled) return;
            drain(); // Place any already-received output before the line being submitted.
            String text = inputText();
            // The typed value already follows the prompt; committing adds only its newline.
            inputStart = output.getDocument().getLength();
            mutateOutput(() -> insertOutput("\n", false));
            output.setCaretPosition(inputStart);
            send.accept(text + "\n");
        } else {
            if (!input.isEnabled()) return;
            String text = input.getText();
            input.setText("");
            if (echoInput) append(text + "\n", false);
            send.accept(text + "\n");
        }
    }

    public void append(String text, boolean err) {
        synchronized (pending) {
            int room = 131072 - pendingChars;
            if (room > 0) {
                String s = text.substring(0, Math.min(room, text.length()));
                pending.add(new Chunk(s, err));
                pendingChars += s.length();
            }
            if (text.length() > room) truncated = true;
            if (!scheduled) {
                scheduled = true;
                SwingUtilities.invokeLater(this::drain);
            }
        }
    }

    private void drain() {
        var batch = new ArrayDeque<Chunk>();
        boolean drop;
        synchronized (pending) {
            batch.addAll(pending);
            pending.clear();
            pendingChars = 0;
            scheduled = false;
            drop = truncated;
            truncated = false;
        }
        mutateOutput(
                () -> {
                    for (var c : batch) insertOutput(c.text(), c.error());
                    if (drop) insertOutput("\n[output truncated]\n", true);
                    var doc = output.getDocument();
                    if (doc.getLength() > 200000) {
                        int remove = Math.min(inputStart, doc.getLength() - 180000);
                        doc.remove(0, remove);
                        inputStart -= remove;
                    }
                });
        // Preserve a pending-input selection while output arrives. Otherwise follow new output.
        if (!inlineInput || !inputEnabled || !output.isFocusOwner())
            output.setCaretPosition(output.getDocument().getLength());
    }

    private void insertOutput(String text, boolean err) throws BadLocationException {
        output.getStyledDocument().insertString(inputStart, text, err ? error : normal);
        inputStart += text.length();
    }

    @FunctionalInterface
    private interface DocumentAction {
        void run() throws BadLocationException;
    }

    private void mutateOutput(DocumentAction action) {
        writingOutput = true;
        try {
            action.run();
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        } finally {
            writingOutput = false;
        }
    }

    public void clear() {
        synchronized (pending) {
            pending.clear();
            pendingChars = 0;
            truncated = false;
        }
        mutateOutput(() -> output.setText(""));
        inputStart = 0;
    }

    public String text() {
        return output.getText();
    }

    /** Used by the REPL's dedicated input field. Program input uses inputComponent(). */
    public JTextField inputField() {
        return input;
    }

    public JTextComponent inputComponent() {
        return inlineInput ? output : input;
    }

    public void focusInput() {
        if (inlineInput) {
            if (inputEnabled && output.getCaretPosition() < inputStart)
                output.setCaretPosition(output.getDocument().getLength());
            output.requestFocusInWindow();
        } else if (input.isEnabled()) input.requestFocusInWindow();
        else output.requestFocusInWindow();
    }

    public boolean hasConsoleFocus() {
        return output.isFocusOwner() || input.isFocusOwner();
    }

    public void setTheme(Theme t) {
        setBackground(t.messagesBackground());
        output.setBackground(t.messagesBackground());
        output.setForeground(t.messagesForeground());
        output.setCaretColor(t.color("messages", "cursor"));
        output.setSelectionColor(t.selection());
        output.setSelectedTextColor(t.color("editor", "selectionForeground"));
        input.setBackground(t.messagesBackground());
        input.setForeground(t.color("messages", "inputForeground"));
        input.setCaretColor(t.color("messages", "cursor"));
        input.setSelectionColor(t.selection());
        input.setSelectedTextColor(t.color("editor", "selectionForeground"));
        input.setDisabledTextColor(t.messagesForeground());
        inputRow.setBackground(t.messagesBackground());
        inputPrompt.setForeground(t.messagesForeground());
        inputHint.setForeground(t.messagesForeground());
        inputRow.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, t.messagesForeground()),
                        BorderFactory.createEmptyBorder(4, 5, 4, 5)));
        StyleConstants.setForeground(normal, t.messagesForeground());
        StyleConstants.setForeground(error, t.color("messages", "errorForeground"));
        normal.addAttribute(STDERR_ATTRIBUTE, false);
        error.addAttribute(STDERR_ATTRIBUTE, true);
        var document = output.getStyledDocument();
        for (int offset = 0; offset < document.getLength(); ) {
            var element = document.getCharacterElement(offset);
            int end = Math.min(element.getEndOffset(), document.getLength());
            boolean stderr =
                    Boolean.TRUE.equals(element.getAttributes().getAttribute(STDERR_ATTRIBUTE));
            document.setCharacterAttributes(offset, end - offset, stderr ? error : normal, true);
            offset = end;
        }
    }

    /** Output is immutable to editing; only unsent stdin may be changed or removed. */
    private final class InputFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attributes)
                throws BadLocationException {
            replace(fb, offset, 0, text, attributes);
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            replace(fb, offset, length, "", null);
        }

        @Override
        public void replace(
                FilterBypass fb, int offset, int length, String text, AttributeSet attributes)
                throws BadLocationException {
            if (writingOutput || !inlineInput) {
                fb.replace(offset, length, text, attributes);
                return;
            }
            if (!inputEnabled) return;
            int end = offset + length;
            if (offset < inputStart) {
                offset = inputStart;
                length = Math.max(0, end - inputStart);
            }
            fb.replace(offset, length, text, normal);
        }
    }

    /** A steady, high-contrast text-cell cursor, including at the end of a prompt. */
    private static final class BlockCaret extends DefaultCaret {
        @Override
        protected synchronized void damage(Rectangle r) {
            if (r == null || getComponent() == null) return;
            x = r.x;
            y = r.y;
            width =
                    Math.max(
                                    2,
                                    getComponent()
                                            .getFontMetrics(getComponent().getFont())
                                            .charWidth('M'))
                            + 2;
            height = r.height;
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            if (!isVisible() || getComponent() == null || !getComponent().isEnabled()) return;
            JTextComponent c = getComponent();
            if (!c.isEditable()) return;
            try {
                Rectangle r = c.modelToView2D(getDot()).getBounds();
                FontMetrics metrics = c.getFontMetrics(c.getFont());
                g.setColor(c.getCaretColor());
                g.fillRect(r.x, r.y, Math.max(2, metrics.charWidth('M')), r.height);
                if (getDot() < c.getDocument().getLength()) {
                    String next =
                            c.getDocument()
                                    .getText(
                                            getDot(),
                                            Math.min(2, c.getDocument().getLength() - getDot()));
                    int count = Character.charCount(next.codePointAt(0));
                    g.setColor(c.getBackground());
                    g.drawString(next.substring(0, count), r.x, r.y + metrics.getAscent());
                }
            } catch (BadLocationException ignored) {
            }
        }
    }
}
