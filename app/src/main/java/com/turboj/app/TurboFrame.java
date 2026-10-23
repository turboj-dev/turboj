package com.turboj.app;

import com.turboj.compiler.*;
import com.turboj.config.*;
import com.turboj.execution.*;
import com.turboj.intelligence.*;
import com.turboj.intelligence.JavaIntelligenceService.Declaration;
import com.turboj.ui.*;
import com.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;
import javax.swing.border.*;

public final class TurboFrame extends JFrame {
    public static final String HELLO =
            "public class Hello {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(\"Hello TurboJ!\");\n"
                    + "    }\n"
                    + "}\n";
    private final EditorComponent editor = new EditorComponent();
    private final WorkerClient worker = new WorkerClient();
    private final ContinuousCompiler compiler = new ContinuousCompiler(new JavacCompilerBackend());
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "turboj-files-snapshots");
                        t.setDaemon(true);
                        return t;
                    });
    private final Settings settings;
    private final ConsolePane console =
            new ConsolePane(
                    s -> {
                        try {
                            worker.sendInput(s);
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            showError(e.getMessage());
                        }
                    });
    private final ReplPane repl = new ReplPane();
    private boolean replVisible;
    private final JLabel status =
            new JLabel(" F1 Docs   F2 Save   F3 Open   F9 Make   ^F9 Run   F10 Menu");
    private final JLabel position = new JLabel(" 1:1 ", SwingConstants.RIGHT);
    private final JPanel editorPanel = new JPanel(new BorderLayout());
    private final JPanel messages = new JPanel(new BorderLayout());
    private final JPanel bottom = new JPanel(new BorderLayout());
    private final JMenuBar menus = new JMenuBar();
    private final Map<String, Action> commands = new LinkedHashMap<>();
    private final Map<String, String> keymap;
    private final Map<KeyStroke, String> editorBindings = new HashMap<>();
    private final Map<Path, Buffer> buffers = new LinkedHashMap<>();
    private final NavigationHistory<Visit> navigation = new NavigationHistory<>();
    private Path bufferKey = untitledKey();
    private IntelligenceController intelligence;
    private com.turboj.editor.document.PieceTable observedDocument;

    private record Buffer(
            Path file, EditorComponent.EditorState state, byte[] fingerprint, long savedVersion) {}

    private record Visit(Path key, EditorComponent.Location location, long version) {}

    private static Path untitledKey() {
        return Path.of(
                System.getProperty("user.dir"), ".turboj-untitled-" + UUID.randomUUID() + ".java");
    }

    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong operation = new AtomicLong();
    private final AtomicLong bufferIdentity = new AtomicLong();
    private final AtomicLong openRequest = new AtomicLong();
    private boolean saving;
    private DeferredSave deferredSave;

    private record DeferredSave(boolean as, Runnable continuation, long identity) {}

    private ScheduledFuture<?> pendingAnalysis;
    private ScheduledFuture<?> pendingRecovery;
    private Path file;
    private byte[] diskFingerprint;
    private long savedVersion;
    private Theme theme;
    private CompilationResult currentResult;
    private String buildStatus = "READY";
    private String search = "";
    private volatile boolean closing;
    private boolean running;
    private volatile long runRequest;
    private CompletableFuture<RunResult> execution = CompletableFuture.completedFuture(null);
    private boolean programOutput;
    private String lastEditorTitle = "", lastMessageTitle = "";
    private final Path recovery = Settings.directory().resolve("sessions").resolve("buffer.java");

    public TurboFrame(Settings settings, Map<String, String> keymap) {
        super("TurboJ");
        this.settings = settings;
        this.keymap = keymap;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(640, 420));
        setSize(settings.width, settings.height);
        setLocationByPlatform(true);
        editorPanel.add(editor, BorderLayout.CENTER);
        messages.add(console, BorderLayout.CENTER);
        console.setInlineInput(true);
        console.setProgramRunning(false);
        var content = new JPanel(new BorderLayout(0, 4));
        content.add(editorPanel, BorderLayout.CENTER);
        content.add(messages, BorderLayout.SOUTH);
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(position, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        setJMenuBar(menus);
        createCommands();
        createMenus();
        bindKeys();
        applyTheme(Theme.bundled(settings.theme));
        editor.setFontSize(settings.fontSize);
        editor.setTabWidth(settings.tabWidth);
        editor.setAutoClose(settings.autoClose);
        intelligence =
                new IntelligenceController(
                        this,
                        editor,
                        this::intelligenceSnapshot,
                        this::navigateDeclaration,
                        text -> console.append(text + "\n", false));
        intelligence.setTheme(theme);
        KeyStroke acceptCompletion =
                KeyStroke.getKeyStroke(keymap.getOrDefault("acceptCompletion", "TAB"));
        KeyStroke dismissCompletion =
                KeyStroke.getKeyStroke(keymap.getOrDefault("dismissCompletion", "ESCAPE"));
        intelligence.setCompletionKeys(
                acceptCompletion == null ? KeyStroke.getKeyStroke("TAB") : acceptCompletion,
                dismissCompletion == null ? KeyStroke.getKeyStroke("ESCAPE") : dismissCompletion);
        editor.setKeyHandler(
                e -> {
                    if (intelligence.handleKey(e)) return true;
                    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                    String id = editorBindings.get(KeyStroke.getKeyStrokeForEvent(e));
                    if (id == null) return false;
                    commands.get(id).actionPerformed(null);
                    return true;
                });
        editor.setOnChange(this::changed);
        editor.setOnCaretChange(this::updatePosition);
        editor.setText(HELLO);
        savedVersion = editor.version();
        refreshTitle();
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        requestQuit();
                    }
                });
        if (Desktop.isDesktopSupported()) {
            var desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
                desktop.setQuitHandler(
                        (e, r) -> {
                            r.cancelQuit();
                            SwingUtilities.invokeLater(this::requestQuit);
                        });
            if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE))
                desktop.setOpenFileHandler(
                        e -> {
                            if (!e.getFiles().isEmpty())
                                SwingUtilities.invokeLater(
                                        () -> openPath(e.getFiles().getFirst().toPath()));
                        });
        }
        worker.warmup()
                .exceptionally(
                        e -> {
                            console.append("Worker startup: " + message(e) + "\n", true);
                            return null;
                        });
    }

    public EditorComponent editor() {
        return editor;
    }

    public IntelligenceController intelligence() {
        return intelligence;
    }

    public Path currentFile() {
        return file;
    }

    private IntelligenceController.Snapshot intelligenceSnapshot() {
        var open = new LinkedHashMap<Path, com.turboj.editor.document.PieceTable.Snapshot>();
        buffers.forEach(
                (key, value) -> {
                    if (!value.state().readOnly())
                        open.put(key, value.state().document().snapshot());
                });
        var source = editor.snapshot();
        open.put(bufferKey, source);
        return new IntelligenceController.Snapshot(bufferKey, source, Map.copyOf(open));
    }

    private void rememberBuffer() {
        buffers.put(
                bufferKey, new Buffer(file, editor.captureState(), diskFingerprint, savedVersion));
    }

    private Visit visit() {
        return new Visit(bufferKey, editor.captureLocation(), editor.version());
    }

    private void activate(Path key, Buffer buffer) {
        operation.incrementAndGet();
        openRequest.incrementAndGet();
        bufferIdentity.incrementAndGet();
        bufferKey = key;
        file = buffer.file();
        diskFingerprint = buffer.fingerprint();
        savedVersion = buffer.savedVersion();
        editor.restoreState(buffer.state());
        refreshTitle();
        editor.requestFocusInWindow();
    }

    public void navigateBack() {
        navigateHistory(false);
    }

    public void navigateForward() {
        navigateHistory(true);
    }

    private void navigateHistory(boolean forward) {
        Visit target = forward ? navigation.forward(visit()) : navigation.back(visit());
        if (target == null) return;
        rememberBuffer();
        Buffer buffer = buffers.get(target.key());
        if (buffer == null) return;
        activate(target.key(), buffer);
        editor.restoreLocation(target.location());
    }

    private void navigateDeclaration(Declaration target) {
        if (target.file() == null || target.source() == null) return;
        Path destination = target.file().toAbsolutePath().normalize();
        Visit previous = visit();
        if (destination.equals(bufferKey)) {
            navigation.jumpedFrom(previous);
            editor.gotoOffset(target.offset());
            editor.emphasizeRange(target.offset(), target.offset() + target.length());
            return;
        }
        rememberBuffer();
        Buffer cached = buffers.get(destination);
        if (cached != null) {
            navigation.jumpedFrom(previous);
            activate(destination, cached);
            editor.gotoOffset(target.offset());
            editor.emphasizeRange(target.offset(), target.offset() + target.length());
            return;
        }
        long identity = bufferIdentity.get();
        long version = editor.version();
        long request = openRequest.incrementAndGet();
        background.execute(
                () -> {
                    try {
                        SafeFiles.Snapshot disk =
                                target.readOnly() ? null : SafeFiles.readSnapshot(destination);
                        if (disk != null && !disk.text().equals(target.source()))
                            throw new IllegalStateException(
                                    "The declaration changed on disk. Navigate again to resolve the"
                                            + " new source.");
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (closing
                                            || bufferIdentity.get() != identity
                                            || editor.version() != version
                                            || openRequest.get() != request) return;
                                    rememberBuffer();
                                    navigation.jumpedFrom(previous);
                                    operation.incrementAndGet();
                                    bufferIdentity.incrementAndGet();
                                    bufferKey = destination;
                                    file = destination;
                                    diskFingerprint = disk == null ? null : disk.fingerprint();
                                    var state =
                                            new EditorComponent.EditorState(
                                                    new com.turboj.editor.document.PieceTable(
                                                            target.source()),
                                                    new EditorComponent.Location(0, 0, 0, 0),
                                                    target.readOnly());
                                    savedVersion = state.document().version();
                                    editor.restoreState(state);
                                    editor.gotoOffset(target.offset());
                                    editor.emphasizeRange(
                                            target.offset(), target.offset() + target.length());
                                    refreshTitle();
                                    editor.requestFocusInWindow();
                                });
                    } catch (Exception e) {
                        errorLater(e);
                    }
                });
    }

    public ConsolePane console() {
        return console;
    }

    public CompilationResult currentResult() {
        return currentResult;
    }

    public boolean isRunning() {
        return running;
    }

    public String buildStatus() {
        return buildStatus;
    }

    private void command(String id, String label, Runnable action) {
        commands.put(
                id,
                new AbstractAction(label) {
                    public void actionPerformed(ActionEvent e) {
                        action.run();
                    }
                });
    }

    private void createCommands() {
        command(
                "new",
                "New",
                () -> {
                    rememberBuffer();
                    operation.incrementAndGet();
                    openRequest.incrementAndGet();
                    bufferIdentity.incrementAndGet();
                    file = null;
                    bufferKey = untitledKey();
                    diskFingerprint = null;
                    editor.setText("");
                    savedVersion = editor.version();
                    refreshTitle();
                });
        command(
                "open",
                "Open…",
                () -> {
                    var c = new JFileChooser();
                    c.setFileFilter(
                            new javax.swing.filechooser.FileNameExtensionFilter(
                                    "Java source (*.java)", "java"));
                    if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        openPath(c.getSelectedFile().toPath());
                });
        command("save", "Save", () -> save(false, () -> {}));
        command("saveAs", "Save as…", () -> save(true, () -> {}));
        command("quit", "Quit TurboJ", this::requestQuit);
        command("complete", "Completion alternatives", () -> intelligence.complete(true));
        command(
                "declaration",
                "Go to declaration",
                () -> intelligence.goToDeclaration(editor.caretOffset()));
        command("documentation", "Quick documentation", () -> intelligence.quickDocumentation());
        command("back", "Navigate back", this::navigateBack);
        command("forward", "Navigate forward", this::navigateForward);
        command("undo", "Undo", editor::undo);
        command("redo", "Redo", editor::redo);
        command("cut", "Cut", editor::cut);
        command("copy", "Copy", editor::copy);
        command("paste", "Paste", editor::paste);
        command("selectAll", "Select all", editor::selectAll);
        command("indent", "Indent", () -> editor.indent(false));
        command("unindent", "Unindent", () -> editor.indent(true));
        command("comment", "Toggle line comment", editor::toggleComment);
        command(
                "find",
                "Find…",
                () -> {
                    String s = JOptionPane.showInputDialog(this, "Find text:", search);
                    if (s != null && !s.isEmpty()) {
                        search = s;
                        editor.find(search, true);
                    }
                });
        command(
                "findNext",
                "Find next",
                () -> {
                    if (!search.isEmpty()) editor.find(search, true);
                });
        command(
                "findPrevious",
                "Find previous",
                () -> {
                    if (!search.isEmpty()) editor.find(search, false);
                });
        command(
                "replace",
                "Replace selection…",
                () -> {
                    if (editor.getSelectedText().isEmpty()) {
                        commands.get("find").actionPerformed(null);
                        return;
                    }
                    String s = JOptionPane.showInputDialog(this, "Replace selected text with:", "");
                    if (s != null) editor.replaceSelection(s);
                });
        command(
                "goto",
                "Go to line…",
                () -> {
                    String s = JOptionPane.showInputDialog(this, "Line number:");
                    try {
                        if (s != null) editor.gotoLine(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        showError("Enter a line number.");
                    }
                });
        command("compile", "Make current buffer", () -> compile(false, false));
        command("run", "Fast Run", () -> compile(true, false));
        command("isolated", "Isolated Run", () -> compile(true, true));
        command(
                "stop",
                "Stop program",
                () -> {
                    if (replVisible) repl.cancel();
                    ++runRequest;
                    worker.stop();
                    running = false;
                    console.setProgramRunning(false);
                    console.append("\nProgram stopped.\n", false);
                });
        command("eof", "Send EOF", worker::closeInput);
        command(
                "clear",
                "Clear output",
                () -> {
                    if (replVisible) repl.clear();
                    else console.clear();
                });
        command("error", "Go to first error", this::goToError);
        command(
                "zoom",
                "Expand editor",
                () -> {
                    messages.setVisible(!messages.isVisible());
                    revalidate();
                });
        command(
                "focus",
                "Switch editor / console",
                () -> {
                    if (console.hasConsoleFocus() || repl.hasConsoleFocus())
                        editor.requestFocusInWindow();
                    else {
                        messages.setVisible(true);
                        if (replVisible) repl.focusInput();
                        else console.focusInput();
                    }
                });
        command("repl", "Java REPL", () -> showRepl(true));
        command("output", "Program output / messages", () -> showRepl(false));
        command(
                "replReset",
                "Reset Java REPL",
                () -> {
                    showRepl(true);
                    repl.reset();
                });
        command(
                "evaluateSelection",
                "Evaluate selection",
                () -> {
                    String selected = editor.getSelectedText();
                    if (selected.isBlank()) {
                        showError("Select Java statements or an expression to evaluate.");
                        return;
                    }
                    showRepl(true);
                    repl.evaluate(selected);
                });
        command("evaluateStatement", "Evaluate current statement", this::evaluateStatement);
        for (String id : Theme.BUNDLED)
            command(
                    "theme." + id,
                    Theme.bundled(id).name(),
                    () -> {
                        settings.theme = id;
                        applyTheme(Theme.bundled(id));
                    });
        command(
                "theme.load",
                "Load theme JSON…",
                () -> {
                    var c = new JFileChooser();
                    if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        background.execute(
                                () -> {
                                    try {
                                        var t = Theme.load(c.getSelectedFile().toPath());
                                        SwingUtilities.invokeLater(() -> applyTheme(t));
                                    } catch (Exception e) {
                                        errorLater(e);
                                    }
                                });
                });
        command(
                "fontUp",
                "Larger font",
                () -> {
                    settings.fontSize = Math.min(48, settings.fontSize + 2);
                    editor.setFontSize(settings.fontSize);
                });
        command(
                "fontDown",
                "Smaller font",
                () -> {
                    settings.fontSize = Math.max(10, settings.fontSize - 2);
                    editor.setFontSize(settings.fontSize);
                });
        command(
                "fullscreen",
                "Toggle full screen",
                () -> {
                    var d = getGraphicsConfiguration().getDevice();
                    d.setFullScreenWindow(d.getFullScreenWindow() == this ? null : this);
                });
        command(
                "metrics",
                "Performance",
                () -> {
                    var samples = editor.inputToPaintSamples();
                    double[] a =
                            Arrays.stream(samples).mapToDouble(n -> n / 1e6).sorted().toArray();
                    JOptionPane.showMessageDialog(
                            this,
                            "Java "
                                    + Runtime.version()
                                    + "\nLast paint: "
                                    + ms(editor.lastPaintNanos())
                                    + " ms\nInput → paint samples: "
                                    + a.length
                                    + (a.length == 0
                                            ? ""
                                            : "\np50 / p95 / p99: "
                                                    + pct(a, .5)
                                                    + " / "
                                                    + pct(a, .95)
                                                    + " / "
                                                    + pct(a, .99)
                                                    + " ms")
                                    + "\nLast compile: "
                                    + (currentResult == null
                                            ? "pending"
                                            : ms(currentResult.durationNanos()) + " ms")
                                    + "\n"
                                    + intelligence.metrics()
                                    + "\n"
                                    + "These are local measured samples, not performance"
                                    + " guarantees.",
                            "TurboJ Performance",
                            JOptionPane.INFORMATION_MESSAGE);
                });
        command("palette", "Command palette…", this::palette);
        command(
                "help",
                "Keyboard help",
                () ->
                        JOptionPane.showMessageDialog(
                                this,
                                "TurboJ — edit, compile, run\n\n"
                                    + "F1 Quick documentation   F2 Save   F3 Open   F5 Expand"
                                    + " editor\n"
                                    + "F6 Editor / console   F9 Make   F10 Menu\n"
                                    + "Ctrl+F9 / Cmd+R Run   Alt+F9 Compile\n"
                                    + "Cmd+Enter Compile and run   Cmd+. Stop\n"
                                    + "Cmd+N New   Cmd+O Open   Cmd+S Save\n"
                                    + "Cmd+F Find   Cmd+G Find next   Cmd+L Go to line\n"
                                    + "Cmd+Shift+P Commands   Cmd+/ Toggle comment\n"
                                    + "Cmd+Z Undo   Cmd+Shift+Z Redo\n\n"
                                    + "Tab Accept inline suggestion   Esc Dismiss\n"
                                    + "Ctrl+Space Completion alternatives\n"
                                    + "Cmd+Click / Cmd+B / F12 Go to declaration\n"
                                    + "Alt+Left Back   Alt+Right Forward\n\n"
                                    + "On macOS function keys may need Fn.\n"
                                    + "Fast Run is for trusted code; it is not a sandbox.\n"
                                    + "Run → Java REPL opens a persistent interactive session.\n"
                                    + "Debugging and project test discovery are not yet available.",
                                "TurboJ Help",
                                JOptionPane.INFORMATION_MESSAGE));
    }

    private void menu(String name, String... ids) {
        var m = new JMenu(name);
        m.setMnemonic(name.charAt(0));
        for (String id : ids) {
            if (id.equals("-")) m.addSeparator();
            else m.add(new JMenuItem(commands.get(id)));
        }
        menus.add(m);
    }

    private void createMenus() {
        menu("File", "new", "open", "save", "saveAs", "-", "quit");
        menu(
                "Edit",
                "undo",
                "redo",
                "-",
                "cut",
                "copy",
                "paste",
                "selectAll",
                "-",
                "indent",
                "unindent",
                "comment",
                "-",
                "complete",
                "documentation");
        menu("Search", "find", "findNext", "findPrevious", "replace", "goto");
        menu("Navigate", "declaration", "back", "forward");
        menu(
                "Run",
                "run",
                "isolated",
                "stop",
                "eof",
                "clear",
                "-",
                "repl",
                "output",
                "replReset",
                "evaluateSelection",
                "evaluateStatement");
        menu("Compile", "compile", "error");
        menu(
                "Options",
                "theme.classic",
                "theme.amber",
                "theme.modern",
                "theme.light",
                "theme.load",
                "-",
                "fontUp",
                "fontDown",
                "zoom",
                "fullscreen",
                "metrics",
                "palette");
        menu("Help", "help");
    }

    private void bindKeys() {
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("F1", "documentation");
        bindings.put("F12", "declaration");
        bindings.put("ctrl SPACE", "complete");
        bindings.put("alt LEFT", "back");
        bindings.put("alt RIGHT", "forward");
        bindings.put("F2", "save");
        bindings.put("F3", "open");
        bindings.put("F5", "zoom");
        bindings.put("F6", "focus");
        bindings.put("F9", "compile");
        bindings.put("ctrl F9", "run");
        bindings.put("alt F9", "compile");
        bindings.put("alt X", "quit");
        String mod = System.getProperty("os.name").contains("Mac") ? "meta " : "ctrl ";
        bindings.put(mod + "B", "declaration");
        for (var e :
                Map.of(
                                "S",
                                "save",
                                "O",
                                "open",
                                "N",
                                "new",
                                "F",
                                "find",
                                "R",
                                "run",
                                "ENTER",
                                "run",
                                "G",
                                "findNext",
                                "L",
                                "goto",
                                "PERIOD",
                                "stop")
                        .entrySet()) bindings.put(mod + e.getKey(), e.getValue());
        bindings.put(mod + "shift S", "saveAs");
        bindings.put(mod + "shift P", "palette");
        bindings.put(mod + "shift G", "findPrevious");
        bindings.put(mod + "SLASH", "comment");
        for (var e : keymap.entrySet())
            if (commands.containsKey(e.getKey()) && KeyStroke.getKeyStroke(e.getValue()) != null) {
                bindings.values().removeIf(id -> id.equals(e.getKey()));
                bindings.put(e.getValue(), e.getKey());
            }
        var im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = getRootPane().getActionMap();
        bindings.forEach(
                (key, id) -> {
                    var ks = KeyStroke.getKeyStroke(key);
                    if (ks != null) {
                        im.put(ks, id);
                        am.put(id, commands.get(id));
                        if (Set.of("complete", "declaration", "documentation", "back", "forward")
                                .contains(id)) editorBindings.put(ks, id);
                    }
                });
        im.put(KeyStroke.getKeyStroke("F10"), "menu");
        am.put(
                "menu",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        menus.getMenu(0).doClick();
                    }
                });
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
        am.put(
                "escape",
                new AbstractAction() {
                    public void actionPerformed(ActionEvent e) {
                        MenuSelectionManager.defaultManager().clearSelectedPath();
                        if (intelligence != null) intelligence.dismiss();
                        editor.requestFocusInWindow();
                    }
                });
    }

    public void applyTheme(Theme t) {
        theme = t;
        lastEditorTitle = "";
        lastMessageTitle = "";
        editor.setPalette(
                t.background(),
                t.foreground(),
                t.keyword(),
                t.string(),
                t.comment(),
                t.selection(),
                t.cursor(),
                t.error(),
                t.antialias());
        console.setTheme(t);
        repl.setTheme(t);
        if (intelligence != null) intelligence.setTheme(t);
        menus.setBackground(t.menuBackground());
        menus.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        for (var m : menus.getComponents()) styleMenu(m, t);
        bottom.setBackground(t.menuBackground());
        status.setForeground(t.menuForeground());
        position.setForeground(t.menuForeground());
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        status.setFont(mono);
        position.setFont(mono);
        editorPanel.setBackground(t.background());
        getContentPane().setBackground(t.background());
        refreshTitle();
        repaint();
    }

    private void styleMenu(Component c, Theme t) {
        c.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        c.setBackground(t.menuBackground());
        c.setForeground(t.menuForeground());
        if (c instanceof JMenu m) {
            m.getPopupMenu().setBorder(BorderFactory.createLineBorder(t.menuForeground(), 1));
            for (Component item : m.getMenuComponents()) styleMenu(item, t);
        }
    }

    public ReplPane repl() {
        return repl;
    }

    private void showRepl(boolean visible) {
        replVisible = visible;
        messages.removeAll();
        messages.add(visible ? repl : console, BorderLayout.CENTER);
        messages.setVisible(true);
        lastMessageTitle = "";
        refreshTitle();
        messages.revalidate();
        messages.repaint();
        if (visible) repl.focusInput();
        else editor.requestFocusInWindow();
    }

    private void evaluateStatement() {
        var syntax = compiler.latestSyntax();
        if (syntax == null || syntax.version() != editor.version()) {
            showError(
                    "Statement analysis is pending. Try again after checking completes, or select"
                            + " code to evaluate.");
            return;
        }
        var range = syntax.statementAt(editor.caretOffset());
        if (range == null) {
            showError(
                    "No complete statement at the caret. Select a Java expression or statement"
                            + " explicitly.");
            return;
        }
        String code = editor.snapshot().subSequence(range.start(), range.end()).toString();
        showRepl(true);
        repl.evaluate(code);
    }

    private void refreshTitle() {
        if (theme == null) return;
        String name = file == null ? "UNTITLED.JAVA" : file.getFileName().toString();
        String dirty =
                editor.isReadOnly() ? " [read only]" : editor.version() != savedVersion ? " *" : "";
        if (!getTitle().equals(name + dirty + " — TurboJ")) setTitle(name + dirty + " — TurboJ");
        if (!lastEditorTitle.equals(name + dirty)) {
            lastEditorTitle = name + dirty;
            editorPanel.setBorder(
                    BorderFactory.createTitledBorder(
                            BorderFactory.createLineBorder(theme.foreground()),
                            "[■]  " + name + dirty + "  ",
                            TitledBorder.CENTER,
                            TitledBorder.TOP,
                            new Font(Font.MONOSPACED, Font.PLAIN, 15),
                            theme.foreground()));
        }
        if (!lastMessageTitle.equals(buildStatus)) {
            lastMessageTitle = buildStatus;
            messages.setBorder(
                    BorderFactory.createTitledBorder(
                            BorderFactory.createLineBorder(theme.messagesForeground()),
                            (replVisible ? " Java REPL " : " Message · " + buildStatus + " "),
                            TitledBorder.CENTER,
                            TitledBorder.TOP,
                            new Font(Font.MONOSPACED, Font.PLAIN, 14),
                            theme.messagesForeground()));
        }
        messages.setBackground(theme.messagesBackground());
    }

    private void updatePosition() {
        position.setText(" " + editor.caretLine() + ":" + editor.caretColumn() + " ");
        if (intelligence != null) intelligence.changed();
    }

    private void changed() {
        if (observedDocument != editor.document()) {
            observedDocument = editor.document();
            operation.incrementAndGet();
            bufferIdentity.incrementAndGet();
            openRequest.incrementAndGet();
        }
        if (intelligence != null) intelligence.changed();
        programOutput = false;
        generation.set(editor.version());
        currentResult = null;
        buildStatus = "CHECKING…";
        editor.setDiagnostics(List.of());
        refreshTitle();
        updatePosition();
        if (editor.isReadOnly()) {
            if (pendingAnalysis != null) pendingAnalysis.cancel(false);
            if (pendingRecovery != null) pendingRecovery.cancel(false);
            buildStatus = "SOURCE ATTACHMENT · READ ONLY";
            refreshTitle();
            return;
        }
        var snap = editor.snapshot();
        String name = file == null ? null : file.getFileName().toString();
        long analysisOp = operation.get();
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        pendingAnalysis =
                background.schedule(
                        () -> {
                            if (generation.get() != snap.version() || operation.get() != analysisOp)
                                return;
                            String text = snap.toString();
                            compiler.submit(
                                    name == null
                                            ? SourceSnapshot.forBuffer(text, snap.version())
                                            : new SourceSnapshot(name, text, snap.version()),
                                    result ->
                                            SwingUtilities.invokeLater(
                                                    () -> {
                                                        if (operation.get() == analysisOp)
                                                            accept(result, false);
                                                    }));
                        },
                        35,
                        TimeUnit.MILLISECONDS);
        if (pendingRecovery != null) pendingRecovery.cancel(false);
        pendingRecovery =
                background.schedule(
                        () -> {
                            if (generation.get() != snap.version() || closing) return;
                            try {
                                Files.createDirectories(recovery.getParent());
                                SafeFiles.atomicWrite(recovery, snap.toString());
                            } catch (Exception ignored) {
                            }
                        },
                        2,
                        TimeUnit.SECONDS);
    }

    private void accept(CompilationResult result, boolean explicit) {
        if (closing || result.version() != editor.version()) return;
        currentResult = result;
        editor.setDiagnostics(result.diagnostics());
        long errors = result.diagnostics().stream().filter(d -> d.kind().equals("ERROR")).count();
        buildStatus =
                result.success()
                        ? (result.cacheHit() ? "CACHE HIT" : "BUILD OK")
                                + " | 0 errors | "
                                + ms(result.durationNanos())
                                + " ms"
                        : "BUILD FAILED | " + errors + " errors";
        refreshTitle();
        if (explicit || (!running && !programOutput)) {
            console.clear();
            console.append(buildStatus + "\n", !result.success());
            for (var d : result.diagnostics())
                console.append(
                        "  " + d.line() + ":" + d.column() + "  " + d.message() + "\n",
                        d.kind().equals("ERROR"));
        }
    }

    public CompletableFuture<CompilationResult> compile(boolean run, boolean isolated) {
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        var snap = editor.snapshot();
        String name = file == null ? null : file.getFileName().toString();
        long op = operation.incrementAndGet();
        long request = run ? ++runRequest : runRequest;
        CompletableFuture<RunResult> previousExecution = execution;
        if (run) {
            // Stop immediately, then await process termination asynchronously before dispatching.
            worker.stop();
            running = false;
            console.setProgramRunning(false);
        }
        messages.setVisible(true);
        buildStatus = "CHECKING…";
        refreshTitle();
        var future =
                CompletableFuture.supplyAsync(
                                () -> {
                                    String text = snap.toString();
                                    return name == null
                                            ? SourceSnapshot.forBuffer(text, snap.version())
                                            : new SourceSnapshot(name, text, snap.version());
                                },
                                background)
                        .thenCompose(compiler::compileNow);
        future.whenComplete(
                (result, error) ->
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (closing
                                            || snap.version() != editor.version()
                                            || op != operation.get()) return;
                                    if (error != null) {
                                        if (!(error instanceof CancellationException)
                                                && !(error.getCause()
                                                        instanceof CancellationException))
                                            showError(message(error));
                                        return;
                                    }
                                    if (result.version() != editor.version()
                                            || op != operation.get()) {
                                        console.append(
                                                "Source changed; press Run again for the current"
                                                        + " buffer.\n",
                                                false);
                                        return;
                                    }
                                    accept(result, true);
                                    if (run && result.success()) {
                                        previousExecution.whenComplete(
                                                (ignored, previousError) ->
                                                        SwingUtilities.invokeLater(
                                                                () -> {
                                                                    if (closing
                                                                            || request != runRequest
                                                                            || op != operation.get()
                                                                            || result.version()
                                                                                    != editor
                                                                                            .version())
                                                                        return;
                                                                    showRepl(false);
                                                                    startRun(
                                                                            result, isolated,
                                                                            request);
                                                                }));
                                    }
                                }));
        return future;
    }

    private void startRun(CompilationResult result, boolean isolated, long request) {
        if (result.mainClass() == null || result.mainClass().isBlank()) {
            console.append("No supported main entry point found.\n", true);
            return;
        }
        running = true;
        programOutput = true;
        console.clear();
        console.setProgramRunning(true);
        console.append(
                (isolated ? "ISOLATED RUN" : "FAST RUN") + " · " + result.mainClass() + "\n",
                false);
        long start = System.nanoTime();
        execution =
                worker.run(
                        result.bytecode(),
                        result.mainClass(),
                        List.of(),
                        isolated,
                        event -> {
                            // ConsolePane batches output before EDT delivery; never enqueue
                            // per-byte UI tasks.
                            if (!closing && request == runRequest)
                                console.append(event.text(), event.stderr());
                        },
                        waiting ->
                                SwingUtilities.invokeLater(
                                        () -> {
                                            if (closing || request != runRequest || !running)
                                                return;
                                            console.setWaitingForInput(waiting);
                                            if (waiting) {
                                                if (replVisible) showRepl(false);
                                                messages.setVisible(true);
                                                console.focusInput();
                                            }
                                        }));
        execution.whenComplete(
                (r, error) ->
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (closing || request != runRequest) return;
                                    running = false;
                                    boolean returnToEditor = console.hasConsoleFocus();
                                    console.setProgramRunning(false);
                                    if (error != null)
                                        console.append("\nWorker: " + message(error) + "\n", true);
                                    else
                                        console.append(
                                                "\n[exit "
                                                        + r.exitCode()
                                                        + " · execution "
                                                        + ms(r.durationNanos())
                                                        + " ms · round trip "
                                                        + ms(System.nanoTime() - start)
                                                        + " ms] "
                                                        + r.detail()
                                                        + "\n",
                                                r.exitCode() != 0);
                                    if (returnToEditor) editor.requestFocusInWindow();
                                }));
        console.focusInput();
    }

    private void goToError() {
        if (currentResult != null)
            currentResult.diagnostics().stream()
                    .filter(d -> d.kind().equals("ERROR"))
                    .findFirst()
                    .ifPresent(
                            d -> {
                                editor.gotoLine((int) d.line());
                                editor.requestFocusInWindow();
                            });
    }

    public void openPath(Path path) {
        Path destination = path.toAbsolutePath().normalize();
        if (destination.equals(bufferKey)) return;
        Buffer cached = buffers.get(destination);
        if (cached != null) {
            rememberBuffer();
            activate(destination, cached);
            return;
        }
        long request = openRequest.incrementAndGet();
        long identity = bufferIdentity.get();
        long version = editor.version();
        background.execute(
                () -> {
                    try {
                        Path p = path.toAbsolutePath().normalize();
                        var loaded = SafeFiles.readSnapshot(p);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (closing
                                            || openRequest.get() != request
                                            || bufferIdentity.get() != identity) return;
                                    if (editor.version() != version) {
                                        showError(
                                                "The buffer changed while opening. Open the file"
                                                        + " again after saving your changes.");
                                        return;
                                    }
                                    rememberBuffer();
                                    operation.incrementAndGet();
                                    bufferIdentity.incrementAndGet();
                                    file = p;
                                    bufferKey = p;
                                    diskFingerprint = loaded.fingerprint();
                                    editor.setText(loaded.text());
                                    savedVersion = editor.version();
                                    settings.recentFiles.remove(p.toString());
                                    settings.recentFiles.addFirst(p.toString());
                                    if (settings.recentFiles.size() > 10)
                                        settings.recentFiles.removeLast();
                                    refreshTitle();
                                    editor.requestFocusInWindow();
                                });
                    } catch (Exception e) {
                        errorLater(e);
                    }
                });
    }

    private void save(boolean as, Runnable next) {
        if (editor.isReadOnly() && !as) {
            showError("Attached source is read only. Use Save as to create an editable copy.");
            return;
        }
        if (saving) {
            deferredSave = new DeferredSave(as, next, bufferIdentity.get());
            return;
        }
        Path target = file;
        if (as || target == null) {
            var chooser = new JFileChooser();
            chooser.setSelectedFile(
                    new java.io.File(file == null ? "Hello.java" : file.getFileName().toString()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            target = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        }
        Path chosen = target;
        Buffer other = buffers.get(chosen);
        if (!chosen.equals(bufferKey)
                && other != null
                && other.state().document() != editor.document()
                && other.state().document().version() != other.savedVersion()) {
            showError("The destination already has unsaved changes in another open buffer.");
            return;
        }
        boolean same = chosen.equals(file);
        byte[] expected = diskFingerprint;
        var snapshot = editor.snapshot();
        long identity = bufferIdentity.get();
        Path originKey = bufferKey;
        var originDocument = editor.document();
        saving = true;
        background.execute(
                () -> {
                    try {
                        byte[] baseline = same ? expected : SafeFiles.fingerprint(chosen);
                        Runnable write =
                                () ->
                                        writeSave(
                                                chosen,
                                                baseline,
                                                snapshot,
                                                identity,
                                                originKey,
                                                originDocument,
                                                next);
                        if (!same && baseline != null)
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (closing || identity != bufferIdentity.get()) {
                                            finishSave();
                                            return;
                                        }
                                        if (JOptionPane.showConfirmDialog(
                                                        this,
                                                        "Replace " + chosen.getFileName() + "?",
                                                        "Save",
                                                        JOptionPane.YES_NO_OPTION)
                                                == JOptionPane.YES_OPTION)
                                            background.execute(write);
                                        else finishSave();
                                    });
                        else write.run();
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!closing) showError(message(e));
                                    finishSave();
                                });
                    }
                });
    }

    private void writeSave(
            Path target,
            byte[] expected,
            com.turboj.editor.document.PieceTable.Snapshot snapshot,
            long identity,
            Path originKey,
            com.turboj.editor.document.PieceTable originDocument,
            Runnable next) {
        try {
            byte[] written =
                    SafeFiles.atomicWriteIfUnchanged(target, snapshot.toString(), expected);
            SwingUtilities.invokeLater(
                    () -> {
                        boolean active = editor.document() == originDocument;
                        Buffer inactive = buffers.get(originKey);
                        if (!closing
                                && (active
                                        || (inactive != null
                                                && inactive.state().document()
                                                        == originDocument))) {
                            boolean renamed = !target.equals(originKey);
                            if (renamed) {
                                buffers.remove(originKey);
                                navigation.remap(
                                        v ->
                                                v.key().equals(originKey)
                                                        ? new Visit(
                                                                target, v.location(), v.version())
                                                        : v);
                            }
                            var oldState = active ? editor.captureState() : inactive.state();
                            var state =
                                    new EditorComponent.EditorState(
                                            originDocument, oldState.location(), false);
                            buffers.put(
                                    target, new Buffer(target, state, written, snapshot.version()));
                            if (active) {
                                file = target;
                                bufferKey = target;
                                editor.setReadOnly(false);
                                diskFingerprint = written;
                                savedVersion = snapshot.version();
                                if (renamed) {
                                    operation.incrementAndGet();
                                    changed();
                                }
                                refreshTitle();
                            }
                            console.append("Saved " + target.getFileName() + "\n", false);
                            if (active
                                    && bufferIdentity.get() == identity
                                    && editor.version() == savedVersion) next.run();
                        }
                        finishSave();
                    });
        } catch (Exception e) {
            SwingUtilities.invokeLater(
                    () -> {
                        if (!closing) showError(message(e));
                        finishSave();
                    });
        }
    }

    private void finishSave() {
        saving = false;
        DeferredSave pending = deferredSave;
        deferredSave = null;
        if (!closing && pending != null && pending.identity() == bufferIdentity.get())
            save(pending.as(), pending.continuation());
    }

    private void requestQuit() {
        rememberBuffer();
        var dirty = new ArrayList<Path>();
        buffers.forEach(
                (key, value) -> {
                    if (!value.state().readOnly()
                            && value.state().document().version() != value.savedVersion())
                        dirty.add(key);
                });
        confirmBuffers(dirty, 0);
    }

    private void confirmBuffers(List<Path> dirty, int index) {
        if (index == dirty.size()) {
            shutdown();
            return;
        }
        Path key = dirty.get(index);
        if (!key.equals(bufferKey)) {
            rememberBuffer();
            activate(key, buffers.get(key));
        }
        withUnsaved(() -> confirmBuffers(dirty, index + 1));
    }

    private void withUnsaved(Runnable next) {
        if (editor.version() == savedVersion) {
            next.run();
            return;
        }
        int answer =
                JOptionPane.showConfirmDialog(
                        this,
                        "Save changes before continuing?",
                        "Unsaved source",
                        JOptionPane.YES_NO_CANCEL_OPTION);
        if (answer == JOptionPane.YES_OPTION) save(false, next);
        else if (answer == JOptionPane.NO_OPTION) next.run();
    }

    public void offerRecovery() {
        background.execute(
                () -> {
                    try {
                        if (!Files.exists(recovery)) return;
                        String text = SafeFiles.readUtf8(recovery);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (editor.version() != savedVersion) return;
                                    if (JOptionPane.showConfirmDialog(
                                                    this,
                                                    "Restore the recoverable editor buffer?",
                                                    "TurboJ recovery",
                                                    JOptionPane.YES_NO_OPTION)
                                            == JOptionPane.YES_OPTION) {
                                        editor.setText(text);
                                        savedVersion = -1;
                                        refreshTitle();
                                    }
                                });
                    } catch (Exception e) {
                        errorLater(e);
                    }
                });
    }

    private void palette() {
        var ids = commands.keySet().toArray(String[]::new);
        var labels =
                commands.values().stream()
                        .map(a -> a.getValue(Action.NAME).toString())
                        .toArray(String[]::new);
        var list = new JList<>(labels);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        var searchField = new JTextField();
        var model = new DefaultListModel<String>();
        var visible = new ArrayList<String>();
        Runnable filter =
                () -> {
                    model.clear();
                    visible.clear();
                    for (int i = 0; i < ids.length; i++)
                        if (labels[i]
                                .toLowerCase(Locale.ROOT)
                                .contains(searchField.getText().toLowerCase(Locale.ROOT))) {
                            model.addElement(labels[i]);
                            visible.add(ids[i]);
                        }
                    list.setModel(model);
                    if (!model.isEmpty()) list.setSelectedIndex(0);
                };
        searchField
                .getDocument()
                .addDocumentListener(
                        new javax.swing.event.DocumentListener() {
                            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                                filter.run();
                            }

                            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                                filter.run();
                            }

                            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                                filter.run();
                            }
                        });
        filter.run();
        var panel = new JPanel(new BorderLayout(0, 8));
        panel.add(searchField, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(460, 350));
        if (JOptionPane.showConfirmDialog(
                                this,
                                panel,
                                "Commands",
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.PLAIN_MESSAGE)
                        == JOptionPane.OK_OPTION
                && list.getSelectedIndex() >= 0)
            commands.get(visible.get(list.getSelectedIndex())).actionPerformed(null);
    }

    public void shutdown() {
        if (closing) return;
        closing = true;
        settings.width = getWidth();
        settings.height = getHeight();
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        if (pendingRecovery != null) pendingRecovery.cancel(false);
        worker.close();
        repl.close();
        compiler.close();
        intelligence.close();
        background.execute(
                () -> {
                    try {
                        settings.save();
                        Files.deleteIfExists(recovery);
                    } catch (Exception e) {
                        System.err.println(message(e));
                    } finally {
                        SwingUtilities.invokeLater(this::dispose);
                    }
                });
        background.shutdown();
    }

    private void showError(String message) {
        programOutput = true;
        console.append(message + "\n", true);
        buildStatus = "ERROR";
        refreshTitle();
    }

    private void errorLater(Throwable e) {
        SwingUtilities.invokeLater(() -> showError(message(e)));
    }

    private static String message(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1e6);
    }

    private static String pct(double[] a, double p) {
        return String.format(
                Locale.ROOT, "%.2f", a[Math.min(a.length - 1, (int) Math.ceil(a.length * p) - 1)]);
    }
}
