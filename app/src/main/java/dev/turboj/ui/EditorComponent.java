package dev.turboj.ui;

import dev.turboj.compiler.CompilerDiagnostic;
import dev.turboj.editor.document.PieceTable;
import dev.turboj.editor.highlighting.JavaLexer;
import dev.turboj.ui.themes.Theme;
import dev.turboj.testing.intelligence.LineIntelligence.Lane;
import dev.turboj.testing.intelligence.LineIntelligence.Snapshot;
import dev.turboj.ui.intelligence.LineGutter;

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
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
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
    private final LineGutter lineGutter = new LineGutter();
    public record LineInspection(int line, Lane lane, Integer deletionAnchor) {}
    private Consumer<LineInspection> onInspectLine = ignored -> {};
    private Runnable onChange = () -> {}, onCaretChange = () -> {};
    private Color foreground, keyword, string, comment, selection, cursor, error;
    private Color ghost,
            aiGhost,
            selectionForeground,
            number,
            annotation,
            definition,
            identifier,
            operator;
    private boolean antialias, autoClose = true;
    private boolean xmlSyntax;

    public void setXmlSyntax(boolean xml) {
        if (xmlSyntax == xml) return;
        xmlSyntax = xml;
        lexed.clear(); lineTexts.clear(); lineColumns.clear();
        repaint();
    }

    public boolean xmlSyntax() { return xmlSyntax; }

    private JavaLexer.Line lex(String text, JavaLexer.State state) {
        return xmlSyntax ? XmlLexer.lex(text, state) : JavaLexer.lex(text, state);
    }
    private List<CompilerDiagnostic> diagnostics = List.of();
    private final List<JavaLexer.Line> lexed = new ArrayList<>();
    private final List<String> lineTexts = new ArrayList<>();
    private final List<int[]> lineColumns = new ArrayList<>();
    private String composition = "";
    private char pendingHighSurrogate;
    private long pendingInputNanos, lastPaintNanos;
    private final long[] inputSamples = new long[1024];
    private int sampleCount;
    private InlineCompletion inlineCompletion;
    // A bounded visual projection. Source offsets, the piece table and its line index stay intact.
    private String[] aiLines;
    private int aiLine, aiCaretInLine, aiScrollOffset;
    private boolean externalCommandBindings;
    private final PressedShortcutDisplay pressedShortcut = new PressedShortcutDisplay(this::repaint);
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

    /** Directional UTF-16 selection; the first selection is the primary caret. */
    public record Selection(int caret, int anchor) {
        public int start() { return Math.min(caret, anchor); }
        public int end() { return Math.max(caret, anchor); }
        public boolean empty() { return caret == anchor; }
    }

    private final List<Selection> secondaryCarets = new ArrayList<>();
    private Clipboard clipboard;
    private record EditingLocations(Location before, Location after,
            List<Selection> beforeSelections, List<Selection> afterSelections) {}
    private final java.util.WeakHashMap<PieceTable, java.util.LinkedHashMap<Long, EditingLocations>>
            editingHistory = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<PieceTable, List<Selection>> bufferSelections =
            new java.util.WeakHashMap<>();

    private record FormattingLocations(Location before, Location after) {}

    private final java.util.WeakHashMap<
                    PieceTable, java.util.LinkedHashMap<Long, FormattingLocations>>
            formattingHistory = new java.util.WeakHashMap<>();

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
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        repaintCaret();
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        clearInlineCompletion();
                        pendingHighSurrogate = 0;
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
                        LineGutter.Hit hit = gutterHit(e.getPoint());
                        if (hit != null && SwingUtilities.isLeftMouseButton(e)) {
                            onInspectLine.accept(new LineInspection(hit.line(), hit.lane(), hit.deletionAnchor()));
                            return;
                        }
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
                        else if (isAIInlineCompletion())
                            aiScrollOffset =
                                    Math.clamp(
                                                    visualTopLine() + amount,
                                                    0,
                                                    document.lineCount() + aiLines.length - 2)
                                            - baseVisualTopLine();
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
        finishTemplate();
        document = new PieceTable(value);
        readOnly = false;
        caret = anchor = topLine = leftColumn = 0;
        secondaryCarets.clear();
        resetVisualState();
        onChange.run();
        onCaretChange.run();
        repaint();
    }

    private void resetVisualState() {
        // Project-level lane allocation survives a buffer change while new evidence loads.
        // The controller explicitly installs null when the intelligence context closes.
        lineGutter.invalidate();
        clearInlineCompletion();
        clearNavigableRange();
        if (emphasisTimer != null) emphasisTimer.stop();
        emphasisStart = emphasisEnd = -1;
        lexed.clear();
        lineTexts.clear();
        lineColumns.clear();
        diagnostics = List.of();
        composition = "";
        pendingHighSurrogate = 0;
        pendingInputNanos = 0;
        preferredColumn = -1;
        metrics();
    }

    public EditorState captureState() {
        bufferSelections.put(document, selections());
        return new EditorState(document, captureLocation(), readOnly);
    }

    public void restoreState(EditorState state) {
        finishTemplate();
        document = Objects.requireNonNull(state.document());
        readOnly = state.readOnly();
        // Reset coordinates before invalidating visuals: the previous buffer may have been longer.
        caret = anchor = topLine = leftColumn = 0;
        secondaryCarets.clear();
        resetVisualState();
        restoreLocation(state.location());
        setSelectionsInternal(bufferSelections.getOrDefault(document,
                List.of(new Selection(caret, anchor))));
        onChange.run();
    }

    public Location captureLocation() {
        return new Location(caret, anchor, topLine, leftColumn);
    }

    public void restoreLocation(Location location) {
        clearInlineCompletion();
        clearNavigableRange();
        secondaryCarets.clear();
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

    public void setOnInspectLine(Consumer<LineInspection> callback) {
        onInspectLine = callback == null ? ignored -> {} : callback;
    }

    public Snapshot lineIntelligence() { return lineGutter.snapshot(); }

    /** The controller supplies evidence mapped to this exact editor generation. */
    public boolean setLineIntelligence(Snapshot snapshot) {
        if (snapshot != null && snapshot.source().version() != version()) return false;
        Snapshot previous = lineGutter.snapshot();
        int width = gutterWidth;
        lineGutter.install(snapshot);
        metrics();
        if (width != gutterWidth) { ensureCaretVisible(); revalidate(); repaint(); }
        else if (snapshot == null || previous == null || isAIInlineCompletion()
                || previous.lines().size() + snapshot.lines().size() > 4096
                || !previous.deletions().equals(snapshot.deletions()))
            repaint(0, 0, lineGutter.cells() * cellWidth, getHeight());
        else {
            var lines = new java.util.HashSet<>(previous.lines().keySet());
            lines.addAll(snapshot.lines().keySet());
            for (int line : lines) if (!Objects.equals(previous.line(line), snapshot.line(line)))
                repaint(0, (line - 1 - topLine) * cellHeight, lineGutter.cells() * cellWidth, cellHeight);
        }
        if (accessibleContext != null) accessibleContext.firePropertyChange(
                AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY, null, getAccessibleContext().getAccessibleDescription());
        return true;
    }

    public void inspectLineIntelligence(Lane lane) {
        onInspectLine.accept(new LineInspection(caretLine(), lane, null));
    }

    public String lineIntelligenceDescription(int line, Lane lane) { return lineGutter.describe(line, lane, null); }

    public Rectangle lineIntelligenceBounds(int line, Lane lane) {
        if (line < 1 || line > document.lineCount()) return new Rectangle();
        int y = offsetBounds(document.lineStart(line - 1)).y;
        return lineGutter.bounds(lane, cellWidth, y, cellHeight);
    }

    private LineGutter.Hit gutterHit(Point point) {
        if (point.x < 0 || point.x >= lineGutter.cells() * cellWidth || point.y < 0 || point.y >= getHeight()) return null;
        int visual = visualTopLine() + point.y / cellHeight;
        int logical = visual;
        if (isAIInlineCompletion()) {
            int lastGhost = aiLine + aiLines.length - 1;
            if (visual > aiLine && visual <= lastGhost) return null;
            if (visual > lastGhost) logical -= aiLines.length - 1;
        }
        return lineGutter.hit(point, logical + 1, (visual - visualTopLine()) * cellHeight,
                document.lineCount(), cellWidth, cellHeight);
    }

    @Override public String getToolTipText(MouseEvent event) {
        LineGutter.Hit hit = gutterHit(event.getPoint());
        return hit == null ? null : lineGutter.describe(hit.line(), hit.lane(), hit.deletionAnchor());
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
        if (isAIInlineCompletion()) {
            if (line > aiLine) line += aiLines.length - 1;
            else if (line == aiLine && offset > caret) {
                line += aiLines.length - 1;
                column =
                        columnAfter(
                                document.lineText(aiLine),
                                aiCaretInLine,
                                offset - document.lineStart(aiLine),
                                aiSuffixColumn());
            }
        }
        return new Rectangle(
                gutterWidth + (column - leftColumn) * cellWidth,
                (line - visualTopLine()) * cellHeight,
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

    /**
     * Displays an insertion-only AI continuation using virtual rows. Existing suffix text moves
     * visually after the suggestion, without being removed, rewritten or entered into undo history.
     */
    public boolean setAIInlineCompletion(InlineCompletion proposal) {
        if (!validCompletion(proposal)
                || proposal.start() != caret
                || proposal.end() != caret
                || proposal.caretInInsertion() != proposal.insertion().length()
                || !proposal.additionalEdits().isEmpty()
                || proposal.insertion().isEmpty()
                || proposal.insertion().length() > 32_768) return false;
        for (int i = 0; i < proposal.insertion().length(); i++)
            if (proposal.insertion().charAt(i) == '\r'
                    && (i + 1 == proposal.insertion().length()
                            || proposal.insertion().charAt(i + 1) != '\n')) return false;
        String normalized = proposal.insertion().replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length > 128) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= normalized.length()
                        || !Character.isLowSurrogate(normalized.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        boolean replacingAI = isAIInlineCompletion();
        inlineCompletion = proposal;
        aiLines = lines;
        aiLine = document.lineAt(caret);
        aiCaretInLine = caret - document.lineStart(aiLine);
        if (!replacingAI) aiScrollOffset = 0;
        aiScrollOffset =
                Math.clamp(visualTopLine(), 0, document.lineCount() + lines.length - 2)
                        - baseVisualTopLine();
        repaintAIRegion();
        return true;
    }

    public boolean isAIInlineCompletion() {
        return inlineCompletion != null && aiLines != null;
    }

    /** Accepts leading whitespace plus the next Java word or punctuation code point. */
    public boolean acceptNextAIWord() {
        if (!isAIInlineCompletion() || !validCompletion(inlineCompletion)) return false;
        InlineCompletion proposal = inlineCompletion;
        PieceTable acceptedDocument = document;
        String insertion = proposal.insertion();
        int end = 0;
        while (end < insertion.length() && Character.isWhitespace(insertion.codePointAt(end)))
            end += Character.charCount(insertion.codePointAt(end));
        if (end < insertion.length()) {
            if (Character.isJavaIdentifierPart(insertion.codePointAt(end))) {
                do {
                    end += Character.charCount(insertion.codePointAt(end));
                } while (end < insertion.length()
                        && Character.isJavaIdentifierPart(insertion.codePointAt(end)));
            } else end += Character.charCount(insertion.codePointAt(end));
        }
        String accepted = insertion.substring(0, end), remaining = insertion.substring(end);
        clearInlineCompletion();
        if (!applyCompletion(
                new InlineCompletion(
                        proposal.version(), caret, caret, caret, accepted, accepted.length())))
            return false;
        // Synchronous listeners can navigate or edit during acceptance. Never place the remainder
        // in that new document/caret; asynchronous responses still require coordinator tickets.
        if (!remaining.isEmpty()
                && document == acceptedDocument
                && version() == proposal.version() + 1
                && caret == proposal.caret() + accepted.length()
                && inlineCompletion == null)
            setAIInlineCompletion(
                    new InlineCompletion(
                            version(), caret, caret, caret, remaining, remaining.length()));
        return true;
    }

    public void clearInlineCompletion() {
        if (inlineCompletion != null) {
            boolean ai = isAIInlineCompletion();
            boolean scrolled = aiScrollOffset != 0;
            inlineCompletion = null;
            aiLines = null;
            aiScrollOffset = 0;
            if (ai && scrolled) repaint();
            else if (ai) repaintAIRegion();
            else repaintCaret();
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
        return applySemanticEdits(proposal.version(), edits, target, target);
    }

    /** Apply sparse formatter edits on the EDT, preserving source positions and one-step undo. */
    public boolean applyFormatting(long expectedVersion, List<dev.javafmt.core.TextEdit> edits) {
        if (readOnly || !composition.isEmpty() || expectedVersion != version()) return false;
        Objects.requireNonNull(edits);
        int previousEnd = -1;
        var replacements = new ArrayList<PieceTable.Edit>(edits.size());
        for (var edit : edits) {
            if (edit.start() < previousEnd
                    || edit.end() > document.length()
                    || !characterBoundary(edit.start())
                    || !characterBoundary(edit.end())) return false;
            previousEnd = edit.end();
            replacements.add(new PieceTable.Edit(edit.start(), edit.end(), edit.replacement()));
        }
        Location before = captureLocation();
        var beforeSelections = selections();
        var afterSelections = beforeSelections.stream().map(s -> new Selection(
                dev.javafmt.core.TextEdit.mapOffset(s.caret(), edits),
                dev.javafmt.core.TextEdit.mapOffset(s.anchor(), edits))).toList();
        int visibleStart = document.lineStart(Math.min(topLine, document.lineCount() - 1));
        int mappedCaret = dev.javafmt.core.TextEdit.mapOffset(caret, edits);
        int mappedAnchor = dev.javafmt.core.TextEdit.mapOffset(anchor, edits);
        int mappedVisibleStart = dev.javafmt.core.TextEdit.mapOffset(visibleStart, edits);
        PieceTable.HistoryResult transaction = document.replaceAll(replacements);
        if (transaction == null) return true;
        Location after =
                new Location(
                        mappedCaret,
                        mappedAnchor,
                        document.lineAt(Math.clamp(mappedVisibleStart, 0, document.length())),
                        leftColumn);
        caret = after.caret();
        anchor = after.anchor();
        topLine = after.topLine();
        changed(document.lineAt(Math.min(edits.getFirst().start(), document.length())), true);
        restoreLocation(after);
        setSelectionsInternal(afterSelections);
        var history =
                formattingHistory.computeIfAbsent(
                        document, ignored -> new java.util.LinkedHashMap<>());
        history.put(transaction.id(), new FormattingLocations(before, after));
        while (history.size() > 2000) history.remove(history.keySet().iterator().next());
        var allHistory = editingHistory.computeIfAbsent(document, ignored -> new java.util.LinkedHashMap<>());
        allHistory.put(transaction.id(), new EditingLocations(before, after, beforeSelections, selections()));
        while (allHistory.size() > 2000) allHistory.remove(allHistory.keySet().iterator().next());
        return true;
    }

    private boolean characterBoundary(int offset) {
        return offset >= 0
                && offset <= document.length()
                && !(offset > 0
                        && offset < document.length()
                        && Character.isHighSurrogate(document.charAt(offset - 1))
                        && Character.isLowSurrogate(document.charAt(offset)))
                && !(offset > 0 && offset < document.length()
                        && document.charAt(offset - 1) == '\r' && document.charAt(offset) == '\n');
    }

    private boolean validCompletion(InlineCompletion proposal) {
        if (proposal == null
                || !secondaryCarets.isEmpty()
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
        int first = offsetBounds(start).y, last = offsetBounds(end).y;
        repaint(0, first, getWidth(), last - first + cellHeight);
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
        setForeground(foreground);
        lineGutter.setBackground(background);
        this.foreground = foreground;
        this.keyword = keyword;
        this.string = string;
        this.comment = comment;
        this.selection = selection;
        this.cursor = cursor;
        this.error = error;
        this.antialias = antialias;
        this.ghost = comment;
        this.aiGhost = comment;
        this.selectionForeground = foreground;
        this.number = string;
        this.annotation = string;
        this.definition = string;
        this.identifier = foreground;
        this.operator = foreground;
        repaint();
    }

    public void setTheme(Theme theme) {
        pressedShortcut.setTheme(theme);
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
        aiGhost = theme.color("intelligence", "aiGhost");
        selectionForeground = theme.color("editor", "selectionForeground");
        number = theme.color("syntax", "number");
        annotation = theme.color("syntax", "annotation");
        definition = theme.color("intelligence", "definition");
        identifier = theme.color("syntax", "identifier");
        operator = theme.color("syntax", "operator");
        lineGutter.setTheme(theme);
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
        if (isAIInlineCompletion()) {
            this.diagnostics = List.copyOf(diagnostics);
            repaint();
            return;
        }
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
        replaceSelections(ignored -> value);
    }

    /** When embedded, CommandJ owns action shortcuts; standalone editor tests retain defaults. */
    public void setExternalCommandBindings(boolean value) {
        externalCommandBindings = value;
    }

    public void setShowPressedShortcuts(boolean enabled) { pressedShortcut.setEnabled(enabled); }
    public boolean showsPressedShortcuts() { return pressedShortcut.enabled(); }
    public void showPressedShortcut(String shortcut) { pressedShortcut.show(shortcut); }
    public String pressedShortcutText() { return pressedShortcut.text(); }

    @Override public void removeNotify() {
        pressedShortcut.clear();
        super.removeNotify();
    }

    public boolean hasInputComposition() {
        return !composition.isEmpty();
    }

    public int selectionStart() { return Math.min(caret, anchor); }
    public int selectionEnd() { return Math.max(caret, anchor); }
    public int caretCount() { return 1 + secondaryCarets.size(); }

    public List<Selection> selections() {
        var result = new ArrayList<Selection>(secondaryCarets.size() + 1);
        result.add(new Selection(caret, anchor));
        result.addAll(secondaryCarets);
        return List.copyOf(result);
    }

    public void setSelection(int anchor, int caret) {
        setSelections(List.of(new Selection(caret, anchor)));
    }

    /** Overlapping selections and duplicate carets are combined before any edit. */
    public void setSelections(List<Selection> values) {
        for (var s : values) if (!characterBoundary(s.caret()) || !characterBoundary(s.anchor()))
            throw new IllegalArgumentException("Selection splits a Unicode character or line separator");
        setSelectionsInternal(values);
        clearInlineCompletion();
        clearNavigableRange();
        ensureCaretVisible();
        onCaretChange.run();
        repaint();
    }

    private void setSelectionsInternal(List<Selection> values) {
        var normalized = normalizeSelections(values, document.length());
        caret = normalized.getFirst().caret();
        anchor = normalized.getFirst().anchor();
        secondaryCarets.clear();
        secondaryCarets.addAll(normalized.subList(1, normalized.size()));
    }

    private List<Selection> normalizeSelections(List<Selection> values, int length) {
        if (values.isEmpty()) throw new IllegalArgumentException("At least one caret is required");
        var sorted = new ArrayList<Selection>(values);
        for (var s : sorted)
            if (s.start() < 0 || s.end() > length)
                throw new IllegalArgumentException("Selection outside document");
        var primary = values.getFirst();
        sorted.sort(java.util.Comparator.comparingInt(Selection::start).thenComparingInt(Selection::end));
        var merged = new ArrayList<Selection>();
        for (var s : sorted) {
            if (!merged.isEmpty() && (s.start() < merged.getLast().end()
                    || (s.start() == merged.getLast().end() && (s.empty() || merged.getLast().empty())))) {
                var p = merged.removeLast();
                int start = p.start(), end = Math.max(p.end(), s.end());
                boolean reverse = p.caret() < p.anchor();
                if (s.equals(primary)) reverse = s.caret() < s.anchor();
                merged.add(reverse ? new Selection(start, end) : new Selection(end, start));
            } else merged.add(s);
        }
        for (int i = 0; i < merged.size(); i++) {
            var s = merged.get(i);
            if (s.start() <= primary.start() && s.end() >= primary.end()) {
                merged.remove(i);
                merged.addFirst(s);
                break;
            }
        }
        return List.copyOf(merged);
    }

    /** Apply validated edits and final selections as one logical undo transaction. */
    public boolean applyEdits(long expectedVersion, List<TextEdit> edits, List<Selection> after) {
        if (readOnly || hasInputComposition() || expectedVersion != version()) return false;
        int end = -1, resultingLength = document.length();
        var replacements = new ArrayList<PieceTable.Edit>(edits.size());
        for (var edit : edits) {
            if (edit.start() < end || edit.end() < edit.start()
                    || !characterBoundary(edit.start()) || !characterBoundary(edit.end())) return false;
            end = edit.end();
            replacements.add(new PieceTable.Edit(edit.start(), edit.end(), edit.text()));
            resultingLength += edit.text().length() - (edit.end() - edit.start());
        }
        final List<Selection> normalized;
        try { normalized = normalizeSelections(after, resultingLength); }
        catch (IllegalArgumentException invalidSelection) { return false; }
        for (var selection : normalized)
            if (!resultBoundary(selection.caret(), resultingLength, edits)
                    || !resultBoundary(selection.anchor(), resultingLength, edits)) return false;
        Location before = captureLocation();
        var beforeSelections = selections();
        var transaction = document.replaceAll(replacements);
        remapTemplate(edits);
        setSelectionsInternal(normalized);
        if (transaction == null) {
            onCaretChange.run();
            repaint();
            return true;
        }
        if (pendingInputNanos == 0) pendingInputNanos = System.nanoTime();
        changed(document.lineAt(Math.min(edits.getFirst().start(), document.length())),
                edits.size() > 1 || transaction.changes().stream().anyMatch(c ->
                        c.removed().contains("\n") || c.inserted().contains("\n")));
        var history = editingHistory.computeIfAbsent(document, ignored -> new java.util.LinkedHashMap<>());
        history.put(transaction.id(), new EditingLocations(before, captureLocation(),
                beforeSelections, selections()));
        while (history.size() > 2000) history.remove(history.keySet().iterator().next());
        return true;
    }

    private List<List<Selection>> templateStops = List.of();
    private int templateStop;
    private java.util.function.IntConsumer templateEntered = index -> {};
    private java.util.function.BiPredicate<Integer, Integer> templateLeaving = (index, next) -> true;

    /** Linked occurrences in one group use real multiple selections and one edit transaction. */
    public void startTemplate(List<List<Selection>> stops) {
        startTemplate(stops, index -> {}, (index, next) -> true);
    }

    public void startTemplate(List<List<Selection>> stops, java.util.function.IntConsumer entered,
            java.util.function.BiPredicate<Integer, Integer> leaving) {
        finishTemplate();
        if (stops.isEmpty()) return;
        for (var group : stops) {
            if (group.isEmpty()) throw new IllegalArgumentException("Empty template group");
            for (var range : group) if (range.start() < 0 || range.end() > document.length()
                    || !characterBoundary(range.start()) || !characterBoundary(range.end()))
                throw new IllegalArgumentException("Invalid template range");
        }
        templateStops = stops.stream().map(List::copyOf).toList(); templateStop = 0;
        templateEntered = entered; templateLeaving = leaving;
        setSelections(templateStops.getFirst());
        templateEntered.accept(0);
    }

    public boolean templateActive() { return !templateStops.isEmpty(); }
    public List<Selection> currentTemplateRanges() { return templateActive() ? templateStops.get(templateStop) : List.of(); }

    public boolean advanceTemplate(boolean previous) {
        if (!templateActive()) return false;
        var current = templateStops.get(templateStop);
        if (selections().stream().anyMatch(s -> current.stream().noneMatch(r -> s.start() >= r.start() && s.end() <= r.end()))) {
            finishTemplate(); return false;
        }
        int next = templateStop + (previous ? -1 : 1);
        if (!templateLeaving.test(templateStop, next)) return true;
        if (next >= templateStops.size()) {
            int end = current.getFirst().end(); finishTemplate(); setSelection(end, end);
        } else {
            templateStop = Math.floorMod(next, templateStops.size()); setSelections(templateStops.get(templateStop));
            templateEntered.accept(templateStop);
        }
        return true;
    }

    public void finishTemplate() {
        templateStops = List.of(); templateStop = 0;
        templateEntered = index -> {}; templateLeaving = (index, next) -> true;
    }

    /** Resume a semantic template after its transaction updated references. */
    public void selectTemplateStop(int index) {
        if (!templateActive() || index < 0 || index >= templateStops.size()) return;
        templateStop = index; setSelections(templateStops.get(index)); templateEntered.accept(index);
    }

    private void remapTemplate(List<TextEdit> edits) {
        if (!templateActive() || edits.isEmpty()) return;
        var active = templateStops.get(templateStop);
        if (edits.stream().anyMatch(e -> active.stream().noneMatch(r -> e.start() >= r.start() && e.end() <= r.end()))) {
            finishTemplate(); return;
        }
        templateStops = templateStops.stream().map(group -> group.stream().map(range -> {
            int start = range.start(), end = range.end();
            for (var edit : edits) {
                int delta = edit.text().length() - (edit.end() - edit.start());
                if (edit.end() <= range.start() && !(edit.start() == range.start() && edit.end() <= range.end())) {
                    start += delta; end += delta;
                } else if (edit.start() >= range.start() && edit.end() <= range.end()) end += delta;
            }
            return new Selection(end, start);
        }).toList()).toList();
    }

    private boolean resultBoundary(int offset, int length, List<TextEdit> edits) {
        if (offset == 0 || offset == length) return true;
        char before = resultCharacter(offset - 1, edits), after = resultCharacter(offset, edits);
        return !(Character.isHighSurrogate(before) && Character.isLowSurrogate(after))
                && !(before == '\r' && after == '\n');
    }

    /** Reads one projected character without flattening the document or modifying it. */
    private char resultCharacter(int offset, List<TextEdit> edits) {
        int originalAt = 0, resultAt = 0;
        for (var edit : edits) {
            int unchanged = edit.start() - originalAt;
            if (offset < resultAt + unchanged) return document.charAt(originalAt + offset - resultAt);
            resultAt += unchanged;
            if (offset < resultAt + edit.text().length()) return edit.text().charAt(offset - resultAt);
            resultAt += edit.text().length();
            originalAt = edit.end();
        }
        return document.charAt(originalAt + offset - resultAt);
    }

    public boolean applySemanticEdits(long expectedVersion, List<TextEdit> edits,
            int afterCaret, int afterAnchor) {
        return applyEdits(expectedVersion, edits, List.of(new Selection(afterCaret, afterAnchor)));
    }

    private static int mapped(int offset, List<TextEdit> edits) {
        int delta = 0;
        for (var edit : edits) {
            if (offset < edit.start()) break;
            if (offset <= edit.end()) return edit.start() + delta + edit.text().length();
            delta += edit.text().length() - edit.end() + edit.start();
        }
        return offset + delta;
    }

    private void applyPreservingSelections(List<TextEdit> edits) {
        edits.sort(java.util.Comparator.comparingInt(TextEdit::start));
        var after = selections().stream().map(s ->
                new Selection(mappedPreserving(s.caret(), edits), mappedPreserving(s.anchor(), edits))).toList();
        applyEdits(version(), edits, after);
    }

    private static int mappedPreserving(int offset, List<TextEdit> edits) {
        int delta = 0;
        for (var edit : edits) {
            if (offset < edit.start()) break;
            if (offset < edit.end()) {
                int local = Math.min(offset - edit.start(), edit.text().length());
                if (local > 0 && local < edit.text().length()
                        && ((Character.isHighSurrogate(edit.text().charAt(local - 1))
                                && Character.isLowSurrogate(edit.text().charAt(local)))
                            || (edit.text().charAt(local - 1) == '\r' && edit.text().charAt(local) == '\n')))
                    local++;
                return edit.start() + delta + local;
            }
            delta += edit.text().length() - edit.end() + edit.start();
        }
        return offset + delta;
    }

    private void replaceSelections(java.util.function.Function<Selection, String> replacement) {
        var before = selections();
        var edits = before.stream().sorted(java.util.Comparator.comparingInt(Selection::start))
                .map(s -> new TextEdit(s.start(), s.end(), replacement.apply(s))).toList();
        var after = before.stream().map(s -> {
            int position = mapped(s.end(), edits);
            return new Selection(position, position);
        }).toList();
        applyEdits(version(), edits, after);
    }

    public void removeSecondaryCarets() {
        if (secondaryCarets.isEmpty()) return;
        secondaryCarets.clear();
        onCaretChange.run();
        repaint();
    }

    public void addCaretAbove() { addCaret(-1); }
    public void addCaretBelow() { addCaret(1); }
    private void addCaret(int direction) {
        var all = new ArrayList<>(selections());
        var edge = all.stream().min(direction < 0
                ? java.util.Comparator.comparingInt(Selection::caret)
                : java.util.Comparator.comparingInt(Selection::caret).reversed()).orElseThrow();
        int sourceLine = document.lineAt(edge.caret()), line = sourceLine + direction;
        if (line < 0 || line >= document.lineCount()) return;
        int column = visualColumn(document.lineText(sourceLine), edge.caret() - document.lineStart(sourceLine));
        int offset = document.lineStart(line) + offsetForColumn(document.lineText(line), column);
        all.add(new Selection(offset, offset));
        setSelections(all);
    }

    /** Rectangular selection uses visual columns, including tabs and Unicode code points. */
    public void rectangularSelection(int anchorOffset, int caretOffset) {
        int aLine = document.lineAt(anchorOffset), cLine = document.lineAt(caretOffset);
        int aColumn = visualColumn(document.lineText(aLine), anchorOffset - document.lineStart(aLine));
        int cColumn = visualColumn(document.lineText(cLine), caretOffset - document.lineStart(cLine));
        var ranges = new ArrayList<Selection>();
        int direction = cLine >= aLine ? 1 : -1;
        for (int line = cLine; ; line -= direction) {
            String text = document.lineText(line);
            ranges.add(new Selection(document.lineStart(line) + offsetForColumn(text, cColumn),
                    document.lineStart(line) + offsetForColumn(text, aColumn)));
            if (line == aLine) break;
        }
        setSelections(ranges);
    }

    public void selectNextOccurrence() {
        if (caret == anchor) { selectCurrentWord(); return; }
        String needle = getSelectedText(), text = getText();
        if (needle.isEmpty()) return;
        var all = new ArrayList<>(selections());
        int from = all.stream().mapToInt(Selection::end).max().orElse(0);
        for (int pass = 0; pass < 2; pass++) {
            int found = text.indexOf(needle, pass == 0 ? from : 0);
            while (found >= 0 && (pass == 0 || found < from)) {
                int start = found, finish = found + needle.length();
                if (characterBoundary(start) && characterBoundary(finish)
                        && all.stream().noneMatch(s -> start < s.end() && finish > s.start())) {
                    all.add(new Selection(finish, start));
                    setSelections(all);
                    return;
                }
                found = text.indexOf(needle, found + 1);
            }
        }
    }

    public void selectAllOccurrences() {
        if (caret == anchor) selectCurrentWord();
        String needle = getSelectedText(), text = getText();
        if (needle.isEmpty()) return;
        var all = new ArrayList<Selection>();
        all.add(new Selection(caret, anchor));
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
            if (characterBoundary(at) && characterBoundary(at + needle.length()))
                all.add(new Selection(at + needle.length(), at));
        setSelections(all);
    }

    private record Lines(int first, int last) {}
    private Lines selectedLines(Selection s) {
        int first = document.lineAt(s.start()), last = document.lineAt(s.end());
        if (!s.empty() && last > first && s.end() == document.lineStart(last)) last--;
        return new Lines(first, last);
    }
    private List<Lines> lineGroups() {
        var sorted = selections().stream().map(this::selectedLines)
                .sorted(java.util.Comparator.comparingInt(Lines::first)).toList();
        var groups = new ArrayList<Lines>();
        for (var lines : sorted) {
            if (!groups.isEmpty() && lines.first() <= groups.getLast().last() + 1) {
                var prior = groups.removeLast();
                groups.add(new Lines(prior.first(), Math.max(prior.last(), lines.last())));
            } else groups.add(lines);
        }
        return groups;
    }
    private int afterLine(int line) {
        return line + 1 < document.lineCount() ? document.lineStart(line + 1) : document.length();
    }
    private int lineDeleteStart(Lines group) {
        return group.last() == document.lineCount() - 1 && group.first() > 0
                ? document.lineEnd(group.first() - 1) : document.lineStart(group.first());
    }

    /** A selection duplicates its exact text; an empty selection duplicates the logical line. */
    public void duplicateLine() {
        if (selections().stream().allMatch(Selection::empty)) { copyLine(1); return; }
        var edits = new ArrayList<TextEdit>();
        for (var s : selections()) {
            if (s.empty()) {
                var lines = selectedLines(s);
                int start = document.lineStart(lines.first()), end = afterLine(lines.last());
                String text = document.substring(start, end);
                edits.add(new TextEdit(end, end, text.endsWith("\n") ? text : document.lineEnding() + text));
            } else edits.add(new TextEdit(s.end(), s.end(), document.substring(s.start(), s.end())));
        }
        edits.sort(java.util.Comparator.comparingInt(TextEdit::start));
        var after = selections().stream().map(s -> {
            int end = mapped(s.end(), edits), start = end - (s.end() - s.start());
            return s.caret() < s.anchor() ? new Selection(start, end) : new Selection(end, start);
        }).toList();
        applyEdits(version(), edits, after);
    }

    public void deleteLine() {
        var edits = new ArrayList<TextEdit>();
        for (var g : lineGroups()) edits.add(new TextEdit(lineDeleteStart(g), afterLine(g.last()), ""));
        applyPreservingSelections(edits);
    }

    public void copyLine(int direction) {
        if (direction == 0) return;
        var edits = new ArrayList<TextEdit>();
        for (var g : lineGroups()) {
            int start = document.lineStart(g.first()), end = afterLine(g.last());
            String text = document.substring(start, end);
            if (!text.endsWith("\n")) text = direction < 0 ? text + document.lineEnding() : document.lineEnding() + text;
            edits.add(new TextEdit(direction < 0 ? start : end, direction < 0 ? start : end, text));
        }
        // Select the copied block and retain directional selections within it.
        var after = new ArrayList<Selection>();
        for (var s : selections()) {
            Lines g = lineGroups().stream().filter(l -> l.first() <= selectedLines(s).first()
                    && l.last() >= selectedLines(s).last()).findFirst().orElseThrow();
            int start = document.lineStart(g.first()), end = afterLine(g.last());
            int insertion = direction < 0 ? start : end;
            int deltaBefore = edits.stream().filter(e -> e.start() < insertion)
                    .mapToInt(e -> e.text().length()).sum();
            int shift = direction < 0 ? deltaBefore : end - start + deltaBefore
                    + (document.substring(start, end).endsWith("\n") ? 0 : document.lineEnding().length());
            after.add(new Selection(s.caret() + shift, s.anchor() + shift));
        }
        applyEdits(version(), edits, after);
    }

    public void moveLine(int direction) {
        if (direction == 0 || readOnly) return;
        var edits = new ArrayList<TextEdit>();
        var movedStarts = new java.util.HashMap<Integer, Integer>();
        var movedEnds = new java.util.HashMap<Integer, Integer>();
        for (var group : lineGroups()) {
            if (direction < 0 && group.first() == 0
                    || direction > 0 && group.last() + 1 == document.lineCount()) continue;
            int first = direction < 0 ? group.first() - 1 : group.first();
            int last = direction < 0 ? group.last() : group.last() + 1;
            int start = document.lineStart(first), position = start;
            var replacement = new StringBuilder();
            for (int target = first; target <= last; target++) {
                int original = direction < 0 ? (target == last ? first : target + 1)
                        : (target == first ? last : target - 1);
                String content = document.lineText(original);
                String ending = document.substring(document.lineEnd(target), afterLine(target));
                movedStarts.put(original, position);
                replacement.append(content).append(ending);
                position += content.length() + ending.length();
                movedEnds.put(original, position);
            }
            edits.add(new TextEdit(start, afterLine(last), replacement.toString()));
        }
        if (edits.isEmpty()) return;
        var after = new ArrayList<Selection>();
        for (var selection : selections()) {
            var group = selectedLines(selection);
            java.util.function.IntUnaryOperator map = offset -> {
                if (!selection.empty() && offset == selection.end() && offset == afterLine(group.last())
                        && group.last() + 1 < document.lineCount())
                    return movedEnds.getOrDefault(group.last(), offset);
                int line = document.lineAt(offset);
                return movedStarts.getOrDefault(line, document.lineStart(line))
                        + Math.min(offset - document.lineStart(line), document.lineText(line).length());
            };
            after.add(new Selection(map.applyAsInt(selection.caret()), map.applyAsInt(selection.anchor())));
        }
        applyEdits(version(), edits, after);
    }

    public void selectAll() { setSelection(0, document.length()); }
    public void selectCurrentLine() {
        int line = document.lineAt(caret);
        setSelection(document.lineStart(line), afterLine(line));
    }
    public void selectCurrentWord() { selectWord(); }

    /** Injectable clipboard for headless hosts/tests; normal application uses the system clipboard. */
    public void setClipboard(Clipboard clipboard) { this.clipboard = Objects.requireNonNull(clipboard); }
    private Clipboard clipboard() {
        return clipboard != null ? clipboard : Toolkit.getDefaultToolkit().getSystemClipboard();
    }
    private record ClipboardText(String text, List<String> parts, boolean lines) implements Transferable {
        private static final DataFlavor FLAVOR = new DataFlavor(
                DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "TurboJ selections");
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DataFlavor.stringFlavor, FLAVOR}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.stringFlavor) || flavor.equals(FLAVOR); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(DataFlavor.stringFlavor)) return text;
            if (flavor.equals(FLAVOR)) return this;
            throw new UnsupportedFlavorException(flavor);
        }
    }
    private boolean copyToClipboard() {
        if (document.length() == 0) return false;
        boolean lines = selections().stream().allMatch(Selection::empty);
        var parts = new ArrayList<String>();
        if (lines) {
            var distinct = new java.util.TreeSet<Integer>();
            for (var s : selections()) distinct.add(document.lineAt(s.caret()));
            for (int line : distinct) {
                String text = document.substring(document.lineStart(line), afterLine(line));
                parts.add(text.endsWith("\n") ? text : text + document.lineEnding());
            }
        } else for (var s : selections().stream().sorted(java.util.Comparator.comparingInt(Selection::start)).toList())
            parts.add(document.substring(s.start(), s.end()));
        try {
            String text = String.join(lines ? "" : document.lineEnding(), parts);
            clipboard().setContents(new ClipboardText(text, List.copyOf(parts), lines), null);
            return true;
        } catch (IllegalStateException | java.awt.HeadlessException e) { return false; }
    }
    public void copy() { copyToClipboard(); }
    public void cut() {
        if (readOnly || !copyToClipboard()) return;
        if (selections().stream().allMatch(Selection::empty)) deleteLine();
        else replaceSelections(s -> "");
    }
    public void paste() {
        if (readOnly) return;
        try {
            Transferable contents = clipboard().getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return;
            var data = contents.isDataFlavorSupported(ClipboardText.FLAVOR)
                    ? (ClipboardText) contents.getTransferData(ClipboardText.FLAVOR) : null;
            String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
            var sorted = selections().stream().sorted(java.util.Comparator.comparingInt(Selection::start)).toList();
            var edits = new ArrayList<TextEdit>();
            for (int i = 0; i < sorted.size(); i++) {
                var s = sorted.get(i);
                String part = data != null && data.parts().size() == sorted.size() ? data.parts().get(i) : text;
                part = part.replace("\r\n", "\n").replace("\r", "\n").replace("\n", document.lineEnding());
                int start = data != null && data.lines() && s.empty()
                        ? document.lineStart(document.lineAt(s.caret())) : s.start();
                int end = data != null && data.lines() && s.empty() ? start : s.end();
                if (edits.isEmpty() || edits.getLast().start() != start || edits.getLast().end() != end)
                    edits.add(new TextEdit(start, end, part));
            }
            var after = selections().stream().map(s -> {
                int at = mapped(s.end(), edits);
                return new Selection(at, at);
            }).toList();
            applyEdits(version(), edits, after);
        } catch (Exception e) { Toolkit.getDefaultToolkit().beep(); }
    }

    public void undo() {
        finishTemplate();
        if (readOnly) return;
        applyHistory(document.undoTransaction(), false);
    }

    /** Merge an already-applied semantic edit and its linked typing into one undo action. */
    public long coalesceEdits(long checkpointId, String beforeText, Location beforeLocation) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Coalesce editor history on EDT");
        if (readOnly || hasInputComposition()) throw new IllegalStateException("Editor cannot coalesce this edit");
        Objects.requireNonNull(beforeText); Objects.requireNonNull(beforeLocation);
        if (beforeLocation.caret() < 0 || beforeLocation.caret() > beforeText.length()
                || beforeLocation.anchor() < 0 || beforeLocation.anchor() > beforeText.length()
                || beforeLocation.topLine() < 0 || beforeLocation.leftColumn() < 0)
            throw new IllegalArgumentException("Invalid source checkpoint location");
        var transaction = document.coalesceSince(checkpointId, beforeText);
        var history = editingHistory.computeIfAbsent(document, ignored -> new java.util.LinkedHashMap<>());
        history.keySet().removeIf(id -> checkpointId == -1 || id > checkpointId);
        var formatted = formattingHistory.get(document);
        if (formatted != null) formatted.keySet().removeIf(id -> checkpointId == -1 || id > checkpointId);
        if (transaction == null) return -1;
        history.put(transaction.id(), new EditingLocations(beforeLocation, captureLocation(),
                List.of(new Selection(beforeLocation.caret(), beforeLocation.anchor())), selections()));
        while (history.size() > 2000) history.remove(history.keySet().iterator().next());
        return transaction.id();
    }
    public void redo() {
        finishTemplate();
        if (readOnly) return;
        applyHistory(document.redoTransaction(), true);
    }
    private void applyHistory(PieceTable.HistoryResult result, boolean forward) {
        if (result == null) return;
        var editing = editingHistory.get(document);
        var positions = editing == null ? null : editing.get(result.id());
        PieceTable.Change change = result.changes().getLast();
        var history = formattingHistory.get(document);
        FormattingLocations formatted = history == null ? null : history.get(result.id());
        Location restored = positions != null ? (forward ? positions.after() : positions.before())
                : formatted == null ? null : forward ? formatted.after() : formatted.before();
        caret = restored == null ? change.start() + change.inserted().length() : restored.caret();
        anchor = restored == null ? caret : restored.anchor();
        secondaryCarets.clear();
        int start = result.changes().stream().mapToInt(PieceTable.Change::start).min().orElse(0);
        changed(document.lineAt(Math.min(start, document.length())), true);
        if (restored != null) restoreLocation(restored);
        if (positions != null) setSelections(forward ? positions.afterSelections() : positions.beforeSelections());
    }

    public void indent(boolean unindent) {
        if (readOnly) return;
        if (!unindent && selections().stream().allMatch(Selection::empty)) {
            replaceSelections(s -> {
                int line = document.lineAt(s.caret());
                int column = visualColumn(document.lineText(line), s.caret() - document.lineStart(line));
                return " ".repeat(tabWidth - column % tabWidth);
            });
            return;
        }
        var edits = new ArrayList<TextEdit>();
        for (var group : lineGroups()) for (int line = group.first(); line <= group.last(); line++) {
            int start = document.lineStart(line), end = start;
            if (unindent) {
                if (end < document.lineEnd(line) && document.charAt(end) == '\t') end++;
                else while (end < document.lineEnd(line) && end - start < tabWidth && document.charAt(end) == ' ') end++;
            }
            edits.add(new TextEdit(start, end, unindent ? "" : " ".repeat(tabWidth)));
        }
        applyPreservingSelections(edits);
    }

    public void toggleComment() {
        var edits = new ArrayList<TextEdit>();
        var lines = new ArrayList<Integer>();
        for (var g : lineGroups()) for (int l = g.first(); l <= g.last(); l++) lines.add(l);
        boolean remove = lines.stream().allMatch(l -> document.lineText(l).stripLeading().startsWith("//"));
        for (int line : lines) {
            String text = document.lineText(line);
            int prefix = leadingWhitespace(text), start = document.lineStart(line) + prefix;
            int end = remove ? start + 2 : start;
            if (remove && end < document.lineEnd(line) && document.charAt(end) == ' ') end++;
            edits.add(new TextEdit(start, end, remove ? "" : "// "));
        }
        applyPreservingSelections(edits);
    }

    public void toggleBlockComment() {
        var edits = new ArrayList<TextEdit>();
        for (var s : selections()) {
            String text = document.substring(s.start(), s.end());
            if (text.startsWith("/*") && text.endsWith("*/") && text.length() >= 4)
                edits.add(new TextEdit(s.start(), s.end(), text.substring(2, text.length() - 2)));
            else if (text.contains("*/")) return;
            else edits.add(new TextEdit(s.start(), s.end(), "/*" + text + "*/"));
        }
        applyPreservingSelections(edits);
    }

    private static int leadingWhitespace(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i;
    }
    public void insertLine(boolean above) {
        var edits = new ArrayList<TextEdit>();
        var target = new java.util.HashMap<Integer, Integer>();
        for (var s : selections().stream().sorted(java.util.Comparator.comparingInt(Selection::start)).toList()) {
            int line = document.lineAt(s.caret());
            int at = above ? document.lineStart(line) : document.lineEnd(line);
            String current = document.lineText(line), indent = current.substring(0, leadingWhitespace(current));
            if (!above && current.stripTrailing().endsWith("{")) indent += " ".repeat(tabWidth);
            if (!target.containsKey(line)) {
                edits.add(new TextEdit(at, at, above ? indent + document.lineEnding() : document.lineEnding() + indent));
                target.put(line, indent.length());
            }
        }
        var after = selections().stream().map(s -> {
            int line = document.lineAt(s.caret()), at = above ? document.lineStart(line) : document.lineEnd(line);
            int position = mapped(at, edits) - (above ? document.lineEnding().length() : 0);
            return new Selection(position, position);
        }).toList();
        applyEdits(version(), edits, after);
    }
    public void splitLine() {
        replaceSelections(s -> {
            int line = document.lineAt(s.start());
            String prefix = document.substring(document.lineStart(line), Math.min(s.start(), document.lineEnd(line)));
            return document.lineEnding() + prefix.substring(0, leadingWhitespace(prefix))
                    + (prefix.stripTrailing().endsWith("{") ? " ".repeat(tabWidth) : "");
        });
    }
    public void joinLines() {
        var boundaries = new java.util.TreeSet<Integer>();
        for (var g : lineGroups()) {
            int last = g.first() == g.last() ? g.last() + 1 : g.last();
            for (int line = g.first(); line < last && line + 1 < document.lineCount(); line++) boundaries.add(line);
        }
        var edits = new ArrayList<TextEdit>();
        for (int line : boundaries) {
            int start = document.lineEnd(line), end = document.lineStart(line + 1);
            while (start > document.lineStart(line) && Character.isWhitespace(document.charAt(start - 1))) start--;
            while (end < document.lineEnd(line + 1) && Character.isWhitespace(document.charAt(end))) end++;
            edits.add(new TextEdit(start, end, start == document.lineStart(line) || end == document.lineEnd(line + 1) ? "" : " "));
        }
        applyPreservingSelections(edits);
    }

    private void deleteToward(java.util.function.ToIntFunction<Selection> boundary) {
        var ranges = selections().stream().map(s -> s.empty() ? new Selection(boundary.applyAsInt(s), s.caret()) : s).toList();
        // Capture original selections in undo, while normalizing overlapping deletion spans.
        var normalized = normalizeSelections(ranges, document.length());
        var edits = normalized.stream().sorted(java.util.Comparator.comparingInt(Selection::start))
                .map(s -> new TextEdit(s.start(), s.end(), "")).toList();
        var after = selections().stream().map(s -> {
            int at = mapped(s.caret(), edits);
            return new Selection(at, at);
        }).toList();
        applyEdits(version(), edits, after);
    }
    public void deleteToLineStart() { deleteToward(s -> document.lineStart(document.lineAt(s.caret()))); }
    public void deleteToLineEnd() { deleteToward(s -> document.lineEnd(document.lineAt(s.caret()))); }
    public void deletePreviousWord() { deleteToward(s -> wordLeft(s.caret())); }
    public void deleteNextWord() { deleteToward(s -> wordRight(s.caret())); }
    public void deleteBackward() { deleteToward(s -> previous(s.caret())); }
    public void deleteForward() { deleteToward(s -> next(s.caret())); }

    private List<Lines> selectedOrAllLines() {
        return selections().stream().allMatch(Selection::empty)
                ? List.of(new Lines(0, document.lineCount() - 1)) : lineGroups();
    }
    public void trimTrailingWhitespace() {
        var edits = new ArrayList<TextEdit>();
        for (var g : selectedOrAllLines()) for (int line = g.first(); line <= g.last(); line++) {
            int end = document.lineEnd(line), start = end;
            while (start > document.lineStart(line) && (document.charAt(start - 1) == ' ' || document.charAt(start - 1) == '\t')) start--;
            if (start != end) edits.add(new TextEdit(start, end, ""));
        }
        applyPreservingSelections(edits);
    }
    public void convertTabsToSpaces() {
        var edits = new ArrayList<TextEdit>();
        for (var g : selectedOrAllLines()) for (int line = g.first(); line <= g.last(); line++) {
            String text = document.lineText(line);
            int column = 0;
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i), width = cp == '\t' ? tabWidth - column % tabWidth : 1;
                if (cp == '\t') edits.add(new TextEdit(document.lineStart(line) + i,
                        document.lineStart(line) + i + 1, " ".repeat(width)));
                i += Character.charCount(cp);
                column += width;
            }
        }
        applyPreservingSelections(edits);
    }
    /** Explicit indentation conversion only; spaces inside code and literals are unchanged. */
    public void convertSpacesToTabs() {
        var edits = new ArrayList<TextEdit>();
        for (var g : selectedOrAllLines()) for (int line = g.first(); line <= g.last(); line++) {
            String text = document.lineText(line);
            int end = leadingWhitespace(text), width = visualColumn(text, end), start = document.lineStart(line);
            edits.add(new TextEdit(start, start + end, "\t".repeat(width / tabWidth) + " ".repeat(width % tabWidth)));
        }
        applyPreservingSelections(edits);
    }
    public void changeCase(boolean upper) {
        if (selections().stream().allMatch(Selection::empty)) selectCurrentWord();
        replaceSelections(s -> {
            String text = document.substring(s.start(), s.end());
            return upper ? text.toUpperCase(java.util.Locale.ROOT) : text.toLowerCase(java.util.Locale.ROOT);
        });
    }
    public void sortSelectedLines() { reorderSelectedLines(false); }
    public void reverseSelectedLines() { reorderSelectedLines(true); }
    private void reorderSelectedLines(boolean reverse) {
        var edits = new ArrayList<TextEdit>();
        for (var g : lineGroups()) {
            var lines = new ArrayList<String>();
            for (int line = g.first(); line <= g.last(); line++) lines.add(document.lineText(line));
            if (reverse) java.util.Collections.reverse(lines); else lines.sort(String::compareTo);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int line = g.first() + i;
                result.append(lines.get(i)).append(document.substring(document.lineEnd(line), afterLine(line)));
            }
            edits.add(new TextEdit(document.lineStart(g.first()), afterLine(g.last()), result.toString()));
        }
        applyPreservingSelections(edits);
    }
    public void transposeCharacters() {
        var edits = new ArrayList<TextEdit>();
        for (var s : selections()) {
            if (!s.empty()) return;
            int line = document.lineAt(s.caret()), end = document.lineEnd(line);
            int middle = s.caret() == end ? previous(s.caret()) : s.caret();
            if (middle <= document.lineStart(line)) continue;
            int start = previous(middle), finish = next(middle);
            edits.add(new TextEdit(start, finish, document.substring(middle, finish) + document.substring(start, middle)));
        }
        edits.sort(java.util.Comparator.comparingInt(TextEdit::start));
        for (int i = 1; i < edits.size(); i++) if (edits.get(i).start() < edits.get(i - 1).end()) return;
        applyPreservingSelections(edits);
    }
    public void smartHome(boolean extend) {
        moveSelections(s -> {
            int line = document.lineAt(s.caret()), start = document.lineStart(line);
            int content = start + leadingWhitespace(document.lineText(line));
            return s.caret() == content ? start : content;
        }, extend);
    }
    public void smartEnd(boolean extend) {
        moveSelections(s -> {
            int line = document.lineAt(s.caret()), end = document.lineEnd(line), content = end;
            while (content > document.lineStart(line) && Character.isWhitespace(document.charAt(content - 1))) content--;
            return s.caret() == content ? end : content;
        }, extend);
    }
    private void moveSelections(java.util.function.ToIntFunction<Selection> target, boolean extend) {
        setSelections(selections().stream().map(s -> {
            int at = target.applyAsInt(s);
            return new Selection(at, extend ? s.anchor() : at);
        }).toList());
    }
    public void goToMatchingBracket() {
        int[] pair = matchingBrackets();
        if (pair.length == 2) gotoOffset(caret == pair[0] || caret - 1 == pair[0] ? pair[1] : pair[0]);
    }
    public void selectMatchingBrackets() {
        int[] pair = matchingBrackets();
        if (pair.length == 2) setSelection(Math.min(pair[0], pair[1]), Math.max(pair[0], pair[1]) + 1);
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
        gutterWidth = cellWidth * (lineGutter.cells() + Math.max(5, Integer.toString(document.lineCount()).length() + 2));
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
                    if (Character.isHighSurrogate(c)) pendingHighSurrogate = c;
                    else if (pendingHighSurrogate != 0) {
                        char high = pendingHighSurrogate;
                        pendingHighSurrogate = 0;
                        replaceSelection(Character.isLowSurrogate(c) ? "" + high + c : "\uFFFD" + c);
                    } else if (!Character.isLowSurrogate(c)) typeCharacter(c);
                }
                e.consume();
                return;
            }
        } else if (e.getID() == KeyEvent.KEY_PRESSED) {
            KeyStroke pressed = KeyStroke.getKeyStrokeForEvent(e);
            if (!externalCommandBindings && pressed.equals(acceptCompletionKey) && acceptInlineCompletion()) {
                e.consume();
                return;
            }
            if (!externalCommandBindings && pressed.equals(dismissCompletionKey) && inlineCompletion != null) {
                clearInlineCompletion();
                e.consume();
                return;
            }
            boolean shift = e.isShiftDown(), command = e.isMetaDown() || e.isControlDown();
            int code = e.getKeyCode();

            if (command && externalCommandBindings && !isMotion(code) && code
                    != KeyEvent.VK_BACK_SPACE && code != KeyEvent.VK_DELETE) {
                super.processKeyEvent(e);
                return;
            }
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
            if (!secondaryCarets.isEmpty() && isMotion(code)) {
                moveMultipleCarets(e);
                e.consume();
                return;
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
                    if (e.isMetaDown()) deleteToLineStart();
                    else if (e.isAltDown() || e.isControlDown()) deletePreviousWord();
                    else deleteBackward();
                }
                case KeyEvent.VK_DELETE -> {
                    if (e.isMetaDown()) deleteToLineEnd();
                    else if (e.isAltDown() || e.isControlDown()) deleteNextWord();
                    else deleteForward();
                }
                case KeyEvent.VK_ENTER -> {
                    if (command || e.isAltDown()) {
                        super.processKeyEvent(e);
                        return;
                    }
                    splitLine();
                }
                case KeyEvent.VK_ESCAPE -> {
                    if (secondaryCarets.isEmpty()) handled = false;
                    else removeSecondaryCarets();
                }
                case KeyEvent.VK_TAB -> {
                    if (externalCommandBindings) {
                        super.processKeyEvent(e);
                        return;
                    }
                    indent(shift);
                }
                default -> handled = false;
            }
            if (handled) {
                e.consume();
                return;
            }
        }
        super.processKeyEvent(e);
    }

    private void moveMultipleCarets(KeyEvent event) {
        boolean extend = event.isShiftDown();
        int code = event.getKeyCode();
        moveSelections(s -> {
            int line = document.lineAt(s.caret());
            int column = visualColumn(document.lineText(line), s.caret() - document.lineStart(line));
            return switch (code) {
                case KeyEvent.VK_LEFT -> event.isMetaDown() ? document.lineStart(line)
                        : event.isAltDown() || event.isControlDown() ? wordLeft(s.caret())
                        : !extend && !s.empty() ? s.start() : previous(s.caret());
                case KeyEvent.VK_RIGHT -> event.isMetaDown() ? document.lineEnd(line)
                        : event.isAltDown() || event.isControlDown() ? wordRight(s.caret())
                        : !extend && !s.empty() ? s.end() : next(s.caret());
                case KeyEvent.VK_HOME -> event.isMetaDown() || event.isControlDown() ? 0 : document.lineStart(line);
                case KeyEvent.VK_END -> event.isMetaDown() || event.isControlDown() ? document.length() : document.lineEnd(line);
                default -> {
                    boolean up = code == KeyEvent.VK_UP || code == KeyEvent.VK_PAGE_UP;
                    if (event.isMetaDown()) yield up ? 0 : document.length();
                    int amount = code == KeyEvent.VK_PAGE_UP || code == KeyEvent.VK_PAGE_DOWN
                            ? Math.max(1, getHeight() / cellHeight - 1) : 1;
                    int target = Math.clamp(line + (up ? -amount : amount), 0, document.lineCount() - 1);
                    yield document.lineStart(target) + offsetForColumn(document.lineText(target), column);
                }
            };
        }, extend);
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
        var before = selections();
        var edits = new ArrayList<TextEdit>();
        var skips = new java.util.HashSet<Selection>();
        for (var selection : before) {
            if (autoClose && selection.empty() && selection.caret() < document.length()
                    && document.charAt(selection.caret()) == c && closes.indexOf(c) >= 0) {
                skips.add(selection);
            } else {
                String value = autoClose && pair >= 0
                        ? c + document.substring(selection.start(), selection.end()) + closes.charAt(pair)
                        : String.valueOf(c);
                edits.add(new TextEdit(selection.start(), selection.end(), value));
            }
        }
        edits.sort(java.util.Comparator.comparingInt(TextEdit::start));
        var after = new ArrayList<Selection>();
        for (var selection : before) {
            if (skips.contains(selection)) {
                int at = mapped(selection.caret() + 1, edits);
                after.add(new Selection(at, at));
            } else if (autoClose && pair >= 0) {
                int end = mapped(selection.end(), edits) - 1;
                int start = end - (selection.end() - selection.start());
                after.add(selection.caret() < selection.anchor()
                        ? new Selection(start, end) : new Selection(end, start));
            } else {
                int at = mapped(selection.end(), edits);
                after.add(new Selection(at, at));
            }
        }
        applyEdits(version(), edits, after);
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
        while (offset > 0 && Character.isWhitespace(Character.codePointBefore(document, offset)))
            offset = previous(offset);
        if (offset > 0 && !Character.isJavaIdentifierPart(Character.codePointBefore(document, offset)))
            return previous(offset);
        while (offset > 0 && Character.isJavaIdentifierPart(Character.codePointBefore(document, offset)))
            offset = previous(offset);
        return offset;
    }

    private int wordRight(int offset) {
        if (offset < document.length()
                && !Character.isJavaIdentifierPart(Character.codePointAt(document, offset))
                && !Character.isWhitespace(Character.codePointAt(document, offset))) return next(offset);
        while (offset < document.length()
                && Character.isJavaIdentifierPart(Character.codePointAt(document, offset))) offset = next(offset);
        while (offset < document.length() && Character.isWhitespace(Character.codePointAt(document, offset)))
            offset = next(offset);
        return offset;
    }

    private void selectWord() {
        int start = caret, end = caret;
        if (start == document.length() || (start < document.length()
                && !Character.isJavaIdentifierPart(Character.codePointAt(document, start)))) {
            if (start > 0 && Character.isJavaIdentifierPart(Character.codePointBefore(document, start))) start = previous(start);
        }
        if (start < document.length() && Character.isJavaIdentifierPart(Character.codePointAt(document, start))) {
            end = start;
            while (start > 0 && Character.isJavaIdentifierPart(Character.codePointBefore(document, start))) start = previous(start);
            while (end < document.length() && Character.isJavaIdentifierPart(Character.codePointAt(document, end))) end = next(end);
        } else { start = caret; end = next(caret); }
        setSelection(start, end);
    }

    private void changed(int firstLine, boolean multiline) {
        lineGutter.invalidate();
        repaint(0, 0, lineGutter.cells() * cellWidth, getHeight());
        clearInlineCompletion();
        clearNavigableRange();
        if (emphasisTimer != null) emphasisTimer.stop();
        emphasisStart = emphasisEnd = -1;
        boolean localDamage = false;
        if (!multiline && firstLine < lexed.size()) {
            JavaLexer.State initial =
                    firstLine == 0 ? JavaLexer.State.NORMAL : lexed.get(firstLine - 1).endState();
            String text = document.lineText(firstLine);
            JavaLexer.Line next = lex(text, initial);
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
        secondaryCarets.clear();
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
        if (isAIInlineCompletion()) return aiOffsetAt(point);
        int line =
                Math.clamp(
                        topLine + Math.max(0, point.y) / cellHeight, 0, document.lineCount() - 1);
        int col = Math.max(0, leftColumn + (point.x - gutterWidth + cellWidth / 2) / cellWidth);
        return document.lineStart(line) + offsetForColumn(document.lineText(line), col);
    }

    private int symbolOffsetAt(Point point) {
        // Navigation targets the glyph under the pointer, rather than the nearest caret boundary.
        if (point.x < gutterWidth || point.y < 0 || point.y >= getHeight()) return -1;
        int visualLine = visualTopLine() + point.y / cellHeight;
        if (visualLine >= visualLineCount() || isGhostPoint(point)) return -1;
        int offset = offsetAt(new Point(point.x - cellWidth / 2, point.y));
        int line = document.lineAt(offset);
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
        return columnAfter(text, 0, end, 0);
    }

    private int columnAfter(String text, int from, int end, int initialColumn) {
        int column = initialColumn;
        for (int i = from; i < Math.min(end, text.length()); ) {
            int cp = text.codePointAt(i);
            column += width(cp, column);
            i += Character.charCount(cp);
        }
        return column;
    }

    private int baseVisualTopLine() {
        return topLine + (isAIInlineCompletion() && topLine > aiLine ? aiLines.length - 1 : 0);
    }

    private int visualTopLine() {
        return baseVisualTopLine() + (isAIInlineCompletion() ? aiScrollOffset : 0);
    }

    private int visualLineCount() {
        return document.lineCount() + (isAIInlineCompletion() ? aiLines.length - 1 : 0);
    }

    private int aiSuffixColumn() {
        int initial = aiLines.length == 1 ? caretColumn() - 1 : 0;
        String tail = aiLines[aiLines.length - 1];
        return columnAfter(tail, 0, tail.length(), initial);
    }

    private void repaintAIRegion() {
        int first = Math.max(0, (aiLine - visualTopLine()) * cellHeight);
        repaint(0, first, getWidth(), Math.max(0, getHeight() - first));
    }

    private int aiOffsetAt(Point point) {
        int row =
                Math.clamp(
                        visualTopLine() + Math.max(0, point.y) / cellHeight,
                        0,
                        visualLineCount() - 1);
        int column = Math.max(0, leftColumn + (point.x - gutterWidth + cellWidth / 2) / cellWidth);
        int lastGhostRow = aiLine + aiLines.length - 1;
        if (row < aiLine || row > lastGhostRow) {
            int line = row < aiLine ? row : row - aiLines.length + 1;
            return document.lineStart(line) + offsetForColumn(document.lineText(line), column);
        }
        String text = document.lineText(aiLine);
        if (row == aiLine && column < caretColumn() - 1)
            return document.lineStart(aiLine) + offsetForColumn(text, column);
        if (row == lastGhostRow && column >= aiSuffixColumn()) {
            int at = aiCaretInLine, current = aiSuffixColumn();
            while (at < text.length()) {
                int cp = text.codePointAt(at), next = current + width(cp, current);
                if (next > column) break;
                current = next;
                at += Character.charCount(cp);
            }
            return document.lineStart(aiLine) + at;
        }
        return caret;
    }

    private boolean isGhostPoint(Point point) {
        if (!isAIInlineCompletion()) return false;
        int row = visualTopLine() + point.y / cellHeight;
        int column = leftColumn + (point.x - gutterWidth) / cellWidth;
        if (row < aiLine || row >= aiLine + aiLines.length) return false;
        if (row == aiLine && column < caretColumn() - 1) return false;
        return row != aiLine + aiLines.length - 1 || column < aiSuffixColumn();
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
            lexed.add(lex(text, state));
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
            if (isAIInlineCompletion()) {
                paintAIProjection(g, clip);
                return;
            }
            int first = Math.max(topLine, topLine + clip.y / cellHeight),
                    last =
                            Math.min(
                                    document.lineCount() - 1,
                                    topLine + (clip.y + clip.height) / cellHeight);
            int selectionStart = Math.min(caret, anchor), selectionEnd = Math.max(caret, anchor);
            for (int line = first; line <= last; line++) {
                int y = (line - topLine) * cellHeight;
                lineGutter.paint(g, line + 1, y, document.lineCount(), cellWidth, cellHeight);
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
                    for (var extra : secondaryCarets) {
                        if (!extra.empty() && extra.end() > start && extra.start() <= start + text.length()) {
                            int a = columns[Math.max(0, extra.start() - start)];
                            int b = columns[Math.min(text.length(), extra.end() - start)];
                            if (extra.end() > start + text.length()) b++;
                            row.setColor(selection);
                            row.fillRect((a - leftColumn) * cellWidth, 0,
                                    Math.max(cellWidth, (b - a) * cellWidth), cellHeight);
                        }
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
                    for (var extra : secondaryCarets)
                        paintSelectedText(row, text, columns, start, extra.start(), extra.end());
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
                    if (isFocusOwner()) for (var extra : secondaryCarets) {
                        if (document.lineAt(extra.caret()) == line) {
                            int x = (columns[Math.min(text.length(), extra.caret() - start)] - leftColumn) * cellWidth;
                            row.setColor(cursor);
                            row.fillRect(x, 1, Math.max(2, cellWidth / 5), cellHeight - 2);
                        }
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
            paintGutterRule(g);
        } finally {
            pressedShortcut.paint(g, getWidth(), getHeight());
            g.dispose();
            lastPaintNanos = System.nanoTime() - started;
            if (pendingInputNanos != 0) {
                inputSamples[sampleCount++ % inputSamples.length] =
                        System.nanoTime() - pendingInputNanos;
                pendingInputNanos = 0;
            }
        }
    }

    private void paintGutterRule(Graphics2D graphics) {
        FontMetrics metrics = graphics.getFontMetrics();
        int height = metrics.getHeight();
        int x = gutterWidth - cellWidth;
        for (int y = 0; y < getHeight(); y += height)
            graphics.drawString("│", x, y + metrics.getAscent());
    }

    private Color ghostColor() {
        return ghost;
    }

    /**
     * Paint only visible virtual rows; the normal editor path pays none of this work when AI is
     * off.
     */
    private void paintAIProjection(Graphics2D g, Rectangle clip) {
        int top = visualTopLine();
        int first = Math.max(0, top + clip.y / cellHeight);
        int last = Math.min(visualLineCount() - 1, top + (clip.y + clip.height) / cellHeight);
        int lastGhostRow = aiLine + aiLines.length - 1;
        for (int visual = first; visual <= last; visual++) {
            int y = (visual - top) * cellHeight;
            int line =
                    visual <= aiLine
                            ? visual
                            : visual >= lastGhostRow ? visual - aiLines.length + 1 : -1;
            if (line >= 0 && (visual <= aiLine || visual > lastGhostRow)) {
                lineGutter.paint(g, line + 1, y, document.lineCount(), cellWidth, cellHeight);
                String label = Integer.toString(line + 1);
                g.setColor(comment);
                g.drawString(
                        label, gutterWidth - cellWidth - label.length() * cellWidth, y + ascent);
            }
            boolean labelFitsOriginal =
                    Integer.toString(aiLine + 1).length() <= gutterWidth / cellWidth - lineGutter.cells() - 4;
            if (visual == aiLine && labelFitsOriginal
                    || visual == aiLine + 1 && !labelFitsOriginal && aiLines.length > 1) {
                g.setColor(aiGhost);
                g.drawString("AI", lineGutter.cells() * cellWidth, y + ascent);
            }
            Graphics2D row =
                    (Graphics2D)
                            g.create(
                                    gutterWidth,
                                    y,
                                    Math.max(0, getWidth() - gutterWidth),
                                    cellHeight);
            try {
                if (visual < aiLine || visual > lastGhostRow) {
                    paintSourceSlice(row, line, 0, document.lineText(line).length(), 0);
                } else {
                    String source = document.lineText(aiLine);
                    if (visual == aiLine) paintSourceSlice(row, aiLine, 0, aiCaretInLine, 0);
                    String inserted = aiLines[visual - aiLine];
                    int initial = visual == aiLine ? caretColumn() - 1 : 0;
                    row.setColor(aiGhost);
                    drawRun(row, inserted, 0, inserted.length(), initial);
                    if (visual == lastGhostRow)
                        paintSourceSlice(
                                row, aiLine, aiCaretInLine, source.length(), aiSuffixColumn());
                    if (visual == aiLine && isFocusOwner()) {
                        int x = (caretColumn() - 1 - leftColumn) * cellWidth;
                        row.setColor(cursor);
                        row.fillRect(x, 1, cellWidth, cellHeight - 2);
                        if (!inserted.isEmpty() && inserted.charAt(0) != '\t') {
                            row.setColor(getBackground());
                            row.drawString(
                                    inserted.substring(
                                            0, Character.charCount(inserted.codePointAt(0))),
                                    x,
                                    ascent);
                        }
                    }
                }
            } finally {
                row.dispose();
            }
        }
        g.setColor(comment);
        paintGutterRule(g);
    }

    private void paintSourceSlice(Graphics2D row, int line, int from, int to, int column) {
        JavaLexer.Line tokens = lexicalLine(line);
        String text = lineTexts.get(line);
        int start = document.lineStart(line);
        // Retain existing source emphasis, diagnostic and navigation colors around the overlay.
        int emphasisFrom = Math.max(from, emphasisStart - start);
        int emphasisTo = Math.min(to, emphasisEnd - start);
        if (emphasisStart >= 0 && emphasisFrom < emphasisTo) {
            int a = columnAfter(text, from, emphasisFrom, column);
            int b = columnAfter(text, emphasisFrom, emphasisTo, a);
            row.setColor(selection);
            row.fillRect((a - leftColumn) * cellWidth, 0, (b - a) * cellWidth, cellHeight);
        }
        int paintedOffset = from, paintedColumn = column;
        for (JavaLexer.Span span : tokens.spans()) {
            int a = Math.max(from, span.start()), b = Math.min(to, span.end());
            if (a >= b) continue;
            int at = columnAfter(text, paintedOffset, a, paintedColumn);
            if (at > leftColumn + getWidth() / cellWidth + 1) break;
            row.setColor(tokenColor(span.kind()));
            drawRun(row, text, a, b, at);
            paintedOffset = b;
            paintedColumn = columnAfter(text, a, b, at);
        }
        if (emphasisStart >= 0 && emphasisFrom < emphasisTo) {
            row.setColor(selectionForeground);
            drawRun(
                    row,
                    text,
                    emphasisFrom,
                    emphasisTo,
                    columnAfter(text, from, emphasisFrom, column));
        }
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if (diagnostic.line() != line + 1 || !"ERROR".equalsIgnoreCase(diagnostic.kind()))
                continue;
            int a = Math.max(from, (int) Math.max(0, diagnostic.column() - 1));
            int b =
                    Math.min(
                            to,
                            diagnostic.end() > diagnostic.start()
                                    ? (int) Math.min(text.length(), diagnostic.end() - start)
                                    : Math.min(text.length(), (int) diagnostic.column()));
            if (a >= b) continue;
            int first = columnAfter(text, from, a, column), last = columnAfter(text, a, b, first);
            row.setColor(error);
            for (int x = (first - leftColumn) * cellWidth;
                    x < (last - leftColumn) * cellWidth;
                    x += 4) row.drawLine(x, cellHeight - 2, x + 2, cellHeight - 1);
        }
        if (navigationStart >= 0) {
            int a = Math.max(from, navigationStart - start),
                    b = Math.min(to, navigationEnd - start);
            if (a < b) {
                int first = columnAfter(text, from, a, column),
                        last = columnAfter(text, a, b, first);
                row.setColor(definition);
                row.drawLine(
                        (first - leftColumn) * cellWidth,
                        cellHeight - 2,
                        (last - leftColumn) * cellWidth,
                        cellHeight - 2);
            }
        }
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
        repaint(0, caretBounds().y, getWidth(), cellHeight);
        for (var extra : secondaryCarets)
            repaint(0, offsetBounds(extra.caret()).y, getWidth(), cellHeight);
    }

    private void repaintMatchingBrackets() {
        for (int offset : matchingBrackets())
            repaint(0, offsetBounds(offset).y, getWidth(), cellHeight);
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
        // An IME commit is an intentional edit, not a stale background transformation.
        composition = "";
        pendingHighSurrogate = 0;
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
        Point point = caretBounds().getLocation();
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
            return "Line " + caretLine() + ", column " + caretColumn()
                    + (lineGutter.cells() == 0 ? "" : ". " + lineGutter.describe(caretLine(), null, null));
        }
    }
}
