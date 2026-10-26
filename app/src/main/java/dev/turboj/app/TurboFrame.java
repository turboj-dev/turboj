package dev.turboj.app;

import dev.turboj.ai.*;
import dev.turboj.compiler.*;
import dev.turboj.config.*;
import dev.turboj.console.OutputCommandController;
import dev.turboj.console.OutputCommandGesture;
import dev.turboj.execution.*;
import dev.turboj.formatting.FormattingController;
import dev.turboj.commandj.core.*;
import dev.turboj.refactoring.*;
import dev.turboj.testing.TurboTestController;
import dev.turboj.testing.TestOutput;
import dev.turboj.testing.LineIntelligenceController;
import dev.turboj.testing.LineIntelligenceDialogs;
import dev.turboj.testing.intelligence.LineIntelligence;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.refactorj.api.Operation;
import dev.turboj.refactorj.api.Model;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.intelligence.*;
import dev.turboj.refactorj.api.JavaIntelligence.Completion;
import dev.turboj.refactorj.api.JavaIntelligence.Declaration;
import dev.turboj.ui.*;
import dev.turboj.ui.themes.*;
import dev.turboj.project.MavenProjectController;
import dev.turboj.project.MavenCompilationWorkspace;
import dev.turboj.project.MavenRunTargets;
import dev.turboj.project.SourceEncoding;
import dev.turboj.project.FirstLaunchForms;
import dev.turboj.project.FirstLaunchProjects; import dev.turboj.project.ProjectSearch;
import dev.turboj.testing.MissingJUnit;
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
    private final dev.turboj.project.PlainProjectCompiler plainCompiler = new dev.turboj.project.PlainProjectCompiler();
    private List<Path> currentPlainClasspath = List.of();
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
    private final OutputCommandController outputCommands;
    private final OutputCommandGesture outputCommandGesture = new OutputCommandGesture();
    private boolean replVisible;
    private final StatusBar statusBar = new StatusBar();
    private String projectStatusText = "", projectStatusDetail = "";
    private final JPanel editorPanel = new JPanel(new BorderLayout());
    private final JPanel messages = new JPanel(new BorderLayout());
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
    private QuickFixController quickFix;
    private FirstLaunchProjects.ProjectResult createdProject;
    private boolean firstLaunchBusy;
    private record LinkedName(Model.WorkspaceSnapshot baseline, Model.Location declaration,
            dev.turboj.editor.document.PieceTable document, long checkpoint, EditorComponent.Location location) {}
    private record LinkedRenameUndo(String planId, LinkedName entered) {}
    private LinkedName linkedName;
    private LinkedRenameUndo linkedRenameUndo;
    private long linkedRenameRequest;
    private boolean linkedRenameBusy;
    private TurboTestController tests;
    private LineIntelligenceController lineIntelligence;
    private java.util.function.Consumer<LineIntelligence.Run> cherryResultsView = ignored -> {};
    private final dev.turboj.console.OutputLog log = new dev.turboj.console.OutputLog(console);
    /** The Run or Compile action in the output, and the Maven goal: each writes only to its own action. */
    private dev.turboj.console.OutputLog.Action runLog, mavenLog;
    private final TestOutput testOutput = new TestOutput(log);
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
    private String testSubject = "";
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
            @Override public Path projectsDirectory() { return workspaceDirectory(); }
            @Override public void openPlainProject(Path root) { TurboFrame.this.openPlainProject(root); }
            @Override public void navigate(Path path, int offset, boolean readOnly) {
                if (readOnly) externalSourceFiles.add(path.toAbsolutePath().normalize());
                openAt(path, offset);
            }
            @Override public void published(MavenModels.Workspace value) {
                if (keyboardJava != null) keyboardJava.changed();
                if (intelligence != null) intelligence.changed();
                updateMavenStatus();
                if (file != null && value.moduleFor(file).isPresent() && isJavaBuffer()) changed();
                else if (isXmlBuffer()) publishPomDiagnostics();
            }
            @Override public void notice(String text, boolean error) { flash(text, error ? StatusBar.Level.ERROR : StatusBar.Level.NORMAL); }
            @Override public void actionStart(String action, String subject) { setMessagesVisible(true); mavenLog = startRunLog(action, subject); }
            @Override public void actionOutput(String text) { if (mavenLog != null) mavenLog.program(text, false); }
            @Override public void actionFinish(boolean success, String detail) {
                if (mavenLog != null) mavenLog.finish(success ? dev.turboj.console.OutputLog.Outcome.SUCCESS : dev.turboj.console.OutputLog.Outcome.FAILURE, detail, null);
            }
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
        outputCommands = new OutputCommandController(console, this::outputCommandDirectory,
                () -> editor.requestFocusInWindow());
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
        statusBar.getAccessibleContext().setAccessibleName("Workspace status");
        statusBar.onClick(StatusBar.Segment.STATE, this::statusStateClicked);
        statusBar.onClick(StatusBar.Segment.TESTS, () -> { if (tests != null && tests.lastReport() != null) showTestResults(); });
        statusBar.onClick(StatusBar.Segment.CARET, () -> commands.get("goto").actionPerformed(null));
        statusBar.onClick(StatusBar.Segment.FORMATTER, () -> openMenu("Code", "Formatter"));
        statusBar.onClick(StatusBar.Segment.KEYMAP, () -> openMenu("≡", "Keyboard Profile"));
        statusBar.onClick(StatusBar.Segment.PROJECT, () -> { if (maven.workspace() != null) commands.get("project.structure").actionPerformed(null); });
        statusBar.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updatePositionLabel(); }
        });
        add(statusBar, BorderLayout.SOUTH);
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
                        this::flash);
        intelligence.setTheme(theme);
        intelligence.setLibraryAccepted(this::junitCompletionAccepted);
        keyboardJava = new KeyboardJavaController(this, editor, intelligence, this::intelligenceSnapshot,
                this::navigateDeclaration, this::flash);
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
                        this::flash,
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
                        this::flash,
                        this::formattingApplied);
        formatting.setEngine(dev.turboj.formatting.StandardFormatters.of(settings.formatter).engine());
        refactoring = new RefactoringController(this, () -> {
            Rectangle caret = editor.caretBounds();
            Point point = new Point(caret.x, caret.y + caret.height);
            SwingUtilities.convertPointToScreen(point, editor);
            return point;
        }, intelligence, this::intelligenceSnapshot, () -> new Model.Location(bufferKey, Math
                .min(editor.captureLocation().caret(), editor.captureLocation().anchor()), Math
                .max(editor.captureLocation().caret(), editor.captureLocation().anchor())),
                new NativeRefactoringHost(), this::flash, () -> {
            operation.incrementAndGet();
            long revision = refactoringRevision;
            changed();
            refactoringRevision = revision;
            if (tests != null && tests.automatic())
                startTests(TurboTestController.Mode.ALL, true);
        });
        tests = new TurboTestController(new NativeTestHost());
        lineIntelligence = new LineIntelligenceController(editor, () -> file, tests,
                Settings.directory().resolve("cherry-runs"), this::cherryResult, this::flash);
        editor.setOnInspectLine(target -> LineIntelligenceDialogs.inspect(this, editor, target,
                this::showCherryResults, () -> quickFix.invoke()));
        quickFix = new QuickFixController(this, editor, intelligence, this::intelligenceSnapshot,
                new NativeRefactoringHost(), refactoring, this::navigateGeneratedSource,
                this::junitAction, this::flash);
        quickFix.setTheme(theme);
        keyboardJava.setRefactoring(refactoring::previewRequest);
        tests.setAutomatic(settings.autoTest);
        // CommandJ owns shortcut dispatch, including completion controls. The coordinators are
        // invoked by registered commands; their former keys cannot bypass a customized profile.
        editor.setKeyHandler(event -> {
            if (!outputCommandGesture.accepts(event, editor.hasInputComposition())) return false;
            commands.get("console.commandLine").actionPerformed(
                    new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "Esc :"));
            return true;
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { outputCommandGesture.reset(); }
        });
        editor.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { outputCommandGesture.reset(); }
        });
        editor.setOnChange(this::changed);
        editor.setOnCaretChange(this::updatePosition);
        // Without a file the editor starts empty: sample code is never presented as the user's source.
        editor.setText("");
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
                            flash("Worker startup: " + message(e), StatusBar.Level.ERROR);
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
        else if (maven.importRequest() == null && createdProject != null && !createdProject.maven())
            project = "Java project · " + createdProject.root().getFileName();
        projectStatusText = project;
        projectStatusDetail = value == null ? project : MavenProjectController.statusText(value);
        refreshStatusLine();
    }

    /** The status line: the state now, and the facts the user acts on, each in its own segment. */
    private void refreshStatusLine() {
        String state = compactStatus(buildStatus);
        if (maven != null && maven.lifecycleRunning()) state = "MAVEN BUILD…";
        else if (maven != null && maven.busy()) state = "MAVEN SYNC…";
        else if (maven != null && maven.workspace() != null && !maven.workspace().resolved()
                && !state.contains("FAILED")) state = "DEPENDENCIES INCOMPLETE";
        if (running && programOutput && lastPlainRunTarget != null && !state.endsWith("…")) state = "RUNNING " + lastPlainRunTarget;
        StatusBar.Level level;
        String symbol;
        if (state.contains("FAILED")) { level = StatusBar.Level.ERROR; symbol = "✖"; }
        else if (state.endsWith("…")) { level = StatusBar.Level.BUSY; symbol = "◐"; }
        else if (state.startsWith("RUNNING")) { level = StatusBar.Level.BUSY; symbol = "▶"; }
        else if (state.contains("INCOMPLETE")) { level = StatusBar.Level.WARNING; symbol = "!"; }
        else if (state.contains("OK") || state.contains("PASSED")) { level = StatusBar.Level.SUCCESS; symbol = "●"; }
        else { level = StatusBar.Level.NORMAL; symbol = "●"; }
        String detail = buildStatus.startsWith("BUILD FAILED") || buildStatus.startsWith("PROJECT BUILD FAILED")
                ? errorsDetail() : state.equals("BUILD OK") && currentResult != null ? dev.turboj.console.OutputLog.duration(currentResult.durationNanos()) : "";
        statusBar.set(StatusBar.Segment.STATE, symbol + " " + state + (detail.isEmpty() ? "" : " · " + detail), level);
        String shortcuts = commandKeymaps.shortcutText("save") + " Save   " + commandKeymaps.shortcutText("compile")
                + " Fast Compile   " + commandKeymaps.shortcutText("run") + " Run   "
                + commandKeymaps.shortcutText("palette") + " Actions";
        statusBar.setTooltip(StatusBar.Segment.STATE, buildStatus + (aiStatus.isEmpty() ? "" : "   " + aiStatus) + "   " + shortcuts);
        var report = tests == null ? null : tests.lastReport();
        if (report == null) statusBar.set(StatusBar.Segment.TESTS, "", StatusBar.Level.NORMAL);
        else if (!report.successful()) statusBar.set(StatusBar.Segment.TESTS, "✖ " + (report.failed() + report.engineFailures().size())
                + " of " + report.tests().size() + " tests failed", StatusBar.Level.ERROR);
        else statusBar.set(StatusBar.Segment.TESTS, "✔ " + report.passed() + "/" + report.tests().size() + " tests",
                report.passed() > 0 ? StatusBar.Level.SUCCESS : StatusBar.Level.WARNING);
        statusBar.set(StatusBar.Segment.FORMATTER, dev.turboj.formatting.StandardFormatters.of(settings.formatter).title(), StatusBar.Level.NORMAL);
        statusBar.setTooltip(StatusBar.Segment.FORMATTER, "Formatter: " + dev.turboj.formatting.StandardFormatters.of(settings.formatter).description());
        var keymap = commandKeymaps.selected();
        statusBar.set(StatusBar.Segment.KEYMAP, keymap == null ? "" : keymap.name().replace(" macOS", ""), StatusBar.Level.NORMAL);
        statusBar.setTooltip(StatusBar.Segment.KEYMAP, keymap == null ? null : "Keyboard profile: " + keymap.name());
        statusBar.set(StatusBar.Segment.PROJECT, projectStatusText, StatusBar.Level.NORMAL);
        statusBar.setTooltip(StatusBar.Segment.PROJECT, projectStatusDetail);
    }

    private String errorsDetail() {
        long errors = currentResult == null ? 0 : currentResult.diagnostics().stream().filter(d -> d.kind().equals("ERROR")).count();
        return errors == 0 ? "" : errors + (errors == 1 ? " error" : " errors");
    }

    /** The state segment leads to what it reports: the first error, or the output. */
    private void statusStateClicked() {
        if (currentResult != null && !currentResult.success()) goToError();
        else { setMessagesVisible(true); console.focusInput(); }
    }

    /** Short UI feedback: the status line's message segment, never the output. */
    void flash(String text) { flash(text, StatusBar.Level.NORMAL); }

    void flash(String text, StatusBar.Level level) {
        if (closing || text == null || text.isBlank()) return;
        if (SwingUtilities.isEventDispatchThread()) statusBar.flash(text, level);
        else SwingUtilities.invokeLater(() -> statusBar.flash(text, level));
    }

    /** A notice written once for the output, now shown in the status line: its newlines are dropped. */
    private void note(String text, boolean error) { flash(text.strip(), error ? StatusBar.Level.ERROR : StatusBar.Level.NORMAL); }

    public StatusBar statusBar() { return statusBar; }

    /** Every message the status line showed recently, newest first. */
    public String statusMessages() { return String.join("\n", statusBar.history()); }

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

    /** Format Document and Format on Save use this style from now on; the editor indents as it does. */
    private void selectFormatter(dev.turboj.formatting.StandardFormatters style) {
        settings.formatter = style.id();
        settings.tabWidth = style.indent();
        editor.setTabWidth(style.indent());
        formatting.setEngine(style.engine());
        markFormatter();
        refreshStatusLine();
        flash("Formatter: " + style.title() + " · " + shortcutText("format.document", "Format Document") + " formats the file");
        String snapshot = settings.snapshotJson();
        background.execute(() -> { try { Settings.saveSnapshot(snapshot); } catch (java.io.IOException failure) { errorLater(failure); } });
    }

    private void markFormatter() {
        for (var style : dev.turboj.formatting.StandardFormatters.values())
            commands.get("format.style." + style.id()).putValue(Action.SELECTED_KEY, style.id().equals(settings.formatter));
    }

    /** The active keymap's cheat sheet, with the user's own bindings and this Mac's keyboard settings, as a PDF to print. */
    private void printCheatSheet() {
        var profile = commandKeymaps.selected();
        var bindings = commandKeymaps.bindings();
        note("Printing the " + profile.name() + " cheat sheet…\n", false);
        background.execute(() -> {
            var environment = dev.turboj.help.CheatSheet.Environment.detect();
            SwingUtilities.invokeLater(() -> {
                String html = dev.turboj.help.CheatSheet.html(commandRegistry, bindings, profile.id(), profile.name(), environment);
                background.execute(() -> {
                    try {
                        var printed = dev.turboj.help.CheatSheetPrinter.print(html, Settings.directory().resolve("cheatsheets"),
                                "cheatsheet-" + profile.id());
                        if (printed.pdf()) Desktop.getDesktop().open(printed.file().toFile());
                        else Desktop.getDesktop().browse(java.net.URI.create(printed.file().toUri() + "#print"));
                        SwingUtilities.invokeLater(() -> flash("Cheat sheet: " + printed.file(), StatusBar.Level.NORMAL));
                    } catch (Exception e) {
                        errorLater(e);
                    }
                });
            });
        });
    }

    /** Like Eclipse, opening a file or jumping within one is a step Back returns to; an empty untitled buffer is no place. */
    private void markJump(Visit previous) {
        if (file != null || !editor.getText().isEmpty()) navigation.jumpedFrom(previous);
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

    public OutputCommandController outputCommands() { return outputCommands; }

    /** Snapshot known project state on the EDT; process launch validates the directory off it. */
    private Path outputCommandDirectory() {
        if (maven.workspace() != null) {
            if (file != null) {
                var module = maven.workspace().moduleFor(file);
                if (module.isPresent()) return module.get().directory();
            }
            return maven.workspace().root();
        }
        if (createdProject != null && file != null && file.startsWith(createdProject.root()))
            return createdProject.root();
        if (file != null && file.toAbsolutePath().getParent() != null)
            return file.toAbsolutePath().getParent();
        return workspaceDirectory();
    }

    /** Called by the native application bootstrap, not by test-only frame construction. */
    public void startFirstLaunch() {
        if (!settings.rememberWorkspace || settings.workspaceDirectory == null || settings.workspaceDirectory.isBlank())
            SwingUtilities.invokeLater(this::chooseWorkspace);
    }

    private Path workspaceDirectory() {
        try {
            if (settings.workspaceDirectory != null && !settings.workspaceDirectory.isBlank())
                return Path.of(settings.workspaceDirectory).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) { }
        return Path.of(System.getProperty("user.home"), "Workspace");
    }

    private void chooseWorkspace() {
        if (firstLaunchBusy || closing) return;
        var choice = FirstLaunchForms.selectWorkspace(this, workspaceDirectory());
        if (choice == null) { editor.requestFocusInWindow(); return; }
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try { return Files.createDirectories(choice.directory()).toRealPath(); }
            catch (java.io.IOException failure) { throw new CompletionException(failure); }
        }, background).whenComplete((directory, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            settings.workspaceDirectory = directory.toString(); settings.rememberWorkspace = choice.remember();
            String preferences = settings.snapshotJson();
            background.execute(() -> { try { Settings.saveSnapshot(preferences); } catch (Exception error) { errorLater(error); } });
            flash("Workspace selected: " + directory, StatusBar.Level.NORMAL); editor.requestFocusInWindow();
        }));
    }

    private void createJavaProject(boolean mavenProject) {
        if (firstLaunchBusy || closing) return;
        if (maven.lifecycleRunning()) { showError("Stop the Maven goal before creating another project."); return; }
        var spec = FirstLaunchForms.project(this, workspaceDirectory(), mavenProject);
        if (spec == null) { editor.requestFocusInWindow(); return; }
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try { return FirstLaunchProjects.createProject(spec); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, background).whenComplete((project, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            createdProject = project;
            if (project.maven()) { maven.clearProject(); maven.open(project.root()); }
            else maven.showPlainProject(project.root(), project.sourceRoots());
            String kind = project.maven() ? "Maven" : "Java";
            if (project.initialCaret() < 0) {
                openPath(project.initialFile());
                note("Created " + kind + " project " + project.root().getFileName() + ". Use New Java Class to create a main class.\n", false);
                return;
            }
            openAt(project.initialFile(), project.initialCaret());
            note("Created " + kind + " project " + project.root().getFileName() + " with "
                    + project.initialFile().getFileName() + ".\n", false);
        }));
    }

    /** A folder without pom.xml: shown in the Project Explorer and used as the project for New Java Class. */
    /** The project the current file belongs to: output locations are written relative to it. */
    private Path projectRoot() {
        if (maven.workspace() != null) return maven.workspace().root();
        if (createdProject != null) return createdProject.root();
        return file == null ? null : file.toAbsolutePath().getParent();
    }

    /** A new Run or Compile action; the previous one, if still open, was superseded by it. */
    private dev.turboj.console.OutputLog.Action startRunLog(String action, String subject) {
        if (runOpen()) runLog.finish(dev.turboj.console.OutputLog.Outcome.STOPPED, "superseded", null);
        runLog = log.start(action, subject);
        return runLog;
    }

    private boolean runOpen() { return runLog != null && runLog.open(); }

    /** " · hello-world · Java 25" after an action's subject in the output header. */
    private String projectSuffix() {
        String project = maven.workspace() != null ? maven.workspace().root().getFileName().toString()
                : createdProject != null ? createdProject.root().getFileName().toString() : "";
        return (project.isEmpty() ? "" : " · " + project) + " · Java " + Runtime.version().feature();
    }

    private void openPlainProject(Path folder) {
        if (closing) return;
        if (maven.lifecycleRunning()) { showError("Stop the Maven goal before opening another project."); return; }
        Path root = folder.toAbsolutePath().normalize();
        var remembered = settings.recentFiles.stream().map(Path::of).filter(path -> path.startsWith(root)).toList();
        record Opened(List<Path> roots, Path start) {}
        CompletableFuture.supplyAsync(() -> {
            List<Path> roots = ProjectContext.plainSourceRoots(root);
            // With the explorer closed, the project shows itself through a file: the last one used, else its main.
            Path start = remembered.stream().filter(Files::isRegularFile).findFirst().orElseGet(() -> ProjectContext.startSource(roots));
            return new Opened(roots, start);
        }, background).whenComplete((opened, failure) -> SwingUtilities.invokeLater(() -> {
            if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            if (maven.lifecycleRunning()) { showError("Stop the Maven goal before opening another project."); return; }
            createdProject = new FirstLaunchProjects.ProjectResult(root, opened.roots(), root.resolve(".turboj/project.properties"), false, -1);
            maven.showPlainProject(root, opened.roots());
            updateMavenStatus();
            if (opened.start() != null) openPath(opened.start());
            flash("Opened Java project " + root.getFileName() + " (" + root + ")"
                    + (opened.start() == null ? ". It has no Java sources yet." : "."), StatusBar.Level.NORMAL);
        }));
    }

    private record NewClassContext(Model.WorkspaceSnapshot snapshot, Path sourceRoot) {}

    private void createMainClass() {
        if (firstLaunchBusy || closing) return;
        var captured = new NativeRefactoringHost().capture();
        var project = createdProject;
        var mavenModel = maven.workspace();
        Path active = file;
        String source = editor.getText();
        var overlays = intelligenceSnapshot().openSources();
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                ProjectContext.Config config;
                if (mavenModel != null && active != null && mavenModel.moduleFor(active).isPresent())
                    config = ProjectContext.fromMaven(mavenModel);
                else if (project != null && (active == null || active.startsWith(project.root())))
                    config = new ProjectContext.Config(project.root(), project.sourceRoots(), List.of(), List.of(), 1, List.of());
                else {
                    if (active == null) throw new IllegalStateException("Create or open a Java project first.");
                    config = ProjectContext.detect(active, source);
                }
                var disk = dev.turboj.refactorj.transactions.DiskWorkspace.open(config.projectRoot(), config.sourceRoots(), config.classPath()).snapshot();
                Map<Path, Model.SourceSnapshot> sources = new LinkedHashMap<>();
                disk.sources().forEach(s -> sources.put(s.file(), s));
                overlays.forEach((path, text) -> {
                    if (path.startsWith(disk.root()) && path.getFileName().toString().endsWith(".java"))
                        sources.put(path, new Model.SourceSnapshot(path, text.toString(), text.version(), true));
                });
                var snapshot = new Model.WorkspaceSnapshot(disk.root(), disk.version(), List.copyOf(sources.values()), disk.sourceRoots(), disk.classpath(), config.projects());
                var roots = mavenModel != null && active != null
                        ? mavenModel.moduleFor(active).map(MavenModels.Module::sourceRoots).orElse(config.sourceRoots()) : config.sourceRoots();
                Path root = roots.stream().filter(p -> active != null && active.startsWith(p)).findFirst()
                        .orElseGet(() -> roots.stream().findFirst().orElse(config.projectRoot()));
                return new NewClassContext(snapshot, root);
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, background).whenComplete((context, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            if (mavenModel != maven.workspace()) { showError("Project changed; create the class again."); return; }
            if (!captured.equals(new NativeRefactoringHost().capture())) { showError("Source changed; create the class again."); return; }
            var spec = FirstLaunchForms.mainClass(this, context.sourceRoot(), "");
            if (spec != null) prepareGenerated(captured, () -> FirstLaunchProjects.mainPlan(context.snapshot(), spec));
        }));
    }

    private void junitAction(dev.turboj.refactorj.api.JavaQuickActions.Inspection inspection,
            dev.turboj.refactorj.api.JavaQuickActions.Proposal proposal) {
        if (proposal.action() != dev.turboj.refactorj.api.JavaQuickActions.Action.ADD_JUNIT_LIBRARY) {
            createJUnitTest(inspection, proposal); return;
        }
        if (firstLaunchBusy || closing) return;
        var captured = new NativeRefactoringHost().capture();
        intelligence.submitSemantic(intelligenceSnapshot(), (service, context) -> service.sharedRefactorJ(context)
                .quickActions().prepare(proposal, () -> closing))
                .whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
                    if (closing) return;
                    if (failure != null) { showError(message(failure)); return; }
                    addJUnitLibrary(captured, proposal.location().file(), inspection.snapshot(), prepared.plan().edits());
                }));
    }

    /** Accepting a completion such as @Test that needs JUnit adds the library, as the quick fix does. */
    private void junitCompletionAccepted(String library) {
        if (!Completion.JUNIT_JUPITER.equals(library) || firstLaunchBusy || closing || file == null) return;
        var captured = new NativeRefactoringHost().capture();
        var request = intelligenceSnapshot();
        intelligence.submitSemantic(request, (service, context) -> service.sharedRefactorJ(context).workspace().snapshot())
                .whenComplete((snapshot, failure) -> SwingUtilities.invokeLater(() -> {
                    if (closing) return;
                    if (failure != null) { showError(message(failure)); return; }
                    addJUnitLibrary(captured, request.file(), snapshot, List.of());
                }));
    }

    /** Eclipse's "Add JUnit 5 library to the build path": a POM test dependency followed by a Maven reload,
     * or TurboJ's bundled library for a plain Java project. Java edits that need the library join the same undo. */
    private void addJUnitLibrary(NativeWorkspaceCommitter.Capture captured, Path target, Model.WorkspaceSnapshot snapshot,
            List<Model.FileEdits> javaEdits) {
        if (firstLaunchBusy || closing) return;
        var model = maven.workspace();
        var module = model == null ? null : model.moduleFor(target).orElse(null);
        if (module != null && !module.isTest(target)) {
            // A test-scoped dependency is invisible to main sources: changing the POM would not fix this file.
            showError("In Maven, JUnit is a test dependency: move " + target.getFileName() + " to "
                    + module.testRoots().stream().findFirst().map(module.directory()::relativize).map(Path::toString).orElse("src/test/java")
                    + ", then add JUnit 5 from there.");
            return;
        }
        rememberBuffer();
        Buffer pom = module == null ? null : buffers.get(module.directory().resolve("pom.xml"));
        String pomOverlay = pom == null ? null : pom.state().document().toString();
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                String pomText = module == null ? null : pomOverlay == null ? Files.readString(module.pom()) : pomOverlay;
                Path projectRoot = module == null ? ProjectContext.detect(target, snapshot.source(target).text()).projectRoot()
                        : module.directory();
                return FirstLaunchProjects.junitLibrary(snapshot, projectRoot, target, pomText);
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, background).whenComplete((library, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            if (!captured.equals(new NativeRefactoringHost().capture())) { showError("Source changed; add the JUnit library again."); return; }
            if (library.available()) {
                showError("JUnit Jupiter is already on this project's test build path. Move the test to a test source folder.");
                return;
            }
            note(library.maven()
                    ? "JUnit: adding org.junit.jupiter:" + library.artifact() + ":" + library.version() + " (test) to pom.xml and reloading the Maven project.\n"
                    : "JUnit: adding TurboJ's bundled JUnit Jupiter " + FirstLaunchProjects.JUNIT_VERSION + " to the project build path.\n", false);
            prepareGenerated(captured, () -> FirstLaunchProjects.junitLibraryPlan(snapshot, library, target, javaEdits));
        }));
    }

    private void createJUnitTest(dev.turboj.refactorj.api.JavaQuickActions.Inspection inspection,
            dev.turboj.refactorj.api.JavaQuickActions.Proposal proposal) {
        if (firstLaunchBusy || closing) return;
        var captured = new NativeRefactoringHost().capture();
        var model = maven.workspace();
        var module = model == null ? null : model.moduleFor(proposal.location().file()).orElse(null);
        Path projectRoot = module == null ? inspection.snapshot().root() : module.directory();
        Path inferredTestRoot = Path.of(proposal.parameters().getOrDefault("testRoot", projectRoot.resolve("src/test/java").toString()));
        if (module != null && module.testRoots().size() > 1) {
            var choice = new JComboBox<>(module.testRoots().toArray(Path[]::new));
            choice.getAccessibleContext().setAccessibleName("JUnit test source folder");
            if (module.testRoots().contains(inferredTestRoot)) choice.setSelectedItem(inferredTestRoot);
            if (EmbeddedOptionPane.showConfirmDialog(this, choice, "Test source folder",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
            inferredTestRoot = (Path) choice.getSelectedItem();
        }
        Path testRoot = module != null && module.testRoots().size() == 1 ? module.testRoots().getFirst() : inferredTestRoot;
        rememberBuffer();
        Buffer pom = buffers.get(projectRoot.resolve("pom.xml"));
        String pomOverlay = pom == null ? null : pom.state().document().toString();
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                String pomText = module == null ? null : pomOverlay == null ? Files.readString(module.pom()) : pomOverlay;
                Path actualRoot = module == null ? ProjectContext.detect(proposal.location().file(),
                        inspection.snapshot().source(proposal.location().file()).text()).projectRoot() : projectRoot;
                return FirstLaunchProjects.testContext(inspection.snapshot(), proposal.parameters(), actualRoot, testRoot, pomText);
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, background).whenComplete((context, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            if (!captured.equals(new NativeRefactoringHost().capture())) { showError("Source changed; invoke Quick Fix again."); return; }
            var spec = FirstLaunchForms.junitTest(this, context);
            if (spec != null) prepareGenerated(captured, () -> FirstLaunchProjects.testPlan(context, spec));
        }));
    }

    private void prepareGenerated(NativeWorkspaceCommitter.Capture capture,
            Callable<FirstLaunchProjects.GeneratedSource> preparation) {
        firstLaunchBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try { return preparation.call(); }
            catch (Exception error) { throw new CompletionException(error); }
        }, background).whenComplete((generated, failure) -> SwingUtilities.invokeLater(() -> {
            firstLaunchBusy = false; if (closing) return;
            if (failure != null) { showError(message(failure)); return; }
            refactoring.apply(new RefactoringController.Prepared(generated.plan(), capture)).whenComplete((applied, error) -> SwingUtilities.invokeLater(() -> {
                if (closing) return;
                if (error != null) { showError(message(error)); return; }
                if (generated.start() < 0) editor.requestFocusInWindow(); // Configuration only: the caret stays where it was.
                else navigateGeneratedSource(new dev.turboj.refactorj.api.JavaQuickActions.Prepared(generated.plan(),
                        new Model.Location(generated.file(), generated.start(), generated.end()), List.of()));
                if (generated.configurationChanged() && maven.importRequest() != null) maven.reload(false);
            }));
        }));
    }

    private void navigateGeneratedSource(dev.turboj.refactorj.api.JavaQuickActions.Prepared prepared) {
        navigateGeneratedSource(prepared, null, true);
    }

    private void navigateGeneratedSource(dev.turboj.refactorj.api.JavaQuickActions.Prepared prepared, String resumeGroup, boolean rememberOrigin) {
        if (prepared.focus() == null) { editor.requestFocusInWindow(); return; }
        var target = prepared.focus();
        Buffer buffer = buffers.get(target.file());
        if (buffer == null) { note("Generated source is unavailable; reopen " + target.file().getFileName() + ".\n", true); return; }
        if (rememberOrigin || !target.file().equals(bufferKey))
            navigateDeclaration(new Declaration(target.file(), buffer.state().document().toString(), target.start(), target.end() - target.start(), false, "Java Quick Fix"));
        editor.setSelection(target.start(), target.end());
        var groups = new LinkedHashMap<String, List<EditorComponent.Selection>>();
        for (var placeholder : prepared.placeholders()) {
            var location = placeholder.location();
            if (location.file().equals(target.file())) groups.computeIfAbsent(placeholder.group(), key -> new ArrayList<>())
                    .add(new EditorComponent.Selection(location.end(), location.start()));
        }
        if (!groups.isEmpty()) {
            var names = List.copyOf(groups.keySet());
            var prior = prepared.plan().before();
            Map<Path, Model.SourceSnapshot> projected = new LinkedHashMap<>();
            prior.sources().forEach(source -> projected.put(source.file(), source));
            for (var change : dev.turboj.refactorj.transactions.ChangeSet.prepare(prepared.plan()).transitions()) {
                if (change.after() == null) projected.remove(change.path());
                else projected.put(change.path(), change.after());
            }
            var after = new Model.WorkspaceSnapshot(prior.root(), prior.version() + 1,
                    List.copyOf(projected.values()), prior.sourceRoots(), prior.classpath(), prior.projects());
            editor.startTemplate(List.copyOf(groups.values()), index -> {
                linkedName = null;
                if (!linkedSemanticName(names.get(index))) return;
                var sources = new LinkedHashMap<Path, Model.SourceSnapshot>();
                after.sources().forEach(s -> sources.put(s.file(), s));
                new NativeRefactoringHost().capture().buffers().forEach((path, value) -> {
                    if (sources.containsKey(path)) sources.put(path,
                            new Model.SourceSnapshot(path, value.text(), value.version(), true));
                });
                var baseline = new Model.WorkspaceSnapshot(after.root(), after.version(), List.copyOf(sources.values()),
                        after.sourceRoots(), after.classpath(), after.projects());
                var range = editor.currentTemplateRanges().getFirst();
                linkedName = new LinkedName(baseline, new Model.Location(bufferKey, range.start(), range.end()),
                        editor.document(), editor.document().peekUndoTransactionId(), editor.captureLocation());
            }, (index, next) -> !linkedSemanticName(names.get(index)) || completeLinkedName(
                    next >= names.size() ? null : names.get(Math.floorMod(next, names.size()))));
            if (resumeGroup != null) editor.selectTemplateStop(names.indexOf(resumeGroup));
        }
        editor.clearInlineCompletion(); editor.requestFocusInWindow();
    }

    private static boolean linkedSemanticName(String group) { return group.equals("methodName") || group.startsWith("parameter."); }

    private boolean completeLinkedName(String resumeGroup) {
        if (linkedRenameBusy) return false;
        var entered = linkedName;
        if (entered == null || entered.document() != editor.document()) return true;
        var range = editor.currentTemplateRanges().getFirst();
        String name = editor.getText().substring(range.start(), range.end());
        String original = entered.baseline().source(bufferKey).text().substring(entered.declaration().start(), entered.declaration().end());
        if (name.equals(original)) return true;
        try { entered.document().validateCoalesceSince(entered.checkpoint(), entered.baseline().source(bufferKey).text()); }
        catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); return false; }
        var capture = new NativeRefactoringHost().capture();
        long ticket = ++linkedRenameRequest; linkedRenameBusy = true;
        intelligence.submitSemantic(intelligenceSnapshot(), (service, context) -> service.sharedRefactorJ(context).quickActions()
                .renameLinked(entered.baseline(), entered.declaration(), name, () -> closing || ticket != linkedRenameRequest))
                .whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
                    if (closing || ticket != linkedRenameRequest) { linkedRenameBusy = false; return; }
                    if (failure != null) { linkedRenameBusy = false; showError(message(failure)); return; }
                    if (!capture.equals(new NativeRefactoringHost().capture()) || editor.document() != entered.document()
                            || !bufferKey.equals(entered.declaration().file()) || !editor.templateActive()) {
                        linkedRenameBusy = false; showError("Source changed; invoke Rename again."); return;
                    }
                    if (!prepared.plan().valid()) {
                        linkedRenameBusy = false; showError(prepared.plan().diagnostics().stream().map(Model.Diagnostic::message)
                                .collect(java.util.stream.Collectors.joining("\n"))); return;
                    }
                    linkedRenameUndo = new LinkedRenameUndo(prepared.plan().id(), entered);
                    refactoring.apply(new RefactoringController.Prepared(prepared.plan(), capture))
                            .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                                linkedRenameBusy = false; linkedRenameUndo = null; linkedName = null;
                                if (closing) return;
                                if (error != null) { showError(message(error)); return; }
                                if (ticket != linkedRenameRequest || editor.document() != entered.document()
                                        || !bufferKey.equals(entered.declaration().file()) || !editor.isFocusOwner()) return;
                                editor.finishTemplate();
                                navigateGeneratedSource(resumeGroup == null
                                        ? new dev.turboj.refactorj.api.JavaQuickActions.Prepared(prepared.plan(), prepared.focus(), List.of())
                                        : prepared, resumeGroup, false);
                            }));
                }));
        return false;
    }

    public CompilationResult currentResult() {
        return currentResult;
    }

    public boolean isRunning() {
        return running;
    }

    /** The project part of the status line: Maven model state, or the open plain Java project. */
    public String projectStatus() { return projectStatusText; }

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
            outputCommandGesture.reset();
            if (quickFix != null && !Set.of("refactor.context-actions", "cut", "copy", "paste", "selectAll").contains(id))
                quickFix.dismiss(false);
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
            case "console.commandLine" -> new String[]{"shell command", "curl", "vim command line", "Esc :", "! command"};
            default -> new String[]{id.replace('.', ' ')};
        };
    }

    private static String[] commandContexts(String id) {
        if (id.equals("refactor.context-actions")) return new String[0];
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
            flash(result.message(), StatusBar.Level.NORMAL);
    }

    private CommandContext commandContext() {
        Set<String> tags = new HashSet<> ();
        tags.add("workspace");
        if (OverlayHost.active(this)) tags.add("overlay");
        if (editor.hasInputComposition()) tags.add("input-method");
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (quickFix != null && quickFix.ownsFocus(focus)) tags.add("quick-fix-popup");
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
        if (editor.inlineCompletion() != null && !editor.templateActive())
            tags.add("completion");
        if (intelligence != null && intelligence.alternativesVisible() && !editor.templateActive()) {
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
        if (id.equals("refactor.context-actions") && !context.has("editor") && !context.has("quick-fix-popup"))
            return Availability.disabled("Focus Java source or its Quick Fix popup");
        if (id.startsWith("explorer.") && !context.has("explorer")) return Availability.disabled("Focus the Project Explorer");
        if (Set.of("view.focusExplorer", "view.toggleExplorer").contains(id) && !maven.hasProject())
            return Availability.disabled("Open a project first");
        if (Set.of("navigation.mavenModule", "navigation.projectDependency").contains(id)
                && maven.importRequest() == null) return Availability.disabled("Open a Maven project first");
        if (Set.of("view.focusTestResults", "view.toggleTestPanel", "navigation.testFailure").contains(id)
                && (tests == null || tests.lastReport() == null)) return Availability.disabled("Run tests first");
        if (id.equals("navigation.testFailure") && tests.lastReport().failed() == 0) return Availability.disabled("No failed tests");
        if (id.equals("console.copy") && console.inputComponent().getSelectionStart() == console.inputComponent().getSelectionEnd())
            return Availability.disabled("Select console output first");
        if (id.equals("console.cancelCommand") && !outputCommands.isRunning())
            return Availability.disabled("No output command is running");
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
            boolean contextPopup = id.equals("refactor.context-actions") && context.has("quick-fix-popup");
            if ((!context.has("editor") && !contextPopup) || !context.has("editable"))
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
        if (id.startsWith("console.")) return "Console";
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
        if (id.startsWith("keymap.") || id.startsWith("help.") || Set.of("keymap", "palette", "help", "metrics", "menu", "escape").contains(id))
            return "Settings";
        return "Edit";
    }

    private void createCommands() {
        command("workspace.select", "Choose Workspace…", this::chooseWorkspace);
        command("file.newJavaProject", "New Java Project…", () -> createJavaProject(false));
        command("file.newMavenProject", "New Maven Project…", () -> createJavaProject(true));
        command("file.newJavaClass", "New Java Class…", this::createMainClass);
        command("new", "New…", () -> openMenu("File", "New"));
        command("java.generate", "Generate…", () -> openMenu("Code", "Generate"));
        command(
                "file.newFile",
                "Java File",
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
                    // A file opens in the editor; a project folder (pom.xml, .turboj, src…) opens as the project.
                    c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                    c.setOpenableDirectory(folder -> ProjectContext.isProjectFolder(folder.toPath()));
                    c.setDialogTitle("Open — a file, or a project folder");
                    Path start = file != null ? file.getParent() : workspaceDirectory();
                    if (start != null && Files.isDirectory(start)) c.setCurrentDirectory(start.toFile());
                    if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                        openPath(c.getSelectedFile().toPath());
                });
        command("save", "Save", () -> save(false, () -> {}));
        command("openFolder", "Open Folder…", maven::chooseProject);
        command("project.open", "Open Project…", maven::chooseProject);
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
        for (var style : dev.turboj.formatting.StandardFormatters.values()) {
            command("format.style." + style.id(), style.title(), () -> selectFormatter(style));
            commands.get("format.style." + style.id()).putValue(Action.SHORT_DESCRIPTION, style.description());
        }
        markFormatter();
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
        command("indent", "Indent / Next Template Placeholder", () -> { if (!editor.advanceTemplate(false)) editor.indent(false); });
        command("unindent", "Unindent / Previous Template Placeholder", () -> { if (!editor.advanceTemplate(true)) editor.indent(true); });
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
        command("replace", "Find and Replace…",
                () -> new dev.turboj.ui.FindReplaceDialog(this, editor, search, query -> search = query).setVisible(true));
        command(
                "goto",
                "Go to line…",
                () -> {
                    String s = EmbeddedOptionPane.showInputDialog(this, "Line number:");
                    try {
                        if (s != null) { int line = Integer.parseInt(s); markJump(visit()); editor.gotoLine(line); }
                    } catch (NumberFormatException e) {
                        showError("Enter a line number.");
                    }
                });
        command("compile", "Fast Compile", () -> compile(false, false));
        command("run", "Run Java / JUnit", () -> runContext(false));
        command("smart.action", "Complete, Import or Run", this::smartAction);
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
                    flash("Program stopped.", StatusBar.Level.WARNING);
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
        command("help.cheatSheet", "Print Keyboard Cheat Sheet…", this::printCheatSheet);
        command("keymap", "Keymap…", this::showKeymapEditor);
        for (var profile : BundledKeymaps.profiles())
            command("keymap.profile." + profile.id(), "Keyboard Profile: " + profile.name(),
                    () -> commandKeymaps.select(profile.id()));
        command("menu", "Open menu", () -> menus.getMenu(0).doClick());
        command("escape", "Return to editor / dismiss", () -> {
            boolean armCommand = editor.isFocusOwner() && !editor.hasInputComposition()
                    && !editor.templateActive() && editor.caretCount() == 1;
            if (console.isCommandLineVisible()) {
                outputCommands.dismiss();
                refreshWorkspaceMinimums();
            }
            ++linkedRenameRequest;
            if (quickFix != null) quickFix.dismiss(true);
            editor.finishTemplate();
            MenuSelectionManager.defaultManager().clearSelectedPath();
            boolean completion = editor.inlineCompletion() != null || (intelligence != null && intelligence.alternativesVisible());
            if (aiCompletion != null)
                aiCompletion.dismiss();
            if (intelligence != null)
                intelligence.dismiss();
            if (!completion) editor.removeSecondaryCarets();
            editor.requestFocusInWindow();
            if (armCommand && !completion) outputCommandGesture.arm();
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
            if (operation == Operation.CREATE_CLASS) continue; // Contextual target inference owns this operation.
            String id = operation == Operation.ORGANIZE_IMPORTS ? "organizeImports" : "refactor."
                    + operation.id();
            command(id, RefactoringController.title(operation) + "…", () -> refactoring
                    .invoke(operation));
        }
        command("refactor.this", "Refactor This…", () -> refactoring.choose(false));
        command("refactor.context-actions", "Quick Fix / Quick Assist…", () -> quickFix.invoke());
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
        registerCommand("editor.inspectLineIntelligence", "Inspect Line Intelligence…", "Testing Intelligence",
                () -> editor.inspectLineIntelligence(null), context -> Availability.available());
        registerCommand("cherry.run", "Cherry Test: Run Configured Suites", "Testing Intelligence",
                this::runCherry, context -> lineIntelligence != null && lineIntelligence.project() != null
                        ? Availability.available() : Availability.disabled("No Cherry Test configuration detected"));
        registerCommand("cherry.cancel", "Cherry Test: Cancel Execution", "Testing Intelligence",
                () -> tests.cherry().cancel(), context -> tests != null && tests.cherry().busy()
                        ? Availability.available() : Availability.disabled("Cherry Test is not running"));
        registerCommand("cherry.results", "Cherry Test: Native Results…", "Testing Intelligence",
                this::showCherryResults, context -> lineIntelligence != null && lineIntelligence.run() != null
                        ? Availability.available() : Availability.disabled("No Cherry Test execution has been loaded"));
        registerCommand("cherry.report", "Cherry Test: Open Full Report", "Testing Intelligence",
                this::openCherryReport, context -> lineIntelligence != null && lineIntelligence.run() != null
                        && lineIntelligence.run().htmlReport() != null ? Availability.available()
                        : Availability.disabled("No HTML report supplied by Cherry Test"));
        registerCommand("cherry.configure", "Cherry Test: Configure CLI…", "Testing Intelligence", () -> {
            String value = EmbeddedOptionPane.showInputDialog(this,
                    "Cherry Test Java CLI JAR or executable path (blank uses CHERRY_TEST_JAR / cherry-test):", settings.cherryTestExecutable);
            if (value == null) return;
            settings.cherryTestExecutable = value.strip();
            String preferences = settings.snapshotJson();
            background.execute(() -> { try { Settings.saveSnapshot(preferences); } catch (java.io.IOException error) { errorLater(error); } });
        }, context -> Availability.available());
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
        log.setClearBeforeEachAction(settings.clearOutputBeforeRun);
        command("run.clearOutputBefore", "Clear Output Before Each Run", () -> {
            settings.clearOutputBeforeRun = !settings.clearOutputBeforeRun;
            log.setClearBeforeEachAction(settings.clearOutputBeforeRun);
            commands.get("run.clearOutputBefore").putValue(Action.SELECTED_KEY, settings.clearOutputBeforeRun);
            String snapshot = settings.snapshotJson();
            background.execute(() -> { try { Settings.saveSnapshot(snapshot); } catch (java.io.IOException failure) { errorLater(failure); } });
        });
        commands.get("run.clearOutputBefore").putValue(Action.SELECTED_KEY, settings.clearOutputBeforeRun);
        KeyStrokeSpec.useSymbols(settings.shortcutSymbols);
        command("settings.shortcutSymbols", "Shortcuts as ⌃⌥⇧⌘ Symbols", () -> {
            settings.shortcutSymbols = !settings.shortcutSymbols;
            KeyStrokeSpec.useSymbols(settings.shortcutSymbols);
            commands.get("settings.shortcutSymbols").putValue(Action.SELECTED_KEY, settings.shortcutSymbols);
            refreshShortcutPresentation();
            refreshStatusLine();
            if (getJMenuBar() != null) getJMenuBar().repaint();
            String snapshot = settings.snapshotJson();
            background.execute(() -> {
                try { Settings.saveSnapshot(snapshot); }
                catch (java.io.IOException failure) { errorLater(failure); }
            });
        });
        commands.get("settings.shortcutSymbols").putValue(Action.SELECTED_KEY, settings.shortcutSymbols);
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
        // Go to Class/Symbol search the whole project without a Java editor; see searchProject.
        var navigation = Map.ofEntries(
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
        command("console.commandLine", "Output Command Line (:! command)", () -> {
            showRepl(false);
            outputCommands.open();
            refreshWorkspaceMinimums();
        });
        command("console.cancelCommand", "Stop Output Command", outputCommands::cancel);
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
        command("navigation.goToFile", "Go to File…", () -> searchProject(ProjectSearchDialog.Tab.FILES));
        command("navigation.goToType", "Go to Class…", () -> searchProject(ProjectSearchDialog.Tab.CLASSES));
        command("navigation.goToSymbol", "Go to Symbol…", () -> searchProject(ProjectSearchDialog.Tab.METHODS));
        command("navigation.searchEverywhere", "Search Everywhere…", () -> searchProject(ProjectSearchDialog.Tab.ALL));
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
        if (values.isEmpty()) { note(error ? "No current errors.\n" : "No current warnings.\n", false); return; }
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
        if (paths.isEmpty()) { flash("No matching files.", StatusBar.Level.NORMAL); return; }
        Object selected = EmbeddedOptionPane.showInputDialog(this, "Choose file:", title, JOptionPane.PLAIN_MESSAGE,
                null, paths.toArray(), paths.getFirst());
        if (selected instanceof Path path) { rememberLocation(); openPath(path); }
    }

    private void chooseRecentLocation() {
        var locations = recentLocations.stream().toList();
        if (locations.isEmpty()) { flash("No navigation locations yet.", StatusBar.Level.NORMAL); return; }
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
        if (buffer == null) { flash("That source buffer is closed. Reopen the file first.", StatusBar.Level.NORMAL); return; }
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
        saveAllBuffers(dirty, index, original, () -> {});
    }

    private void saveAllBuffers(List<Path> dirty, int index, dev.turboj.editor.document.PieceTable original, Runnable finished) {
        if (index == dirty.size()) {
            rememberBuffer();
            buffers.entrySet().stream().filter(entry -> entry.getValue().state().document() == original).findFirst()
                    .ifPresent(entry -> activate(entry.getKey(), entry.getValue()));
            finished.run();
            return;
        }
        Path key = dirty.get(index);
        if (!buffers.containsKey(key)) { saveAllBuffers(dirty, index + 1, original, finished); return; }
        if (!key.equals(bufferKey)) { rememberBuffer(); activate(key, buffers.get(key)); }
        save(false, () -> saveAllBuffers(dirty, index + 1, original, finished));
    }

    private final ProjectSearch projectSearch = new ProjectSearch();

    /** One keyboard search over the project's classes, files, methods and fields, opened on the given category. */
    private void searchProject(ProjectSearchDialog.Tab tab) {
        if (closing) return;
        rememberBuffer();
        Map<Path, String> overlays = new HashMap<>();
        buffers.forEach((path, buffer) -> { if (!buffer.state().readOnly()) overlays.put(path, buffer.state().document().toString()); });
        var model = maven.workspace();
        Path active = file, known = model != null ? model.root()
                : createdProject != null && (active == null || active.startsWith(createdProject.root())) ? createdProject.root() : null;
        String text = editor.getText();
        var root = known != null ? CompletableFuture.completedFuture(known) : CompletableFuture.supplyAsync(() -> {
            // A loose file belongs to its detected project; with nothing open, the workspace is the project set.
            try { return active == null ? workspaceDirectory() : ProjectContext.detect(active, text).projectRoot(); }
            catch (java.io.IOException failure) { throw new CompletionException(failure); }
        }, background);
        var index = root.thenApplyAsync(path -> {
            try { return projectSearch.scan(path, overlays); }
            catch (java.io.IOException failure) { throw new CompletionException(failure); }
        }, background);
        String name = known != null ? known.getFileName().toString() : active == null ? "workspace" : "project";
        new ProjectSearchDialog(this, tab, name, active, index, item -> {
            rememberLocation();
            if (item.kind() == ProjectSearch.Kind.FILE) openPath(item.file()); else openAt(item.file(), item.offset());
        }).setVisible(true);
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
                    if (failure != null) flash("Test/source navigation: " + message(failure), StatusBar.Level.ERROR);
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

    /** A sample with a chain, a lambda and a record: where 80, 100 and 120 columns and 2 or 4 spaces differ. */
    private static final String FORMATTER_PREVIEW = """
            public class Orders {
            record Order(String customer, int quantity, double price) {}
            double total(java.util.List<Order> orders, String customer) {
            return orders.stream().filter(order -> order.customer().equals(customer)).mapToDouble(order -> order.quantity() * order.price()).sum();
            }
            }
            """;

    private void showGeneralSettings() {
        JSpinner tabs = new JSpinner(new SpinnerNumberModel(settings.tabWidth, 1, 16, 1));
        JCheckBox pairs = new JCheckBox("Automatically close brackets and quotes", settings.autoClose);
        var styles = java.util.Arrays.stream(dev.turboj.formatting.StandardFormatters.values())
                .filter(dev.turboj.formatting.StandardFormatters::offered).toArray(dev.turboj.formatting.StandardFormatters[]::new);
        var formatter = new JComboBox<>(styles);
        formatter.setName("settings-formatter");
        formatter.setSelectedItem(dev.turboj.formatting.StandardFormatters.of(settings.formatter));
        formatter.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                var style = (dev.turboj.formatting.StandardFormatters) value;
                return super.getListCellRendererComponent(list, style == null ? "" : style.title()
                        + (style == dev.turboj.formatting.StandardFormatters.DEFAULT ? " (default)" : ""), index, selected, focus);
            }
        });
        JLabel description = new JLabel(" ");
        JTextArea preview = new JTextArea(8, 72);
        preview.setName("settings-formatter-preview");
        preview.setEditable(false);
        preview.setFont(editor.getFont());
        long[] previewRequest = {0};
        Runnable refreshPreview = () -> {
            var style = (dev.turboj.formatting.StandardFormatters) formatter.getSelectedItem();
            if (style == null) return;
            description.setText(style.description());
            long ticket = ++previewRequest[0];
            // The engine formats the sample off the EDT, as Format Document does.
            CompletableFuture.supplyAsync(() -> style.engine().format(new dev.javafmt.core.FormatRequest(FORMATTER_PREVIEW, 1,
                    dev.javafmt.core.LineEndingPolicy.PRESERVE), null, "25"), background)
                    .whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        if (ticket != previewRequest[0]) return;
                        preview.setText(failure != null ? message(failure) : result.success() ? result.formattedText()
                                : result.diagnostics().getFirst().message());
                        preview.setCaretPosition(0);
                    }));
        };
        formatter.addActionListener(event -> refreshPreview.run());
        refreshPreview.run();
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JLabel label = new JLabel("Tab width:"); label.setLabelFor(tabs);
        JLabel formatterLabel = new JLabel("Java formatter:"); formatterLabel.setLabelFor(formatter);
        for (JComponent row : new JComponent[]{label, tabs, pairs, Box.createVerticalStrut(8) instanceof JComponent strut ? strut : new JPanel(),
                formatterLabel, formatter, description, new JScrollPane(preview)}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(row);
        }
        int originalTabs = settings.tabWidth;
        if (EmbeddedOptionPane.showConfirmDialog(this, form, "General Settings", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            var chosen = (dev.turboj.formatting.StandardFormatters) formatter.getSelectedItem();
            if (chosen != null && !chosen.id().equals(settings.formatter)) selectFormatter(chosen);
            // The formatter sets the indent; a width the user typed here wins.
            if ((int) tabs.getValue() != originalTabs) settings.tabWidth = (int) tabs.getValue();
            settings.autoClose = pairs.isSelected();
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

    /** Opens a menu bar path from the keyboard, as F10 and the arrows would: ⌘N shows File ▸ New. */
    private void openMenu(String... titles) {
        var path = new ArrayList<MenuElement>(List.of(menus));
        MenuElement parent = menus;
        for (String title : titles) {
            JMenu found = null;
            for (MenuElement child : parent instanceof JMenu menu ? menu.getPopupMenu().getSubElements() : parent.getSubElements())
                if (child instanceof JMenu menu && title.equals(menu.getText())) found = menu;
            if (found == null) return;
            path.add(found);
            path.add(found.getPopupMenu());
            parent = found;
        }
        JPopupMenu last = (JPopupMenu) path.getLast();
        for (MenuElement item : last.getSubElements())
            if (item instanceof JMenuItem first && first.isEnabled()) { path.add(first); break; }
        ((JMenu) path.get(1)).doClick(0);
        MenuSelectionManager.defaultManager().setSelectedPath(path.toArray(MenuElement[]::new));
    }

    private void createMenus() {
        var profiles = BundledKeymaps.profiles();
        var layout = MenuLayout.menus(List.of(Theme.BUNDLED), profiles.stream().map(KeymapProfile::id).toList(),
                profiles.stream().map(KeymapProfile::name).toList());
        var groups = new HashMap<String, ButtonGroup>();
        groups.put(MenuLayout.PROFILES, keyboardProfileGroup);
        for (var menu : layout) {
            var bar = new JMenu(menu.title());
            bar.getAccessibleContext().setAccessibleName(menu.accessibleName());
            if (menu.mnemonic() != '\0') bar.setMnemonic(menu.mnemonic());
            addMenuItems(bar, menu.items(), groups);
            menus.add(bar);
        }
    }

    private void addMenuItems(JMenu parent, List<MenuLayout.Item> items, Map<String, ButtonGroup> groups) {
        for (var item : items) {
            switch (item) {
                case MenuLayout.Separator ignored -> parent.addSeparator();
                case MenuLayout.Submenu submenu -> {
                    var child = new JMenu(submenu.title());
                    addMenuItems(child, submenu.items(), groups);
                    parent.add(child);
                }
                case MenuLayout.Command command -> {
                    Action action = commands.get(command.id());
                    if (action == null) throw new IllegalStateException("Menu command is not registered: " + command.id());
                    // Menus show the shortcut beside the item: no tooltip repeats it (or shows an empty box).
                    JMenuItem menuItem = switch (command.kind()) {
                        case PLAIN -> new JMenuItem(action) { @Override public String getToolTipText(MouseEvent event) { return null; } };
                        case CHECK -> new JCheckBoxMenuItem(action) { @Override public String getToolTipText(MouseEvent event) { return null; } };
                        case RADIO -> {
                            var radio = new JRadioButtonMenuItem(action) { @Override public String getToolTipText(MouseEvent event) { return null; } };
                            groups.computeIfAbsent(command.group(), group -> new ButtonGroup()).add(radio);
                            yield radio;
                        }
                    };
                    if (command.text() != null) menuItem.setText(command.text());
                    parent.add(menuItem);
                }
            }
        }
    }

    private void initializeKeymap() {
        try {
            commandKeymaps.select(BundledKeymaps.current(settings.keymapProfile));
        } catch (IllegalArgumentException missing) {
            commandKeymaps.select(BundledKeymaps.DEFAULT);
        }
        if (settings.commandKeymapJson != null) {
            try {
                KeymapJson.read(commandKeymaps, settings.commandKeymapJson);
            } catch (IllegalArgumentException invalid) {
                flash("Keymap preferences: " + invalid.getMessage(), StatusBar.Level.NORMAL);
            }
        } else if (!keymap.isEmpty()) {
            // Preserve old effective bindings. New user edits require explicit conflict resolution.
            commandKeymaps.select(BundledKeymaps.DEFAULT);
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
                    flash("Ignored invalid legacy shortcut for " + id, StatusBar.Level.NORMAL);
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
                this::commandContext, this::flash, (id, sequence) -> {
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
                                        note(
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
        if (quickFix != null) quickFix.setTheme(t);
        if (maven != null) maven.setTheme(t);
        MenuStyler.style(menus, t);
        statusBar.setColors(t.color("chrome", "statusBackground"), t.color("chrome", "statusForeground"),
                t.color("diagnostics", "success"), t.color("diagnostics", "warning"), t.color("diagnostics", "error"));
        for (String id : Theme.BUNDLED) {
            Theme preset = Theme.bundled(id);
            commands.get("theme." + id)
                    .putValue(
                            Action.SELECTED_KEY,
                            id.equals(t.id()) && preset.colors().equals(t.colors()));
        }
        statusBar.setFont(AsciiChrome.font());
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
        int chromeHeight = menus.getPreferredSize().height + statusBar.getPreferredSize().height;
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

    /** The caret segment (or the selection's size) and the file segment: line ending and encoding. */
    private void updatePositionLabel() {
        int selected = editor.selectionEnd() - editor.selectionStart();
        String caret;
        if (selected == 0) caret = "Ln " + editor.caretLine() + ", Col " + editor.caretColumn();
        else {
            String text = editor.getSelectedText();
            long lines = text.chars().filter(c -> c == '\n').count() + 1;
            caret = (lines > 1 ? lines + " lines, " : "") + selected + " chars";
        }
        statusBar.set(StatusBar.Segment.CARET, caret, StatusBar.Level.NORMAL);
        String name = file == null ? "Untitled.java" : file.getFileName().toString();
        statusBar.setTooltip(StatusBar.Segment.CARET, (file == null ? name : file.toString()) + (editor.version() != savedVersion ? " · modified" : ""));
        var encoding = file == null ? null : fileEncodings.get(file);
        var document = editor.document();
        // The first line's ending stands for the file's; no full-text copy on every caret move.
        int firstEnd = document.lineCount() > 1 ? document.lineEnd(0) : -1;
        String separator = firstEnd >= 0 && firstEnd < document.length() && document.charAt(firstEnd) == '\r' ? "CRLF" : "LF";
        statusBar.set(StatusBar.Segment.FILE, separator + " " + (encoding == null ? "UTF-8" : encoding.charset().name()), StatusBar.Level.NORMAL);
    }

    private void changed() {
        if (refactoringPublishing)
            return;
        if (lineIntelligence != null) lineIntelligence.changed();
        if (keyboardJava != null) keyboardJava.changed();
        if (quickFix != null) quickFix.changed();
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
        var plainSnapshot = intelligenceSnapshot();
        Path plainFile = file;
        pendingAnalysis =
                background.schedule(
                        () -> {
                            if (generation.get() != snap.version() || operation.get() != analysisOp)
                                return;
                            compilePlain(plainSnapshot, plainFile).whenComplete((compiled, error) ->
                                    SwingUtilities.invokeLater(() -> {
                                        if (closing || operation.get() != analysisOp || editor.version() != snap.version()) return;
                                        if (error != null) { flash("Project compilation: " + message(error), StatusBar.Level.ERROR); return; }
                                        currentPlainClasspath = compiled.classpath(); accept(compiled.result(), false);
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
        // Background checks only update the status line and the editor's marks; an explicit compile writes the log.
        if (explicit && runOpen()) {
            String name = file == null ? "Untitled.java" : file.toString();
            Path root = projectRoot();
            for (var d : result.diagnostics())
                runLog.problem(d.kind().equals("ERROR") ? dev.turboj.console.OutputLog.Level.ERROR
                        : d.kind().contains("WARNING") ? dev.turboj.console.OutputLog.Level.WARN : dev.turboj.console.OutputLog.Level.INFO,
                        name, d.line(), d.column(), d.message(), root);
            if (result.success()) runLog.info((result.cacheHit() ? "Up to date" : "Compiled") + " in " + dev.turboj.console.OutputLog.duration(result.durationNanos()));
            else {
                if (!MissingJUnit.locations(result.diagnostics(), editor.getText()).isEmpty())
                    runLog.error("JUnit is not on this project's build path: " + shortcutText("refactor.context-actions", "Cmd+1")
                            + " on @Test adds JUnit 5.");
                runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, errors + (errors == 1 ? " error" : " errors"), null);
            }
        }
    }

    /**
     * One key for the common next step: accept the shown completion; else import what the caret's line misses
     * (a type, a static member such as assertEquals, the JUnit library); else complete the name being typed; else Run.
     */
    private void smartAction() {
        if (closing) return;
        if (intelligence.alternativesVisible() || editor.inlineCompletion() != null) { completionKey("TAB"); return; }
        if (!isJavaBuffer() || quickFix == null) { runContext(false); return; }
        int caret = editor.caretOffset();
        char before = caret > 0 ? editor.getText().charAt(caret - 1) : ' ';
        boolean typing = Character.isJavaIdentifierPart(before) || before == '.' || before == '@';
        quickFix.importOrElse(() -> { if (typing) intelligence.complete(true); else runContext(false); });
    }

    private String shortcutText(String command, String fallback) {
        var bindings = commandKeymaps.commandBindings(commandRegistry, command);
        return bindings.isEmpty() ? fallback : bindings.getFirst().sequence().display();
    }

    /** Tests that cannot compile only for lack of JUnit get the fix offered, as Eclipse's JUnit setup does. */
    private void offerMissingJUnit(List<dev.turboj.compiler.CompilerDiagnostic> diagnostics) {
        if (closing || file == null || !isJavaBuffer()) return;
        var candidates = MissingJUnit.locations(diagnostics, editor.getText());
        if (candidates.isEmpty()) return;
        var snapshot = intelligenceSnapshot();
        long version = editor.version();
        record Fix(dev.turboj.refactorj.api.JavaQuickActions.Inspection inspection, dev.turboj.refactorj.api.JavaQuickActions.Proposal proposal) {}
        intelligence.submitSemantic(snapshot, (service, context) -> {
            var actions = service.sharedRefactorJ(context).quickActions();
            for (int offset : candidates) {
                var inspection = actions.inspect(new Model.Location(snapshot.file(), offset), () -> closing);
                for (var proposal : inspection.proposals())
                    if (proposal.action() == dev.turboj.refactorj.api.JavaQuickActions.Action.ADD_JUNIT_LIBRARY) return new Fix(inspection, proposal);
            }
            return (Fix) null;
        }).whenComplete((fix, failure) -> SwingUtilities.invokeLater(() -> {
            if (closing || failure != null || fix == null || editor.version() != version) return;
            String how = maven.workspace() != null ? "add org.junit.jupiter:junit-jupiter as a test dependency in pom.xml and reload the project"
                    : "add TurboJ's bundled JUnit 5 to this project";
            if (EmbeddedOptionPane.showConfirmDialog(this, "These tests need JUnit, which is not on this project's build path.\nAdd JUnit 5? TurboJ will "
                    + how + ", then fix the imports.", "Add JUnit 5", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                junitAction(fix.inspection(), fix.proposal());
        }));
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
        if (explicit) {
            startRunLog(processors ? "Generate Sources" : run ? (isolated ? "Isolated Run" : "Run") : "Compile",
                    (activeFile == null ? "" : activeFile.getFileName().toString()) + projectSuffix());
            runLog.phase("compile");
        }
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
            if (failure != null) {
                buildStatus = "NATIVE BUILD FAILED"; refreshTitle();
                if (runOpen()) { runLog.error(message(failure)); runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, null); }
                else flash(message(failure), StatusBar.Level.ERROR);
                return;
            }
            tests.setMavenCompiler(nativeMavenCompiler());
            if (processors && value.active().success()) { generatedSourceRevision++; intelligence.changed(); }
            // Other files' problems first; accept() adds the current file's and closes a failed action.
            if (explicit && runOpen()) for (var diagnostic : value.workspace().diagnostics())
                if (!diagnostic.fileName().equals(activeName))
                    runLog.problem(diagnostic.diagnostic().isError() ? dev.turboj.console.OutputLog.Level.ERROR : dev.turboj.console.OutputLog.Level.WARN,
                            diagnostic.fileName(), diagnostic.diagnostic().line(), diagnostic.diagnostic().column(),
                            diagnostic.diagnostic().message(), model.root());
            accept(value.active(), explicit);
            buildStatus = "TURBOJ NATIVE · " + (value.active().success() ? "BUILD OK" : "BUILD FAILED") + " | " + ms(value.workspace().durationNanos()) + " ms";
            refreshTitle();
            if (explicit && runOpen() && value.active().success() && !run) runLog.finish(dev.turboj.console.OutputLog.Outcome.SUCCESS, null, null);
            if (run && value.active().success()) {
                if (value.targets().isEmpty()) {
                    runLog.error("No runnable main class found in this Maven project. Use Run ▸ Run All Tests for unit tests.");
                    runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, null);
                    return;
                }
                MavenRunTargets.Target target = value.targets().size() == 1 ? value.targets().getFirst()
                        : (MavenRunTargets.Target) EmbeddedOptionPane.showInputDialog(this,
                                "Choose the application's main class", "Run Maven project", JOptionPane.QUESTION_MESSAGE,
                                null, value.targets().toArray(), value.targets().getFirst());
                if (target == null) { runLog.finish(dev.turboj.console.OutputLog.Outcome.STOPPED, "no main class chosen", null); return; }
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
            if (failure != null) { finishPendingSaveRun(saveRequest); if (runOpen()) { runLog.error("Unable to start Maven project: " + message(failure)); runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, null); } else flash("Unable to start Maven project: " + message(failure), StatusBar.Level.ERROR); return; }
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

    private long contextRunTicket;
    private record ContextRun(boolean test, boolean main) {}
    private record LastTestLaunch(Path root, Path file, String moduleId, TurboTestController.Selection selection) {}
    private LastTestLaunch lastTestLaunch;

    private void runContext(boolean saved) {
        if (!isJavaBuffer()) { compile(true, false); return; }
        if (!saved) {
            var dirty = dirtyBufferKeys();
            if (!dirty.isEmpty()) {
                int answer = EmbeddedOptionPane.showConfirmDialog(this, "Save all modified sources before Run?",
                        "Save before Run", JOptionPane.YES_NO_CANCEL_OPTION);
                if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return;
                if (answer == JOptionPane.YES_OPTION) {
                    saveAllBuffers(new ArrayList<>(dirty), 0, editor.document(), () -> runContext(true)); return;
                }
            }
        }
        var capture = new NativeRefactoringHost().capture(); var snapshot = intelligenceSnapshot();
        var runDocument = editor.document();
        var previousTest = lastTestLaunch;
        var runModel = maven.workspace();
        boolean repeatCandidate = "eclipse-macos".equals(commandKeymaps.selectedId()) && previousTest != null
                && snapshot.file().startsWith(previousTest.root())
                && (runModel == null ? previousTest.moduleId().isEmpty()
                        : runModel.moduleFor(snapshot.file()).map(MavenModels.Module::id).orElse("").equals(previousTest.moduleId()));
        int caret = editor.caretOffset(); long ticket = ++contextRunTicket;
        intelligence.submitSemantic(snapshot, (service, context) -> {
            boolean test = service.isTestClass(context, caret);
            return new ContextRun(test, !test && (!repeatCandidate || service.hasMainMethod(context)));
        }).whenComplete((target, error) -> SwingUtilities.invokeLater(() -> {
                    if (closing || ticket != contextRunTicket || editor.document() != runDocument || !bufferKey.equals(snapshot.file())
                            || !capture.equals(new NativeRefactoringHost().capture())) return;
                    if (maven.workspace() != runModel) { flash("Project changed; invoke Run again.", StatusBar.Level.NORMAL); return; }
                    if (error != null) { showError("Cannot resolve Run target: " + message(error)); return; }
                    if (lineIntelligence != null && lineIntelligence.project() != null) {
                        runCherry();
                        if (target.test()) return;
                    }
                    if (target.test()) startTests(TurboTestController.Mode.CLASS, false);
                    else if (repeatCandidate && !target.main()) startTests(previousTest.selection().mode(), false, false, previousTest);
                    else compile(true, false);
                }));
    }

    private record PlainCompilation(CompilationResult result, List<Path> classpath) {}

    private CompletableFuture<PlainCompilation> compilePlain(IntelligenceController.Snapshot snapshot, Path actualFile) {
        return CompletableFuture.supplyAsync(() -> {
            if (actualFile == null) return null;
            try {
                var config = ProjectContext.detect(actualFile, snapshot.source().toString());
                // Loose files retain their existing single-file behavior. A declared project compiles coherently.
                if (!Files.isRegularFile(config.projectRoot().resolve(".turboj/project.properties"))
                        && !Files.isRegularFile(config.projectRoot().resolve(FirstLaunchProjects.JUNIT_MARKER))) return null;
                return new PlainCompilation(plainCompiler.compile(actualFile, snapshot.source(), snapshot.openSources(), config).active(), config.classPath());
            } catch (Exception failure) { throw new CompletionException(failure); }
        }, nativeProjectCompiler).thenCompose(compiled -> {
            if (compiled != null) return CompletableFuture.completedFuture(compiled);
            String text = snapshot.source().toString(); long version = snapshot.source().version();
            var source = actualFile == null ? SourceSnapshot.forBuffer(text, version)
                    : new SourceSnapshot(actualFile.getFileName().toString(), text, version);
            return compiler.compileNow(source).thenApply(result -> new PlainCompilation(result, List.of()));
        });
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
        String subject = file == null ? "Untitled.java" : file.getFileName().toString();
        startRunLog(run ? (isolated ? "Isolated Run" : "Run") : "Compile", subject + projectSuffix());
        runLog.phase("compile");
        buildStatus = "CHECKING…";
        refreshTitle();
        var prepared = compilePlain(intelligenceSnapshot(), file);
        var future = prepared.thenApply(PlainCompilation::result);
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
                                                        instanceof CancellationException)) {
                                            runLog.error(message(error));
                                            runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, null);
                                            showError(message(error));
                                        }
                                        return;
                                    }
                                    if (result.version() != editor.version()
                                            || op != operation.get()) {
                                        runLog.warn("Source changed; press Run again for the current buffer.");
                                        runLog.finish(dev.turboj.console.OutputLog.Outcome.STOPPED, null, null);
                                        return;
                                    }
                                    currentPlainClasspath = prepared.join().classpath();
                                    accept(result, true);
                                    if (!run && result.success()) runLog.finish(dev.turboj.console.OutputLog.Outcome.SUCCESS, null, null);
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
        startRun(result, isolated, request, currentPlainClasspath);
    }

    private void startRun(CompilationResult result, boolean isolated, long request, List<Path> classpath) {
        startRun(result, isolated, request, classpath, true);
    }

    /** {@code explicit}: the user asked for this run, so program input takes the focus; a run started by Save never moves the caret. */
    private void startRun(CompilationResult result, boolean isolated, long request, List<Path> classpath, boolean explicit) {
        if (result.mainClass() == null || result.mainClass().isBlank()) {
            if (!runOpen()) startRunLog("Run", projectSuffix().replaceFirst("^ · ", ""));
            runLog.error("No supported main entry point found.");
            runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, null);
            return;
        }
        running = true;
        programOutput = true;
        if (!runOpen()) startRunLog(isolated ? "Isolated Run" : "Run", result.mainClass() + projectSuffix());
        // The phase line is written before program input opens, so typed input is never split by it.
        runLog.phase("run " + result.mainClass() + ".main" + (isolated ? " (isolated)" : ""));
        console.setProgramRunning(true);
        refreshStatusLine();
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
                                runLog.program(event.text(), event.stderr());
                        },
                        waiting ->
                                SwingUtilities.invokeLater(
                                        () -> {
                                            if (closing || request != runRequest || !running)
                                                return;
                                            console.setWaitingForInput(waiting);
                                            if (waiting && !initialInput.isEmpty() && initialInputSent.compareAndSet(false, true)) {
                                                try { programWorker.sendInput(initialInput.endsWith("\n") ? initialInput : initialInput + "\n"); }
                                                catch (IllegalStateException finished) { flash("Initial input was not sent because the program already stopped.", StatusBar.Level.WARNING); }
                                            }
                                            if (waiting) {
                                                if (replVisible) showRepl(false);
                                                setMessagesVisible(true);
                                                if (explicit && !console.commandLine().isFocusOwner()) console.focusProgramInput();
                                            }
                                        }));
        execution.whenComplete(
                (r, error) ->
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (closing || request != runRequest) return;
                                    running = false;
                                    boolean returnToEditor = console.hasConsoleFocus()
                                            && !console.commandLine().isFocusOwner();
                                    console.setProgramRunning(false);
                                    if (error != null) {
                                        runLog.error("Worker: " + message(error));
                                        runLog.finish(dev.turboj.console.OutputLog.Outcome.FAILURE, null, System.nanoTime() - start);
                                    } else
                                        runLog.finish(r.exitCode() == 0 ? dev.turboj.console.OutputLog.Outcome.SUCCESS
                                                        : dev.turboj.console.OutputLog.Outcome.FAILURE,
                                                "exit " + r.exitCode() + (r.detail() == null || r.detail().isBlank()
                                                        || r.detail().equals("Completed") ? "" : " · " + r.detail()),
                                                r.durationNanos());
                                    refreshStatusLine();
                                    if (returnToEditor) editor.requestFocusInWindow();
                                }));
        if (explicit) console.focusProgramInput();
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

    /** Opens asynchronously, then places the caret once the file is the active buffer. */
    private void openAt(Path path, int offset) {
        Path destination = path.toAbsolutePath().normalize();
        if (destination.equals(bufferKey)) markJump(visit());
        openPath(destination);
        javax.swing.Timer follow = new javax.swing.Timer(25, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        follow.addActionListener(event -> {
            if (destination.equals(file)) { editor.gotoOffset(offset); editor.requestFocusInWindow(); follow.stop(); }
            else if (System.nanoTime() > deadline) follow.stop();
        });
        follow.start();
    }

    public void openPath(Path path) {
        Path destination = path.toAbsolutePath().normalize();
        if (destination.getFileName().toString().equals("pom.xml") && !externalSourceFiles.contains(destination)) maven.open(destination);
        if (destination.equals(bufferKey)) return;
        Visit previous = visit();
        Buffer cached = buffers.get(destination);
        if (cached != null) {
            rememberBuffer();
            markJump(previous);
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
                        if (Files.isDirectory(p)) {
                            boolean mavenProject = Files.isRegularFile(p.resolve("pom.xml"));
                            SwingUtilities.invokeLater(() -> { if (mavenProject) maven.open(p); else openPlainProject(p); });
                            return;
                        }
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
                                    markJump(previous);
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
                            note(
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
                            flash("Saved " + target.getFileName(), StatusBar.Level.NORMAL);
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
        outputCommandGesture.reset();
        outputCommands.close();
        repl.close();
        compiler.close();
        Thread.ofPlatform().daemon().name("turboj-plain-compiler-close").start(plainCompiler::close);
        maven.close();
        if (keyboardJava != null) keyboardJava.dispose();
        if (quickFix != null) quickFix.close();
        intelligence.close();
        aiCompletion.close();
        formatting.close();
        if (refactoring != null)
            refactoring.close();
        if (tests != null)
            tests.close();
        if (lineIntelligence != null) lineIntelligence.close();
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
            flash("Save automation paused: resolve dependencies and trust this Maven project through Run or Test.", StatusBar.Level.NORMAL);
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
                            flash("Auto Run on Save: compilation changed before execution.", StatusBar.Level.NORMAL); return;
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
        note(candidates == 0 ? "Auto Run on Save: this project has no runnable main.\n"
                : "Auto Run on Save: use Run once to choose a main class.\n", false);
    }

    private void startTests(TurboTestController.Mode mode, boolean automatic) {
        startTests(mode, automatic, false);
    }

    private void startTests(TurboTestController.Mode mode, boolean automatic, boolean onSave) {
        startTests(mode, automatic, onSave, null);
    }

    private void startTests(TurboTestController.Mode mode, boolean automatic, boolean onSave, LastTestLaunch repeat) {
        if (pendingTestSnapshot || closing || (!automatic && tests.busy()))
            return;
        String sourceName = file == null ? "" : file.getFileName().toString().replaceFirst("\\.java$", "");
        testSubject = switch (mode) {
            case ALL -> "all tests"; case FAILED -> "failed tests"; case COMPILE -> "project";
            case CLASS -> repeat != null ? repeat.selection().className() : sourceName;
            case METHOD -> repeat != null ? repeat.selection().className() + "#" + repeat.selection().methodName() : "test at caret in " + sourceName;
        };
        var mavenModel = maven.workspace();
        if (maven.importRequest() != null && (maven.busy() || mavenModel == null || !mavenModel.resolved())) {
            if (!automatic) note(maven.busy() ? "Wait for Maven dependencies to finish loading.\n"
                    : "Maven dependencies are incomplete. Refresh Dependencies or check Maven Settings.\n", true);
            return;
        }
        if (mavenModel != null && mode != TurboTestController.Mode.COMPILE && !maven.trusted()) {
            if (automatic || !maven.requireTrust()) return;
        }
        saveTestRun = onSave;
        var capture = new NativeRefactoringHost().capture();
        var semantic = intelligenceSnapshot();
        if (repeat != null && (mavenModel == null ? !repeat.moduleId().isEmpty()
                : repeat.moduleId().isEmpty()
                    || !mavenModel.moduleFor(semantic.file()).map(MavenModels.Module::id).orElse("").equals(repeat.moduleId())
                    || !mavenModel.moduleFor(repeat.file()).map(MavenModels.Module::id).orElse("").equals(repeat.moduleId()))) {
            flash("The previous JUnit launch belongs to another project or module; run from the test source again.", StatusBar.Level.ERROR);
            return;
        }
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
            if (repeat != null) {
                var current = engine.workspace().snapshot();
                if (!current.root().equals(repeat.root()) || current.sources().stream().noneMatch(source -> source.file().equals(repeat.file())))
                    throw new IllegalArgumentException("The previous JUnit launch is no longer in this project; run from the test source again.");
                return new TestInvocation(current, repeat.selection());
            }
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
                flash("TurboTest: " + message(failure), StatusBar.Level.NORMAL);
                return;
            }
            if (!capture.equals(new NativeRefactoringHost().capture())) {
                pendingTestSnapshot = false;
                if (onSave) runningSaveAutomation = null;
                flash("Source changed while selecting tests; run again.", StatusBar.Level.NORMAL);
                return;
            }
            if (mavenModel != maven.workspace()) { pendingTestSnapshot = false; if (onSave) runningSaveAutomation = null; return; }
            testCapture = capture;
            testWorkspace = invocation.workspace();
            if (!automatic && (mode == TurboTestController.Mode.METHOD || mode == TurboTestController.Mode.CLASS)) {
                String module = mavenModel == null ? "" : mavenModel.moduleFor(semantic.file()).map(MavenModels.Module::id).orElse("");
                lastTestLaunch = new LastTestLaunch(invocation.workspace().root(), repeat == null ? semantic.file() : repeat.file(), module, invocation.selection());
                if (repeat != null) flash("Run previous JUnit launch: " + invocation.selection().className(), StatusBar.Level.NORMAL);
            }
            if (mavenModel != null) {
                Map<Path, String> overlays = new LinkedHashMap<>();
                invocation.workspace().sources().forEach(source -> overlays.put(source.file(), source.text()));
                String moduleId = mode == TurboTestController.Mode.METHOD || mode == TurboTestController.Mode.CLASS
                        ? repeat == null ? mavenModel.moduleFor(semantic.file()).map(MavenModels.Module::id).orElse("") : repeat.moduleId() : "";
                CompletableFuture.supplyAsync(this::nativeMavenCompiler, nativeProjectCompiler).whenComplete((coordinator, error) -> SwingUtilities.invokeLater(() -> {
                    if (closing || selectionRequest != testSelectionRequest) return;
                    pendingTestSnapshot = false;
                    if (maven.workspace() != mavenModel || !capture.equals(new NativeRefactoringHost().capture())) {
                        if (onSave) runningSaveAutomation = null;
                        return;
                    }
                    if (error != null) { if (onSave) runningSaveAutomation = null; flash(message(error), StatusBar.Level.ERROR); return; }
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
            testOutput.begin(compilationOnly, testSubject + projectSuffix());
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
            if (!testOutput.status(text)) flash(text);
        }

        @Override
        public void completed(String message, TestEngine.Report result) {
            if (result == null) {
                if (testOutput.open()) testOutput.completed(message); else flash(message);
                refreshStatusLine();
            } else report(result);
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
            testOutput.compilation(result, model != null ? model.root() : projectRoot());
            afterSaveCompilation(result);
            if (!result.success() && !tests.automatic() && !saveTestRun) offerMissingJUnit(active);
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
            testOutput.program(event.text(), event.stderr());
        }
    }

    private void runCherry() {
        if (lineIntelligence == null) return;
        if (maven.workspace() != null && !maven.requireTrust()) return;
        lineIntelligence.start(settings.cherryTestExecutable, List.of());
    }

    private void cherryResult(LineIntelligence.Run result) {
        if (closing) return;
        flash(result.message(), StatusBar.Level.NORMAL);
        cherryResultsView.accept(result);
    }

    private void showCherryResults() {
        if (lineIntelligence == null || lineIntelligence.run() == null) return;
        cherryResultsView = LineIntelligenceDialogs.results(this, editor, lineIntelligence.run(),
                location -> openTestLocation(location.file(), location.line(), null),
                result -> { if (Objects.equals(result.root(), lineIntelligence.project())) runCherry();
                    else flash("The displayed Cherry run belongs to another project.", StatusBar.Level.NORMAL); },
                result -> { if (Objects.equals(result.root(), lineIntelligence.project())) tests.cherry().cancel(); },
                this::openCherryReport);
    }

    private void openCherryReport() {
        var result = lineIntelligence == null ? null : lineIntelligence.run();
        openCherryReport(result);
    }

    private void openCherryReport(LineIntelligence.Run result) {
        if (result == null || result.htmlReport() == null) return;
        Path report = result.htmlReport();
        background.execute(() -> {
            try {
                if (!Files.isRegularFile(report)) throw new java.io.IOException("Cherry report does not exist: " + report);
                Desktop.getDesktop().browse(report.toUri());
            } catch (Exception failure) { errorLater(failure); }
        });
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
            note("Source for " + test.className() + " is not attached.\n", false);
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
            if (direction == NativeWorkspaceCommitter.Direction.APPLY && linkedRenameUndo != null && linkedRenameUndo.planId().equals(plan.id())
                    && (editor.document() != linkedRenameUndo.entered().document()
                        || !bufferKey.equals(linkedRenameUndo.entered().declaration().file()) || !editor.templateActive()))
                throw new IllegalStateException("Linked edit context changed; no rename applied");
            if (direction == NativeWorkspaceCommitter.Direction.APPLY && linkedRenameUndo != null && linkedRenameUndo.planId().equals(plan.id())) {
                var entered = linkedRenameUndo.entered();
                entered.document().validateCoalesceSince(entered.checkpoint(), entered.baseline().source(entered.declaration().file()).text());
                // Reserve one undo record before caller edits can trim the typing checkpoint.
                editor.coalesceEdits(entered.checkpoint(), entered.baseline().source(entered.declaration().file()).text(), entered.location());
            }
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
            Component previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
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
                    boolean savedConfiguration = NativeWorkspaceCommitter.savesConfiguration(plan, expected.buffers().get(fileEdit.file()));
                    buffers.put(fileEdit.file(), new Buffer(buffer.file(), editor.captureState(),
                            savedConfiguration ? SafeFiles.utf8Fingerprint(editor.getText()) : buffer.fingerprint(),
                            savedConfiguration ? editor.version() : buffer.savedVersion()));
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
                // New/previously closed Java sources participate in the same workspace undo.
                // Navigation after a quick fix must not make its creation impossible to undo.
                for (var transition : changeSet.transitions()) {
                    if (transition.after() == null || buffers.containsKey(transition.path())
                            || !transition.path().getFileName().toString().endsWith(".java")) continue;
                    var source = transition.after();
                    var document = new dev.turboj.editor.document.PieceTable(source.text());
                    byte[] fingerprint = SafeFiles.utf8Fingerprint(source.text());
                    buffers.put(transition.path(), new Buffer(transition.path(),
                            new EditorComponent.EditorState(document, new EditorComponent.Location(0, 0, 0, 0), false),
                            fingerprint, document.version()));
                }
                if (linkedRenameUndo != null && linkedRenameUndo.planId().equals(plan.id())) {
                    var entered = linkedRenameUndo.entered();
                    Buffer renamed = buffers.get(entered.declaration().file());
                    if (renamed == null || renamed.state().document() != entered.document())
                        throw new IllegalStateException("Linked rename document changed");
                    editor.restoreState(renamed.state());
                    long merged = editor.coalesceEdits(entered.checkpoint(),
                            entered.baseline().source(entered.declaration().file()).text(), entered.location());
                    if (merged >= 0) transactions.put(entered.document(), merged);
                    beforeText.put(entered.declaration().file(), entered.baseline().source(entered.declaration().file()).text());
                    before.put(entered.declaration().file(), new Buffer(renamed.file(),
                            new EditorComponent.EditorState(entered.document(), entered.location(), false),
                            renamed.fingerprint(), renamed.savedVersion()));
                }
                Map<Path, Buffer> after = new LinkedHashMap<> ();

                for (var transition : changeSet.transitions()) {
                    Buffer buffer = buffers.get(transition.path());

                    if (buffer != null && !buffer.state().readOnly())
                        after.put(transition.path(), buffer);
                }
                if (linkedRenameUndo != null && linkedRenameUndo.planId().equals(plan.id())) {
                    Path renamed = linkedRenameUndo.entered().declaration().file();
                    after.put(renamed, buffers.get(renamed));
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
                if (previousFocus != null && previousFocus != editor && previousFocus.isShowing())
                    previousFocus.requestFocusInWindow();
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
                        this::flash);
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
        flash(message, StatusBar.Level.ERROR);
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
