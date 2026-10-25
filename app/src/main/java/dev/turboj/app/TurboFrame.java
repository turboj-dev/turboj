package dev.turboj.app;

import dev.turboj.ai.*;
import dev.turboj.compiler.*;
import dev.turboj.config.*;
import dev.turboj.execution.*;
import dev.turboj.formatting.FormattingController;
import dev.turboj.commandj.core.*;
import dev.turboj.refactoring.*;
import dev.turboj.testing.TurboTestController;
import dev.turboj.testing.TestOutput;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.refactorj.api.Operation;
import dev.turboj.refactorj.api.Model;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.intelligence.*;
import dev.turboj.refactorj.api.JavaIntelligence.Declaration;
import dev.turboj.ui.*;
import dev.turboj.ui.themes.*;
import dev.turboj.project.MavenProjectController;
import dev.turboj.project.MavenCompilationWorkspace;
import dev.turboj.project.MavenRunTargets;
import dev.turboj.project.SourceEncoding;
import dev.turboj.mavenj.api.MavenModels;

import java.awt.*;
import java.awt.event.*;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.JTextComponent;

public final class TurboFrame extends JFrame {
    public static final String HELLO =
            "public class Hello {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        System.out.println(\"Hello TurboJ!\");\n"
                    + "    }\n"
                    + "}\n";
    private final EditorComponent editor = new EditorComponent();
    private final WorkerClient worker = new WorkerClient();
    private WorkerClient programWorker = worker;
    private record ProjectWorkerKey(Path home, List<Path> modulePath, String moduleName, String mainClass) {}
    private final Map<ProjectWorkerKey, WorkerClient> projectWorkers = new HashMap<>();
    private volatile MavenCompilationWorkspace mavenCompiler;
    private volatile long generatedSourceRevision;
    private final ScheduledThreadPoolExecutor nativeProjectCompiler = nativeCompilerExecutor();
    private ScheduledFuture<?> pendingMavenCompile;
    private Future<?> nativeCompileTask;
    private CompletableFuture<NativeCompilation> pendingNativeResult;

    private static ScheduledThreadPoolExecutor nativeCompilerExecutor() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "turboj-native-maven-compile"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    private final ContinuousCompiler compiler = new ContinuousCompiler(new JavacCompilerBackend());
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "turboj-files-snapshots");
                        t.setDaemon(true);
                        return t;
                    });
    private final Settings settings;
    private final ThemeCatalog themeCatalog;
    private final ConsolePane console =
            new ConsolePane(
                    s -> {
                        try {
                            programWorker.sendInput(s);
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            showError(e.getMessage());
                        }
                    });
    private final ReplPane repl = new ReplPane();
    private boolean replVisible;
    private final JLabel status = new JLabel(" READY");
    private String projectStatusText = "", projectStatusDetail = "";
    private final JLabel position = new JLabel(" 1:1 ", SwingConstants.RIGHT);
    private final JPanel editorPanel = new JPanel(new BorderLayout());
    private final JPanel messages = new JPanel(new BorderLayout());
    private final JPanel bottom = new JPanel(new BorderLayout());
    private final MavenProjectController maven;
    private JSplitPane projectSplit;
    private JSplitPane outputSplit;
    private int outputHeight = 190;
    private boolean outputLayoutReady;
    private final AsciiMenuBar menus = new AsciiMenuBar();
    private long themeChoiceGeneration;
    private final Map<String, Action> commands = new LinkedHashMap<>();
    private final Map<String, String> keymap;
    private final ButtonGroup keyboardProfileGroup = new ButtonGroup();
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final KeymapManager commandKeymaps = new KeymapManager();
    private CommandKeyDispatcher commandDispatcher;
    private AutoCloseable keymapListener;
    private Component lastTextCommandTarget = editor;
    private final java.beans.PropertyChangeListener textCommandFocus = event -> {
        if (event.getNewValue() instanceof Component target && isTextCommandTarget(target)
                && SwingUtilities.isDescendingFrom(target, this) && !OverlayHost.active(this))
            lastTextCommandTarget = target;
    };
    private volatile long keymapPreferenceVersion;
    private final Map<Path, Buffer> buffers = new LinkedHashMap<>();
    private final Map<Path, SourceEncoding.Encoding> fileEncodings = new ConcurrentHashMap<>();
    private final Set<Path> externalSourceFiles = new HashSet<>();
    private final NavigationHistory<Visit> navigation = new NavigationHistory<>();
    private final NavigationHistory<Visit> editLocations = new NavigationHistory<>();
    private final ArrayDeque<Map.Entry<Path, Buffer>> closedBuffers = new ArrayDeque<>();
    private final ArrayDeque<Visit> recentLocations = new ArrayDeque<>();
    private boolean editorMaximized;
    private boolean restoreExplorer, restoreMessages;
    private int restoreDivider;
    private Path bufferKey = untitledKey();
    private IntelligenceController intelligence;
    private KeyboardJavaController keyboardJava;
    private AICompletionController aiCompletion;
    private FormattingController formatting;
    private RefactoringController refactoring;
    private TurboTestController tests;
    private final TestOutput testOutput = new TestOutput(console);
    private List<String> testCompilerDetails = List.of();
    private NativeWorkspaceCommitter.Capture testCapture;
    private Model.WorkspaceSnapshot testWorkspace;
    private EmbeddedDialog testResults;
    private boolean pendingTestSnapshot;
    private long testSelectionRequest;
    private boolean saveTestRun;
    private record SaveAutomation(Path file, long version, long revision, long runGeneration, boolean run, boolean tests) {}
    private SaveAutomation queuedSaveAutomation, runningSaveAutomation, pendingSaveRun;
    private final javax.swing.Timer saveAutomationTimer = new javax.swing.Timer(150, event -> trySaveAutomation());
    private MavenRunTargets.Target lastMavenRunTarget;
    private String lastPlainRunTarget;
    private List<String> programArguments = List.of();
    private String configuredInput = "";
    private Visit lastEditVisit;

    private record TestInvocation(Model.WorkspaceSnapshot workspace,
            TurboTestController.Selection selection) {}

    private boolean refactoringPublishing;
    private long refactoringRevision;

    private record RefactoringHistory(Map<Path, Buffer> before, Map<Path, Buffer> after, Map<Path,
            String> beforeText, Map<Path, String> afterText,
            Map<dev.turboj.editor.document.PieceTable, Long> transactions,
            Map<dev.turboj.editor.document.PieceTable, Long> stableHeads, Path activeBefore,
            Path activeAfter) {}

    private final CredentialStore aiCredentials = new MacKeychainCredentialStore();
    private String aiStatus = "";
    private dev.turboj.editor.document.PieceTable observedDocument;

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
    private boolean saving, formattingSave;
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
        editor.setShowPressedShortcuts(settings.showPressedShortcuts);
        this.themeCatalog =
                new ThemeCatalog(Settings.directory().resolve("themes"), externalThemePath());
        this.keymap = keymap;
        initializeKeymap();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(640, 420));
        setSize(settings.width, settings.height);
        setLocationByPlatform(true);
        editorPanel.add(editor, BorderLayout.CENTER);
        maven = new MavenProjectController(new MavenProjectController.Host() {
            @Override public Component owner() { return TurboFrame.this; }
            @Override public Path activeFile() { return file; }
            @Override public Map<Path, String> pomOverlays() {
                rememberBuffer();
                Map<Path, String> overlays = new LinkedHashMap<>();
                buffers.forEach((path, buffer) -> {
                    if (path.getFileName().toString().equals("pom.xml") && !buffer.state().readOnly()
                            && buffer.state().document().version() != buffer.savedVersion())
                        overlays.put(path, buffer.state().document().toString());
                });
                return Map.copyOf(overlays);
            }
            @Override public void openFile(Path path) { openPath(path); }
            @Override public void navigate(Path path, int offset, boolean readOnly) {
                if (readOnly) externalSourceFiles.add(path.toAbsolutePath().normalize());
                openPath(path);
                javax.swing.Timer follow = new javax.swing.Timer(25, null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                follow.addActionListener(event -> {
                    if (path.equals(file)) { editor.gotoOffset(offset); follow.stop(); }
                    else if (System.nanoTime() > deadline) follow.stop();
                });
                follow.start();
            }
            @Override public void published(MavenModels.Workspace value) {
                if (keyboardJava != null) keyboardJava.changed();
                if (intelligence != null) intelligence.changed();
                updateMavenStatus();
                if (file != null && value.moduleFor(file).isPresent() && isJavaBuffer()) changed();
                else if (isXmlBuffer()) publishPomDiagnostics();
            }
            @Override public void notice(String text, boolean error) { console.append(text, error); }
            @Override public void previewPom(String title, java.util.function.UnaryOperator<String> edit) { previewPomEdit(title, edit); }
            @Override public void stateChanged() {
                updateMavenStatus();
                if (projectSplit != null) {
                    projectSplit.setDividerSize(maven.panel().isVisible() ? 5 : 0);
                    if (maven.panel().isVisible() && projectSplit.getDividerLocation() < 130) projectSplit.setDividerLocation(235);
                    projectSplit.revalidate();
                    refreshWorkspaceMinimums();
                }
            }
        });
        messages.add(console, BorderLayout.CENTER);
        console.setInlineInput(true);
        console.setProgramRunning(false);
        var content = new JPanel(new BorderLayout());
        projectSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, maven.panel(), editorPanel);
        projectSplit.setBorder(null); projectSplit.setDividerSize(0); projectSplit.setResizeWeight(0);
        outputSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, projectSplit, messages) {
            @Override public void doLayout() {
                super.doLayout();
                // Swing preserves a pixel divider location even when the window becomes smaller.
                // Clamp after layout so neither pane disappears after resizing or changing zoom.
                if (getBottomComponent().isVisible()) {
                    int available = getHeight() - getDividerSize();
                    int lower = getTopComponent().getMinimumSize().height;
                    int upper = available - getBottomComponent().getMinimumSize().height;
                    if (upper >= lower) {
                        int current = getUI().getDividerLocation(this);
                        int bounded = Math.clamp(current, lower, upper);
                        if (bounded != current) { setDividerLocation(bounded); super.doLayout(); }
                    }
                }
            }
        };
        outputSplit.setName("workspace-output-split");
        outputSplit.getAccessibleContext().setAccessibleName("Resize output vertically");
        outputSplit.setToolTipText("Drag to resize output; use Resize Output in the command palette, then Up/Down");
        outputSplit.setBorder(null); outputSplit.setDividerSize(5); outputSplit.setResizeWeight(1);
        outputSplit.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { refreshTitle(); }
            @Override public void focusLost(FocusEvent event) { refreshTitle(); }
        });
        outputSplit.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (!outputLayoutReady && outputSplit.getHeight() > 0) {
                    outputLayoutReady = true;
                    refreshWorkspaceMinimums(); // Native title-bar insets exist only after peer creation.
                    restoreOutputHeight();
                }
            }
        });
        content.add(outputSplit, BorderLayout.CENTER);
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(position, BorderLayout.EAST);
        bottom.setName("workspace-status-line");
        status.setName("workspace-status");
        status.getAccessibleContext().setAccessibleName("Workspace status");
        position.setName("caret-status");
        bottom.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updatePositionLabel(); }
        });
        add(bottom, BorderLayout.SOUTH);
        setJMenuBar(menus);
        InFramePopupFactory.install(getRootPane());
        createCommands();
        createMenus();
        menus.sealMenus();
        bindKeys();
        applyTheme(Theme.bundled(settings.theme));
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
        keyboardJava = new KeyboardJavaController(this, editor, intelligence, this::intelligenceSnapshot,
                this::navigateDeclaration, text -> console.append(text + "\n", false));
        intelligence.setProjectProvider(new IntelligenceController.ProjectProvider() {
            private MavenModels.Workspace previous;
            private ProjectContext.Config cached;
            private long previousGenerated;
            @Override public ProjectContext.Config context(Path source, String text) throws Exception {
                var workspace = maven.workspace();
                if (workspace == null || workspace.moduleFor(source).isEmpty()) return ProjectContext.detect(source, text);
                if (workspace != previous || previousGenerated != generatedSourceRevision) {
                    cached = ProjectContext.fromMaven(workspace, generatedSourceRevision == 0 ? null : mavenCompiler, generatedSourceRevision);
                    previous = workspace; previousGenerated = generatedSourceRevision;
                }
                return cached;
            }
        });
        KeyStroke acceptCompletion =
                KeyStroke.getKeyStroke(keymap.getOrDefault("acceptCompletion", "TAB"));
        KeyStroke dismissCompletion =
                KeyStroke.getKeyStroke(keymap.getOrDefault("dismissCompletion", "ESCAPE"));
        intelligence.setCompletionKeys(
                acceptCompletion == null ? KeyStroke.getKeyStroke("TAB") : acceptCompletion,
                dismissCompletion == null ? KeyStroke.getKeyStroke("ESCAPE") : dismissCompletion);
        aiCompletion =
                new AICompletionController(
                        editor,
                        intelligence.completions(),
                        () -> new AICompletionController.Snapshot(bufferKey, editor.snapshot()),
                        settings.ai,
                        config ->
                                AIProviders.create(
                                        config,
                                        () -> {
                                            try {
                                                return aiCredentials.read(config.cloudEndpoint());
                                            } catch (java.io.IOException error) {
                                                throw new UncheckedIOException(error);
                                            }
                                        }),
                        text -> console.append(text + "\n", false),
                        text -> {
                            aiStatus = text;
                            refreshStatusLine();
                        });
        aiCompletion.setKeys(
                acceptCompletion == null ? KeyStroke.getKeyStroke("TAB") : acceptCompletion,
                dismissCompletion == null ? KeyStroke.getKeyStroke("ESCAPE") : dismissCompletion,
                shortcut("aiAcceptWord", "ctrl alt ENTER"));
        formatting =
                new FormattingController(
                        editor,
                        text -> console.append(text + "\n", false),
                        this::formattingApplied);
        refactoring = new RefactoringController(this, () -> {
            Rectangle caret = editor.caretBounds();
            Point point = new Point(caret.x, caret.y + caret.height);
            SwingUtilities.convertPointToScreen(point, editor);
            return point;
        }, intelligence, this::intelligenceSnapshot, () -> new Model.Location(bufferKey, Math
                .min(editor.captureLocation().caret(), editor.captureLocation().anchor()), Math
                .max(editor.captureLocation().caret(), editor.captureLocation().anchor())),
                new NativeRefactoringHost(), text -> console.append(text + "\n", false), () -> {
            operation.incrementAndGet();
            long revision = refactoringRevision;
            changed();
            refactoringRevision = revision;
            if (tests != null && tests.automatic())
                startTests(TurboTestController.Mode.ALL, true);
        });
        tests = new TurboTestController(new NativeTestHost());
        keyboardJava.setRefactoring(refactoring::previewRequest);
        tests.setAutomatic(settings.autoTest);
        // CommandJ owns shortcut dispatch, including completion controls. The coordinators are
        // invoked by registered commands; their former keys cannot bypass a customized profile.
        editor.setKeyHandler(e -> false);
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
        restoreExternalTheme();
        themeCatalog.request("", 0, null, ignored -> {});
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

    public AICompletionController aiCompletion() {
        return aiCompletion;
    }

    public FormattingController formatting() {
        return formatting;
    }

    public RefactoringController refactoring() {
        return refactoring;
    }

    public TurboTestController tests() {
        return tests;
    }

    public MavenProjectController mavenProjects() { return maven; }

    private boolean isXmlBuffer() {
        return file != null && isXmlPath(file);
    }

    private static boolean isXmlPath(Path path) { String name = path.getFileName().toString().toLowerCase(Locale.ROOT); return name.endsWith(".xml") || name.endsWith(".pom"); }

    private boolean isJavaBuffer() {
        return file == null || file.getFileName().toString().endsWith(".java");
    }

    private void updateMavenStatus() {
        if (maven == null) return;
        var value = maven.workspace();
        String project = "";
        if (value != null) {
            String java = value.modules().stream().filter(module -> !module.packaging().equals("pom"))
                    .map(module -> module.mainJava().languageLevel()).filter(level -> !level.isBlank())
                    .distinct().sorted().reduce((a, b) -> a + "/" + b).orElse("?");
            project = "Maven · Java " + java + (value.resolved() ? "" : " · Dependencies incomplete")
                    + (value.offline() ? " · Offline" : "");
        }
        projectStatusText = project;
        projectStatusDetail = value == null ? project : MavenProjectController.statusText(value);
        refreshStatusLine();
    }

    private void refreshStatusLine() {
        String state = compactStatus(buildStatus);
        if (maven != null && maven.lifecycleRunning()) state = "MAVEN BUILD…";
        else if (maven != null && maven.busy()) state = "MAVEN SYNC…";
        else if (maven != null && maven.workspace() != null && !maven.workspace().resolved()
                && !state.contains("FAILED")) state = "DEPENDENCIES INCOMPLETE";
        if (aiStatus.endsWith("unavailable")) state += " | " + aiStatus;
        status.setText(" " + state + (projectStatusText.isEmpty() ? "" : " | " + projectStatusText));
        String shortcuts = commandKeymaps.shortcutText("save") + " Save   " + commandKeymaps.shortcutText("compile")
                + " Fast Compile   " + commandKeymaps.shortcutText("run") + " Run   "
                + commandKeymaps.shortcutText("palette") + " Actions";
        status.setToolTipText((projectStatusDetail.isEmpty() ? "" : projectStatusDetail + "   ") + buildStatus
                + (aiStatus.isEmpty() ? "" : "   " + aiStatus) + "   " + shortcuts);
    }

    static String compactStatus(String value) {
        String first = value.split("\\|", 2)[0].strip();
        if (first.equals("CACHE HIT")) return "BUILD OK";
        return first.replace("TURBOJ NATIVE · ", "NATIVE ");
    }

    private java.nio.charset.Charset sourceEncoding(Path path) {
        return SourceEncoding.select(maven.workspace(), path, fileEncodings.get(path), null).charset();
    }

    private synchronized MavenCompilationWorkspace nativeMavenCompiler() {
        if (mavenCompiler == null) {
            try { mavenCompiler = new MavenCompilationWorkspace(); }
            catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
        }
        return mavenCompiler;
    }

    private void publishPomDiagnostics() {
        if (!isXmlBuffer()) return;
        var workspace = maven.workspace();
        if (workspace == null) return;
        var diagnostics = workspace.diagnostics().stream().filter(d -> d.file() != null && d.file().equals(file))
                .map(d -> new CompilerDiagnostic(d.severity().name(), d.message(), d.line(), d.column(), -1, -1)).toList();
        editor.setDiagnostics(diagnostics);
        buildStatus = workspace.resolved() ? "MAVEN MODEL READY" : "MAVEN MODEL · DIAGNOSTICS";
        refreshTitle();
    }

    private void previewPomEdit(String title, java.util.function.UnaryOperator<String> edit) {
        var document = editor.document();
        var snapshot = editor.snapshot();
        background.execute(() -> {
            try {
                String original = snapshot.toString(), result = edit.apply(original);
                SwingUtilities.invokeLater(() -> {
                    if (closing || editor.document() != document || editor.version() != snapshot.version()) return;
                    JTextArea preview = new JTextArea(result, 24, 76); preview.setEditable(false);
                    preview.setCaretPosition(0); preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                    if (EmbeddedOptionPane.showConfirmDialog(this, new JScrollPane(preview), title + " — Preview", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                    if (editor.document() != document || editor.version() != snapshot.version()) { showError("POM changed during preview; prepare the edit again."); return; }
                    editor.selectAll();
                    editor.replaceSelection(result);
                });
            } catch (Exception failure) { errorLater(failure); }
        });
    }

    private void navigatePomDeclaration() {
        if (file == null || !isXmlBuffer()) return;
        Path selected = file; int offset = editor.caretOffset(); var snapshot = editor.snapshot();
        background.execute(() -> maven.navigatePom(selected, snapshot.toString(), offset));
    }

    public Path currentFile() {
        return file;
    }

    private IntelligenceController.Snapshot intelligenceSnapshot() {
        var open = new LinkedHashMap<Path, dev.turboj.editor.document.PieceTable.Snapshot>();
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
        rememberLocation();
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
                                                    new dev.turboj.editor.document.PieceTable(
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
        registerCommand(id, label, commandCategory(id), action, context -> commandAvailability(id,
                context));
    }

    /** Extension point for native ecosystem actions; menus/palette/shortcuts share this registration. */
    public Action registerCommand(String id, String title, String category, Runnable action,
            java.util.function.Function<CommandContext, Availability> availability) {
        return registerContextCommand(id, title, category, context -> action.run(), availability);
    }

    private Action registerContextCommand(String id, String title, String category,
            java.util.function.Consumer<CommandContext> action,
            java.util.function.Function<CommandContext, Availability> availability) {
        commandRegistry.register(Command.of(id, title, category, (context, cancellation) -> {
            action.accept(context);
            return CompletableFuture.completedFuture(CommandResult.success());
        }).withAliases(commandAliases(id)).requiring(commandContexts(id)).when(context -> context.has("overlay")
                ? Availability.disabled("Close the active dialog first") : availability.apply(context)));
        var result = new CommandAction(commandRegistry, id, this::commandContext,
                this::commandFinished);
        commands.put(id, result);
        if (commandDispatcher != null) refreshShortcutPresentation();
        return result;
    }

    private static String[] commandAliases(String id) {
        if (id.startsWith("keymap.profile."))
            return new String[]{"switch keyboard profile", "change keymap", id.replace('.', ' ')};
        return switch (id) {
            case "deleteLine" -> new String[]{"remove row", "clear line", "delete current line"};
            case "duplicateLine" -> new String[]{"duplicate row", "copy line below"};
            case "keymap" -> new String[]{"keyboard settings", "configure shortcuts", "bindings"};
            case "editor.moveStatementUp", "editor.moveStatementDown", "editor.moveCodeBlockUp", "editor.moveCodeBlockDown" -> new String[]{"move method", "move statement", "move block"};
            case "palette" -> new String[]{"find action", "show all commands"};
            default -> new String[]{id.replace('.', ' ')};
        };
    }

    private static String[] commandContexts(String id) {
        if (id.startsWith("editor.") || id.startsWith("java.") || id.startsWith("refactor.")
                || id.startsWith("completion.") || id.startsWith("documentation.")
                || Set.of("complete", "documentation", "ai.request", "ai.another", "ai.larger").contains(id)
                || Set.of("duplicateLine", "deleteLine", "moveLineUp", "moveLineDown", "indent", "unindent", "comment").contains(id))
            return new String[]{"editor"};
        if (id.startsWith("explorer.")) return new String[]{"explorer"};
        return new String[0];
    }

    public CommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public KeymapManager commandKeymaps() {
        return commandKeymaps;
    }

    private void commandFinished(CommandResult result) {
        if (result.status() == CommandResult.Status.FAILED || result.status()
                == CommandResult.Status.DISABLED)
            console.append(result.message() + "\n", false);
    }

    private CommandContext commandContext() {
        Set<String> tags = new HashSet<> ();
        tags.add("workspace");
        if (OverlayHost.active(this)) tags.add("overlay");
        if (editor.hasInputComposition()) tags.add("input-method");
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (focus == editor || ((focus == null || focus instanceof MenuElement)
                && lastTextCommandTarget == editor)) tags.add("editor");
        if (maven.hasExplorerFocus()) tags.add("explorer");
        if (console.hasConsoleFocus()) tags.add("console");
        if (repl.hasConsoleFocus()) tags.add("jshell");
        if (!editor.isReadOnly())
            tags.add("editable");
        var selection = editor.captureLocation();

        if (selection.caret() != selection.anchor())
            tags.add("selection");
        if (running)
            tags.add("running");
        if (editor.inlineCompletion() != null)
            tags.add("completion");
        if (intelligence != null && intelligence.alternativesVisible()) {
            tags.add("completion");
            tags.add("completion-popup");
        }
        var attributes = new HashMap<String, Object>();
        attributes.put("documentVersion", editor.version());
        Component textTarget = focusedTextCommandTarget();
        if (textTarget != null) attributes.put("textCommandTarget", textTarget);
        return new CommandContext(tags, attributes);
    }

    private boolean isTextCommandTarget(Component target) {
        return target == editor || target instanceof JTextComponent;
    }

    /** Swing menus temporarily take focus; palette actions retain their captured text target. */
    private Component focusedTextCommandTarget() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (!OverlayHost.active(this) && isTextCommandTarget(focus)
                && SwingUtilities.isDescendingFrom(focus, this)) return focus;
        if (focus == null || focus instanceof MenuElement || OverlayHost.active(this))
            return lastTextCommandTarget.isShowing() ? lastTextCommandTarget : null;
        return null;
    }

    private Component textCommandTarget(CommandContext context) {
        return context.value("textCommandTarget", Component.class)
                .orElse(context.has("editor") ? editor : null);
    }

    private Availability textCommandAvailability(String id, CommandContext context) {
        Component target = textCommandTarget(context);
        boolean selected, editable;
        if (target == editor) {
            var location = editor.captureLocation();
            selected = location.caret() != location.anchor();
            editable = !editor.isReadOnly();
        } else if (target instanceof JTextComponent text && text.isEnabled()) {
            selected = text.getSelectionStart() != text.getSelectionEnd();
            editable = text.isEditable();
            if (id.equals("cut") && SwingUtilities.getAncestorOfClass(ConsolePane.class, text)
                    instanceof ConsolePane pane) editable &= pane.isSelectionEditable(text);
        } else return Availability.disabled("Focus a text area");
        if ((id.equals("cut") || id.equals("paste")) && !editable)
            return Availability.disabled("This text is read only");
        if ((id.equals("copy") || id.equals("cut")) && !selected && target != editor)
            return Availability.disabled("Select text first");
        return Availability.available();
    }

    private void textCommand(String id, String title) {
        registerContextCommand(id, title, "Edit", context -> {
            Component target = textCommandTarget(context);
            if (target == editor) {
                switch (id) {
                    case "copy" -> editor.copy();
                    case "cut" -> editor.cut();
                    case "paste" -> editor.paste();
                    case "selectAll" -> editor.selectAll();
                    default -> throw new IllegalArgumentException(id);
                }
            } else if (target instanceof JTextComponent text) {
                switch (id) {
                    case "copy" -> text.copy();
                    case "cut" -> text.cut();
                    case "paste" -> text.paste();
                    case "selectAll" -> text.selectAll();
                    default -> throw new IllegalArgumentException(id);
                }
            }
        }, context -> closing ? Availability.disabled("The application is closing")
                : textCommandAvailability(id, context));
    }

    private Availability commandAvailability(String id, CommandContext context) {
        if (id.startsWith("explorer.") && !context.has("explorer")) return Availability.disabled("Focus the Project Explorer");
        if (Set.of("view.focusExplorer", "view.toggleExplorer", "navigation.mavenModule", "navigation.projectDependency").contains(id)
                && maven.importRequest() == null) return Availability.disabled("Open a Maven project first");
        if (Set.of("view.focusTestResults", "view.toggleTestPanel", "navigation.testFailure").contains(id)
                && (tests == null || tests.lastReport() == null)) return Availability.disabled("Run tests first");
        if (id.equals("navigation.testFailure") && tests.lastReport().failed() == 0) return Availability.disabled("No failed tests");
        if (id.equals("console.copy") && console.inputComponent().getSelectionStart() == console.inputComponent().getSelectionEnd())
            return Availability.disabled("Select console output first");
        if (id.equals("file.reopenClosed") && closedBuffers.isEmpty()) return Availability.disabled("No closed files to reopen");
        if (id.equals("view.restoreLayout") && !editorMaximized) return Availability.disabled("The editor is not maximized");
        if (id.equals("navigation.goToTest") && (!isJavaBuffer() || file == null)) return Availability.disabled("Open a saved Java source file");
        boolean projectCommand = Set.of("compile", "run", "isolated", "compileProject", "make",
                "tests.all", "tests.failed", "tests.current", "tests.class").contains(id);
        boolean mavenProject = maven.importRequest() != null;
        if (mavenProject && projectCommand) {
            if (maven.busy()) return Availability.disabled("Maven project is loading dependencies");
            if (maven.workspace() == null) return Availability.disabled("Maven import failed — see output and Reload Maven Project");
            if (!maven.workspace().resolved()) return Availability.disabled("Maven dependencies are incomplete — Refresh Dependencies or check Maven Settings");
        }
        if (!isJavaBuffer() && (id.startsWith("refactor.") || id.equals("organizeImports") || id.startsWith("format.")
                || id.startsWith("completion.") || id.startsWith("documentation.")
                || Set.of("complete", "documentation", "evaluateStatement").contains(id)
                || !mavenProject && Set.of("compile", "run", "isolated").contains(id)))
            return Availability.disabled("Focus a Java source file");
        if (id.startsWith("maven.") || id.startsWith("project.")) {
            if (!Set.of("project.open", "project.explorer").contains(id) && maven.importRequest() == null)
                return Availability.disabled("Open a Maven project first");
            if (id.equals("maven.stop") && !maven.lifecycleRunning()) return Availability.disabled("No Maven build is running");
            if (id.startsWith("maven.build.") && maven.lifecycleRunning()) return Availability.disabled("A Maven build is already running");
            if (!Set.of("project.open", "project.explorer", "project.reload", "project.settings", "project.profiles", "maven.refresh", "maven.stop").contains(id)
                    && !id.startsWith("maven.build.") && maven.workspace() == null) return Availability.disabled("Maven import has not completed");
            if (id.equals("project.processors") && (!isJavaBuffer() || file == null || maven.workspace() == null || maven.workspace().moduleFor(file).isEmpty()))
                return Availability.disabled("Focus Java source in a Maven module");
        }
        if (closing)
            return Availability.disabled("The application is closing");
        Set<String> edits = Set.of("undo", "redo", "cut", "paste", "indent", "unindent", "comment",
                "duplicateLine", "deleteLine", "moveLineUp", "moveLineDown", "format.document",
                "format.selection");

        if (edits.contains(id) && !context.has("editable"))
            return Availability.disabled("Attached source is read only");
        if ((edits.contains(id) || Set.of("copy", "selectAll").contains(id)) && !context
                .has("editor"))
            return Availability.disabled("Focus the source editor");
        if (Set.of("cut", "copy", "format.selection", "evaluateSelection").contains(id) && !context
                .has("selection"))
            return Availability.disabled("Select source text first");
        if (id.equals("undo") && !editor.document().canUndo() && (refactoring == null
                || !refactoring.canUndo()))
            return Availability.disabled("Nothing to undo");
        if (id.equals("redo") && !editor.document().canRedo() && (refactoring == null
                || !refactoring.canRedo()))
            return Availability.disabled("Nothing to redo");
        if (id.equals("eof") && !running)
            return Availability.disabled("No program is running");
        if (id.equals("stop") && !running && !hasPendingSaveRun())
            return Availability.disabled("No program is running or waiting to run");
        if (id.equals("completion.accept") && !context.has("completion"))
            return Availability.disabled("No completion is visible");
        if (Set.of("completion.next", "completion.previous").contains(id) && !context
                .has("completion-popup"))
            return Availability.disabled("Completion alternatives are not open");
        if (id.equals("completion.acceptWord") && !editor.isAIInlineCompletion())
            return Availability.disabled("No AI suggestion is visible");
        if (id.startsWith("refactor.") || id.equals("organizeImports")) {
            if (!context.has("editor") || !context.has("editable"))
                return Availability.disabled("Focus editable Java source");
            if (refactoring == null || refactoring.busy())
                return Availability.disabled("A semantic operation is in progress");
        }
        if (id.startsWith("tests.") || id.equals("compileProject") || id.equals("make")) {
            if (tests == null)
                return Availability.disabled("Test controller is initializing");
            if (id.equals("tests.stop") && !tests.busy() && !pendingTestSnapshot
                    && (queuedSaveAutomation == null || !queuedSaveAutomation.tests()))
                return Availability.disabled("No tests are running or waiting to run");
            if (Set.of("tests.current", "tests.class", "tests.all", "tests.failed",
                    "compileProject", "make").contains(id)) {
                if (tests.busy() || pendingTestSnapshot || (refactoring != null && refactoring
                        .busy()))
                    return Availability.disabled("A project operation is already running");
                boolean wholeMavenProject = mavenProject && Set.of("tests.all", "tests.failed", "compileProject", "make").contains(id);
                if (!wholeMavenProject && (!context.has("editor") || !context.has("editable")))
                    return Availability.disabled("Focus editable Java source");
            }
            if (id.equals("tests.failed") && (tests.lastReport() == null || tests.lastReport()
                    .failed() == 0))
                return Availability.disabled("No failed tests to rerun");
            if (id.equals("tests.results") && tests.lastReport() == null)
                return Availability.disabled("No test results yet");
        }
        return Availability.available();
    }

    private static String commandCategory(String id) {
        if (id.startsWith("view.") || id.startsWith("explorer.")) return "Panels";
        if (id.startsWith("navigation.")) return "Navigation";
        if (id.startsWith("file.")) return "File";
        if (id.startsWith("settings.")) return "Settings";
        if (id.startsWith("project.")) return "Project";
        if (id.startsWith("maven.")) return "Maven";
        if (id.startsWith("tests."))
            return "TurboTest";
        if (id.equals("compileProject") || id.equals("make"))
            return "Compilation";
        if (id.startsWith("refactor."))
            return "Refactoring";
        if (id.startsWith("format.") || id.equals("organizeImports"))
            return "Formatting";
        if (id.startsWith("ai.") || id.startsWith("completion.") || id.equals("complete"))
            return "Java Intelligence";
        if (id.startsWith("theme.") || Set.of("fontUp", "fontDown", "zoom", "fullscreen", "showPressedShortcuts")
                .contains(id))
            return "Appearance";
        if (Set.of("new", "open", "save", "saveAs", "quit").contains(id))
            return "File";
        if (Set.of("run", "run.onSave", "isolated", "stop", "eof", "clear", "repl", "replReset", "output",
                "focus", "evaluateSelection", "evaluateStatement").contains(id))
            return "Execution";
        if (Set.of("compile", "error").contains(id))
            return "Compilation";
        if (Set.of("declaration", "documentation", "back", "forward", "goto").contains(id))
            return "Navigation";
        if (id.startsWith("keymap.") || Set.of("keymap", "palette", "help", "metrics", "menu", "escape").contains(id))
            return "Settings";
        return "Edit";
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
                    editor.requestFocusInWindow();
                });
        command(
                "open",
                "Open…",
                () -> {
                    var c = new EmbeddedFileChooser();
                    c.setFileFilter(
                            new javax.swing.filechooser.FileNameExtensionFilter(
                                    "Java and XML source", "java", "xml"));
                    if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        openPath(c.getSelectedFile().toPath());
                });
        command("save", "Save", () -> save(false, () -> {}));
        command("openFolder", "Open Folder / Maven Project…", maven::chooseProject);
        command("project.open", "Open Maven Project…", maven::chooseProject);
        command("project.reload", "Reload Maven Project", () -> maven.reload(false));
        command("project.structure", "Project Structure…", maven::showStructure);
        command("project.dependencies", "Dependencies…", maven::showDependencies);
        command("project.dependencyTree", "Dependency Graph…", maven::showDependencies);
        command("project.profiles", "Active Profiles…", maven::showProfiles);
        command("project.settings", "Maven Settings…", maven::showSettings);
        command("project.effectivePom", "Effective POM…", maven::showEffectivePom);
        command("project.parentPom", "Go to Parent POM", maven::openParentPom);
        command("project.pomDeclaration", "Go to POM Declaration", this::navigatePomDeclaration);
        command("project.sources", "Attach Dependency Sources…", () -> maven.downloadAttachment("sources"));
        command("project.javadoc", "Attach Dependency Javadoc…", () -> maven.downloadAttachment("javadoc"));
        command("project.processors", "Generate Annotation Sources…", () -> { if (maven.requireTrust()) compileMaven(false, false, true, true); });
        command("project.explorer", "Toggle Project Explorer", maven::toggleExplorer);
        command("project.findFile", "Find Project File…", () -> maven.findFile(false));
        command("project.findModule", "Find Module…", () -> maven.findFile(true));
        command("maven.refresh", "Refresh Dependencies", () -> maven.reload(true));
        command("maven.stop", "Stop Maven Build", maven::cancelLifecycle);
        for (String goal : List.of("validate", "compile", "test", "package", "verify", "install", "clean", "dependency:tree", "help:effective-pom"))
            command("maven.build." + goal, "Maven " + goal, () -> maven.runLifecycle(goal));
        command("maven.build.custom", "Run Maven Goal…", () -> maven.runLifecycle(null));
        for (String action : List.of("Add Dependency", "Update Dependency Version", "Remove Dependency", "Add Module"))
            command("project.pom." + action.toLowerCase(Locale.ROOT).replace(' ', '-'), action + "…", () -> maven.editPom(action));
        command("saveAs", "Save as…", () -> save(true, () -> {}));
        command("quit", "Quit TurboJ", this::requestQuit);
        command("complete", "Completion alternatives", () -> intelligence.complete(true));
        command("completion.smart", "Smart Completion", () -> intelligence.smartComplete());
        command("completion.parameterInformation", "Parameter Information", () -> intelligence.parameterInformation());
        command("documentation.focus", "Focus Quick Documentation", () -> intelligence.focusDocumentation());
        command(
                "declaration",
                "Go to declaration",
                () -> { if (isXmlBuffer()) navigatePomDeclaration(); else intelligence.goToDeclaration(editor.caretOffset()); });
        command("documentation", "Quick documentation", () -> intelligence.quickDocumentation());
        command("back", "Navigate back", this::navigateBack);
        command("forward", "Navigate forward", this::navigateForward);
        command("format.document", "Format Document", () -> formatting.format(false));
        command("format.selection", "Format Selection", () -> formatting.format(true));
        command(
                "format.onSave",
                "Format on Save",
                () -> {
                    settings.formatOnSave = !settings.formatOnSave;
                    commands.get("format.onSave")
                            .putValue(Action.SELECTED_KEY, settings.formatOnSave);
                    String snapshot = settings.snapshotJson();
                    background.execute(
                            () -> {
                                try {
                                    Settings.saveSnapshot(snapshot);
                                } catch (java.io.IOException error) {
                                    errorLater(error);
                                }
                            });
                });
        commands.get("format.onSave").putValue(Action.SELECTED_KEY, settings.formatOnSave);
        command("ai.request", "Request AI completion", () -> aiCompletion.request(true, false));
        command("ai.another", "Request another AI completion", () -> aiCompletion.requestAnother());
        command(
                "ai.larger",
                "Request a longer AI completion",
                () -> aiCompletion.request(true, true));
        command("ai.settings", "Configure AI completion…", () -> showAISettings(settings.ai));
        command(
                "ai.disabled",
                "Disabled",
                () -> saveAIConfig(settings.ai.withMode(AIConfig.Mode.DISABLED)));
        command(
                "ai.local",
                "Local…",
                () -> showAISettings(settings.ai.withMode(AIConfig.Mode.LOCAL)));
        command(
                "ai.cloud",
                "Cloud…",
                () -> showAISettings(settings.ai.withMode(AIConfig.Mode.CLOUD)));
        command("undo", "Undo", () -> {
            if (refactoring != null && refactoring.canUndo())
                refactoring.undo();
            else
                editor.undo();
        });
        command("redo", "Redo", () -> {
            if (refactoring != null && refactoring.canRedo())
                refactoring.redo();
            else
                editor.redo();
        });
        textCommand("cut", "Cut");
        textCommand("copy", "Copy");
        textCommand("paste", "Paste");
        textCommand("selectAll", "Select all");
        command("duplicateLine", "Duplicate line", editor::duplicateLine);
        command("deleteLine", "Delete line", editor::deleteLine);
        command("moveLineUp", "Move line up", () -> editor.moveLine(-1));
        command("moveLineDown", "Move line down", () -> editor.moveLine(1));
        command("indent", "Indent", () -> editor.indent(false));
        command("unindent", "Unindent", () -> editor.indent(true));
        command("comment", "Toggle line comment", editor::toggleComment);
        command(
                "find",
                "Find…",
                () -> {
                    String s = EmbeddedOptionPane.showInputDialog(this, "Find text:", search);
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
                    String s = EmbeddedOptionPane.showInputDialog(this, "Replace selected text with:", "");
                    if (s != null) editor.replaceSelection(s);
                });
        command(
                "goto",
                "Go to line…",
                () -> {
                    String s = EmbeddedOptionPane.showInputDialog(this, "Line number:");
                    try {
                        if (s != null) editor.gotoLine(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        showError("Enter a line number.");
                    }
                });
        command("compile", "Fast Compile", () -> compile(false, false));
        command("run", "Fast Run", () -> compile(true, false));
        command("isolated", "Isolated Run", () -> compile(true, true));
        command(
                "stop",
                "Stop program",
                () -> {
                    if (replVisible) repl.cancel();
                    ++runRequest;
                    queuedSaveAutomation = withoutSaveAction(queuedSaveAutomation, true);
                    runningSaveAutomation = withoutSaveAction(runningSaveAutomation, true);
                    pendingSaveRun = null;
                    if (queuedSaveAutomation == null) saveAutomationTimer.stop();
                    programWorker.stop();
                    running = false;
                    console.setProgramRunning(false);
                    console.append("\nProgram stopped.\n", false);
                });
        command("eof", "Send EOF", () -> programWorker.closeInput());
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
                    setMessagesVisible(!messages.isVisible());
                    revalidate();
                });
        command(
                "focus",
                "Switch editor / console",
                () -> {
                    if (console.hasConsoleFocus() || repl.hasConsoleFocus())
                        editor.requestFocusInWindow();
                    else {
                        setMessagesVisible(true);
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
                    () -> selectTheme(Theme.bundled(id), null));
        command("theme.more", "More themes…", this::showThemeBrowser);
        command(
                "theme.load",
                "Load theme JSON…",
                () -> {
                    var chooser = new EmbeddedFileChooser(Settings.directory().resolve("themes").toFile());
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        Path path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                        long choice = ++themeChoiceGeneration;
                        background.execute(
                                () -> {
                                    try {
                                        Theme loaded = Theme.load(path);
                                        SwingUtilities.invokeLater(
                                                () -> {
                                                    if (choice == themeChoiceGeneration && !closing)
                                                        selectTheme(loaded, path);
                                                });
                                    } catch (Exception e) {
                                        errorLater(e);
                                    }
                                });
                    }
                });
        command(
                "fontUp",
                "Larger font",
                () -> changeInterfaceFontSize(settings.fontSize + 2));
        command(
                "fontDown",
                "Smaller font",
                () -> changeInterfaceFontSize(settings.fontSize - 2));
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
                    EmbeddedOptionPane.showMessageDialog(
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
                                    + "\n\n"
                                    + aiCompletion.metrics()
                                    + "\n"
                                    + "These are local measured samples, not performance"
                                    + " guarantees.",
                            "TurboJ Performance",
                            JOptionPane.INFORMATION_MESSAGE);
                });
        command("palette", "Command palette…", this::palette);
        command(
                "help", "Keyboard reference…", () -> new ShortcutReferenceDialog(this, commandRegistry, commandKeymaps, commandContext()).setVisible(true));
        command("keymap", "Keymap…", this::showKeymapEditor);
        for (var profile : BundledKeymaps.profiles())
            command("keymap.profile." + profile.id(), "Keyboard Profile: " + profile.name(),
                    () -> commandKeymaps.select(profile.id()));
        command("menu", "Open menu", () -> menus.getMenu(0).doClick());
        command("escape", "Return to editor / dismiss", () -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            boolean completion = editor.inlineCompletion() != null || (intelligence != null && intelligence.alternativesVisible());
            if (aiCompletion != null)
                aiCompletion.dismiss();
            if (intelligence != null)
                intelligence.dismiss();
            if (!completion) editor.removeSecondaryCarets();
            editor.requestFocusInWindow();
        });
        command("completion.accept", "Accept suggestion", () -> completionKey("TAB"));
        command("completion.acceptWord", "Accept next AI word",
                () -> completionKey("ctrl alt ENTER"));
        command("completion.next", "Next completion", () -> completionKey("DOWN"));
        command("completion.previous", "Previous completion", () -> completionKey("UP"));
        command("completion.dismiss", "Dismiss suggestion", () -> {
            aiCompletion.dismiss();
            intelligence.dismiss();
            editor.clearInlineCompletion();
        });
        for (Operation operation : Operation.values()) {
            String id = operation == Operation.ORGANIZE_IMPORTS ? "organizeImports" : "refactor."
                    + operation.id();
            command(id, RefactoringController.title(operation) + "…", () -> refactoring
                    .invoke(operation));
        }
        command("refactor.this", "Refactor This…", () -> refactoring.choose(false));
        command("refactor.context-actions", "Context Actions…", () -> refactoring.choose(true));
        command("refactor.inline", "Inline…", () -> refactoring.chooseInline());
        command("compileProject", "Compile Project",
                () -> startTests(TurboTestController.Mode.COMPILE, false));
        command("make", "Make Project", () -> startTests(TurboTestController.Mode.COMPILE, false));
        command("tests.current", "Run Current Test",
                () -> startTests(TurboTestController.Mode.METHOD, false));
        command("tests.class", "Run Test Class", () -> startTests(TurboTestController.Mode.CLASS,
                false));
        command("tests.all", "Run All Tests", () -> startTests(TurboTestController.Mode.ALL,
                false));
        command("tests.failed", "Run Failed Tests",
                () -> startTests(TurboTestController.Mode.FAILED, false));
        command("tests.stop", "Stop Tests", this::stopTests);
        command("tests.results", "Test Results…", this::showTestResults);
        command("tests.auto", "Auto Test on Changes", () -> {
            if (!settings.autoTest && maven.workspace() != null && !maven.requireTrust()) return;
            settings.autoTest = !settings.autoTest;
            tests.setAutomatic(settings.autoTest);
            commands.get("tests.auto").putValue(Action.SELECTED_KEY, settings.autoTest);
            String snapshot = settings.snapshotJson();
            background.execute(() -> {
                try {
                    Settings.saveSnapshot(snapshot);
                } catch (java.io.IOException failure) {
                    errorLater(failure);
                }
            });
        });
        commands.get("tests.auto").putValue(Action.SELECTED_KEY, settings.autoTest);
        command("run.onSave", "Auto Run on Save", () -> toggleSaveAutomation(true));
        commands.get("run.onSave").putValue(Action.SELECTED_KEY, settings.autoRunOnSave);
        command("tests.onSave", "Auto Test on Save", () -> toggleSaveAutomation(false));
        commands.get("tests.onSave").putValue(Action.SELECTED_KEY, settings.autoTestOnSave);
        command("showPressedShortcuts", "Show Pressed Shortcuts", () -> {
            settings.showPressedShortcuts = !settings.showPressedShortcuts;
            editor.setShowPressedShortcuts(settings.showPressedShortcuts);
            commands.get("showPressedShortcuts").putValue(Action.SELECTED_KEY, settings.showPressedShortcuts);
            String snapshot = settings.snapshotJson();
            background.execute(() -> {
                try { Settings.saveSnapshot(snapshot); }
                catch (java.io.IOException failure) { errorLater(failure); }
            });
        });
        commands.get("showPressedShortcuts").putValue(Action.SELECTED_KEY, settings.showPressedShortcuts);
        createKeyboardCommands();
        createJavaKeyboardCommands();
    }

    private void javaKeyboardCommand(String id, String label, boolean modifies, Runnable action) {
        registerCommand(id, label, "Java Structure", action, context -> {
            if (!context.has("editor") || !isJavaBuffer()) return Availability.disabled("Focus Java source");
            if (modifies && editor.isReadOnly()) return Availability.disabled("Attached source is read only");
            if (editor.selections().size() > 1) return Availability.disabled("Java structure actions use one source range; remove secondary carets first");
            if (keyboardJava == null || intelligence == null || (refactoring != null && refactoring.busy()))
                return Availability.disabled("Java analysis is initializing or a refactoring is active");
            return Availability.available();
        });
    }

    private void createJavaKeyboardCommands() {
        var selections = Map.of("expandSelection", dev.turboj.refactorj.api.JavaStructure.Selection.EXPAND,
                "selectCurrentStatement", dev.turboj.refactorj.api.JavaStructure.Selection.STATEMENT,
                "selectCurrentMethod", dev.turboj.refactorj.api.JavaStructure.Selection.METHOD,
                "selectCurrentBlock", dev.turboj.refactorj.api.JavaStructure.Selection.BLOCK,
                "selectEnclosingType", dev.turboj.refactorj.api.JavaStructure.Selection.TYPE,
                "selectMatchingBrackets", dev.turboj.refactorj.api.JavaStructure.Selection.BRACKETS);
        for (var entry : selections.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            javaKeyboardCommand("editor." + entry.getKey(), humanize(entry.getKey()), false, () -> keyboardJava.select(entry.getValue()));
        javaKeyboardCommand("editor.shrinkSelection", "Shrink Selection", false, () -> keyboardJava.shrinkSelection());
        var edits = Map.ofEntries(
                Map.entry("editor.moveStatementUp", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_STATEMENT_UP),
                Map.entry("editor.moveStatementDown", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_STATEMENT_DOWN),
                Map.entry("editor.moveCodeBlockUp", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_BLOCK_UP),
                Map.entry("editor.moveCodeBlockDown", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_BLOCK_DOWN),
                Map.entry("editor.moveElementLeft", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_ELEMENT_LEFT),
                Map.entry("editor.moveElementRight", dev.turboj.refactorj.api.JavaStructure.Edit.MOVE_ELEMENT_RIGHT),
                Map.entry("java.generateConstructor", dev.turboj.refactorj.api.JavaStructure.Edit.CONSTRUCTOR),
                Map.entry("java.generateGetters", dev.turboj.refactorj.api.JavaStructure.Edit.GETTERS),
                Map.entry("java.generateSetters", dev.turboj.refactorj.api.JavaStructure.Edit.SETTERS),
                Map.entry("java.generateGetterSetter", dev.turboj.refactorj.api.JavaStructure.Edit.GETTERS_SETTERS),
                Map.entry("java.generateEquals", dev.turboj.refactorj.api.JavaStructure.Edit.EQUALS),
                Map.entry("java.generateHashCode", dev.turboj.refactorj.api.JavaStructure.Edit.HASH_CODE),
                Map.entry("java.generateToString", dev.turboj.refactorj.api.JavaStructure.Edit.TO_STRING),
                Map.entry("java.implementMethods", dev.turboj.refactorj.api.JavaStructure.Edit.IMPLEMENT_METHODS),
                Map.entry("java.overrideMethods", dev.turboj.refactorj.api.JavaStructure.Edit.OVERRIDE_METHODS),
                Map.entry("java.surroundIf", dev.turboj.refactorj.api.JavaStructure.Edit.SURROUND_IF),
                Map.entry("java.surroundTryFinally", dev.turboj.refactorj.api.JavaStructure.Edit.SURROUND_TRY_FINALLY),
                Map.entry("java.completeStatement", dev.turboj.refactorj.api.JavaStructure.Edit.COMPLETE_STATEMENT),
                Map.entry("java.generateJavadoc", dev.turboj.refactorj.api.JavaStructure.Edit.JAVADOC));
        for (var entry : edits.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            javaKeyboardCommand(entry.getKey(), humanize(entry.getKey().substring(entry.getKey().indexOf('.') + 1)) + "…", true,
                    () -> keyboardJava.edit(entry.getValue()));
        var navigation = Map.ofEntries(
                Map.entry("goToSymbol", dev.turboj.refactorj.api.JavaStructure.Navigation.SYMBOLS),
                Map.entry("goToType", dev.turboj.refactorj.api.JavaStructure.Navigation.TYPES),
                Map.entry("goToImplementation", dev.turboj.refactorj.api.JavaStructure.Navigation.IMPLEMENTATIONS),
                Map.entry("findReferences", dev.turboj.refactorj.api.JavaStructure.Navigation.REFERENCES),
                Map.entry("typeHierarchy", dev.turboj.refactorj.api.JavaStructure.Navigation.TYPE_HIERARCHY),
                Map.entry("callHierarchy", dev.turboj.refactorj.api.JavaStructure.Navigation.CALLERS),
                Map.entry("calledMethods", dev.turboj.refactorj.api.JavaStructure.Navigation.CALLEES),
                Map.entry("nextMethod", dev.turboj.refactorj.api.JavaStructure.Navigation.NEXT_METHOD),
                Map.entry("previousMethod", dev.turboj.refactorj.api.JavaStructure.Navigation.PREVIOUS_METHOD),
                Map.entry("matchingBrace", dev.turboj.refactorj.api.JavaStructure.Navigation.MATCHING_BRACE));
        for (var entry : navigation.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            javaKeyboardCommand("navigation." + entry.getKey(), humanize(entry.getKey()), false, () -> keyboardJava.navigate(entry.getValue()));
        commandRegistry.alias("navigation.findUsages", "navigation.findReferences");
        commandRegistry.alias("editor.moveBlockUp", "editor.moveCodeBlockUp");
        commandRegistry.alias("editor.moveBlockDown", "editor.moveCodeBlockDown");
        commandRegistry.alias("java.introduceLocalVariable", "refactor.extract-variable");
        for (var alias : Map.ofEntries(
                Map.entry("goFile", "navigation.goToFile"), Map.entry("goType", "navigation.goToType"),
                Map.entry("goSymbol", "navigation.goToSymbol"), Map.entry("implementation", "navigation.goToImplementation"),
                Map.entry("references", "navigation.findReferences"), Map.entry("signature", "completion.parameterInformation"),
                Map.entry("recentFiles", "navigation.recentFiles"), Map.entry("navigation.nextEditor", "navigation.nextEditorTab"),
                Map.entry("navigation.previousEditor", "navigation.previousEditorTab"), Map.entry("navigation.closeEditor", "file.close"),
                Map.entry("navigation.reopenEditor", "file.reopenClosed"), Map.entry("navigation.previousEdit", "navigation.previousEditLocation"),
                Map.entry("editor.selectLine", "editor.selectCurrentLine")).entrySet()) commandRegistry.alias(alias.getKey(), alias.getValue());
    }

    private static String humanize(String value) {
        String text = value.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void editorCommand(String id, String title, boolean modifies, Runnable action) {
        registerCommand(id, title, "Edit", action, context -> {
            if (!context.has("editor")) return Availability.disabled("Focus the source editor");
            if (modifies && !context.has("editable")) return Availability.disabled("Attached source is read only");
            if (id.equals("editor.autoIndentLines") && !isJavaBuffer()) return Availability.disabled("Focus Java source");
            if (id.equals("editor.autoIndentLines") && editor.selections().size() > 1)
                return Availability.disabled("Auto-indent uses one selected range; remove secondary carets first");
            if (editor.hasInputComposition()) return Availability.disabled("Finish input-method composition first");
            return Availability.available();
        });
    }

    private void createKeyboardCommands() {
        editorCommand("editor.copyLineUp", "Copy Line Up", true, () -> editor.copyLine(-1));
        editorCommand("editor.copyLineDown", "Copy Line Down", true, () -> editor.copyLine(1));
        editorCommand("editor.insertLineAbove", "Insert Line Above", true, () -> editor.insertLine(true));
        editorCommand("editor.insertLineBelow", "Insert Line Below", true, () -> editor.insertLine(false));
        editorCommand("editor.joinLines", "Join Lines", true, editor::joinLines);
        editorCommand("editor.splitLine", "Split Line", true, editor::splitLine);
        editorCommand("editor.deleteToLineStart", "Delete to Line Start", true, editor::deleteToLineStart);
        editorCommand("editor.deleteToLineEnd", "Delete to Line End", true, editor::deleteToLineEnd);
        editorCommand("editor.deletePreviousWord", "Delete Previous Word", true, editor::deletePreviousWord);
        editorCommand("editor.deleteNextWord", "Delete Next Word", true, editor::deleteNextWord);
        editorCommand("editor.trimTrailingWhitespace", "Trim Trailing Whitespace", true, editor::trimTrailingWhitespace);
        editorCommand("editor.convertTabsToSpaces", "Convert Tabs to Spaces", true, editor::convertTabsToSpaces);
        editorCommand("editor.convertSpacesToTabs", "Convert Leading Spaces to Tabs", true, editor::convertSpacesToTabs);
        editorCommand("editor.toggleBlockComment", "Toggle Block Comment", true, editor::toggleBlockComment);
        editorCommand("editor.upperCase", "Convert Selection to Uppercase", true, () -> editor.changeCase(true));
        editorCommand("editor.lowerCase", "Convert Selection to Lowercase", true, () -> editor.changeCase(false));
        editorCommand("editor.changeCase", "Change Selection Case…", true, () -> {
            Object choice = EmbeddedOptionPane.showInputDialog(this, "Convert selected text:", "Change Case",
                    JOptionPane.PLAIN_MESSAGE, null, new String[]{"Uppercase", "Lowercase"}, "Uppercase");
            if (choice != null) editor.changeCase(choice.equals("Uppercase"));
        });
        editorCommand("editor.sortSelectedLines", "Sort Selected Lines", true, editor::sortSelectedLines);
        editorCommand("editor.reverseSelectedLines", "Reverse Selected Lines", true, editor::reverseSelectedLines);
        editorCommand("editor.transposeCharacters", "Transpose Characters", true, editor::transposeCharacters);
        editorCommand("editor.smartHome", "Smart Home", false, () -> editor.smartHome(false));
        editorCommand("editor.smartEnd", "Smart End", false, () -> editor.smartEnd(false));
        editorCommand("editor.selectCurrentWord", "Select Current Word", false, editor::selectCurrentWord);
        editorCommand("editor.selectCurrentLine", "Select Current Line", false, editor::selectCurrentLine);
        editorCommand("editor.selectNextOccurrence", "Select Next Occurrence", false, editor::selectNextOccurrence);
        editorCommand("editor.selectAllOccurrences", "Select All Occurrences", false, editor::selectAllOccurrences);
        editorCommand("editor.addCaretAbove", "Add Caret Above", false, editor::addCaretAbove);
        editorCommand("editor.addCaretBelow", "Add Caret Below", false, editor::addCaretBelow);
        editorCommand("editor.removeSecondaryCarets", "Remove Secondary Carets", false, editor::removeSecondaryCarets);
        editorCommand("editor.toggleColumnSelection", "Rectangular Selection from Selected Range", false, () -> {
            var location = editor.captureLocation();
            editor.rectangularSelection(location.anchor(), location.caret());
        });
        editorCommand("editor.autoIndentLines", "Auto-Indent Java Lines", true, () -> {
            if (editor.captureLocation().caret() == editor.captureLocation().anchor()) editor.selectCurrentLine();
            formatting.format(true);
        });
        command("view.focusEditor", "Focus Editor", () -> editor.requestFocusInWindow());
        command("view.focusExplorer", "Focus Project Explorer", maven::focusExplorer);
        command("view.focusConsole", "Focus Console", this::focusConsole);
        command("view.resizeOutput", "Resize Output", () -> {
            setMessagesVisible(true);
            outputSplit.requestFocusInWindow();
        });
        command("view.focusJShell", "Focus JShell", () -> showRepl(true));
        command("view.focusTestResults", "Focus Test Results", this::showTestResults);
        command("view.focusDiagnostics", "Compiler Diagnostics…", this::showDiagnostics);
        command("view.focusNextPanel", "Focus Next Panel", () -> focusPanel(1));
        command("view.focusPreviousPanel", "Focus Previous Panel", () -> focusPanel(-1));
        command("view.toggleExplorer", "Toggle Explorer", maven::toggleExplorer);
        command("view.toggleConsole", "Toggle Console", () -> {
            if (messages.isVisible() && !replVisible) { setMessagesVisible(false); editor.requestFocusInWindow(); }
            else focusConsole();
            revalidate();
        });
        command("view.toggleTestPanel", "Toggle Test Results", () -> {
            if (testResults != null && testResults.isVisible()) testResults.dispose(); else showTestResults();
        });
        command("view.maximizeEditor", "Maximize Editor Area", this::maximizeEditor);
        command("view.restoreLayout", "Restore Previous Layout", this::restoreEditorLayout);
        command("explorer.open", "Open Selected Explorer Item", maven::openSelectedItem);
        command("explorer.expand", "Expand Explorer Item", () -> maven.expandSelectedItem(true));
        command("explorer.collapse", "Collapse Explorer Item", () -> maven.expandSelectedItem(false));
        command("console.copy", "Copy Selected Console Output", () -> console.inputComponent().copy());
        command("navigation.nextError", "Next Error", () -> nextDiagnostic(true, 1));
        command("navigation.previousError", "Previous Error", () -> nextDiagnostic(true, -1));
        command("navigation.nextWarning", "Next Warning", () -> nextDiagnostic(false, 1));
        command("navigation.previousWarning", "Previous Warning", () -> nextDiagnostic(false, -1));
        command("navigation.recentFiles", "Recent Files…", this::chooseRecentFile);
        command("navigation.recentLocations", "Recent Locations…", this::chooseRecentLocation);
        command("navigation.previousEditLocation", "Previous Edit Location", () -> navigateEdit(false));
        command("navigation.nextEditLocation", "Next Edit Location", () -> navigateEdit(true));
        command("navigation.nextEditorTab", "Next Open File", () -> cycleBuffer(1));
        command("navigation.previousEditorTab", "Previous Open File", () -> cycleBuffer(-1));
        command("file.close", "Close Current File", this::closeBuffer);
        command("file.reopenClosed", "Reopen Closed File", this::reopenClosedBuffer);
        command("file.saveAll", "Save All", () -> saveAllBuffers(new ArrayList<>(dirtyBufferKeys()), 0, editor.document()));
        command("navigation.goToFile", "Go to File…", this::findFile);
        command("navigation.goToTest", "Navigate Between Source and Test", this::navigateRelatedTest);
        command("navigation.testFailure", "Go to Test Failure", () -> {
            tests.lastReport().tests().stream().filter(test -> test.status() == TestEngine.Status.FAILED).findFirst().ifPresent(this::navigateTest);
        });
        command("navigation.mavenModule", "Navigate to Maven Module…", () -> maven.findFile(true));
        command("navigation.projectDependency", "Navigate to Project Dependency", maven::focusDependencies);
        command("run.configuration", "Program Arguments and Standard Input…", this::showRunConfiguration);
        command("settings.general", "General Settings…", this::showGeneralSettings);
        for (var alias : Map.of("editor.deleteLine", "deleteLine", "editor.duplicateLine", "duplicateLine",
                "editor.moveLineUp", "moveLineUp", "editor.moveLineDown", "moveLineDown", "editor.toggleLineComment", "comment",
                "editor.indentSelection", "indent", "editor.unindentSelection", "unindent", "navigation.goToDefinition", "declaration",
                "test.rerunFailed", "tests.failed", "maven.refreshProject", "maven.refresh").entrySet())
            commandRegistry.alias(alias.getKey(), alias.getValue());
        commandRegistry.alias("editor.cutLine", "cut");
        commandRegistry.alias("editor.copyLine", "copy");
    }

    private void focusConsole() {
        showRepl(false);
        console.focusInput();
    }

    private void focusPanel(int direction) {
        var targets = new ArrayList<Runnable>();
        targets.add(() -> editor.requestFocusInWindow());
        int current = 0;
        if (maven.panel().isVisible()) {
            targets.add(maven::focusExplorer);
            if (maven.hasExplorerFocus()) current = targets.size() - 1;
        }
        if (messages.isVisible()) {
            targets.add(() -> { if (replVisible) repl.focusInput(); else console.focusInput(); });
            if (console.hasConsoleFocus() || repl.hasConsoleFocus()) current = targets.size() - 1;
        }
        targets.get(Math.floorMod(current + direction, targets.size())).run();
    }

    private void maximizeEditor() {
        if (editorMaximized) return;
        editorMaximized = true;
        restoreExplorer = maven.panel().isVisible(); restoreMessages = messages.isVisible();
        restoreDivider = projectSplit.getDividerLocation();
        maven.panel().setVisible(false); setMessagesVisible(false);
        projectSplit.setDividerSize(0); revalidate(); editor.requestFocusInWindow();
    }

    private void restoreEditorLayout() {
        if (!editorMaximized) return;
        editorMaximized = false;
        maven.panel().setVisible(restoreExplorer); setMessagesVisible(restoreMessages);
        projectSplit.setDividerSize(restoreExplorer ? 5 : 0);
        if (restoreExplorer) projectSplit.setDividerLocation(restoreDivider);
        revalidate(); editor.requestFocusInWindow();
    }

    private void nextDiagnostic(boolean error, int direction) {
        if (currentResult == null) return;
        var values = currentResult.diagnostics().stream()
                .filter(d -> error ? d.isError() : d.kind().contains("WARNING"))
                .sorted(Comparator.comparingLong(CompilerDiagnostic::start)).toList();
        if (values.isEmpty()) { console.append(error ? "No current errors.\n" : "No current warnings.\n", false); return; }
        int caret = editor.caretOffset();
        var next = direction > 0 ? values.stream().filter(d -> d.start() > caret).findFirst().orElse(values.getFirst())
                : values.reversed().stream().filter(d -> d.start() < caret).findFirst().orElse(values.getLast());
        goToDiagnostic(next);
    }

    private void goToDiagnostic(CompilerDiagnostic diagnostic) {
        rememberLocation();
        if (diagnostic.start() >= 0) editor.gotoOffset((int) diagnostic.start());
        else editor.gotoLine((int) diagnostic.line());
        editor.emphasizeRange(editor.caretOffset(), (int) Math.max(editor.caretOffset() + 1, diagnostic.end()));
        editor.requestFocusInWindow();
    }

    private void showDiagnostics() {
        var diagnostics = currentResult == null ? List.<CompilerDiagnostic>of() : currentResult.diagnostics();
        var dialog = new EmbeddedDialog(this, "Compiler Diagnostics", false);
        var list = new JList<>(diagnostics.toArray(CompilerDiagnostic[]::new));
        list.setName("compiler-diagnostics");
        list.getAccessibleContext().setAccessibleName("Compiler diagnostics; Enter opens source");
        list.setCellRenderer((view, item, index, selected, focused) -> {
            JLabel label = new JLabel(item.kind() + "  " + item.line() + ":" + item.column() + "  " + item.message());
            label.setOpaque(true); label.setFont(AsciiChrome.font());
            label.setBackground(selected ? view.getSelectionBackground() : view.getBackground());
            label.setForeground(selected ? view.getSelectionForeground() : view.getForeground());
            return label;
        });
        if (!diagnostics.isEmpty()) list.setSelectedIndex(0);
        Runnable open = () -> { var item = list.getSelectedValue(); if (item != null)
            OverlayHost.closeThen(dialog, () -> goToDiagnostic(item)); };
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-diagnostic");
        list.getActionMap().put("open-diagnostic", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { open.run(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) open.run(); }
        });
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(diagnostics.isEmpty() ? "No current diagnostics." : "Enter opens source · Escape closes"), BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.setContentPane(panel); dialog.setSize(760, 400); dialog.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(list::requestFocusInWindow); dialog.setVisible(true);
    }

    private void rememberLocation() {
        Visit value = visit();
        if (!value.equals(recentLocations.peekFirst())) recentLocations.addFirst(value);
        while (recentLocations.size() > 100) recentLocations.removeLast();
    }

    private void chooseRecentFile() {
        rememberBuffer();
        var paths = new LinkedHashSet<Path>();
        buffers.values().stream().map(Buffer::file).filter(Objects::nonNull).forEach(paths::add);
        settings.recentFiles.stream().map(Path::of).forEach(paths::add);
        chooseFile("Recent Files", paths.stream().toList());
    }

    private void chooseFile(String title, List<Path> paths) {
        if (paths.isEmpty()) { console.append("No matching files.\n", false); return; }
        Object selected = EmbeddedOptionPane.showInputDialog(this, "Choose file:", title, JOptionPane.PLAIN_MESSAGE,
                null, paths.toArray(), paths.getFirst());
        if (selected instanceof Path path) { rememberLocation(); openPath(path); }
    }

    private void chooseRecentLocation() {
        var locations = recentLocations.stream().toList();
        if (locations.isEmpty()) { console.append("No navigation locations yet.\n", false); return; }
        String[] labels = java.util.stream.IntStream.range(0, locations.size()).mapToObj(i -> {
            Visit v = locations.get(i); return (i + 1) + ". " + v.key().getFileName() + " · offset " + v.location().caret();
        }).toArray(String[]::new);
        Object selected = EmbeddedOptionPane.showInputDialog(this, "Choose location:", "Recent Locations", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (selected != null) restoreVisit(locations.get(Arrays.asList(labels).indexOf(selected)));
    }

    private void restoreVisit(Visit target) {
        if (target == null) return;
        rememberBuffer();
        Buffer buffer = buffers.get(target.key());
        if (buffer == null) { console.append("That source buffer is closed. Reopen the file first.\n", false); return; }
        activate(target.key(), buffer); editor.restoreLocation(target.location());
    }

    private void navigateEdit(boolean forward) {
        restoreVisit(forward ? editLocations.forward(visit()) : editLocations.back(visit()));
    }

    private void cycleBuffer(int direction) {
        rememberBuffer();
        var keys = new ArrayList<>(buffers.keySet());
        if (keys.size() < 2) return;
        rememberLocation();
        Path next = keys.get(Math.floorMod(keys.indexOf(bufferKey) + direction, keys.size()));
        activate(next, buffers.get(next));
    }

    private void closeBuffer() {
        withUnsaved(() -> {
            rememberBuffer();
            closedBuffers.addFirst(Map.entry(bufferKey, buffers.remove(bufferKey)));
            while (closedBuffers.size() > 32) closedBuffers.removeLast();
            if (!buffers.isEmpty()) {
                var next = new ArrayList<>(buffers.entrySet()).getLast(); activate(next.getKey(), next.getValue());
            } else {
                operation.incrementAndGet(); openRequest.incrementAndGet(); bufferIdentity.incrementAndGet();
                file = null; bufferKey = untitledKey(); diskFingerprint = null;
                editor.setText(""); savedVersion = editor.version(); refreshTitle();
            }
        });
    }

    private void reopenClosedBuffer() {
        if (closedBuffers.isEmpty()) return;
        var closed = closedBuffers.removeFirst(); rememberBuffer();
        // Keep the closed snapshot (including unsaved text); later Save still checks its disk fingerprint.
        Buffer existing = buffers.get(closed.getKey());
        buffers.putIfAbsent(closed.getKey(), closed.getValue());
        activate(closed.getKey(), existing == null ? closed.getValue() : existing);
    }

    private List<Path> dirtyBufferKeys() {
        rememberBuffer();
        return buffers.entrySet().stream().filter(e -> !e.getValue().state().readOnly()
                && e.getValue().state().document().version() != e.getValue().savedVersion()).map(Map.Entry::getKey).toList();
    }

    private void saveAllBuffers(List<Path> dirty, int index, dev.turboj.editor.document.PieceTable original) {
        if (index == dirty.size()) {
            rememberBuffer();
            buffers.entrySet().stream().filter(entry -> entry.getValue().state().document() == original).findFirst()
                    .ifPresent(entry -> activate(entry.getKey(), entry.getValue()));
            return;
        }
        Path key = dirty.get(index);
        if (!buffers.containsKey(key)) { saveAllBuffers(dirty, index + 1, original); return; }
        if (!key.equals(bufferKey)) { rememberBuffer(); activate(key, buffers.get(key)); }
        save(false, () -> saveAllBuffers(dirty, index + 1, original));
    }

    private void findFile() {
        if (maven.workspace() != null) { maven.findFile(false); return; }
        String query = EmbeddedOptionPane.showInputDialog(this, "File name contains:");
        if (query == null) return;
        Path root = file == null ? Path.of(System.getProperty("user.dir")) : file.getParent();
        background.execute(() -> {
            try (var paths = Files.find(root, 12, (path, attributes) -> attributes.isRegularFile()
                    && !path.toString().contains("/.git/") && !path.toString().contains("/target/")
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))) {
                var found = paths.limit(500).sorted().toList();
                SwingUtilities.invokeLater(() -> { if (!closing) chooseFile("Find File", found); });
            } catch (Exception failure) { errorLater(failure); }
        });
    }

    private void navigateRelatedTest() {
        if (file == null) return;
        Path expectedFile = bufferKey;
        long expectedVersion = editor.version();
        String name = file.getFileName().toString();
        String other = name.endsWith("Test.java") ? name.substring(0, name.length() - 9) + ".java" : name.replace(".java", "Test.java");
        intelligence.submitSemantic(intelligenceSnapshot(), (service, context) -> service.sharedRefactorJ(context)
                .workspace().snapshot().sources().stream()
                .map(Model.SourceSnapshot::file).filter(path -> path.getFileName().toString().equals(other)).toList())
                .whenComplete((paths, failure) -> SwingUtilities.invokeLater(() -> {
                    if (closing || !bufferKey.equals(expectedFile) || editor.version() != expectedVersion) return;
                    if (failure != null) console.append("Test/source navigation: " + message(failure) + "\n", true);
                    else chooseFile("Source / Test", paths);
                }));
    }

    private void showRunConfiguration() {
        JTextArea args = new JTextArea(String.join("\n", programArguments), 5, 38);
        JTextArea stdin = new JTextArea(configuredInput, 5, 38);
        args.getAccessibleContext().setAccessibleName("Program arguments, one per line");
        stdin.getAccessibleContext().setAccessibleName("Initial standard input");
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Program arguments — one argument per line, no shell quoting:")); form.add(new JScrollPane(args));
        form.add(new JLabel("Initial stdin — sent once when the program first requests input:")); form.add(new JScrollPane(stdin));
        form.add(new JLabel("Stored for this session only. Console input remains interactive."));
        if (EmbeddedOptionPane.showConfirmDialog(this, form, "Run Configuration", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            programArguments = args.getText().isEmpty() ? List.of() : args.getText().lines().toList();
            configuredInput = stdin.getText();
        }
    }

    private void showGeneralSettings() {
        JSpinner tabs = new JSpinner(new SpinnerNumberModel(settings.tabWidth, 1, 16, 1));
        JCheckBox pairs = new JCheckBox("Automatically close brackets and quotes", settings.autoClose);
        JPanel form = new JPanel(new GridLayout(0, 1));
        JLabel label = new JLabel("Tab width:"); label.setLabelFor(tabs);
        form.add(label); form.add(tabs); form.add(pairs);
        if (EmbeddedOptionPane.showConfirmDialog(this, form, "General Settings", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            settings.tabWidth = (int) tabs.getValue(); settings.autoClose = pairs.isSelected();
            editor.setTabWidth(settings.tabWidth); editor.setAutoClose(settings.autoClose);
            String snapshot = settings.snapshotJson();
            background.execute(() -> { try { Settings.saveSnapshot(snapshot); } catch (Exception failure) { errorLater(failure); } });
        }
    }

    private void completionKey(String key) {
        KeyStroke stroke = switch (key) {
            case "TAB" -> shortcut("acceptCompletion", "TAB");
            case "ctrl alt ENTER" -> shortcut("aiAcceptWord", "ctrl alt ENTER");
            default -> KeyStroke.getKeyStroke(key);
        };
        var event = new KeyEvent(editor, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke
                .getModifiers(), stroke.getKeyCode(), KeyEvent.CHAR_UNDEFINED);

        if (aiCompletion.handleKey(event) || intelligence.handleKey(event))
            return;
        if (key.equals("TAB"))
            editor.acceptInlineCompletion();
    }

    private void menu(String name, String... ids) {
        var m = new JMenu(name);
        var themeGroup = new ButtonGroup();
        m.setMnemonic(name.charAt(0));
        for (String id : ids) {
            if (id.equals("-")) m.addSeparator();
            else if (id.startsWith("theme.") && !id.equals("theme.load")) {
                var item = new JRadioButtonMenuItem(commands.get(id));
                themeGroup.add(item);
                m.add(item);
            } else m.add(new JMenuItem(commands.get(id)));
        }
        menus.add(m);
    }

    private void createMenus() {
        menu("File", "new", "open", "openFolder", "navigation.recentFiles", "save", "saveAs", "file.saveAll", "file.close", "file.reopenClosed", "-", "quit");
        menu("Project", "project.open", "project.reload", "project.explorer", "project.findFile", "project.findModule", "-", "project.structure", "project.dependencies", "project.dependencyTree", "project.profiles", "project.settings", "project.effectivePom", "project.parentPom", "project.pomDeclaration", "project.sources", "project.javadoc", "-",
                "project.pom.add-dependency", "project.pom.update-dependency-version", "project.pom.remove-dependency", "project.pom.add-module");
        menu("Build", "compile", "compileProject", "make", "error", "project.processors", "-", "maven.build.validate", "maven.build.compile", "maven.build.test", "maven.build.package", "maven.build.verify", "maven.build.install", "maven.build.clean", "maven.build.dependency:tree", "maven.build.help:effective-pom", "maven.build.custom", "maven.refresh", "maven.stop");
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
                "duplicateLine", "deleteLine", "moveLineUp", "moveLineDown",
                "-",
                "complete",
                "documentation");
        menu("Search", "find", "findNext", "findPrevious", "replace", "goto");
        menu("Navigate", "declaration", "navigation.goToFile", "navigation.goToTest", "navigation.testFailure", "navigation.nextError", "navigation.previousError", "navigation.recentLocations", "back", "forward");
        var code = new JMenu("Code");
        code.add(new JMenuItem(commands.get("format.document")));
        code.add(new JMenuItem(commands.get("format.selection")));
        code.add(new JCheckBoxMenuItem(commands.get("format.onSave")));
        code.add(new JMenuItem(commands.get("organizeImports")));
        menus.add(code);
        var refactorMenu = new JMenu("Refactor");
        refactorMenu.add(new JMenuItem(commands.get("refactor.this")));
        refactorMenu.add(new JMenuItem(commands.get("refactor.context-actions")));
        refactorMenu.addSeparator();
        for (Operation operation : List.of(Operation.RENAME, Operation.CHANGE_SIGNATURE,
                Operation.MOVE, Operation.SAFE_DELETE))
            refactorMenu.add(new JMenuItem(commands.get("refactor." + operation.id())));
        refactorMenu.add(refactoringMenu("Extract", Operation.EXTRACT_METHOD,
                Operation.EXTRACT_VARIABLE, Operation.EXTRACT_CONSTANT, Operation.EXTRACT_FIELD,
                Operation.EXTRACT_PARAMETER, Operation.EXTRACT_INTERFACE));
        refactorMenu.add(refactoringMenu("Inline", Operation.INLINE_VARIABLE,
                Operation.INLINE_METHOD));
        refactorMenu.add(refactoringMenu("Type hierarchy", Operation.INTRODUCE_INTERFACE,
                Operation.PULL_UP, Operation.PUSH_DOWN));
        refactorMenu.add(refactoringMenu("Encapsulate", Operation.ENCAPSULATE_FIELD,
                Operation.CONSTRUCTOR_TO_FACTORY));
        refactorMenu.add(refactoringMenu("Modernize", Operation.ANONYMOUS_TO_LAMBDA,
                Operation.LAMBDA_TO_METHOD_REFERENCE, Operation.METHOD_REFERENCE_TO_LAMBDA,
                Operation.SWITCH_TO_EXPRESSION, Operation.TYPE_TO_VAR, Operation.VAR_TO_TYPE));
        refactorMenu.add(refactoringMenu("Quick fixes", Operation.ADD_IMPORT,
                Operation.REMOVE_IMPORT, Operation.IMPLEMENT_METHODS, Operation.CREATE_METHOD,
                Operation.CREATE_CONSTRUCTOR, Operation.CREATE_FIELD, Operation.CREATE_LOCAL,
                Operation.FIX_TYPE_MISMATCH, Operation.REMOVE_UNREACHABLE));
        menus.add(refactorMenu);
        menu(
                "Run",
                "run",
                "isolated",
                "run.configuration",
                "stop",
                "eof",
                "clear",
                "-",
                "repl",
                "output",
                "replReset",
                "evaluateSelection",
                "evaluateStatement");
        JMenu testMenu = new JMenu("Test");

        for (String id : List.of("tests.current", "tests.class", "tests.all", "tests.failed",
                "tests.stop", "tests.results"))
            testMenu.add(new JMenuItem(commands.get(id)));
        testMenu.addSeparator();
        testMenu.add(new JCheckBoxMenuItem(commands.get("tests.auto")));
        menus.add(testMenu);
        var options = new JMenu("Options");
        options.setMnemonic('O');
        var themes = new JMenu("Themes");
        var group = new ButtonGroup();
        for (String id : Theme.BUNDLED) {
            var item = new JRadioButtonMenuItem(commands.get("theme." + id));
            group.add(item);
            themes.add(item);
        }
        themes.addSeparator();
        themes.add(new JMenuItem(commands.get("theme.load")));
        themes.add(new JMenuItem(commands.get("theme.more")));
        options.add(themes);
        var keyboardProfiles = new JMenu("Keyboard Profile");
        for (var profile : BundledKeymaps.profiles()) {
            var item = new JRadioButtonMenuItem(commands.get("keymap.profile." + profile.id()));
            item.setText(profile.name());
            keyboardProfileGroup.add(item);
            keyboardProfiles.add(item);
        }
        options.add(keyboardProfiles);
        var aiMenu = new JMenu("AI Completion");
        for (String id : List.of("ai.disabled", "ai.local", "ai.cloud"))
            aiMenu.add(new JMenuItem(commands.get(id)));
        aiMenu.addSeparator();
        for (String id : List.of("ai.settings", "ai.request", "ai.another", "ai.larger"))
            aiMenu.add(new JMenuItem(commands.get(id)));
        options.add(aiMenu);
        options.addSeparator();
        options.add(new JCheckBoxMenuItem(commands.get("run.onSave")));
        options.add(new JCheckBoxMenuItem(commands.get("tests.onSave")));
        options.add(new JCheckBoxMenuItem(commands.get("showPressedShortcuts")));
        for (String id : List.of("fontUp", "fontDown", "zoom", "fullscreen", "metrics", "palette",
                "keymap", "settings.general", "view.focusEditor", "view.focusExplorer", "view.focusConsole", "view.focusJShell", "view.focusDiagnostics", "view.focusNextPanel", "view.maximizeEditor", "view.restoreLayout"))
            options.add(new JMenuItem(commands.get(id)));
        menus.add(options);
        menu("Help", "help");
    }

    private JMenu refactoringMenu(String title, Operation... operations) {
        JMenu menu = new JMenu(title);

        for (Operation operation : operations)
            menu.add(new JMenuItem(commands.get("refactor." + operation.id())));
        return menu;
    }

    private void initializeKeymap() {
        try {
            commandKeymaps.select(settings.keymapProfile);
        } catch (IllegalArgumentException missing) {
            commandKeymaps.select("turboj-classic");
        }
        if (settings.commandKeymapJson != null) {
            try {
                KeymapJson.read(commandKeymaps, settings.commandKeymapJson);
            } catch (IllegalArgumentException invalid) {
                console.append("Keymap preferences: " + invalid.getMessage() + "\n", false);
            }
        } else if (!keymap.isEmpty()) {
            // Preserve old effective bindings. New user edits require explicit conflict resolution.
            commandKeymaps.select("turboj-classic");
            var imported = new TreeMap<String, List<KeyBinding>>();
            for (var entry : keymap.entrySet()) {
                String id = switch (entry.getKey()) {
                    case "acceptCompletion" -> "completion.accept";
                    case "dismissCompletion" -> "escape";
                    case "aiAcceptWord" -> "completion.acceptWord";
                    default -> entry.getKey();
                };

                try {
                    var binding = new KeyBinding(SwingKeys.parseLegacy(entry.getValue()),
                            BindingContext.any());
                    imported.put(id, List.of(binding));
                } catch (IllegalArgumentException invalid) {
                    console.append("Ignored invalid legacy shortcut for " + id + "\n", false);
                }
            }
            commandKeymaps.migrateLegacyBindings(imported);
        }
        settings.keymapProfile = commandKeymaps.selectedId();
    }

    private void bindKeys() {
        editor.setExternalCommandBindings(true);
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("permanentFocusOwner", textCommandFocus);
        commandDispatcher = new CommandKeyDispatcher(this, commandRegistry, commandKeymaps,
                this::commandContext, text -> console.append(text + "\n", false), (id, sequence) -> {
                    if (!editor.hasInputComposition()) editor.showPressedShortcut(sequence.display() + "  "
                            + commandRegistry.find(id).map(Command::title).orElse(id));
                });
        keymapListener = commandKeymaps.onChange(() -> {
            Runnable update = () -> {
                refreshShortcutPresentation();
                persistKeymap();
            };

            if (SwingUtilities.isEventDispatchThread())
                update.run();
            else
                SwingUtilities.invokeLater(update);
        });
        refreshShortcutPresentation();
    }

    private void refreshShortcutPresentation() {
        updateMavenStatus();
        if (!commands.containsKey("keymap.profile." + commandKeymaps.selectedId()))
            keyboardProfileGroup.clearSelection();
        for (var entry : commands.entrySet()) {
            if (entry.getKey().startsWith("keymap.profile."))
                entry.getValue().putValue(Action.SELECTED_KEY,
                        entry.getKey().equals("keymap.profile." + commandKeymaps.selectedId()));
            List<KeyBinding> bindings = ShortcutReference.bindings(commandRegistry, commandKeymaps.bindings(), entry.getKey());
            KeyStroke accelerator = null;

            for (var binding : bindings)
                if (binding.sequence().strokes().size() == 1) {
                    try {
                        accelerator = SwingKeys.toSwing(binding.sequence().strokes().getFirst());
                    } catch (IllegalArgumentException ignored) {}
                    break;
                }
            entry.getValue().putValue(Action.ACCELERATOR_KEY, accelerator);
            entry.getValue().putValue(Action.SHORT_DESCRIPTION, commandKeymaps.shortcutText(entry
                    .getKey()));
        }
    }

    private void persistKeymap() {
        settings.keymapProfile = commandKeymaps.selectedId();
        String snapshot = KeymapJson.write(commandKeymaps);
        String config = settings.snapshotJson();
        long version = ++keymapPreferenceVersion;
        background.execute(() -> {
            if (version != keymapPreferenceVersion)
                return;
            try {
                KeymapJson.save(Settings.directory().resolve("commandj-keymap.json"), snapshot);
                Settings.saveSnapshot(config);
            } catch (java.io.IOException failure) {
                errorLater(failure);
            }
        });
    }

    public void showKeymapEditor() {
        new KeymapEditorDialog(this, commandRegistry, commandKeymaps, () -> {}).setVisible(true);
    }

    /** Preview is intentionally separate from committing a user preference. */
    public void showThemeBrowser() {
        ++themeChoiceGeneration;
        Path external = externalThemePath();
        String selected =
                external == null
                        ? ThemeCatalog.bundledKey(settings.theme)
                        : ThemeCatalog.localKey(external);
        themeCatalog.includeExternal(external);
        var dialog =
                new ThemeBrowserDialog(
                        this,
                        theme,
                        selected,
                        themeCatalog,
                        this::applyTheme,
                        choice -> selectTheme(choice.theme(), choice.entry().localPath()));
        dialog.setVisible(true);
    }

    private Path externalThemePath() {
        try {
            return settings.externalThemePath == null
                    ? null
                    : Path.of(settings.externalThemePath).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private void restoreExternalTheme() {
        Path path = externalThemePath();
        if (path == null) return;
        long choice = themeChoiceGeneration;
        background.execute(
                () -> {
                    try {
                        Theme loaded = Theme.load(path);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!closing && choice == themeChoiceGeneration)
                                        applyTheme(loaded);
                                });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!closing && choice == themeChoiceGeneration)
                                        console.append(
                                                "Saved theme unavailable; using "
                                                        + theme.name()
                                                        + ": "
                                                        + message(e)
                                                        + "\n",
                                                true);
                                });
                    }
                });
    }

    private void selectTheme(Theme selected, Path external) {
        ++themeChoiceGeneration;
        settings.theme = external == null ? selected.id() : "classic";
        settings.externalThemePath =
                external == null ? null : external.toAbsolutePath().normalize().toString();
        applyTheme(selected);
        String preferences = settings.snapshotJson();
        background.execute(
                () -> {
                    try {
                        Settings.saveSnapshot(preferences);
                    } catch (Exception e) {
                        errorLater(e);
                    }
                });
    }

    private void changeInterfaceFontSize(int size) {
        int next = Math.clamp(size, 10, 48);
        if (settings.fontSize == next) return;
        settings.fontSize = next;
        applyTheme(theme);
        String preferences = settings.snapshotJson();
        background.execute(() -> {
            try { Settings.saveSnapshot(preferences); }
            catch (Exception error) { errorLater(error); }
        });
    }

    public void applyTheme(Theme t) {
        theme = t;
        AsciiChrome.setEditorFontSize(settings.fontSize);
        if (editor.getFont().getSize() != settings.fontSize) editor.setFontSize(settings.fontSize);
        MenuStyler.install(t);
        OverlayHost.forOwner(this).setTheme(AsciiChrome.editor(t));
        lastEditorTitle = "";
        lastMessageTitle = "";
        editor.setTheme(t);
        console.setTheme(t);
        repl.setTheme(t);
        if (intelligence != null) intelligence.setTheme(t);
        if (maven != null) maven.setTheme(t);
        MenuStyler.style(menus, t);
        bottom.setBackground(t.color("chrome", "statusBackground"));
        Color statusForeground = AsciiPalette.readable(t.color("chrome", "statusForeground"),
                t.color("chrome", "statusBackground"), 4.5);
        status.setForeground(statusForeground);
        position.setForeground(statusForeground);
        for (String id : Theme.BUNDLED) {
            Theme preset = Theme.bundled(id);
            commands.get("theme." + id)
                    .putValue(
                            Action.SELECTED_KEY,
                            id.equals(t.id()) && preset.colors().equals(t.colors()));
        }
        Font mono = AsciiChrome.font();
        status.setFont(mono);
        position.setFont(mono);
        AsciiChrome.splitPane(projectSplit, AsciiChrome.editor(t));
        AsciiChrome.splitPane(outputSplit, AsciiChrome.editor(t));
        outputSplit.setDividerSize(messages.isVisible() ? AsciiChrome.scaled(5) : 0);
        editorPanel.setBackground(t.background());
        getContentPane().setBackground(t.background());
        refreshTitle();
        refreshWorkspaceMinimums();
        revalidate();
        repaint();
    }

    public ReplPane repl() {
        return repl;
    }

    private void setMessagesVisible(boolean visible) {
        if (messages.isVisible() == visible) return;
        if (!visible && messages.getHeight() > 0) outputHeight = messages.getHeight();
        messages.setVisible(visible);
        if (outputSplit != null) {
            outputSplit.setDividerSize(visible ? AsciiChrome.scaled(5) : 0);
            refreshWorkspaceMinimums();
            if (outputLayoutReady) {
                if (visible) restoreOutputHeight();
                else outputSplit.setDividerLocation(outputSplit.getHeight());
            }
            outputSplit.revalidate();
            outputSplit.repaint();
        }
    }

    private void restoreOutputHeight() {
        int available = outputSplit.getHeight() - outputSplit.getDividerSize();
        int desired = Math.max(messages.getMinimumSize().height, outputHeight);
        int lower = Math.min(projectSplit.getMinimumSize().height, Math.max(0, available / 2));
        int upper = Math.max(lower, available - messages.getMinimumSize().height);
        int location = Math.clamp(available - desired, lower, upper);
        outputSplit.setDividerLocation(location);
    }

    private void refreshWorkspaceMinimums() {
        if (outputSplit == null || theme == null) return;
        Insets editorEdge = editorPanel.getInsets();
        int editorHeight = editorEdge.top + editorEdge.bottom + editor.getFontMetrics(editor.getFont()).getHeight() + 2;
        int projectHeight = maven.panel().isVisible() ? maven.panel().getMinimumSize().height : 0;
        projectSplit.setMinimumSize(new Dimension(0, Math.max(editorHeight, projectHeight)));
        Insets outputEdge = messages.getInsets();
        int outputHeight = (replVisible ? repl : console).getMinimumSize().height;
        messages.setMinimumSize(new Dimension(0, outputEdge.top + outputEdge.bottom + outputHeight));
        int chromeHeight = menus.getPreferredSize().height + bottom.getPreferredSize().height;
        Insets frameEdge = getInsets();
        int minimumHeight = projectSplit.getMinimumSize().height + chromeHeight + frameEdge.top + frameEdge.bottom;
        if (messages.isVisible()) minimumHeight += messages.getMinimumSize().height + outputSplit.getDividerSize();
        setMinimumSize(new Dimension(640, Math.max(420, minimumHeight)));
    }

    private void showRepl(boolean visible) {
        replVisible = visible;
        messages.removeAll();
        messages.add(visible ? repl : console, BorderLayout.CENTER);
        setMessagesVisible(true);
        lastMessageTitle = "";
        refreshTitle();
        refreshWorkspaceMinimums();
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
            editorPanel.setBorder(new AsciiBorder(name + dirty, AsciiChrome.editor(theme), AsciiBorder.Line.SINGLE));
        }
        String messageTitle = replVisible ? "Java REPL" : "Output";
        if (outputSplit != null && outputSplit.isFocusOwner()) messageTitle += " · ↑/↓ resize · Esc editor";
        if (!lastMessageTitle.equals(messageTitle)) {
            lastMessageTitle = messageTitle;
            messages.setBorder(new AsciiBorder(messageTitle, AsciiChrome.output(theme), AsciiBorder.Line.SINGLE));
        }
        messages.setBackground(theme.messagesBackground());
        refreshStatusLine();
        updatePositionLabel();
    }

    private void updatePosition() {
        updatePositionLabel();
        if (intelligence != null && isJavaBuffer()) intelligence.changed();
        if (aiCompletion != null && isJavaBuffer()) aiCompletion.changed();
    }

    private void updatePositionLabel() {
        String name = file == null ? "Untitled.java" : file.getFileName().toString();
        String suffix = (editor.version() != savedVersion ? " *" : "")
                + " · " + editor.caretLine() + ":" + editor.caretColumn() + " ";
        FontMetrics metrics = position.getFontMetrics(position.getFont());
        int width = bottom.getWidth() > 0 ? bottom.getWidth() : getWidth();
        int available = Math.max(0, width / 2 - metrics.stringWidth(" " + suffix));
        if (metrics.stringWidth(name) > available) {
            int end = name.length();
            while (end > 0 && metrics.stringWidth(name.substring(0, end) + "…") > available)
                end = name.offsetByCodePoints(end, -1);
            name = metrics.stringWidth("…") <= available ? name.substring(0, end) + "…" : "";
        }
        position.setText(" " + name + suffix);
        position.setToolTipText(file == null ? "Unsaved source" : file.toString());
    }

    private void changed() {
        if (refactoringPublishing)
            return;
        if (keyboardJava != null) keyboardJava.changed();
        if (observedDocument == editor.document()) {
            Visit here = visit();
            if (lastEditVisit == null || !lastEditVisit.key().equals(here.key())
                    || Math.abs(lastEditVisit.location().caret() - here.location().caret()) > 80)
                editLocations.jumpedFrom(lastEditVisit == null ? here : lastEditVisit);
            lastEditVisit = here;
        }
        // Buffer navigation is not a source change. Per-document versions still detect edits.
        if (observedDocument == editor.document())
            refactoringRevision++;
        if (observedDocument != editor.document()) {
            observedDocument = editor.document();
            operation.incrementAndGet();
            bufferIdentity.incrementAndGet();
            openRequest.incrementAndGet();
        }
        editor.setXmlSyntax(isXmlBuffer());
        if (intelligence != null) intelligence.setEnabled(isJavaBuffer());
        if (formatting != null) {
            var workspace = maven.workspace();
            String level = workspace == null || file == null ? "25" : workspace.moduleFor(file)
                    .map(module -> (module.isTest(file) ? module.testJava() : module.mainJava()).languageLevel()).orElse("25");
            formatting.setLanguageLevel(level.isBlank() ? "25" : level);
        }
        if (intelligence != null && isJavaBuffer()) intelligence.changed();
        else if (intelligence != null) intelligence.dismiss();
        if (aiCompletion != null && !isJavaBuffer()) aiCompletion.dismiss();
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
        if (pendingMavenCompile != null) pendingMavenCompile.cancel(false);
        if (!isJavaBuffer()) {
            buildStatus = isXmlBuffer() ? "XML CHECKING…" : "TEXT";
            if (isXmlBuffer()) {
                pendingAnalysis = background.schedule(() -> {
                    var diagnostics = XmlDiagnostics.inspect(snap.toString());
                    SwingUtilities.invokeLater(() -> {
                        if (closing || operation.get() != analysisOp || editor.version() != snap.version()) return;
                        editor.setDiagnostics(diagnostics);
                        buildStatus = diagnostics.isEmpty() ? "XML READY" : "XML DIAGNOSTICS"; refreshTitle();
                    });
                }, 60, TimeUnit.MILLISECONDS);
                maven.configurationChanged(file);
            }
            refreshTitle();
        } else if (maven.workspace() != null && file != null && maven.workspace().moduleFor(file).isPresent()) {
            var captured = intelligenceSnapshot();
            var workspace = maven.workspace();
            long revision = refactoringRevision;
            pendingMavenCompile = nativeProjectCompiler.schedule(() -> SwingUtilities.invokeLater(() -> {
                if (!closing && operation.get() == analysisOp && editor.version() == snap.version() && workspace == maven.workspace() && revision == refactoringRevision)
                    compileMaven(false, false, false);
            }), 180, TimeUnit.MILLISECONDS);
        } else {
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
        }
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

    private record NativeCompilation(CompilationResult active, MavenCompilationWorkspace.Result workspace,
            List<MavenRunTargets.Target> targets) {}

    private CompletableFuture<CompilationResult> compileMaven(boolean run, boolean isolated, boolean explicit) {
        return compileMaven(run, isolated, explicit, false);
    }

    private CompletableFuture<CompilationResult> compileMaven(boolean run, boolean isolated, boolean explicit, boolean processors) {
        var model = maven.workspace();
        if (model == null || maven.busy())
            return CompletableFuture.failedFuture(new IllegalStateException("Wait for Maven project import to finish"));
        var module = file == null ? Optional.<MavenModels.Module>empty() : model.moduleFor(file);
        if (processors && module.isEmpty())
            return CompletableFuture.failedFuture(new IllegalStateException("Select source in a Maven module"));
        if (run && !maven.requireTrust()) return CompletableFuture.failedFuture(new CancellationException("Project execution was not authorized"));
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        if (pendingMavenCompile != null) pendingMavenCompile.cancel(false);
        var capture = intelligenceSnapshot();
        Path activeFile = file;
        String activeName = activeFile == null ? "" : activeFile.toString();
        long revision = refactoringRevision, op = operation.incrementAndGet();
        long request = run ? ++runRequest : runRequest;
        CompletableFuture<RunResult> previous = execution;
        if (run) { programWorker.stop(); running = false; console.setProgramRunning(false); }
        setMessagesVisible(true);
        buildStatus = "TURBOJ NATIVE · COMPILING"; refreshTitle();
        boolean testSource = module.isPresent() && module.get().isTest(activeFile);
        if (nativeCompileTask != null && !nativeCompileTask.isDone()) nativeCompileTask.cancel(true);
        if (pendingNativeResult != null && !pendingNativeResult.isDone()) pendingNativeResult.cancel(false);
        var future = new CompletableFuture<NativeCompilation>();
        pendingNativeResult = future;
        nativeCompileTask = nativeProjectCompiler.submit(() -> {
            try {
                if (future.isCancelled()) return;
                Map<Path, String> overlays = new LinkedHashMap<>();
                capture.openSources().forEach((path, snapshot) -> { if (path.getFileName().toString().endsWith(".java")) overlays.put(path, snapshot.toString()); });
                var coordinator = nativeMavenCompiler();
                var result = coordinator.compile(model, overlays, revision, testSource, processors, maven.trusted());
                var diagnostics = result.diagnostics().stream().filter(d -> d.fileName().equals(activeName))
                        .map(WorkspaceCompilationResult.SourceDiagnostic::diagnostic).toList();
                Map<String, byte[]> bytecode = new LinkedHashMap<>();
                if (result.success() && module.isPresent()) result.modules().stream()
                        .filter(compiled -> compiled.moduleId().equals(module.get().id()) && (!compiled.tests() || testSource))
                        .forEach(compiled -> bytecode.putAll(compiled.compilation().bytecode()));
                var targets = run && result.success() ? MavenRunTargets.candidates(result, activeFile) : List.<MavenRunTargets.Target>of();
                String main = targets.size() == 1 ? targets.getFirst().mainClass() : null;
                var active = new CompilationResult(capture.source().version(), result.success(), diagnostics, bytecode,
                        result.durationNanos(), result.modules().stream().allMatch(MavenCompilationWorkspace.ModuleResult::cacheHit), main);
                future.complete(new NativeCompilation(active, result, targets));
            } catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        future.whenComplete((value, failure) -> SwingUtilities.invokeLater(() -> {
            if (closing || model != maven.workspace() || revision != refactoringRevision || op != operation.get()
                    || !Objects.equals(activeFile, file) || capture.source().version() != editor.version()) return;
            if (failure != null) { buildStatus = "NATIVE BUILD FAILED"; refreshTitle(); console.append(message(failure) + "\n", true); return; }
            tests.setMavenCompiler(nativeMavenCompiler());
            if (processors && value.active().success()) { generatedSourceRevision++; intelligence.changed(); }
            accept(value.active(), explicit);
            buildStatus = "TURBOJ NATIVE · " + (value.active().success() ? "BUILD OK" : "BUILD FAILED") + " | " + ms(value.workspace().durationNanos()) + " ms";
            refreshTitle();
            if (explicit || !value.active().success()) for (var diagnostic : value.workspace().diagnostics())
                if (!diagnostic.fileName().equals(activeName)) console.append(diagnostic.fileName() + ":" + diagnostic.diagnostic().line() + " " + diagnostic.diagnostic().message() + "\n", diagnostic.diagnostic().isError());
            if (run && value.active().success()) {
                if (value.targets().isEmpty()) {
                    console.append("No runnable main class found in this Maven project. Use Test → Run All Tests for unit tests.\n", false);
                    return;
                }
                MavenRunTargets.Target target = value.targets().size() == 1 ? value.targets().getFirst()
                        : (MavenRunTargets.Target) EmbeddedOptionPane.showInputDialog(this,
                                "Choose the application's main class", "Run Maven project", JOptionPane.QUESTION_MESSAGE,
                                null, value.targets().toArray(), value.targets().getFirst());
                if (target == null) return;
                if (closing || request != runRequest || model != maven.workspace() || revision != refactoringRevision || op != operation.get()) return;
                launchMavenTarget(model, target, value.active(), revision, op, request, isolated, previous);
            } else if (value.active().success() && tests.automatic() && maven.trusted() && !tests.busy()) startTests(TurboTestController.Mode.ALL, true);
        }));
        return future.thenApply(NativeCompilation::active);
    }

    /** Pin the chosen module off the EDT; a later source/model generation cannot run stale output. */
    private void launchMavenTarget(MavenModels.Workspace model, MavenRunTargets.Target target,
            CompilationResult compiled, long revision, long op, long request, boolean isolated,
            CompletableFuture<RunResult> previous) {
        launchMavenTarget(model, target, compiled, revision, op, request, isolated, previous, false, null);
    }

    private void launchMavenTarget(MavenModels.Workspace model, MavenRunTargets.Target target,
            CompilationResult compiled, long revision, long op, long request, boolean isolated,
            CompletableFuture<RunResult> previous, boolean preserveOutput, SaveAutomation saveRequest) {
        lastMavenRunTarget = target;
        CompletableFuture.supplyAsync(() -> {
            try { return nativeMavenCompiler().pinExecution(target.moduleId(), target.tests(), model.generation(), revision); }
            catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
        }, nativeProjectCompiler).whenComplete((pin, failure) -> SwingUtilities.invokeLater(() -> {
            if (closing || request != runRequest || model != maven.workspace() || revision != refactoringRevision || op != operation.get()
                    || (preserveOutput && !settings.autoRunOnSave)) { finishPendingSaveRun(saveRequest); releaseExecution(pin); return; }
            if (failure != null) { finishPendingSaveRun(saveRequest); console.append("Unable to start Maven project: " + message(failure) + "\n", true); return; }
            var module = model.module(target.moduleId()).orElseThrow();
            Path javaHome = (target.tests() ? module.testJava() : module.mainJava()).javaHome();
            Path selectedJdk = javaHome == null ? Path.of(System.getProperty("java.home")) : javaHome;
            var executable = new CompilationResult(compiled.version(), true, compiled.diagnostics(), pin.bytecode(),
                    compiled.durationNanos(), compiled.cacheHit(), target.mainClass());
            previous.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                if (closing || request != runRequest || model != maven.workspace() || revision != refactoringRevision || op != operation.get()
                        || (preserveOutput && !settings.autoRunOnSave)) { finishPendingSaveRun(saveRequest); releaseExecution(pin); return; }
                var key = new ProjectWorkerKey(selectedJdk, pin.modulePath(), pin.moduleName(), pin.moduleName().isBlank() ? "" : target.mainClass());
                boolean modular = !key.moduleName().isBlank();
                programWorker = modular ? new WorkerClient(java.time.Duration.ofSeconds(30), 1024 * 1024, 256, key.home(), key.modulePath(), key.moduleName(), key.mainClass())
                        : projectWorkers.computeIfAbsent(key, config -> new WorkerClient(java.time.Duration.ofSeconds(30), 1024 * 1024, 256, config.home()));
                finishPendingSaveRun(saveRequest);
                showRepl(false); startRun(executable, isolated, request, pin.classpath(), !preserveOutput);
                WorkerClient launched = programWorker;
                execution.whenComplete((completed, problem) -> { releaseExecution(pin); if (modular) launched.close(); });
            }));
        }));
    }

    private static void releaseExecution(MavenCompilationWorkspace.ExecutionSnapshot snapshot) {
        if (snapshot != null) CompletableFuture.runAsync(snapshot::close);
    }

    public CompletableFuture<CompilationResult> compile(boolean run, boolean isolated) {
        if (maven.importRequest() != null) {
            if (maven.busy() || maven.workspace() == null)
                return CompletableFuture.failedFuture(new IllegalStateException("Wait for Maven project import to finish"));
            return compileMaven(run, isolated, true);
        }
        if (!isJavaBuffer()) return CompletableFuture.failedFuture(new IllegalStateException("Select a Java source file"));
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        var snap = editor.snapshot();
        String name = file == null ? null : file.getFileName().toString();
        long op = operation.incrementAndGet();
        long request = run ? ++runRequest : runRequest;
        CompletableFuture<RunResult> previousExecution = execution;
        if (run) {
            // Stop immediately, then await process termination asynchronously before dispatching.
            programWorker.stop();
            running = false;
            console.setProgramRunning(false);
        }
        setMessagesVisible(true);
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
        programWorker = worker;
        lastPlainRunTarget = result.mainClass();
        startRun(result, isolated, request, List.of());
    }

    private void startRun(CompilationResult result, boolean isolated, long request, List<Path> classpath) {
        startRun(result, isolated, request, classpath, true);
    }

    private void startRun(CompilationResult result, boolean isolated, long request, List<Path> classpath, boolean clearOutput) {
        if (result.mainClass() == null || result.mainClass().isBlank()) {
            console.append("No supported main entry point found.\n", true);
            return;
        }
        running = true;
        programOutput = true;
        if (clearOutput) console.clear();
        console.setProgramRunning(true);
        console.append(
                (isolated ? "ISOLATED RUN" : "FAST RUN") + " · " + result.mainClass() + "\n",
                false);
        long start = System.nanoTime();
        String initialInput = configuredInput;
        AtomicBoolean initialInputSent = new AtomicBoolean();
        execution =
                programWorker.run(
                        result.version(),
                        result.bytecode(),
                        result.mainClass(),
                        programArguments,
                        classpath,
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
                                            if (waiting && !initialInput.isEmpty() && initialInputSent.compareAndSet(false, true)) {
                                                try { programWorker.sendInput(initialInput.endsWith("\n") ? initialInput : initialInput + "\n"); }
                                                catch (IllegalStateException finished) { console.append("Initial input was not sent because the program already stopped.\n", false); }
                                            }
                                            if (waiting) {
                                                if (replVisible) showRepl(false);
                                                setMessagesVisible(true);
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
        if (destination.getFileName().toString().equals("pom.xml") && !externalSourceFiles.contains(destination)) maven.open(destination);
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
                        if (Files.isDirectory(p)) { SwingUtilities.invokeLater(() -> maven.open(p)); return; }
                        var loaded = isXmlPath(p) ? SafeFiles.readXmlSnapshot(p) : SafeFiles.readSnapshot(p, sourceEncoding(p));
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
                                    fileEncodings.put(p, new SourceEncoding.Encoding(loaded.encoding(), loaded.bom()));
                                    editor.setText(loaded.text());
                                    if (externalSourceFiles.contains(p)) editor.setReadOnly(true);
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
        if (saving || formattingSave) {
            deferredSave = new DeferredSave(as, next, bufferIdentity.get());
            return;
        }
        Path target = file;
        if (as || target == null) {
            var chooser = new EmbeddedFileChooser();
            chooser.setSelectedFile(
                    new java.io.File(file == null ? SourceSnapshot.forBuffer(editor.getText(), editor.version()).fileName() : file.getFileName().toString()));
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
        if (settings.formatOnSave && isJavaBuffer() && !editor.isReadOnly()) {
            formattingSave = true;
            long identity = bufferIdentity.get();
            formatting.format(
                    false,
                    outcome -> {
                        formattingSave = false;
                        if (closing || bufferIdentity.get() != identity) {
                            finishSave();
                        } else if (outcome == FormattingController.Outcome.STALE) {
                            console.append(
                                    "Source changed during formatting; save again.\n", false);
                            finishSave();
                        } else {
                            // Invalid Java must still be saveable; only formatting is skipped on
                            // failure.
                            beginSave(chosen, next);
                        }
                    });
        } else beginSave(chosen, next);
    }

    private void beginSave(Path chosen, Runnable next) {
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
                                        if (EmbeddedOptionPane.showConfirmDialog(
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
            dev.turboj.editor.document.PieceTable.Snapshot snapshot,
            long identity,
            Path originKey,
            dev.turboj.editor.document.PieceTable originDocument,
            Runnable next) {
        try {
            var encoding = SourceEncoding.select(maven.workspace(), target, fileEncodings.get(target), fileEncodings.get(originKey));
            byte[] written =
                    SafeFiles.atomicWriteIfUnchanged(target, snapshot.toString(), expected, encoding.charset(), encoding.bom());
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
                            fileEncodings.put(target, encoding);
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
                                    && editor.version() == savedVersion) {
                                next.run();
                                if (!closing && target.equals(file) && editor.version() == snapshot.version())
                                    scheduleSaveAutomation();
                            }
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
                EmbeddedOptionPane.showConfirmDialog(
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
                                    if (EmbeddedOptionPane.showConfirmDialog(
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
        new CommandPaletteDialog(this, commandRegistry, commandKeymaps, commandContext(),
                this::commandFinished).setVisible(true);
    }

    public void shutdown() {
        if (closing) return;
        closing = true;
        saveAutomationTimer.stop();
        queuedSaveAutomation = runningSaveAutomation = pendingSaveRun = null;
        testSelectionRequest++;
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("permanentFocusOwner", textCommandFocus);
        settings.width = getWidth();
        settings.height = getHeight();
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        if (pendingRecovery != null) pendingRecovery.cancel(false);
        worker.close();
        if (programWorker != worker) programWorker.close();
        projectWorkers.values().forEach(WorkerClient::close);
        if (pendingMavenCompile != null) pendingMavenCompile.cancel(false);
        if (nativeCompileTask != null) nativeCompileTask.cancel(true);
        if (pendingNativeResult != null) pendingNativeResult.cancel(false);
        nativeProjectCompiler.shutdownNow();
        if (mavenCompiler != null) Thread.ofPlatform().daemon().name("turboj-compiler-close").start(mavenCompiler::close);
        themeCatalog.close();
        repl.close();
        compiler.close();
        maven.close();
        if (keyboardJava != null) keyboardJava.dispose();
        intelligence.close();
        aiCompletion.close();
        formatting.close();
        if (refactoring != null)
            refactoring.close();
        if (tests != null)
            tests.close();
        if (testResults != null)
            testResults.dispose();
        if (commandDispatcher != null)
            commandDispatcher.close();
        if (keymapListener != null)
            try {
                keymapListener.close();
            } catch (Exception ignored) {}
        commandRegistry.close();
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

    private KeyStroke shortcut(String id, String fallback) {
        KeyStroke value = KeyStroke.getKeyStroke(keymap.getOrDefault(id, fallback));
        return value == null ? KeyStroke.getKeyStroke(fallback) : value;
    }

    private void toggleSaveAutomation(boolean run) {
        boolean enabling = run ? !settings.autoRunOnSave : !settings.autoTestOnSave;
        if (enabling && maven.workspace() != null && !maven.requireTrust()) return;
        if (run) settings.autoRunOnSave = enabling;
        else settings.autoTestOnSave = enabling;
        commands.get(run ? "run.onSave" : "tests.onSave").putValue(Action.SELECTED_KEY, enabling);
        if (!settings.autoRunOnSave && !settings.autoTestOnSave) {
            saveAutomationTimer.stop(); queuedSaveAutomation = runningSaveAutomation = null;
        }
        String snapshot = settings.snapshotJson();
        background.execute(() -> { try { Settings.saveSnapshot(snapshot); } catch (java.io.IOException failure) { errorLater(failure); } });
    }

    private void scheduleSaveAutomation() {
        if (!isJavaBuffer() || file == null || (!settings.autoRunOnSave && !settings.autoTestOnSave)) return;
        queuedSaveAutomation = new SaveAutomation(file, editor.version(), refactoringRevision, runRequest,
                settings.autoRunOnSave, settings.autoTestOnSave);
        saveAutomationTimer.setRepeats(false);
        saveAutomationTimer.restart();
    }

    private boolean currentSave(SaveAutomation request) {
        return request != null && !closing && request.file().equals(file)
                && request.version() == editor.version() && request.revision() == refactoringRevision;
    }

    private boolean hasPendingSaveRun() {
        return pendingSaveRun != null || queuedSaveAutomation != null && queuedSaveAutomation.run()
                || runningSaveAutomation != null && runningSaveAutomation.run();
    }

    private static SaveAutomation withoutSaveAction(SaveAutomation request, boolean run) {
        if (request == null) return null;
        boolean keepRun = !run && request.run(), keepTests = run && request.tests();
        return keepRun || keepTests ? new SaveAutomation(request.file(), request.version(),
                request.revision(), request.runGeneration(), keepRun, keepTests) : null;
    }

    private void finishPendingSaveRun(SaveAutomation request) {
        if (pendingSaveRun == request) pendingSaveRun = null;
    }

    private void stopTests() {
        ++testSelectionRequest;
        pendingTestSnapshot = false;
        queuedSaveAutomation = withoutSaveAction(queuedSaveAutomation, false);
        SaveAutomation remainingRun = withoutSaveAction(runningSaveAutomation, false);
        runningSaveAutomation = null;
        tests.stop();
        buildStatus = "TESTS STOPPED";
        refreshTitle();
        // Canceling tests must not cancel the independently requested automatic program run.
        if (queuedSaveAutomation == null && currentSave(remainingRun)) queuedSaveAutomation = remainingRun;
        if (queuedSaveAutomation == null) saveAutomationTimer.stop();
        else saveAutomationTimer.restart();
    }

    private void trySaveAutomation() {
        SaveAutomation request = queuedSaveAutomation;
        if (!currentSave(request)) { queuedSaveAutomation = null; return; }
        if (saving || formattingSave || pendingTestSnapshot || tests.busy() || maven.busy()
                || refactoring.busy()) { saveAutomationTimer.restart(); return; }
        queuedSaveAutomation = null;
        if (maven.importRequest() != null && (maven.workspace() == null || !maven.workspace().resolved() || !maven.trusted())) {
            console.append("Save automation paused: resolve dependencies and trust this Maven project through Run or Test.\n", false);
            return;
        }
        boolean run = request.run() && settings.autoRunOnSave && request.runGeneration() == runRequest;
        boolean test = request.tests() && settings.autoTestOnSave;
        if (!run && !test) return;
        if (pendingAnalysis != null) pendingAnalysis.cancel(false);
        if (pendingMavenCompile != null) pendingMavenCompile.cancel(false);
        runningSaveAutomation = new SaveAutomation(request.file(), request.version(), request.revision(), request.runGeneration(), run, test);
        startTests(test ? TurboTestController.Mode.ALL : TurboTestController.Mode.COMPILE, true, true);
    }

    /** The test controller compiles the coherent workspace once before either automatic action. */
    private void afterSaveCompilation(WorkspaceCompilationResult compiled) {
        SaveAutomation request = runningSaveAutomation;
        runningSaveAutomation = null;
        if (!currentSave(request) || !compiled.success() || !request.run() || !settings.autoRunOnSave
                || request.runGeneration() != runRequest) return;
        pendingSaveRun = request;
        var model = maven.workspace();
        if (model != null) {
            CompletableFuture.supplyAsync(() -> nativeMavenCompiler().latest().orElseThrow(), nativeProjectCompiler)
                    .whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        if (!currentSave(request) || model != maven.workspace() || !settings.autoRunOnSave
                                || request.runGeneration() != runRequest) { finishPendingSaveRun(request); return; }
                        if (failure != null || result.sourceGeneration() != compiled.version()) {
                            finishPendingSaveRun(request);
                            console.append("Auto Run on Save: compilation changed before execution.\n", false); return;
                        }
                        var candidates = MavenRunTargets.candidates(result, request.file());
                        MavenRunTargets.Target target = candidates.size() == 1 ? candidates.getFirst()
                                : candidates.contains(lastMavenRunTarget) ? lastMavenRunTarget : null;
                        if (target == null) { finishPendingSaveRun(request); autoRunUnavailable(candidates.size()); return; }
                        long run = ++runRequest, op = operation.get();
                        var previous = execution;
                        programWorker.stop(); running = false; console.setProgramRunning(false);
                        var value = new CompilationResult(editor.version(), true, List.of(), Map.of(), result.durationNanos(), false, target.mainClass());
                        launchMavenTarget(model, target, value, request.revision(), op, run, settings.isolated, previous, true, request);
                    }));
        } else {
            var bytes = compiled.bytecode();
            String selected = JavacCompilerBackend.findMainForSource(bytes, request.file().toString());
            if (selected == null) {
                var candidates = bytes.entrySet().stream().filter(entry -> JavacCompilerBackend.findMain(
                        Map.of(entry.getKey(), entry.getValue()), entry.getKey() + ".java") != null).map(Map.Entry::getKey).sorted().toList();
                selected = candidates.size() == 1 ? candidates.getFirst() : candidates.contains(lastPlainRunTarget) ? lastPlainRunTarget : null;
                if (selected == null) { finishPendingSaveRun(request); autoRunUnavailable(candidates.size()); return; }
            }
            String main = selected;
            List<Path> classpath = testWorkspace == null ? List.of() : testWorkspace.classpath();
            long run = ++runRequest;
            var previous = execution;
            programWorker.stop(); running = false; console.setProgramRunning(false);
            previous.whenComplete((ignored, failure) -> SwingUtilities.invokeLater(() -> {
                if (!currentSave(request) || run != runRequest || !settings.autoRunOnSave) { finishPendingSaveRun(request); return; }
                finishPendingSaveRun(request);
                programWorker = worker; lastPlainRunTarget = main;
                showRepl(false);
                startRun(new CompilationResult(editor.version(), true, List.of(), bytes, compiled.durationNanos(), false, main), settings.isolated, run, classpath, false);
            }));
        }
    }

    private void autoRunUnavailable(int candidates) {
        console.append(candidates == 0 ? "Auto Run on Save: this project has no runnable main.\n"
                : "Auto Run on Save: use Run once to choose a main class.\n", false);
    }

    private void startTests(TurboTestController.Mode mode, boolean automatic) {
        startTests(mode, automatic, false);
    }

    private void startTests(TurboTestController.Mode mode, boolean automatic, boolean onSave) {
        if (pendingTestSnapshot || closing || (!automatic && tests.busy()))
            return;
        var mavenModel = maven.workspace();
        if (maven.importRequest() != null && (maven.busy() || mavenModel == null || !mavenModel.resolved())) {
            if (!automatic) console.append(maven.busy() ? "Wait for Maven dependencies to finish loading.\n"
                    : "Maven dependencies are incomplete. Refresh Dependencies or check Maven Settings.\n", true);
            return;
        }
        if (mavenModel != null && mode != TurboTestController.Mode.COMPILE && !maven.trusted()) {
            if (automatic || !maven.requireTrust()) return;
        }
        saveTestRun = onSave;
        var capture = new NativeRefactoringHost().capture();
        var semantic = intelligenceSnapshot();
        int offset = editor.caretOffset();
        long sourceGeneration = refactoringRevision;
        long selectionRequest = ++testSelectionRequest;
        pendingTestSnapshot = true;
        CompletableFuture<TestInvocation> selected;
        if (mavenModel != null && mode != TurboTestController.Mode.METHOD && mode != TurboTestController.Mode.CLASS) {
            selected = CompletableFuture.supplyAsync(() -> {
                var sources = semantic.openSources().entrySet().stream().filter(entry -> entry.getKey().startsWith(mavenModel.root()) && entry.getKey().getFileName().toString().endsWith(".java"))
                        .map(entry -> new Model.SourceSnapshot(entry.getKey(), entry.getValue().toString(), entry.getValue().version(), true)).toList();
                var snapshot = new Model.WorkspaceSnapshot(mavenModel.root(), sourceGeneration, sources,
                        dev.turboj.project.MavenSemanticAdapter.sourceRoots(mavenModel), List.of(), dev.turboj.project.MavenSemanticAdapter.configurations(mavenModel));
                return new TestInvocation(snapshot, new TurboTestController.Selection(mode, "", ""));
            }, nativeProjectCompiler);
        } else selected = intelligence.submitSemantic(semantic, (service, context) -> {
            var engine = service.sharedRefactorJ(context);
            var selection = new TurboTestController.Selection(mode, "", "");

            if (mode == TurboTestController.Mode.METHOD || mode == TurboTestController.Mode.CLASS) {
                var target = service.testTarget(context, offset);

                if (target == null || target.className() == null || target.className().isBlank())
                    throw new IllegalArgumentException("Place the caret inside a Java test class");
                if (mode == TurboTestController.Mode.METHOD && (target.methodName() == null
                        || target.methodName().isBlank()))
                    throw new IllegalArgumentException("Place the caret inside the test method to run");
                String method = target.methodName() == null ? "" : target.methodName();

                if (!target.parameterTypes().isEmpty())
                    method += "(" + target.parameterTypes() + ")";
                selection = new TurboTestController.Selection(mode, target.className(), method);
            }
            return new TestInvocation(engine.workspace().snapshot(), selection);
        });
        selected.whenComplete((invocation, failure) -> SwingUtilities.invokeLater(() -> {
            if (closing || selectionRequest != testSelectionRequest)
                return;
            if (failure != null) {
                pendingTestSnapshot = false;
                if (onSave) runningSaveAutomation = null;
                console.append("TurboTest: " + message(failure) + "\n", false);
                return;
            }
            if (!capture.equals(new NativeRefactoringHost().capture())) {
                pendingTestSnapshot = false;
                if (onSave) runningSaveAutomation = null;
                console.append("Source changed while selecting tests; run again.\n", false);
                return;
            }
            testCapture = capture;
            testWorkspace = invocation.workspace();
            if (mavenModel != null) {
                if (maven.workspace() != mavenModel) { pendingTestSnapshot = false; if (onSave) runningSaveAutomation = null; return; }
                Map<Path, String> overlays = new LinkedHashMap<>();
                invocation.workspace().sources().forEach(source -> overlays.put(source.file(), source.text()));
                String moduleId = mode == TurboTestController.Mode.METHOD || mode == TurboTestController.Mode.CLASS
                        ? mavenModel.moduleFor(bufferKey).map(MavenModels.Module::id).orElse("") : "";
                CompletableFuture.supplyAsync(this::nativeMavenCompiler, nativeProjectCompiler).whenComplete((coordinator, error) -> SwingUtilities.invokeLater(() -> {
                    if (closing || selectionRequest != testSelectionRequest) return;
                    pendingTestSnapshot = false;
                    if (maven.workspace() != mavenModel || !capture.equals(new NativeRefactoringHost().capture())) {
                        if (onSave) runningSaveAutomation = null;
                        return;
                    }
                    if (error != null) { if (onSave) runningSaveAutomation = null; console.append(message(error) + "\n", true); return; }
                    tests.setMavenCompiler(coordinator);
                    tests.runMaven(invocation.workspace(), mavenModel, overlays, invocation.selection(), moduleId,
                            () -> !closing && maven.workspace() == mavenModel && capture.equals(new NativeRefactoringHost().capture()), maven.trusted(), automatic);
                }));
            } else {
                pendingTestSnapshot = false;
                if (automatic && !onSave) tests.afterRefactoring(testWorkspace);
                else tests.run(testWorkspace, invocation.selection());
            }
        }));
    }

    private final class NativeTestHost implements TurboTestController.Host {
        @Override
        public void begin(boolean compilationOnly) {
            showRepl(false);
            setMessagesVisible(true);
            testOutput.begin(compilationOnly);
            buildStatus = compilationOnly ? "PROJECT COMPILING…" : "TURBOTEST · COMPILING…";
            refreshTitle();
        }

        @Override
        public boolean current(Model.WorkspaceSnapshot snapshot) {
            return !closing && testWorkspace != null && testWorkspace.equals(snapshot)
                    && testCapture != null && testCapture.equals(new NativeRefactoringHost()
                    .capture());
        }

        @Override
        public void status(String text) {
            testOutput.status(text);
        }

        @Override
        public void completed(String message, TestEngine.Report result) {
            if (result == null) status(message);
            else report(result);
        }

        @Override
        public void compilation(dev.turboj.compiler.WorkspaceCompilationResult result) {
            var model = maven.workspace();
            if (model != null && testWorkspace != null) testWorkspace = new Model.WorkspaceSnapshot(model.root(), result.version(),
                    result.sources().stream().filter(source -> Path.of(source.fileName()).isAbsolute() && Path.of(source.fileName()).startsWith(model.root()))
                            .map(source -> new Model.SourceSnapshot(Path.of(source.fileName()), source.source(), source.version(), true)).toList(),
                    testWorkspace.sourceRoots(), testWorkspace.classpath(), testWorkspace.projects());
            List<dev.turboj.compiler.CompilerDiagnostic> active = result.diagnostics().stream()
                    .filter(d -> d.fileName().equals(bufferKey.toString()) || (file != null && d
                    .fileName().equals(file.toString())))
                    .map(dev.turboj.compiler.WorkspaceCompilationResult.SourceDiagnostic::diagnostic)
                    .toList();
            editor.setDiagnostics(active);
            buildStatus = result.success() ? "PROJECT BUILD OK" : "PROJECT BUILD FAILED";
            refreshTitle();
            testOutput.compilation(result, model != null ? model.root()
                    : testWorkspace == null ? null : testWorkspace.root());
            afterSaveCompilation(result);
        }

        @Override
        public void report(TestEngine.Report result) {
            testCompilerDetails = testOutput.compilerDetails();
            testOutput.report(result);
            buildStatus = "TURBOTEST · " + TestOutput.outcome(result);
            refreshTitle();
            if (!tests.automatic() && !saveTestRun)
                showTestResults();
        }

        @Override
        public void output(dev.turboj.execution.OutputEvent event) {
            console.append(event.text(), event.stderr());
        }
    }

    private void showTestResults() {
        TestEngine.Report report = tests == null ? null : tests.lastReport();

        if (report == null)
            return;
        if (testResults != null)
            testResults.dispose();
        testResults = new EmbeddedDialog(this, "TurboTest — " + report.passed() + " passed, " + report
                .failed() + " failed", false);
        var list = new JList<TestEngine.Test> (report.tests().toArray(TestEngine.Test[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view, test, index, selected, focus) -> {
            JLabel row = new JLabel("[" + test.status() + "] " + TestOutput.name(test) + " · "
                    + TestOutput.duration(test.durationNanos()));
            row.setFont(AsciiChrome.font());
            row.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            row.setOpaque(true);
            row.setBackground(selected ? view.getSelectionBackground() : view.getBackground());
            row.setForeground(selected ? view.getSelectionForeground()
                    : ConsolePane.colorFor(theme, TestOutput.role(test.status()), view.getBackground()));
            return row;
        });
        JTextArea detail = new JTextArea();
        detail.setEditable(false);
        detail.setFont(editor.getFont().deriveFont(12f));
        list.addListSelectionListener(event -> {
            var selected = list.getSelectedValue();
            detail.setText(selected == null ? "" : "[" + selected.status() + "] "
                    + TestOutput.name(selected) + "\nDuration: "
                    + TestOutput.duration(selected.durationNanos())
                    + (selected.failure() == null || selected.failure().isBlank() ? "" : "\n\n" + selected.failure())
                    + (selected.trace() == null || selected.trace().isBlank() ? "" : "\n\n" + selected.trace()));
            detail.setCaretPosition(0);
        });
        if (!report.tests().isEmpty())
            list.setSelectedIndex(0);
        else
            detail.setText(String.join("\n", report.engineFailures()));
        var details = new JTabbedPane();
        details.addTab("Test details", new JScrollPane(detail));
        JTextArea compilationDetails = new JTextArea(testCompilerDetails.isEmpty()
                ? "No compiler diagnostics." : String.join("\n\n", testCompilerDetails));
        compilationDetails.setEditable(false);
        compilationDetails.setFont(detail.getFont());
        compilationDetails.setCaretPosition(0);
        details.addTab("Compilation details (" + testCompilerDetails.size() + ")",
                new JScrollPane(compilationDetails));
        if (!report.engineFailures().isEmpty()) {
            JTextArea failures = new JTextArea(String.join("\n\n", report.engineFailures()));
            failures.setEditable(false);
            failures.setFont(detail.getFont());
            failures.setCaretPosition(0);
            details.addTab("Engine errors", new JScrollPane(failures));
        }
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), details);
        split.setResizeWeight(.45);
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel(TestOutput.summary(report) + " · Source " + report.sourceVersion()),
                BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        JButton open = new JButton("Go to source"), failed = new JButton("Run failed"),
                close = new JButton("Close");
        open.addActionListener(event -> navigateTest(list.getSelectedValue()));
        failed.setEnabled(report.failed() > 0);
        failed.addActionListener(event -> {
            testResults.dispose();
            startTests(TurboTestController.Mode.FAILED, false);
        });
        close.addActionListener(event -> testResults.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(open);
        actions.add(failed);
        actions.add(close);
        panel.add(actions, BorderLayout.SOUTH);
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-test");
        list.getActionMap().put("open-test", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                open.doClick();
            }
        });
        testResults.setContentPane(panel);
        testResults.setSize(Math.min(950, getWidth()), Math.min(600, getHeight()));
        testResults.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(list::requestFocusInWindow);
        testResults.getRootPane().registerKeyboardAction(event -> testResults.dispose(), KeyStroke
                .getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        testResults.setVisible(true);
    }

    private void navigateTest(TestEngine.Test test) {
        if (test == null || testWorkspace == null)
            return;
        String className = test.className().split("\\$")[0];
        String relative = className.replace('.', '/') + ".java";
        Path path = testWorkspace.sources().stream().map(Model.SourceSnapshot::file)
                .filter(candidate -> candidate.toString().replace('\\', '/').endsWith(relative))
                .findFirst().orElse(null);

        if (path == null) {
            console.append("Source for " + test.className() + " is not attached.\n", false);
            return;
        }
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(path.getFileName()
                .toString()) + ":([0-9]+)").matcher(test.trace());

        if (match.find()) {
            openTestLocation(path, Integer.parseInt(match.group(1)), null);
            return;
        }
        // A passing test has no stack location. Resolve a real declaration; never choose one
        // arbitrarily when a method name has overloads.
        intelligence.submitSemantic(intelligenceSnapshot(), (service, context) -> service
                .sharedRefactorJ(context).workspace().symbols(test.methodName(),
                dev.turboj.refactorj.api.Cancellation.NONE).stream().filter(symbol -> symbol.name()
                .equals(test.methodName()) && symbol.declaration() != null && symbol.declaration()
                .file().equals(path)).toList()).whenComplete((symbols, failure) -> SwingUtilities
                .invokeLater(() -> {
            if (closing)
                return;
            if (failure == null && symbols.size() == 1)
                openTestLocation(path, null, symbols.getFirst().declaration().start());
            else {
                openTestLocation(path, 1, null);
                console
                        .append("Opened test source; a unique declaration location is unavailable.\n",
                        false);
            }
        }));
    }

    private void openTestLocation(Path path, Integer line, Integer offset) {
        if (testResults != null)
            testResults.dispose();
        openPath(path);
        javax.swing.Timer follow = new javax.swing.Timer(30, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        follow.addActionListener(event -> {
            if (path.equals(file)) {
                if (offset != null)
                    editor.gotoOffset(offset);
                else
                    editor.gotoLine(line);
                follow.stop();
            } else if (System.nanoTime() > deadline)
                follow.stop();
        });
        follow.start();
    }

    /** EDT-only open-buffer participant. Disk publication is owned by NativeWorkspaceCommitter. */
    private final class NativeRefactoringHost implements NativeWorkspaceCommitter.Host {
        @Override
        public NativeWorkspaceCommitter.Capture capture() {
            if (!SwingUtilities.isEventDispatchThread())
                throw new IllegalStateException("Capture buffers on EDT");
            rememberBuffer();
            Map<Path, NativeWorkspaceCommitter.BufferSnapshot> snapshots = new LinkedHashMap<> ();
            buffers.forEach((key, value) -> {
                if (!value.state().readOnly())
                    snapshots.put(key, new NativeWorkspaceCommitter.BufferSnapshot(key, value
                            .state().document().toString(), value.state().document().version(),
                            value.state().document(), value.fingerprint() == null ? null : HexFormat
                            .of().formatHex(value.fingerprint())));
            });
            return new NativeWorkspaceCommitter.Capture(refactoringRevision, snapshots);
        }

        @Override
        public boolean historyReady(Object token, NativeWorkspaceCommitter.Direction direction) {
            if (closing || !(token instanceof RefactoringHistory history))
                return false;
            rememberBuffer();
            boolean undo = direction == NativeWorkspaceCommitter.Direction.UNDO;
            Map<Path, Buffer> expected = undo ? history.after() : history.before();
            Map<Path, Buffer> opposite = undo ? history.before() : history.after();
            Map<Path, String> texts = undo ? history.afterText() : history.beforeText();

            if (!expected.containsKey(bufferKey) && !bufferKey.equals(undo ? history
                    .activeAfter() : history.activeBefore()))
                return false;
            for (Path absent : opposite.keySet())
                if (!expected.containsKey(absent) && buffers.containsKey(absent))
                    return false;
            for (var entry : expected.entrySet()) {
                Buffer current = buffers.get(entry.getKey());

                if (current == null || current.state().document() != entry.getValue().state()
                        .document() || !current.state().document().toString().equals(texts.get(entry
                        .getKey())))
                    return false;
            }
            for (var entry : history.stableHeads().entrySet())
                if (entry.getKey().peekUndoTransactionId() != entry.getValue())
                    return false;
            for (var entry : history.transactions().entrySet()) {
                long next = undo ? entry.getKey().peekUndoTransactionId() : entry.getKey()
                        .peekRedoTransactionId();

                if (next != entry.getValue())
                    return false;
            }
            return true;
        }

        @Override
        public NativeWorkspaceCommitter.Publication publish(NativeWorkspaceCommitter.Capture expected,
                Model.Plan plan, NativeWorkspaceCommitter.Direction direction, Object token) {
            if (closing)
                throw new IllegalStateException("Editor is closing; no refactoring buffers were changed");
            if (!expected.equals(capture()))
                throw new IllegalStateException("Source changed during refactoring; buffer changes rejected");
            if (editor.hasInputComposition())
                throw new IllegalStateException("Finish input composition before applying refactoring");
            if (saving || formattingSave)
                throw new IllegalStateException("Wait for the current save to finish");
            if (direction != NativeWorkspaceCommitter.Direction.APPLY) {
                if (!historyReady(token, direction))
                    throw new IllegalStateException("Undo later edits before undoing the workspace refactoring");
                restoreRefactoring((RefactoringHistory) token, direction);
                remapRefactoringNavigation(plan, direction
                        == NativeWorkspaceCommitter.Direction.UNDO);
                return new NativeWorkspaceCommitter.Publication(capture(), token);
            }
            var changeSet = dev.turboj.refactorj.transactions.ChangeSet.prepare(plan);
            Map<Path, Buffer> before = new LinkedHashMap<> ();

            for (var transition : changeSet.transitions()) {
                Buffer buffer = buffers.get(transition.path());

                if (buffer != null && !buffer.state().readOnly()) {
                    if (transition.before() == null || !buffer.state().document().toString()
                            .equals(transition.before().text()))
                        throw new IllegalStateException("Prepared source differs from the open buffer");
                    before.put(transition.path(), buffer);
                }
            }
            // Even a disk-only refactoring must not overtake subsequent typing in the active buffer.
            before.putIfAbsent(bufferKey, buffers.get(bufferKey));
            Map<Path, String> beforeText = bufferTexts(before);
            Map<dev.turboj.editor.document.PieceTable, Long> transactions = new LinkedHashMap<> ();
            Path activeBefore = bufferKey, activeAfter = bufferKey;
            refactoringPublishing = true;
            try {
                for (var fileEdit : plan.edits()) {
                    Buffer buffer = buffers.get(fileEdit.file());

                    if (buffer == null || buffer.state().readOnly())
                        continue;
                    editor.restoreState(buffer.state());
                    var edits = fileEdit.edits().stream().map(e -> new dev.javafmt.core.TextEdit(e
                            .start(), e.end(), e.text())).toList();
                    long oldVersion = editor.version();

                    if (!editor.applyFormatting(oldVersion, edits))
                        throw new IllegalStateException("Cannot publish prepared buffer edits");
                    if (editor.version() != oldVersion)
                        transactions.put(editor.document(), editor.document()
                                .peekUndoTransactionId());
                    buffers.put(fileEdit.file(), new Buffer(buffer.file(), editor.captureState(),
                            buffer.fingerprint(), buffer.savedVersion()));
                }
                for (var fileOperation : plan.fileOperations()) {
                    if (fileOperation.kind() == Model.FileOperationKind.MOVE) {
                        Buffer moved = buffers.remove(fileOperation.path());

                        if (moved != null)
                            buffers.put(fileOperation.destination(), new Buffer(fileOperation
                                    .destination(), moved.state(), moved.fingerprint(), moved
                                    .savedVersion()));
                        if (activeAfter.equals(fileOperation.path()))
                            activeAfter = fileOperation.destination();
                    } else if (fileOperation.kind() == Model.FileOperationKind.DELETE)
                        buffers.remove(fileOperation.path());
                }
                Map<Path, Buffer> after = new LinkedHashMap<> ();

                for (var transition : changeSet.transitions()) {
                    Buffer buffer = buffers.get(transition.path());

                    if (buffer != null && !buffer.state().readOnly())
                        after.put(transition.path(), buffer);
                }
                if (!buffers.containsKey(activeAfter))
                    activeAfter = replacementBuffer();
                after.putIfAbsent(activeAfter, buffers.get(activeAfter));
                activate(activeAfter, buffers.get(activeAfter));
                Map<dev.turboj.editor.document.PieceTable,
                        Long> stableHeads = new LinkedHashMap<> ();

                for (Buffer unchanged : after.values())
                    if (!transactions.containsKey(unchanged.state().document()))
                        stableHeads.put(unchanged.state().document(), unchanged.state().document()
                                .peekUndoTransactionId());
                var history = new RefactoringHistory(Map.copyOf(before), Map.copyOf(after), Map
                        .copyOf(beforeText), Map.copyOf(bufferTexts(after)), Map
                        .copyOf(transactions), Map.copyOf(stableHeads), activeBefore, activeAfter);
                remapRefactoringNavigation(plan, false);
                refactoringRevision++;
                return new NativeWorkspaceCommitter.Publication(capture(), history);
            } finally {
                refactoringPublishing = false;
            }
        }

        private void restoreRefactoring(RefactoringHistory history,
                NativeWorkspaceCommitter.Direction direction) {
            boolean undo = direction == NativeWorkspaceCommitter.Direction.UNDO;
            Map<Path, Buffer> target = undo ? history.before() : history.after();
            Map<Path, Buffer> current = undo ? history.after() : history.before();
            Path selected = undo ? history.activeBefore() : history.activeAfter();
            refactoringPublishing = true;
            try {
                for (var entry : history.transactions().entrySet()) {
                    Buffer state = target.values().stream().filter(b -> b.state().document()
                            == entry.getKey()).findFirst().orElseGet(() -> current.values().stream()
                            .filter(b -> b.state().document() == entry.getKey()).findFirst()
                            .orElseThrow());
                    editor.restoreState(state.state());
                    if (undo)
                        editor.undo();
                    else
                        editor.redo();
                }
                current.keySet().forEach(buffers::remove);
                target.forEach(buffers::put);
                if (!buffers.containsKey(selected))
                    selected = replacementBuffer();
                activate(selected, buffers.get(selected));
                refactoringRevision++;
            } finally {
                refactoringPublishing = false;
            }
        }

        private Path replacementBuffer() {
            if (!buffers.isEmpty())
                return buffers.keySet().iterator().next();
            Path key = untitledKey();
            var document = new dev.turboj.editor.document.PieceTable("");
            buffers.put(key, new Buffer(null, new EditorComponent.EditorState(document,
                    new EditorComponent.Location(0, 0, 0, 0), false), null, document.version()));
            return key;
        }

        private Map<Path, String> bufferTexts(Map<Path, Buffer> values) {
            Map<Path, String> texts = new LinkedHashMap<> ();
            values.forEach((path, buffer) -> texts.put(path, buffer.state().document().toString()));
            return texts;
        }
    }

    private void remapRefactoringNavigation(Model.Plan plan, boolean reverse) {
        navigation.remap(visit -> {
            Path key = visit.key();

            for (var operation : plan.fileOperations())
                if (operation.kind() == Model.FileOperationKind.MOVE) {
                    Path from = reverse ? operation.destination() : operation.path(),
                            to = reverse ? operation.path() : operation.destination();

                    if (key.equals(from))
                        key = to;
                }
            var location = visit.location();

            for (var changes : plan.edits())
                if (changes.file().equals(reverse ? key : visit.key())) {
                    List<dev.javafmt.core.TextEdit> edits = new ArrayList<> ();
                    int delta = 0;

                    for (var edit : changes.edits()) {
                        if (reverse)
                            edits.add(new dev.javafmt.core.TextEdit(edit.start() + delta, edit
                                    .start() + delta + edit.text().length(), plan.before()
                                    .source(changes.file()).text().substring(edit.start(), edit
                                    .end())));
                        else
                            edits.add(new dev.javafmt.core.TextEdit(edit.start(), edit.end(), edit
                                    .text()));
                        delta += edit.text().length() - (edit.end() - edit.start());
                    }
                    location = new EditorComponent.Location(dev.javafmt.core.TextEdit
                            .mapOffset(location.caret(), edits), dev.javafmt.core.TextEdit
                            .mapOffset(location.anchor(), edits), location.topLine(), location
                            .leftColumn());
                }
            Buffer target = buffers.get(key);
            return new Visit(key, location, target == null ? visit.version() : target.state()
                    .document().version());
        });
    }

    private void formattingApplied(FormattingController.Applied result) {
        Path key = bufferKey;
        navigation.remap(
                visit -> {
                    if (!visit.key().equals(key) || visit.version() != result.before().version())
                        return visit;
                    var old = visit.location();
                    int top =
                            result.before()
                                    .lineStart(
                                            Math.min(
                                                    old.topLine(),
                                                    result.before().lineCount() - 1));
                    int mappedTop = dev.javafmt.core.TextEdit.mapOffset(top, result.edits());
                    var mapped =
                            new EditorComponent.Location(
                                    dev.javafmt.core.TextEdit.mapOffset(
                                            old.caret(), result.edits()),
                                    dev.javafmt.core.TextEdit.mapOffset(
                                            old.anchor(), result.edits()),
                                    result.document()
                                            .lineAt(
                                                    Math.clamp(
                                                            mappedTop,
                                                            0,
                                                            result.document().length())),
                                    old.leftColumn());
                    return new Visit(key, mapped, result.afterVersion());
                });
    }

    private void showAISettings(AIConfig initial) {
        var dialog =
                new AISettingsDialog(
                        this,
                        initial,
                        aiCredentials,
                        this::saveAIConfig,
                        text -> console.append(text + "\n", false));
        dialog.setTheme(theme);
        dialog.setVisible(true);
        editor.requestFocusInWindow();
    }

    private void saveAIConfig(AIConfig value) {
        value.validate();
        settings.ai = value;
        aiCompletion.configure(value);
        String snapshot = settings.snapshotJson();
        background.execute(
                () -> {
                    try {
                        Settings.saveSnapshot(snapshot);
                    } catch (java.io.IOException error) {
                        SwingUtilities.invokeLater(
                                () -> showError("Could not save AI preferences."));
                    }
                });
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
