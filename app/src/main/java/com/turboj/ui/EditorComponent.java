package com.turboj.ui;

import com.turboj.compiler.CompilerDiagnostic;
import com.turboj.editor.document.PieceTable;
import com.turboj.editor.highlighting.JavaLexer;
import com.turboj.ui.themes.Theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** One painting component, with a UTF-16 piece table and code-point-aware caret motion. */
public final class EditorComponent extends JComponent
        implements InputMethodListener, InputMethodRequests {
    private PieceTable document = new PieceTable();
    private int caret, anchor, topLine, leftColumn, preferredColumn = -1;
    private int cellWidth = 10, cellHeight = 20, ascent = 15, gutterWidth = 60, tabWidth = 4;
    private FontMetrics fontMetrics;
    private Runnable onChange = () -> {}, onCaretChange = () -> {};
    private Color foreground, keyword, string, comment, selection, cursor, error;
    private Color ghost, selectionForeground, number, annotation, definition, identifier, operator;
    private boolean antialias, autoClose = true;
    private List<CompilerDiagnostic> diagnostics = List.of();
    private final List<JavaLexer.Line> lexed = new ArrayList<>();
    private final List<String> lineTexts = new ArrayList<>();
    private final List<int[]> lineColumns = new ArrayList<>();
    private String composition = "";
    private long pendingInputNanos, lastPaintNanos;
    private final long[] inputSamples = new long[1024];
    private int sampleCount;
    private InlineCompletion inlineCompletion;
    private KeyStroke acceptCompletionKey = KeyStroke.getKeyStroke("TAB");
    private KeyStroke dismissCompletionKey = KeyStroke.getKeyStroke("ESCAPE");
    private Predicate<KeyEvent> keyHandler = event -> false;
    private Consumer<SymbolHover> onSymbolHover = hover -> {};
    private IntConsumer onGoDeclaration = offset -> {};
    private Point hoverPoint;
    private boolean commandDown, readOnly;
    private int navigationStart = -1, navigationEnd = -1;
    private int emphasisStart = -1, emphasisEnd = -1;
    private javax.swing.Timer emphasisTimer;

    /** All offsets are UTF-16 offsets into the current source version. */
    public record TextEdit(int start, int end, String text) {
        public TextEdit {
            Objects.requireNonNull(text);
        }
    }

    public record InlineCompletion(
            long version,
            int caret,
            int start,
            int end,
            String insertion,
            int caretInInsertion,
            List<TextEdit> additionalEdits) {
        public InlineCompletion {
            Objects.requireNonNull(insertion);
            additionalEdits = List.copyOf(additionalEdits);
        }

        public InlineCompletion(
                long version,
                int caret,
                int start,
                int end,
                String insertion,
                int caretInInsertion) {
            this(version, caret, start, end, insertion, caretInInsertion, List.of());
        }
    }

    public record Location(int caret, int anchor, int topLine, int leftColumn) {}

    /** EDT-owned document reference: switching buffers retains its undo/redo history. */
    public record EditorState(PieceTable document, Location location, boolean readOnly) {}

    /** Point is in editor coordinates. An offset of -1 means the pointer left the editor. */
    public record SymbolHover(int offset, Point point, boolean commandDown) {}

    public EditorComponent() {
        setOpaque(true);
        setTheme(Theme.bundled("classic"));
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        metrics();
        enableEvents(KeyEvent.KEY_EVENT_MASK);
        enableInputMethods(true);
        addInputMethodListener(this);
        addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        repaintCaret();
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        clearInlineCompletion();
                        commandDown = false;
                        clearNavigableRange();
                        repaintCaret();
                    }
                });
        MouseAdapter mouse =
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        requestFocusInWindow();
                        int offset = offsetAt(e.getPoint());
                        if (SwingUtilities.isLeftMouseButton(e) && e.isMetaDown()) {
                            int symbolOffset = symbolOffsetAt(e.getPoint());
                            if (symbolOffset >= 0) {
                                clearInlineCompletion();
                                onGoDeclaration.accept(symbolOffset);
                                return;
                            }
                        }
                        moveTo(offset, e.isShiftDown());
                        if (e.getClickCount() == 2) selectWord();
                        if (e.getClickCount() >= 3) {
                            anchor = document.lineStart(document.lineAt(caret));
                            caret = document.lineEnd(document.lineAt(caret));
                            repaint();
                        }
                    }

                    @Override
                    public void mouseDragged(MouseEvent e) {
                        clearNavigableRange();
                        if (e.getY() < 0) topLine = Math.max(0, topLine - 1);
                        if (e.getY() >= getHeight())
                            topLine = Math.min(document.lineCount() - 1, topLine + 1);
                        moveTo(offsetAt(e.getPoint()), true);
                    }

                    @Override
                    public void mouseMoved(MouseEvent e) {
                        hoverPoint = e.getPoint();
                        commandDown = e.isMetaDown();
                        int offset = symbolOffsetAt(hoverPoint);
                        if (!commandDown || offset < navigationStart || offset >= navigationEnd)
                            clearNavigableRange();
                        notifyHover();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hoverPoint = null;
                        clearNavigableRange();
                        onSymbolHover.accept(new SymbolHover(-1, e.getPoint(), false));
                    }

                    @Override
                    public void mouseWheelMoved(MouseWheelEvent e) {
                        clearNavigableRange();
                        onSymbolHover.accept(new SymbolHover(-1, e.getPoint(), false));
                        int amount =
                                (int)
                                        Math.copySign(
                                                Math.max(
                                                        1,
                                                        Math.ceil(
                                                                Math.abs(
                                                                                e
                                                                                        .getPreciseWheelRotation())
                                                                        * 3)),
                                                e.getPreciseWheelRotation());
                        if (e.isShiftDown()) leftColumn = Math.max(0, leftColumn + amount);
                        else
                            topLine =
                                    Math.max(
                                            0,
                                            Math.min(document.lineCount() - 1, topLine + amount));
                        repaint();
                    }
                };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public PieceTable document() {
        return document;
    }

    public PieceTable.Snapshot snapshot() {
        return document.snapshot();
    }

    public long version() {
        return document.version();
    }

    public String getText() {
        return document.toString();
    }

    public void setText(String value) {
        document = new PieceTable(value);
        readOnly = false;
        caret = anchor = topLine = leftColumn = 0;
        resetVisualState();
        onChange.run();
        onCaretChange.run();
        repaint();
    }

    private void resetVisualState() {
        clearInlineCompletion();
        clearNavigableRange();
        if (emphasisTimer != null) emphasisTimer.stop();
        emphasisStart = emphasisEnd = -1;
        lexed.clear();
        lineTexts.clear();
        lineColumns.clear();
        diagnostics = List.of();
        composition = "";
        pendingInputNanos = 0;
        preferredColumn = -1;
        metrics();
    }

    public EditorState captureState() {
        return new EditorState(document, captureLocation(), readOnly);
    }

    public void restoreState(EditorState state) {
        document = Objects.requireNonNull(state.document());
        readOnly = state.readOnly();
        // Reset coordinates before invalidating visuals: the previous buffer may have been longer.
        caret = anchor = topLine = leftColumn = 0;
        resetVisualState();
        restoreLocation(state.location());
        onChange.run();
    }

    public Location captureLocation() {
        return new Location(caret, anchor, topLine, leftColumn);
    }

    public void restoreLocation(Location location) {
        clearInlineCompletion();
        clearNavigableRange();
        caret = Math.clamp(location.caret(), 0, document.length());
        anchor = Math.clamp(location.anchor(), 0, document.length());
        topLine = Math.clamp(location.topLine(), 0, document.lineCount() - 1);
        leftColumn = Math.max(0, location.leftColumn());
        preferredColumn = -1;
        onCaretChange.run();
        repaint();
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        if (readOnly) {
            clearInlineCompletion();
            composition = "";
        }
        repaintCaret();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setKeyHandler(Predicate<KeyEvent> handler) {
        keyHandler = handler == null ? event -> false : handler;
    }

    public void setCompletionKeys(KeyStroke accept, KeyStroke dismiss) {
        acceptCompletionKey = accept == null ? KeyStroke.getKeyStroke("TAB") : accept;
        dismissCompletionKey = dismiss == null ? KeyStroke.getKeyStroke("ESCAPE") : dismiss;
    }

    public void setOnSymbolHover(Consumer<SymbolHover> callback) {
        onSymbolHover = callback == null ? hover -> {} : callback;
    }

    public void setOnGoDeclaration(IntConsumer callback) {
        onGoDeclaration = callback == null ? offset -> {} : callback;
    }

    private void notifyHover() {
        if (hoverPoint != null)
            onSymbolHover.accept(
                    new SymbolHover(
                            symbolOffsetAt(hoverPoint), new Point(hoverPoint), commandDown));
    }

    public void setNavigableRange(long version, int start, int end) {
        clearNavigableRange();
        if (version != version()
                || !commandDown
                || hoverPoint == null
                || start < 0
                || end <= start
                || end > document.length()) return;
        int hoverOffset = symbolOffsetAt(hoverPoint);
        if (hoverOffset < start || hoverOffset >= end) return;
        navigationStart = start;
        navigationEnd = end;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repaintRange(start, end);
    }

    public void clearNavigableRange() {
        if (navigationStart >= 0 && navigationEnd <= document.length())
            repaintRange(navigationStart, navigationEnd);
        navigationStart = navigationEnd = -1;
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
    }

    public void emphasizeRange(int start, int end) {
        if (emphasisTimer != null) emphasisTimer.stop();
        if (emphasisStart >= 0 && emphasisEnd <= document.length())
            repaintRange(emphasisStart, emphasisEnd);
        emphasisStart = Math.clamp(start, 0, document.length());
        emphasisEnd = Math.clamp(end, emphasisStart, document.length());
        repaintRange(emphasisStart, emphasisEnd);
        emphasisTimer =
                new javax.swing.Timer(
                        650,
                        event -> {
                            if (emphasisStart >= 0 && emphasisEnd <= document.length())
                                repaintRange(emphasisStart, emphasisEnd);
                            emphasisStart = emphasisEnd = -1;
                        });
        emphasisTimer.setRepeats(false);
        emphasisTimer.start();
    }

    public Rectangle caretBounds() {
        return offsetBounds(caret);
    }

    public Rectangle offsetBounds(int offset) {
        offset = Math.clamp(offset, 0, document.length());
        int line = document.lineAt(offset);
        int column = visualColumn(document.lineText(line), offset - document.lineStart(line));
        return new Rectangle(
                gutterWidth + (column - leftColumn) * cellWidth,
                (line - topLine) * cellHeight,
                cellWidth,
                cellHeight);
    }

    public InlineCompletion inlineCompletion() {
        return inlineCompletion;
    }

    public boolean setInlineCompletion(InlineCompletion proposal) {
        clearInlineCompletion();
        if (!validCompletion(proposal) || proposal.end() != caret) return false;
        String prefix = document.substring(proposal.start(), caret);
        if (!proposal.insertion().startsWith(prefix)) return false;
        String suffix = proposal.insertion().substring(prefix.length());
        if (suffix.isEmpty() || suffix.indexOf('\n') >= 0 || suffix.indexOf('\r') >= 0)
            return false;
        // Ghost text never covers source after the caret. Such proposals remain available in the
        // popup.
        int endOfLine = document.lineEnd(document.lineAt(caret));
        if (!document.substring(caret, endOfLine).isBlank()) return false;
        inlineCompletion = proposal;
        repaintCaret();
        return true;
    }

    public void clearInlineCompletion() {
        if (inlineCompletion != null) {
            inlineCompletion = null;
            repaintCaret();
        }
    }

    public boolean acceptInlineCompletion() {
        InlineCompletion proposal = inlineCompletion;
        clearInlineCompletion();
        return applyCompletion(proposal);
    }

    /** Applies replacement and import edits in one undo record, only after explicit acceptance. */
    public boolean applyCompletion(InlineCompletion proposal) {
        if (!validCompletion(proposal)) return false;
        List<TextEdit> edits = new ArrayList<>(proposal.additionalEdits());
        TextEdit primary = new TextEdit(proposal.start(), proposal.end(), proposal.insertion());
        edits.add(primary);
        edits.sort(
                java.util.Comparator.comparingInt(TextEdit::start).thenComparingInt(TextEdit::end));
        int begin = edits.getFirst().start(), end = edits.getLast().end();
        int position = begin, target = -1;
        StringBuilder replacement = new StringBuilder();
        for (TextEdit edit : edits) {
            if (edit.start() < position
                    || edit.end() < edit.start()
                    || edit.end() > document.length()) return false;
            replacement.append(document.substring(position, edit.start()));
            if (edit == primary)
                target = begin + replacement.length() + proposal.caretInInsertion();
            replacement.append(edit.text());
            position = edit.end();
        }
        if (target < 0) return false;
        edit(begin, end, replacement.toString());
        moveTo(target, false);
        return true;
    }

    private boolean validCompletion(InlineCompletion proposal) {
        if (proposal == null
                || readOnly
                || !composition.isEmpty()
                || caret != anchor
                || proposal.version() != version()
                || proposal.caret() != caret
                || proposal.start() < 0
                || proposal.start() > caret
                || proposal.end() < caret
                || proposal.end() > document.length()
                || proposal.caretInInsertion() < 0
                || proposal.caretInInsertion() > proposal.insertion().length()) return false;
        for (TextEdit edit : proposal.additionalEdits())
            if (edit.start() < 0 || edit.end() < edit.start() || edit.end() > document.length())
                return false;
        return true;
    }

    private void repaintRange(int start, int end) {
        int first = document.lineAt(start), last = document.lineAt(end);
        repaint(0, (first - topLine) * cellHeight, getWidth(), (last - first + 1) * cellHeight);
    }

    public void setOnChange(Runnable callback) {
        onChange = callback == null ? () -> {} : callback;
    }

    public void setOnCaretChange(Runnable callback) {
        onCaretChange = callback == null ? () -> {} : callback;
    }

    public void setPalette(
            Color background,
            Color foreground,
            Color keyword,
            Color string,
            Color comment,
            Color selection,
            Color cursor,
            Color error,
            boolean antialias) {
        setBackground(background);
        this.foreground = foreground;
        this.keyword = keyword;
        this.string = string;
        this.comment = comment;
        this.selection = selection;
        this.cursor = cursor;
        this.error = error;
        this.antialias = antialias;
        this.ghost = comment;
        this.selectionForeground = foreground;
        this.number = string;
        this.annotation = string;
        this.definition = string;
        this.identifier = foreground;
        this.operator = foreground;
        repaint();
    }

    public void setTheme(Theme theme) {
        setPalette(
                theme.background(),
                theme.foreground(),
                theme.keyword(),
                theme.string(),
                theme.comment(),
                theme.selection(),
                theme.cursor(),
                theme.error(),
                theme.antialias());
        ghost = theme.color("intelligence", "ghost");
        selectionForeground = theme.color("editor", "selectionForeground");
        number = theme.color("syntax", "number");
        annotation = theme.color("syntax", "annotation");
        definition = theme.color("intelligence", "definition");
        identifier = theme.color("syntax", "identifier");
        operator = theme.color("syntax", "operator");
        repaint();
    }

    public void setFontSize(int size) {
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.clamp(size, 10, 48)));
        metrics();
        lineColumns.replaceAll(ignored -> null);
        ensureCaretVisible();
        revalidate();
        repaint();
    }

    public void setTabWidth(int width) {
        tabWidth = Math.clamp(width, 1, 16);
        lineColumns.replaceAll(ignored -> null);
        repaint();
    }

    public void setAutoClose(boolean enabled) {
        autoClose = enabled;
    }

    public void setDiagnostics(List<CompilerDiagnostic> diagnostics) {
        if (this.diagnostics.equals(diagnostics)) return;
        for (CompilerDiagnostic d : this.diagnostics)
            repaint(0, ((int) d.line() - 1 - topLine) * cellHeight, getWidth(), cellHeight);
        this.diagnostics = List.copyOf(diagnostics);
        for (CompilerDiagnostic d : this.diagnostics)
            repaint(0, ((int) d.line() - 1 - topLine) * cellHeight, getWidth(), cellHeight);
    }

    public int caretLine() {
        return document.lineAt(caret) + 1;
    }

    public int caretColumn() {
        int line = document.lineAt(caret);
        return visualColumn(document.lineText(line), caret - document.lineStart(line)) + 1;
    }

    public int caretOffset() {
        return caret;
    }

    public String getSelectedText() {
        return document.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    public void gotoLine(int line) {
        moveTo(document.lineStart(Math.clamp(line - 1, 0, document.lineCount() - 1)), false);
        requestFocusInWindow();
    }

    public void gotoOffset(int offset) {
        moveTo(Math.clamp(offset, 0, document.length()), false);
        requestFocusInWindow();
    }

    public boolean find(String query, boolean forward) {
        if (query == null || query.isEmpty()) return false;
        String text = document.toString();
        int match;
        if (forward) {
            match = text.indexOf(query, Math.max(caret, anchor));
            if (match < 0) match = text.indexOf(query);
        } else {
            match = text.lastIndexOf(query, Math.min(caret, anchor) - 1);
            if (match < 0) match = text.lastIndexOf(query);
        }
        if (match < 0) return false;
        clearInlineCompletion();
        clearNavigableRange();
        anchor = match;
        caret = match + query.length();
        ensureCaretVisible();
        onCaretChange.run();
        repaint();
        return true;
    }

    public void replaceSelection(String value) {
        edit(Math.min(caret, anchor), Math.max(caret, anchor), value);
    }

    public void selectAll() {
        clearInlineCompletion();
        clearNavigableRange();
        anchor = 0;
        caret = document.length();
        onCaretChange.run();
        repaint();
    }

    public void copy() {
        if (caret == anchor) return;
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(getSelectedText()), null);
        } catch (IllegalStateException | java.awt.HeadlessException e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    public void cut() {
        if (readOnly) return;
        if (caret != anchor) {
            copy();
            replaceSelection("");
        }
    }

    public void paste() {
        if (readOnly) return;
        try {
            Object value =
                    Toolkit.getDefaultToolkit()
                            .getSystemClipboard()
                            .getData(DataFlavor.stringFlavor);
            if (value instanceof String text)
                replaceSelection(
                        text.replace("\r\n", "\n")
                                .replace("\r", "\n")
                                .replace("\n", document.lineEnding()));
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    public void undo() {
        if (readOnly) return;
        applyHistory(document.undo());
    }

    public void redo() {
        if (readOnly) return;
        applyHistory(document.redo());
    }

    private void applyHistory(PieceTable.Change change) {
        if (change == null) return;
        caret = anchor = change.start() + change.inserted().length();
        changed(
                document.lineAt(change.start()),
                change.removed().indexOf('\n') >= 0 || change.inserted().indexOf('\n') >= 0);
    }

    public void indent(boolean unindent) {
        if (readOnly) return;
        int first = document.lineAt(Math.min(caret, anchor)),
                last = document.lineAt(Math.max(caret, anchor));
        if (caret != anchor && Math.max(caret, anchor) == document.lineStart(last) && last > first)
            last--;
        if (caret == anchor && !unindent) {
            replaceSelection(" ".repeat(tabWidth - (caretColumn() - 1) % tabWidth));
            return;
        }
        int start = document.lineStart(first), end = document.lineEnd(last);
        String block = document.substring(start, end);
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!unindent) lines[i] = " ".repeat(tabWidth) + lines[i];
            else if (lines[i].startsWith("\t")) lines[i] = lines[i].substring(1);
            else {
                int n = 0;
                while (n < Math.min(tabWidth, lines[i].length()) && lines[i].charAt(n) == ' ') n++;
                lines[i] = lines[i].substring(n);
            }
        }
        String replacement = String.join("\n", lines);
        edit(start, end, replacement);
        anchor = start;
        repaint();
    }

    public void toggleComment() {
        if (readOnly) return;
        int first = document.lineAt(Math.min(caret, anchor)),
                last = document.lineAt(Math.max(caret, anchor));
        if (caret != anchor && Math.max(caret, anchor) == document.lineStart(last) && last > first)
            last--;
        int start = document.lineStart(first), end = document.lineEnd(last);
        String[] lines = document.substring(start, end).split("\n", -1);
        boolean remove =
                Arrays.stream(lines).allMatch(line -> line.stripLeading().startsWith("//"));
        for (int i = 0; i < lines.length; i++) {
            int p = 0;
            while (p < lines[i].length()
                    && (lines[i].charAt(p) == ' ' || lines[i].charAt(p) == '\t')) p++;
            if (remove) {
                int after = p + 2;
                if (after < lines[i].length() && lines[i].charAt(after) == ' ') after++;
                lines[i] = lines[i].substring(0, p) + lines[i].substring(after);
            } else lines[i] = lines[i].substring(0, p) + "// " + lines[i].substring(p);
        }
        edit(start, end, String.join("\n", lines));
        anchor = start;
        repaint();
    }

    public long lastPaintNanos() {
        return lastPaintNanos;
    }

    public long[] inputToPaintSamples() {
        int count = Math.min(sampleCount, inputSamples.length);
        return Arrays.copyOf(inputSamples, count);
    }

    public void resetInputToPaintSamples() {
        sampleCount = 0;
        pendingInputNanos = 0;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(cellWidth * 86, cellHeight * 28);
    }

    private void metrics() {
        fontMetrics = getFontMetrics(getFont());
        cellWidth = Math.max(1, fontMetrics.charWidth('M'));
        cellHeight = fontMetrics.getHeight() + 2;
        ascent = fontMetrics.getAscent() + 1;
        gutterWidth = cellWidth * Math.max(5, Integer.toString(document.lineCount()).length() + 2);
    }

    @Override
    protected void processKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_META) {
            commandDown = e.getID() != KeyEvent.KEY_RELEASED;
            if (!commandDown) clearNavigableRange();
            notifyHover();
        }
        if (keyHandler.test(e)) {
            e.consume();
            return;
        }
        if (e.getID() == KeyEvent.KEY_TYPED) {
            char c = e.getKeyChar();
            if (!e.isMetaDown()
                    && !e.isControlDown()
                    && c >= ' '
                    && c != 127
                    && composition.isEmpty()) {
                if (!readOnly) {
                    if (pendingInputNanos == 0) pendingInputNanos = System.nanoTime();
                    typeCharacter(c);
                }
                e.consume();
                return;
            }
        } else if (e.getID() == KeyEvent.KEY_PRESSED) {
            KeyStroke pressed = KeyStroke.getKeyStrokeForEvent(e);
            if (pressed.equals(acceptCompletionKey) && acceptInlineCompletion()) {
                e.consume();
                return;
            }
            if (pressed.equals(dismissCompletionKey) && inlineCompletion != null) {
                clearInlineCompletion();
                e.consume();
                return;
            }
            boolean shift = e.isShiftDown(), command = e.isMetaDown() || e.isControlDown();
            int code = e.getKeyCode();
            if (command) {
                switch (code) {
                    case KeyEvent.VK_A -> selectAll();
                    case KeyEvent.VK_C -> copy();
                    case KeyEvent.VK_X -> cut();
                    case KeyEvent.VK_V -> paste();
                    case KeyEvent.VK_Z -> {
                        if (shift) redo();
                        else undo();
                    }
                    case KeyEvent.VK_Y -> redo();
                    case KeyEvent.VK_SLASH -> toggleComment();
                    default -> {
                        if (!isMotion(code)
                                && code != KeyEvent.VK_BACK_SPACE
                                && code != KeyEvent.VK_DELETE) {
                            super.processKeyEvent(e);
                            return;
                        }
                    }
                }
                if (!isMotion(code)
                        && code != KeyEvent.VK_BACK_SPACE
                        && code != KeyEvent.VK_DELETE) {
                    e.consume();
                    return;
                }
            }
            int line = document.lineAt(caret);
            boolean handled = true;
            switch (code) {
                case KeyEvent.VK_LEFT -> {
                    int target =
                            e.isMetaDown()
                                    ? document.lineStart(line)
                                    : e.isAltDown() || e.isControlDown()
                                            ? wordLeft(caret)
                                            : !shift && anchor != caret
                                                    ? Math.min(caret, anchor)
                                                    : previous(caret);
                    moveTo(target, shift);
                }
                case KeyEvent.VK_RIGHT -> {
                    int target =
                            e.isMetaDown()
                                    ? document.lineEnd(line)
                                    : e.isAltDown() || e.isControlDown()
                                            ? wordRight(caret)
                                            : !shift && anchor != caret
                                                    ? Math.max(caret, anchor)
                                                    : next(caret);
                    moveTo(target, shift);
                }
                case KeyEvent.VK_UP -> {
                    if (e.isMetaDown()) moveTo(0, shift);
                    else moveVertical(-1, shift);
                }
                case KeyEvent.VK_DOWN -> {
                    if (e.isMetaDown()) moveTo(document.length(), shift);
                    else moveVertical(1, shift);
                }
                case KeyEvent.VK_HOME -> moveTo(command ? 0 : document.lineStart(line), shift);
                case KeyEvent.VK_END ->
                        moveTo(command ? document.length() : document.lineEnd(line), shift);
                case KeyEvent.VK_PAGE_UP ->
                        moveVertical(-Math.max(1, getHeight() / cellHeight - 1), shift);
                case KeyEvent.VK_PAGE_DOWN ->
                        moveVertical(Math.max(1, getHeight() / cellHeight - 1), shift);
                case KeyEvent.VK_BACK_SPACE -> {
                    if (caret != anchor) replaceSelection("");
                    else if (caret > 0)
                        edit(
                                e.isMetaDown()
                                        ? document.lineStart(line)
                                        : e.isAltDown() || e.isControlDown()
                                                ? wordLeft(caret)
                                                : previous(caret),
                                caret,
                                "");
                }
                case KeyEvent.VK_DELETE -> {
                    if (caret != anchor) replaceSelection("");
                    else if (caret < document.length())
                        edit(
                                caret,
                                e.isAltDown() || e.isControlDown() ? wordRight(caret) : next(caret),
                                "");
                }
                case KeyEvent.VK_ENTER -> {
                    if (command || e.isAltDown()) {
                        super.processKeyEvent(e);
                        return;
                    }
                    String prefix =
                            document.substring(
                                    document.lineStart(line),
                                    Math.min(caret, document.lineEnd(line)));
                    int spaces = 0;
                    while (spaces < prefix.length()
                            && (prefix.charAt(spaces) == ' ' || prefix.charAt(spaces) == '\t'))
                        spaces++;
                    String indent =
                            prefix.substring(0, spaces)
                                    + (prefix.stripTrailing().endsWith("{")
                                            ? " ".repeat(tabWidth)
                                            : "");
                    replaceSelection(document.lineEnding() + indent);
                }
                case KeyEvent.VK_TAB -> indent(shift);
                default -> handled = false;
            }
            if (handled) {
                e.consume();
                return;
            }
        }
        super.processKeyEvent(e);
    }

    private static boolean isMotion(int key) {
        return key == KeyEvent.VK_LEFT
                || key == KeyEvent.VK_RIGHT
                || key == KeyEvent.VK_UP
                || key == KeyEvent.VK_DOWN
                || key == KeyEvent.VK_HOME
                || key == KeyEvent.VK_END
                || key == KeyEvent.VK_PAGE_UP
                || key == KeyEvent.VK_PAGE_DOWN;
    }

    private void typeCharacter(char c) {
        String opens = "([{\"'", closes = ")] }\"'".replace(" ", "");
        int pair = opens.indexOf(c);
        if (autoClose
                && caret == anchor
                && caret < document.length()
                && document.charAt(caret) == c
                && closes.indexOf(c) >= 0) {
            moveTo(caret + 1, false);
            return;
        }
        if (autoClose && pair >= 0) {
            String selected = getSelectedText();
            int start = Math.min(caret, anchor);
            replaceSelection(c + selected + closes.charAt(pair));
            caret = start + 1 + selected.length();
            anchor = selected.isEmpty() ? caret : start + 1;
            onCaretChange.run();
            repaintCaret();
        } else replaceSelection(String.valueOf(c));
    }

    private int previous(int offset) {
        if (offset == 0) return 0;
        int before = offset - 1;
        if (before > 0
                && Character.isLowSurrogate(document.charAt(before))
                && Character.isHighSurrogate(document.charAt(before - 1))) before--;
        if (before > 0 && document.charAt(before) == '\n' && document.charAt(before - 1) == '\r')
            before--;
        return before;
    }

    private int next(int offset) {
        if (offset >= document.length()) return document.length();
        int after = offset + 1;
        if (after < document.length()
                && Character.isHighSurrogate(document.charAt(offset))
                && Character.isLowSurrogate(document.charAt(after))) after++;
        if (after < document.length()
                && document.charAt(offset) == '\r'
                && document.charAt(after) == '\n') after++;
        return after;
    }

    private int wordLeft(int offset) {
        while (offset > 0 && Character.isWhitespace(document.charAt(previous(offset))))
            offset = previous(offset);
        if (offset > 0 && !Character.isJavaIdentifierPart(document.charAt(previous(offset))))
            return previous(offset);
        while (offset > 0 && Character.isJavaIdentifierPart(document.charAt(previous(offset))))
            offset = previous(offset);
        return offset;
    }

    private int wordRight(int offset) {
        if (offset < document.length()
                && !Character.isJavaIdentifierPart(document.charAt(offset))
                && !Character.isWhitespace(document.charAt(offset))) return next(offset);
        while (offset < document.length()
                && Character.isJavaIdentifierPart(document.charAt(offset))) offset = next(offset);
        while (offset < document.length() && Character.isWhitespace(document.charAt(offset)))
            offset = next(offset);
        return offset;
    }

    private void selectWord() {
        clearInlineCompletion();
        clearNavigableRange();
        anchor = wordLeft(caret);
        caret = wordRight(anchor);
        onCaretChange.run();
        repaint();
    }

    private void edit(int start, int end, String value) {
        if (readOnly) return;
        if (pendingInputNanos == 0) pendingInputNanos = System.nanoTime();
        int firstLine = document.lineAt(start);
        boolean multiline = document.lineAt(end) != firstLine || value.indexOf('\n') >= 0;
        document.replace(start, end, value);
        caret = anchor = start + value.length();
        changed(firstLine, multiline);
    }

    private void changed(int firstLine, boolean multiline) {
        clearInlineCompletion();
        clearNavigableRange();
        if (emphasisTimer != null) emphasisTimer.stop();
        emphasisStart = emphasisEnd = -1;
        boolean localDamage = false;
        if (!multiline && firstLine < lexed.size()) {
            JavaLexer.State initial =
                    firstLine == 0 ? JavaLexer.State.NORMAL : lexed.get(firstLine - 1).endState();
            String text = document.lineText(firstLine);
            JavaLexer.Line next = JavaLexer.lex(text, initial);
            localDamage = next.endState() == lexed.get(firstLine).endState();
            if (localDamage) {
                lexed.set(firstLine, next);
                lineTexts.set(firstLine, text);
                lineColumns.set(firstLine, null);
            }
        }
        if (!localDamage) {
            int cached = Math.min(firstLine, lexed.size());
            lexed.subList(cached, lexed.size()).clear();
            lineTexts.subList(cached, lineTexts.size()).clear();
            lineColumns.subList(cached, lineColumns.size()).clear();
        }
        setDiagnostics(List.of());
        preferredColumn = -1;
        int previousTop = topLine, previousLeft = leftColumn;
        metrics();
        ensureCaretVisible();
        onChange.run();
        onCaretChange.run();
        if (multiline || previousTop != topLine || previousLeft != leftColumn) repaint();
        else
            repaint(
                    0,
                    Math.max(0, (firstLine - topLine) * cellHeight),
                    getWidth(),
                    localDamage ? cellHeight : getHeight());
    }

    private void moveTo(int target, boolean extend) {
        clearInlineCompletion();
        clearNavigableRange();
        repaintMatchingBrackets();
        int oldLine = document.lineAt(caret), oldTop = topLine, oldLeft = leftColumn;
        boolean selected = caret != anchor || extend;
        caret = Math.clamp(target, 0, document.length());
        if (!extend) anchor = caret;
        preferredColumn = -1;
        ensureCaretVisible();
        onCaretChange.run();
        if (selected || topLine != oldTop || leftColumn != oldLeft) repaint();
        else {
            repaint(0, (oldLine - topLine) * cellHeight, getWidth(), cellHeight);
            repaintCaret();
        }
        repaintMatchingBrackets();
    }

    private void moveVertical(int delta, boolean extend) {
        int column = preferredColumn >= 0 ? preferredColumn : caretColumn() - 1;
        int line = Math.clamp(document.lineAt(caret) + delta, 0, document.lineCount() - 1);
        moveTo(document.lineStart(line) + offsetForColumn(document.lineText(line), column), extend);
        preferredColumn = column;
    }

    private void ensureCaretVisible() {
        int line = document.lineAt(caret), column = caretColumn() - 1;
        int visibleLines = Math.max(1, getHeight() / cellHeight),
                visibleColumns = Math.max(1, (getWidth() - gutterWidth) / cellWidth - 1);
        if (line < topLine) topLine = line;
        else if (line >= topLine + visibleLines) topLine = line - visibleLines + 1;
        if (column < leftColumn) leftColumn = column;
        else if (column >= leftColumn + visibleColumns) leftColumn = column - visibleColumns + 1;
    }

    public int offsetAt(Point point) {
        int line =
                Math.clamp(
                        topLine + Math.max(0, point.y) / cellHeight, 0, document.lineCount() - 1);
        int col = Math.max(0, leftColumn + (point.x - gutterWidth + cellWidth / 2) / cellWidth);
        return document.lineStart(line) + offsetForColumn(document.lineText(line), col);
    }

    private int symbolOffsetAt(Point point) {
        // Navigation targets the glyph under the pointer, rather than the nearest caret boundary.
        if (point.x < gutterWidth || point.y < 0 || point.y >= getHeight()) return -1;
        int line = topLine + point.y / cellHeight;
        if (line >= document.lineCount()) return -1;
        int offset = offsetAt(new Point(point.x - cellWidth / 2, point.y));
        if (offset >= document.lineEnd(line)) return -1;
        int cp = document.charAt(offset);
        if (Character.isHighSurrogate((char) cp)
                && offset + 1 < document.length()
                && Character.isLowSurrogate(document.charAt(offset + 1)))
            cp = Character.toCodePoint((char) cp, document.charAt(offset + 1));
        return Character.isJavaIdentifierPart(cp) ? offset : -1;
    }

    private int width(int cp, int column) {
        return cp == '\t'
                ? tabWidth - column % tabWidth
                : cp < 128
                        ? 1
                        : Math.max(1, (fontMetrics.charWidth(cp) + cellWidth / 2) / cellWidth);
    }

    private int visualColumn(String text, int end) {
        int column = 0;
        for (int i = 0; i < Math.min(end, text.length()); ) {
            int cp = text.codePointAt(i);
            column += width(cp, column);
            i += Character.charCount(cp);
        }
        return column;
    }

    private int offsetForColumn(String text, int wanted) {
        int column = 0, i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i), w = width(cp, column);
            if (column + w > wanted) break;
            column += w;
            i += Character.charCount(cp);
        }
        return i;
    }

    private JavaLexer.Line lexicalLine(int line) {
        while (lexed.size() <= line) {
            int index = lexed.size();
            String text = document.lineText(index);
            JavaLexer.State state =
                    index == 0 ? JavaLexer.State.NORMAL : lexed.get(index - 1).endState();
            lineTexts.add(text);
            lexed.add(JavaLexer.lex(text, state));
            lineColumns.add(null);
        }
        return lexed.get(line);
    }

    private int[] columns(int line) {
        int[] result = lineColumns.get(line);
        if (result != null) return result;
        String text = lineTexts.get(line);
        result = new int[text.length() + 1];
        int column = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i), count = Character.charCount(cp);
            result[i] = column;
            if (count == 2) result[i + 1] = column;
            column += width(cp, column);
            i += count;
            result[i] = column;
        }
        lineColumns.set(line, result);
        return result;
    }

    private Color tokenColor(JavaLexer.Kind kind) {
        return switch (kind) {
            case KEYWORD -> keyword;
            case DECLARATION, TEXT -> identifier;
            case OPERATOR -> operator;
            case STRING -> string;
            case NUMBER -> number;
            case ANNOTATION -> annotation;
            case COMMENT -> comment;
        };
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        long started = System.nanoTime();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(getBackground());
            Rectangle clip = g.getClipBounds();
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            g.setFont(getFont());
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    antialias
                            ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                            : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            int first = Math.max(topLine, topLine + clip.y / cellHeight),
                    last =
                            Math.min(
                                    document.lineCount() - 1,
                                    topLine + (clip.y + clip.height) / cellHeight);
            int selectionStart = Math.min(caret, anchor), selectionEnd = Math.max(caret, anchor);
            for (int line = first; line <= last; line++) {
                int y = (line - topLine) * cellHeight;
                JavaLexer.Line tokens = lexicalLine(line);
                String text = lineTexts.get(line);
                int start = document.lineStart(line);
                int[] columns = columns(line);
                g.setColor(comment);
                String number = Integer.toString(line + 1);
                g.drawString(
                        number, gutterWidth - cellWidth - number.length() * cellWidth, y + ascent);
                Graphics2D row =
                        (Graphics2D)
                                g.create(
                                        gutterWidth,
                                        y,
                                        Math.max(0, getWidth() - gutterWidth),
                                        cellHeight);
                try {
                    if (emphasisStart >= 0
                            && emphasisEnd > start
                            && emphasisStart <= start + text.length()) {
                        int a = columns[Math.max(0, emphasisStart - start)];
                        int b = columns[Math.min(text.length(), emphasisEnd - start)];
                        row.setColor(selection);
                        row.fillRect(
                                (a - leftColumn) * cellWidth,
                                0,
                                Math.max(cellWidth, (b - a) * cellWidth),
                                cellHeight);
                    }
                    if (selectionStart != selectionEnd
                            && selectionEnd > start
                            && selectionStart <= start + text.length()) {
                        int a = columns[Math.max(0, selectionStart - start)],
                                b = columns[Math.min(text.length(), selectionEnd - start)];
                        if (selectionEnd > start + text.length()) b++;
                        row.setColor(selection);
                        row.fillRect(
                                (a - leftColumn) * cellWidth,
                                0,
                                Math.max(cellWidth, (b - a) * cellWidth),
                                cellHeight);
                    }
                    for (JavaLexer.Span span : tokens.spans()) {
                        int column = columns[span.start()];
                        if (column > leftColumn + getWidth() / cellWidth) break;
                        if (columns[span.end()] < leftColumn) continue;
                        int from = span.start(), to = span.end();
                        if (column < leftColumn)
                            from = Math.max(from, offsetForColumn(text, leftColumn));
                        if (columns[to] > leftColumn + getWidth() / cellWidth + 1)
                            to =
                                    Math.min(
                                            to,
                                            offsetForColumn(
                                                    text, leftColumn + getWidth() / cellWidth + 1));
                        row.setColor(tokenColor(span.kind()));
                        drawRun(row, text, from, to, columns[from]);
                    }
                    paintSelectedText(row, text, columns, start, selectionStart, selectionEnd);
                    if (emphasisStart >= 0)
                        paintSelectedText(row, text, columns, start, emphasisStart, emphasisEnd);
                    for (CompilerDiagnostic diagnostic : diagnostics) {
                        if (diagnostic.line() == line + 1
                                && "ERROR".equalsIgnoreCase(diagnostic.kind())) {
                            int a = visualColumn(text, (int) Math.max(0, diagnostic.column() - 1));
                            int endOffset =
                                    diagnostic.end() > diagnostic.start()
                                            ? (int)
                                                    Math.min(
                                                            text.length(), diagnostic.end() - start)
                                            : Math.min(text.length(), (int) diagnostic.column());
                            int b = Math.max(a + 1, visualColumn(text, endOffset));
                            row.setColor(error);
                            for (int x = (a - leftColumn) * cellWidth;
                                    x < (b - leftColumn) * cellWidth;
                                    x += 4) row.drawLine(x, cellHeight - 2, x + 2, cellHeight - 1);
                        }
                    }
                    if (navigationStart >= 0
                            && navigationEnd > start
                            && navigationStart <= start + text.length()) {
                        int a = columns[Math.max(0, navigationStart - start)];
                        int b = columns[Math.min(text.length(), navigationEnd - start)];
                        row.setColor(definition);
                        row.drawLine(
                                (a - leftColumn) * cellWidth,
                                cellHeight - 2,
                                (b - leftColumn) * cellWidth,
                                cellHeight - 2);
                    }
                    String ghost = "";
                    if (inlineCompletion != null && document.lineAt(caret) == line) {
                        ghost =
                                inlineCompletion
                                        .insertion()
                                        .substring(caret - inlineCompletion.start());
                        row.setColor(ghostColor());
                        drawRun(row, ghost, 0, ghost.length(), columns[caret - start]);
                    }
                    if (document.lineAt(caret) == line && isFocusOwner()) {
                        int x =
                                (columns[Math.min(text.length(), caret - start)] - leftColumn)
                                        * cellWidth;
                        row.setColor(cursor);
                        row.fillRect(x, 1, cellWidth, cellHeight - 2);
                        if (composition.isEmpty()
                                && ghost.isEmpty()
                                && caret - start < text.length()) {
                            int cp = text.codePointAt(caret - start);
                            if (cp != '\t') {
                                row.setColor(getBackground());
                                row.drawString(
                                        text.substring(
                                                caret - start,
                                                caret - start + Character.charCount(cp)),
                                        x,
                                        ascent);
                            }
                        } else if (!ghost.isEmpty()) {
                            row.setColor(getBackground());
                            row.drawString(
                                    ghost.substring(0, Character.charCount(ghost.codePointAt(0))),
                                    x,
                                    ascent);
                        }
                        if (!composition.isEmpty()) {
                            row.setColor(cursor);
                            row.drawString(composition, x, ascent);
                            row.drawLine(
                                    x,
                                    cellHeight - 2,
                                    x + row.getFontMetrics().stringWidth(composition),
                                    cellHeight - 2);
                        }
                    }
                } finally {
                    row.dispose();
                }
            }
            if (isFocusOwner()) {
                g.setColor(keyword);
                for (int offset : matchingBrackets()) {
                    int line = document.lineAt(offset);
                    if (line < topLine || line > last || line >= lineColumns.size()) continue;
                    int x =
                            gutterWidth
                                    + (columns(line)[offset - document.lineStart(line)]
                                                    - leftColumn)
                                            * cellWidth;
                    if (x >= gutterWidth)
                        g.drawRect(
                                x,
                                (line - topLine) * cellHeight + 1,
                                cellWidth - 1,
                                cellHeight - 3);
                }
            }
            g.setColor(comment);
            g.drawLine(gutterWidth - cellWidth / 2, 0, gutterWidth - cellWidth / 2, getHeight());
        } finally {
            g.dispose();
            lastPaintNanos = System.nanoTime() - started;
            if (pendingInputNanos != 0) {
                inputSamples[sampleCount++ % inputSamples.length] =
                        System.nanoTime() - pendingInputNanos;
                pendingInputNanos = 0;
            }
        }
    }

    private Color ghostColor() {
        return ghost;
    }

    private void paintSelectedText(
            Graphics2D row, String text, int[] columns, int lineStart, int from, int to) {
        int begin = Math.max(0, from - lineStart), end = Math.min(text.length(), to - lineStart);
        if (begin >= end) return;
        row.setColor(selectionForeground);
        drawRun(row, text, begin, end, columns[begin]);
    }

    private void drawRun(Graphics2D g, String text, int from, int to, int initialColumn) {
        int runStart = from, column = initialColumn, runColumn = column;
        for (int i = from; i < to; ) {
            int cp = text.codePointAt(i), count = Character.charCount(cp), w = width(cp, column);
            if (cp == '\t' || count == 2 || w != 1) {
                if (i > runStart)
                    g.drawString(
                            text.substring(runStart, i),
                            (runColumn - leftColumn) * cellWidth,
                            ascent);
                if (cp != '\t')
                    g.drawString(
                            text.substring(i, i + count),
                            (column - leftColumn) * cellWidth,
                            ascent);
                runStart = i + count;
                runColumn = column + w;
            }
            column += w;
            i += count;
        }
        if (to > runStart)
            g.drawString(
                    text.substring(runStart, to), (runColumn - leftColumn) * cellWidth, ascent);
    }

    private void repaintCaret() {
        repaint(0, (document.lineAt(caret) - topLine) * cellHeight, getWidth(), cellHeight);
    }

    private void repaintMatchingBrackets() {
        for (int offset : matchingBrackets())
            repaint(0, (document.lineAt(offset) - topLine) * cellHeight, getWidth(), cellHeight);
    }

    private int[] matchingBrackets() {
        String braces = "()[]{}";
        int origin =
                caret < document.length() && braces.indexOf(document.charAt(caret)) >= 0
                        ? caret
                        : caret > 0 && braces.indexOf(document.charAt(caret - 1)) >= 0
                                ? caret - 1
                                : -1;
        if (origin < 0 || !codeAt(origin)) return new int[0];
        int type = braces.indexOf(document.charAt(origin)), direction = type % 2 == 0 ? 1 : -1;
        char current = braces.charAt(type), partner = braces.charAt(type ^ 1);
        int depth = 1, limit = Math.min(document.length(), 20_000);
        for (int offset = origin + direction, n = 0;
                offset >= 0 && offset < document.length() && n < limit;
                offset += direction, n++) {
            int line = document.lineAt(offset);
            if (line >= lexed.size()) break; // Caret motion never forces lexical parsing.
            char c = document.charAt(offset);
            if ((c == current || c == partner) && codeAt(offset)) {
                if (c == current) depth++;
                else if (--depth == 0) return new int[] {origin, offset};
            }
        }
        return new int[0];
    }

    private boolean codeAt(int offset) {
        int line = document.lineAt(offset), column = offset - document.lineStart(line);
        if (line >= lexed.size()) return false;
        for (JavaLexer.Span span : lexed.get(line).spans()) {
            if (span.start() > column) break;
            if (column < span.end())
                return span.kind() != JavaLexer.Kind.COMMENT
                        && span.kind() != JavaLexer.Kind.STRING;
        }
        return true;
    }

    @Override
    public void inputMethodTextChanged(InputMethodEvent event) {
        if (readOnly) {
            composition = "";
            event.consume();
            return;
        }
        clearInlineCompletion();
        AttributedCharacterIterator iterator = event.getText();
        StringBuilder text = new StringBuilder();
        if (iterator != null)
            for (char c = iterator.first();
                    c != AttributedCharacterIterator.DONE;
                    c = iterator.next()) text.append(c);
        int committed = Math.min(event.getCommittedCharacterCount(), text.length());
        if (committed > 0) replaceSelection(text.substring(0, committed));
        composition = text.substring(committed);
        repaintCaret();
        event.consume();
    }

    @Override
    public void caretPositionChanged(InputMethodEvent event) {
        event.consume();
    }

    @Override
    public InputMethodRequests getInputMethodRequests() {
        return this;
    }

    @Override
    public Rectangle getTextLocation(TextHitInfo offset) {
        Point point =
                new Point(
                        gutterWidth + (caretColumn() - 1 - leftColumn) * cellWidth,
                        (caretLine() - 1 - topLine) * cellHeight);
        if (isShowing()) SwingUtilities.convertPointToScreen(point, this);
        return new Rectangle(point.x, point.y, cellWidth, cellHeight);
    }

    @Override
    public TextHitInfo getLocationOffset(int x, int y) {
        return null;
    }

    @Override
    public int getInsertPositionOffset() {
        return caret;
    }

    @Override
    public AttributedCharacterIterator getCommittedText(
            int begin, int end, AttributedCharacterIterator.Attribute[] attributes) {
        return new AttributedString(document.substring(begin, end)).getIterator();
    }

    @Override
    public int getCommittedTextLength() {
        return document.length();
    }

    @Override
    public AttributedCharacterIterator cancelLatestCommittedText(
            AttributedCharacterIterator.Attribute[] attributes) {
        return null;
    }

    @Override
    public AttributedCharacterIterator getSelectedText(
            AttributedCharacterIterator.Attribute[] attributes) {
        return new AttributedString(getSelectedText()).getIterator();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleEditor();
        return accessibleContext;
    }

    protected final class AccessibleEditor extends AccessibleJComponent {
        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.TEXT;
        }

        @Override
        public String getAccessibleName() {
            return "Java source editor";
        }

        @Override
        public String getAccessibleDescription() {
            return "Line " + caretLine() + ", column " + caretColumn();
        }
    }
}
