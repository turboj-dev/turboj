package dev.turboj.ui.themes;

import dev.turboj.commandj.swing.EmbeddedDialog;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.*;

/** A reversible preview transaction. Only Apply/Enter calls the persistent selection consumer. */
public final class ThemeBrowserDialog extends EmbeddedDialog {
    private final Theme original;
    private final ThemeCatalog catalog;
    private final Consumer<Theme> preview;
    private final Consumer<ThemeCatalog.Preview> confirm;
    private final JTextField search = new JTextField();
    private final JPanel cards = new JPanel(new GridLayout(2, 3, 7, 7));
    private final JScrollBar vertical = new JScrollBar(JScrollBar.VERTICAL);
    private boolean updatingScroll, preserveSelection;
    private double wheelRemainder;
    private final CardLayout previewLayout = new CardLayout();
    private final JPanel previewArea = new JPanel(previewLayout);
    private final ThemeDrum drum = new ThemeDrum();
    private final JButton random = new JButton("Random theme"), skip = new JButton("Skip spin");
    private final JCheckBox reducedMotion = new JCheckBox("Reduce motion");
    private final javax.swing.Timer spinTimer = new javax.swing.Timer(16, e -> tickSpin());
    private final Map<String, ThemeCatalog.Preview> prepared = new LinkedHashMap<>();
    private ThemeCatalog.WheelResult wheel;
    private boolean spinning, paused;
    private int spinStep;
    private long spinStart, pausedAt, prefetchRequest;

    private final PreviewButton[] tiles = new PreviewButton[ThemeCatalog.PAGE_SIZE];
    private final JLabel info = new JLabel("Loading local themes…"),
            selectionLabel = new JLabel(" ");
    private final JButton previous = new JButton("Previous page"),
            next = new JButton("Next page"),
            apply = new JButton("Apply theme"),
            cancel = new JButton("Cancel"),
            refresh = new JButton("Refresh");
    private ThemeCatalog.Page page;
    private ThemeCatalog.Preview selected;
    private int selectedIndex, desiredIndex;
    private long request;
    private boolean finished, loading;
    private Theme visibleTheme;

    public ThemeBrowserDialog(
            JFrame owner,
            Theme initial,
            String selectedKey,
            ThemeCatalog catalog,
            Consumer<Theme> preview,
            Consumer<ThemeCatalog.Preview> confirm) {
        super(owner, "More themes", true);
        this.original = initial;
        this.catalog = catalog;
        this.preview = preview;
        this.confirm = confirm;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setName("theme-browser");
        setMinimumSize(new Dimension(680, 450));
        setSize(720, 480);
        var available = owner.getContentPane().getBounds();
        setSize(
                Math.min(getWidth(), available.width - 60),
                Math.min(getHeight(), available.height - 60));
        var body = new JPanel(new BorderLayout(0, 7));
        body.setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 9));
        setContentPane(body);
        var top = new JPanel(new BorderLayout(8, 8));
        var label = new JLabel("Search");
        label.setLabelFor(search);
        top.add(label, BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        top.add(refresh, BorderLayout.EAST);
        search.setName("theme-search");
        search.getAccessibleContext().setAccessibleName("Search local themes");
        search.putClientProperty("JTextField.placeholderText", "Theme name");
        body.add(top, BorderLayout.NORTH);
        for (int i = 0; i < tiles.length; i++) {
            final int index = i;
            tiles[i] = new PreviewButton();
            tiles[i].setName("theme-preview-" + i);
            tiles[i].addActionListener(e -> { if (page != null) choose(page.firstIndex() + index); });
            cards.add(tiles[i]);
        }
        cards.setName("theme-grid");
        var catalogViewport = new JPanel(new BorderLayout(5, 0));
        catalogViewport.add(cards, BorderLayout.CENTER);
        catalogViewport.add(vertical, BorderLayout.EAST);
        vertical.setName("theme-scrollbar");
        vertical.setUnitIncrement(1);
        vertical.setBlockIncrement(2);
        vertical.getAccessibleContext().setAccessibleName("Scroll theme rows");
        vertical.addAdjustmentListener(
                e -> {
                    if (!updatingScroll && !spinning && page != null && !finished)
                        load(e.getValue(), null, selectedIndex, true);
                });
        cards.addMouseWheelListener(
                e -> {
                    if (spinning || page == null || finished) return;
                    wheelRemainder += e.getPreciseWheelRotation();
                    int rows = (int) wheelRemainder;
                    if (rows != 0) {
                        wheelRemainder -= rows;
                        vertical.setValue(vertical.getValue() + rows);
                    }
                    e.consume();
                });
        previewArea.add(catalogViewport, "cards");
        previewArea.add(drum, "wheel");
        body.add(previewArea, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 5));
        var descriptions = new JPanel(new GridLayout(3, 1, 0, 2));
        selectionLabel.setName("theme-selection");
        info.setName("theme-page-status");
        selectionLabel.putClientProperty("html.disable", Boolean.TRUE);
        info.putClientProperty("html.disable", Boolean.TRUE);
        descriptions.add(selectionLabel);
        descriptions.add(info);
        descriptions.add(
                new JLabel("Arrows preview · Page Up/Down browse · Enter applies · Esc cancels"));
        footer.add(descriptions, BorderLayout.NORTH);
        var actions = new JPanel(new GridLayout(2, 1, 0, 4));
        var wheelActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        wheelActions.add(random);
        wheelActions.add(skip);
        wheelActions.add(reducedMotion);
        var selectionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        selectionActions.add(previous);
        selectionActions.add(next);
        selectionActions.add(cancel);
        selectionActions.add(apply);
        actions.add(wheelActions);
        actions.add(selectionActions);
        footer.add(actions, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);
        random.setName("theme-random");
        skip.setName("theme-skip-spin");
        reducedMotion.setName("theme-reduced-motion");
        skip.setVisible(false);
        spinTimer.setCoalesce(true);
        random.addActionListener(e -> startRandom());
        skip.addActionListener(e -> finishSpin());
        reducedMotion.addActionListener(
                e -> {
                    if (reducedMotion.isSelected() && wheel != null) finishSpin();
                });
        previous.setName("theme-previous");
        next.setName("theme-next");
        apply.setName("theme-apply");
        cancel.setName("theme-cancel");
        apply.addActionListener(e -> accept());
        cancel.addActionListener(e -> cancel());
        previous.addActionListener(e -> vertical.setValue(vertical.getValue() - 2));
        next.addActionListener(e -> vertical.setValue(vertical.getValue() + 2));
        refresh.addActionListener(
                e -> {
                    catalog.refresh();
                    load(0, null, 0);
                });
        search.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                changed();
                            }

                            private void changed() {
                                load(0, null, 0);
                            }
                        });
        bind("LEFT", () -> move(-1));
        bind("RIGHT", () -> move(1));
        bind("UP", () -> move(-3));
        bind("DOWN", () -> move(3));
        bind("PAGE_UP", () -> move(-ThemeCatalog.PAGE_SIZE));
        bind("PAGE_DOWN", () -> move(ThemeCatalog.PAGE_SIZE));
        bind("ENTER", this::accept);
        bind("ESCAPE", this::cancel);
        onClosing(this::cancel);
        onOpened(() -> search.requestFocusInWindow());
        onClosed(() -> { if (!finished) cancel(); });
        style(initial);
        setLocationRelativeTo(owner);
        load(-1, selectedKey, -1);
    }

    private void bind(String key, Runnable operation) {
        String action = "theme-browser." + key;
        Action handler =
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        operation.run();
                    }
                };
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, action);
        getRootPane().getActionMap().put(action, handler);
        // Preserve native Left/Right text editing in search; vertical/page keys browse without
        // moving focus.
        if (!key.equals("LEFT") && !key.equals("RIGHT")) {
            search.getInputMap().put(stroke, action);
            search.getActionMap().put(action, handler);
        }
        for (PreviewButton tile : tiles) {
            tile.getInputMap().put(stroke, action);
            tile.getActionMap().put(action, handler);
        }
    }

    private void load(int firstRow, String preferred, int targetIndex) {
        load(firstRow, preferred, targetIndex, false);
    }

    private void load(int requestedRow, String preferred, int targetIndex, boolean preserve) {
        if (finished || spinning) return;
        loading = true;
        desiredIndex = targetIndex;
        preserveSelection = preserve;
        apply.setEnabled(false);
        info.setText("Loading local themes…");
        request =
                catalog.requestWindow(
                        search.getText(),
                        requestedRow,
                        preferred,
                        result -> SwingUtilities.invokeLater(() -> render(result)));
    }

    private void render(ThemeCatalog.Page result) {
        if (finished || spinning || result.requestId() != request) return;
        page = result;
        loading = false;
        random.setEnabled(result.total() > 0);
        for (int i = 0; i < tiles.length; i++)
            tiles[i].setPreview(i < page.previews().size() ? page.previews().get(i) : null);
        previous.setEnabled(page.firstIndex() > 0);
        next.setEnabled(page.firstIndex() + ThemeCatalog.PAGE_SIZE < page.total());
        updatingScroll = true;
        vertical.setValues(page.firstIndex() / 3, 2, 0, Math.max(2, (page.total() + 2) / 3));
        vertical.setEnabled(page.total() > 6);
        updatingScroll = false;
        if (page.problem() != null) info.setText("Cannot read theme catalog: " + page.problem());
        else
            info.setText(
                    page.total()
                            + " themes · "
                            + (page.total() == 0 ? 0 : page.firstIndex() + 1)
                            + "–"
                            + Math.min(page.total(), page.firstIndex() + 6)
                            + (page.invalidFiles() > 0
                                    ? " · " + page.invalidFiles() + " invalid files skipped"
                                    : "")
                            + (page.truncated()
                                    ? " · Catalog limited to " + ThemeCatalog.MAX_ENTRIES
                                    : ""));
        if (preserveSelection) {
            for (int i = 0; i < tiles.length; i++)
                tiles[i].setSelected(selected != null && page.firstIndex() + i == selectedIndex);
            apply.setEnabled(selected != null && selected.theme() != null);
            cards.repaint();
            return;
        }
        int index = desiredIndex < 0 ? page.selected() : desiredIndex;
        choose(
                Math.clamp(
                        index,
                        page.firstIndex(),
                        Math.max(
                                page.firstIndex(),
                                page.firstIndex() + page.previews().size() - 1)));
    }

    private void choose(int index) {
        if (loading || spinning || page == null || finished) return;
        selectedIndex = index;
        desiredIndex = index;
        int local = index - page.firstIndex();
        selected = local >= 0 && local < page.previews().size() ? page.previews().get(local) : null;
        for (int i = 0; i < tiles.length; i++) tiles[i].setSelected(i == local && selected != null);
        apply.setEnabled(selected != null && selected.theme() != null);
        if (selected == null)
            selectionLabel.setText("No matching themes. The previous preview remains visible.");
        else if (selected.theme() == null)
            selectionLabel.setText(
                    selected.entry().name() + " · Invalid theme: " + selected.problem());
        else {
            selectionLabel.setText(
                    selected.entry().name() + " · Preview only; press Enter to keep it");
            preview.accept(selected.theme());
            style(selected.theme());
        }
        if (local >= 0 && local < tiles.length && selected != null) {
            for (PreviewButton tile : tiles)
                if (tile.isFocusOwner()) {
                    tiles[local].requestFocusInWindow();
                    break;
                }
        }
        cards.repaint();
    }

    private void move(int delta) {
        if (spinning || page == null || page.total() == 0 || finished) return;
        int index =
                Math.clamp((loading ? desiredIndex : selectedIndex) + delta, 0, page.total() - 1);
        if (index < page.firstIndex() || index >= page.firstIndex() + 6 || loading) {
            int row = index < page.firstIndex() ? index / 3 : Math.max(0, index / 3 - 1);
            if (Math.abs(delta) >= 6
                    && selectedIndex >= page.firstIndex()
                    && selectedIndex < page.firstIndex() + 6)
                row = Math.max(0, page.firstIndex() / 3 + (delta < 0 ? -2 : 2));
            load(row, null, index);
        } else choose(index);
    }

    private void startRandom() {
        if (finished || loading || spinning || page == null || page.total() == 0) return;
        spinning = true;
        wheel = null;
        prepared.clear();
        paused = false;
        spinStep = 0;
        search.setEnabled(false);
        refresh.setEnabled(false);
        random.setEnabled(false);
        previous.setEnabled(false);
        next.setEnabled(false);
        vertical.setEnabled(false);
        apply.setEnabled(false);
        skip.setVisible(true);
        skip.setEnabled(false);
        previewLayout.show(previewArea, "wheel");
        selectionLabel.setText(
                "Preparing a random choice from " + page.total() + " matching themes…");
        info.setText("Each center crossing previews a real theme. Apply keeps the final choice.");
        request =
                catalog.requestRandom(
                        search.getText(),
                        result -> SwingUtilities.invokeLater(() -> beginSpin(result)));
    }

    private void beginSpin(ThemeCatalog.WheelResult result) {
        if (finished || !spinning || result.requestId() != request) return;
        if (result.problem() != null) {
            stopSpin();
            selectionLabel.setText("Cannot choose a theme: " + result.problem());
            return;
        }
        wheel = result;
        prepared.put(result.target().entry().key(), result.target());
        for (var p : result.prepared()) prepared.put(p.entry().key(), p);
        skip.setEnabled(true);
        info.setText(
                "Random choice from "
                        + result.total()
                        + " matches · Short sequence of real themes · 3–5 seconds");
        if (reducedMotion.isSelected() || wheel.plan().deadlines().isEmpty()) {
            finishSpin();
            return;
        }
        if (!applyCrossing(0)) return;
        drum.show(wheel.plan(), 0, 0);
        spinStart = System.nanoTime();
        spinTimer.start();
    }

    private void tickSpin() {
        if (!spinning || wheel == null || finished || paused) return;
        long elapsed = (System.nanoTime() - spinStart) / 1_000_000;
        ThemeWheelPlan plan = wheel.plan();
        if (elapsed >= plan.deadlines().get(spinStep)) {
            int nextStep = spinStep + 1;
            if (nextStep == plan.frames().size() - 1) {
                finishSpin();
                return;
            }
            ThemeCatalog.Preview palette = prepared.get(plan.frames().get(nextStep).entry().key());
            if (palette == null) {
                pauseSpin();
                info.setText("Loading the next local theme…");
                prefetchNext();
                return;
            }
            // Deliver at most one actual palette crossing per paint interval; never skip a name.
            spinStep = nextStep;
            if (!applyCrossing(spinStep)) return;
            prefetchNext();
        }
        double fraction = plan.positionAt(elapsed) - spinStep;
        if (fraction >= 1) fraction = 0; // A delayed EDT catches up one visible crossing per tick.
        drum.show(plan, spinStep, fraction);
    }

    private boolean applyCrossing(int index) {
        var frame = wheel.plan().frames().get(index);
        var palette =
                frame.entry().key().equals(wheel.target().entry().key())
                        ? wheel.target()
                        : prepared.get(frame.entry().key());
        if (palette != null && palette.theme() != null) {
            preview.accept(palette.theme());
            style(palette.theme());
            selectionLabel.setText(palette.entry().name() + " · Random preview; nothing saved");
            drum.getAccessibleContext()
                    .setAccessibleDescription("Previewing " + palette.entry().name());
            return true;
        }
        String unavailable = frame.entry().name();
        stopSpin();
        if (selected != null && selected.theme() != null) {
            preview.accept(selected.theme());
            style(selected.theme());
        }
        selectionLabel.setText("Random preview stopped: cannot load " + unavailable);
        return false;
    }

    private void prefetchNext() {
        if (wheel == null || !spinning) return;
        var frames = wheel.plan().frames();
        var upcoming = new ArrayList<ThemeCatalog.Entry>();
        var keep = new HashSet<String>();
        keep.add(wheel.target().entry().key());
        keep.add(frames.get(spinStep).entry().key());
        for (int i = spinStep + 1; i < Math.min(frames.size(), spinStep + 7); i++) {
            upcoming.add(frames.get(i).entry());
            keep.add(frames.get(i).entry().key());
        }
        prepared.keySet().retainAll(keep);
        prefetchRequest =
                catalog.prefetch(
                        upcoming,
                        batch ->
                                SwingUtilities.invokeLater(
                                        () -> {
                                            if (finished
                                                    || !spinning
                                                    || wheel == null
                                                    || batch.requestId() != prefetchRequest) return;
                                            for (var palette : batch.previews())
                                                prepared.put(palette.entry().key(), palette);
                                            if (paused) resumeSpin();
                                        }));
    }

    private void pauseSpin() {
        if (!spinning || wheel == null || paused) return;
        paused = true;
        pausedAt = System.nanoTime();
        spinTimer.stop();
    }

    private void resumeSpin() {
        if (!spinning || wheel == null || !paused) return;
        spinStart += System.nanoTime() - pausedAt;
        paused = false;
        spinTimer.start();
    }

    private void finishSpin() {
        if (!spinning || wheel == null) return;
        ThemeCatalog.Preview target = wheel.target();
        preview.accept(target.theme());
        style(target.theme());
        String key = target.entry().key();
        stopSpin();
        load(-1, key, -1);
    }

    private void stopSpin() {
        spinTimer.stop();
        spinning = false;
        paused = false;
        wheel = null;
        prepared.clear();
        search.setEnabled(true);
        refresh.setEnabled(true);
        random.setEnabled(page != null && page.total() > 0);
        previous.setEnabled(page != null && page.firstIndex() > 0);
        next.setEnabled(page != null && page.firstIndex() + 6 < page.total());
        vertical.setEnabled(page != null && page.total() > 6);
        apply.setEnabled(selected != null && selected.theme() != null);
        skip.setVisible(false);
        previewLayout.show(previewArea, "cards");
        previewArea.revalidate();
        previewArea.repaint();
    }

    private void accept() {
        if (finished || spinning || loading || selected == null || selected.theme() == null) return;
        finished = true;
        spinTimer.stop();
        catalog.cancelPending();
        confirm.accept(selected);
        super.dispose();
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        spinTimer.stop();
        catalog.cancelPending();
        preview.accept(original);
        super.dispose();
    }

    @Override
    public void dispose() {
        if (!finished) cancel();
        else super.dispose();
    }

    private void style(Theme theme) {
        visibleTheme = theme;
        drum.setTheme(theme);
        dev.turboj.commandj.swing.AsciiStyle.apply(getContentPane(), dev.turboj.ui.AsciiChrome.editor(theme));
        cards.repaint();
    }

    private final class PreviewButton extends JToggleButton {
        private ThemeCatalog.Preview value;

        PreviewButton() {
            setEnabled(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusable(true);
        }

        void setPreview(ThemeCatalog.Preview preview) {
            value = preview;
            setEnabled(value != null);
            setSelected(false);
            getAccessibleContext()
                    .setAccessibleName(value == null ? "Empty preview slot" : value.entry().name());
            setToolTipText(
                    value == null
                            ? null
                            : "Theme: "
                                    + value.entry().name()
                                    + (value.problem() == null ? "" : ": " + value.problem()));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (value == null) return;
                Theme t = value.theme() == null ? visibleTheme : value.theme();
                int w = getWidth(), h = getHeight();
                g.setColor(visibleTheme.background());
                g.fillRect(0, 0, w, h);
                g.setColor(
                        dev.turboj.ui.AsciiChrome.editor(visibleTheme).border());
                g.setStroke(new BasicStroke(isSelected() || hasFocus() ? 3 : 1));
                g.drawRect(2, 2, w - 5, h - 5);
                g.setClip(8, 8, w - 16, h - 16);
                g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
                g.setColor(dev.turboj.ui.AsciiChrome.editor(visibleTheme).foreground());
                g.drawString((isSelected() ? "[x] " : "[ ] ") + value.entry().name(), 8, 17);
                if (value.theme() == null) {
                    g.drawString("Invalid theme JSON", 8, 38);
                    return;
                }
                int top = 23, consoleY = Math.max(90, h - 32);
                g.setColor(t.menuBackground());
                g.fillRect(7, top, w - 14, 13);
                g.setColor(t.menuForeground());
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
                g.drawString("File Edit Run  HelloWorld.java", 10, top + 10);
                g.setColor(t.background());
                g.fillRect(7, top + 13, w - 14, consoleY - top - 13);
                int x = 11, y = top + 24;
                x = part(g, "public class ", x, y, t.keyword());
                part(g, "HelloWorld {", x, y, t.foreground());
                x = part(g, "  public static void ", 11, y + 9, t.keyword());
                part(g, "main(String[] args) {", x, y + 9, t.foreground());
                x = part(g, "    System.out.println(", 11, y + 18, t.foreground());
                x = part(g, "\"Hello TurboJ!\"", x, y + 18, t.string());
                part(g, ");", x, y + 18, t.foreground());
                part(g, "  }", 11, y + 27, t.foreground());
                part(g, "}", 11, y + 36, t.foreground());
                g.setColor(t.messagesBackground());
                g.fillRect(7, consoleY, w - 14, h - consoleY - 6);
                g.setColor(t.messagesForeground());
                g.drawString("BUILD OK · 0 errors", 11, consoleY + 10);
                g.drawString("Hello TurboJ!", 11, consoleY + 20);
            } finally {
                g.dispose();
            }
        }

        private int part(Graphics2D g, String text, int x, int y, Color color) {
            g.setColor(color);
            g.drawString(text, x, y);
            return x + g.getFontMetrics().stringWidth(text);
        }
    }
}
