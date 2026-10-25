package dev.turboj.project;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.config.SafeFiles;
import com.google.gson.Gson;
import dev.turboj.mavenj.api.MavenJ;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.mavenj.core.MavenImportDefaults;
import dev.turboj.mavenj.workspace.*;
import dev.turboj.ui.themes.Theme;
import dev.turboj.ui.AsciiChrome;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.tree.*;

/** Native Maven orchestration. The EDT only captures buffers and publishes immutable results. */
public final class MavenProjectController implements AutoCloseable {
    public interface Host {
        Component owner();
        Path activeFile();
        Map<Path, String> pomOverlays();
        void openFile(Path file);
        default void navigate(Path file, int offset, boolean readOnly) { throw new UnsupportedOperationException("Host does not support source navigation"); }
        void published(Workspace workspace);
        void notice(String text, boolean error);
        void previewPom(String title, java.util.function.UnaryOperator<String> edit);
        void stateChanged();
    }

    private final Host host;
    private volatile MavenJ engine;
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mavenj-project"); thread.setDaemon(true); return thread;
    });
    private final AtomicLong requests = new AtomicLong();
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JLabel summary = new JLabel(" No Maven project ");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("No project"));
    private volatile Workspace workspace;
    private volatile ImportRequest request;
    private volatile boolean busy, closed;
    private volatile MetadataWatcher watcher;
    private ScheduledFuture<?> pending;
    private volatile MavenLifecycle.Execution execution;
    private boolean lifecycleStarting;
    private volatile boolean trusted;
    private Theme theme;
    private Path mavenExecutable;
    private boolean defaultRepositoryPolicy;
    private Set<String> reportedDiagnostics = Set.of();
    private Set<Path> existingRoots = Set.of();
    private record Preferences(String userSettings, String globalSettings, String localRepository, String javaHome,
            boolean offline, Set<String> repositories, Set<String> activeProfiles, Set<String> inactiveProfiles, String mavenExecutable,
            Boolean defaultRepositoryPolicy) {}
    private record Entry(String name, Path path, boolean directory) {
        Entry(String name, Path path) { this(name, path, false); }
        @Override public String toString() { return name; }
    }

    public MavenProjectController(Host host) { this(host, null); }

    public MavenProjectController(Host host, MavenJ engine) {
        this.host = Objects.requireNonNull(host); this.engine = engine;
        panel.setPreferredSize(new Dimension(235, 400));
        panel.setMinimumSize(new Dimension(130, 100));
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(summary, BorderLayout.CENTER);
        JButton collapse = new JButton("x");
        collapse.getAccessibleContext().setAccessibleName("Collapse project explorer");
        collapse.setToolTipText("Collapse project explorer");
        collapse.addActionListener(event -> { panel.setVisible(false); host.stateChanged(); });
        footer.add(collapse, BorderLayout.EAST);
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) activateTree(); }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        tree.getActionMap().put("open", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { activateTree(); } });
        tree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            @Override public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof DefaultMutableTreeNode branch && branch.getUserObject() instanceof Entry entry
                        && branch.getChildCount() == 1 && ((DefaultMutableTreeNode) branch.getFirstChild()).getUserObject().equals("…"))
                    loadDirectory(branch, entry.path());
            }
            @Override public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {}
        });
        panel.setVisible(false);
    }

    public JPanel panel() { return panel; }
    public Workspace workspace() { return workspace; }
    public ImportRequest importRequest() { return request; }
    public boolean busy() { return busy; }
    public boolean lifecycleRunning() { return lifecycleStarting || execution != null && !execution.completion().isDone(); }
    public boolean trusted() { return trusted; }
    public void toggleExplorer() { panel.setVisible(!panel.isVisible()); host.stateChanged(); }

    /** Focus the existing tree; no separate keyboard dispatcher or duplicate model. */
    public void focusExplorer() {
        panel.setVisible(true);
        host.stateChanged();
        if (tree.getSelectionPath() == null && tree.getRowCount() > 0) tree.setSelectionRow(0);
        tree.requestFocusInWindow();
    }

    public boolean hasExplorerFocus() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus != null && SwingUtilities.isDescendingFrom(focus, panel);
    }

    public void openSelectedItem() { activateTree(); }

    public void expandSelectedItem(boolean expand) {
        var path = tree.getSelectionPath();
        if (path == null) return;
        if (expand) tree.expandPath(path); else tree.collapsePath(path);
    }

    public void focusDependencies() {
        focusExplorer();
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.toString().equals("Dependencies")) {
                var path = new javax.swing.tree.TreePath(node.getPath());
                tree.setSelectionPath(path);
                tree.expandPath(path);
                tree.scrollPathToVisible(path);
                return;
            }
        }
    }

    public void chooseProject() {
        var chooser = new EmbeddedFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Maven project (pom.xml or folder)", "xml"));
        if (chooser.showOpenDialog(host.owner()) == JFileChooser.APPROVE_OPTION) open(chooser.getSelectedFile().toPath());
    }

    /** Switch to a plain Java project without retaining a previous Maven model. */
    public void clearProject() {
        requireEdt();
        if (lifecycleRunning()) throw new IllegalStateException("Stop the Maven goal before switching projects.");
        requests.incrementAndGet();
        if (pending != null) pending.cancel(false);
        if (watcher != null) { watcher.close(); watcher = null; }
        request = null; workspace = null; busy = false; trusted = false;
        reportedDiagnostics = Set.of(); panel.setVisible(false); host.stateChanged();
    }

    public void open(Path project) {
        requireEdt();
        Path normalized = project.toAbsolutePath().normalize();
        if (workspace != null && workspace.moduleFor(normalized).isPresent()) return;
        if (request != null && (request.project().equals(normalized) || workspace != null && (workspace.root().equals(normalized) || workspace.rootPom().equals(normalized)))) return;
        trusted = false;
        reportedDiagnostics = Set.of();
        workspace = null;
        if (watcher != null) { watcher.close(); watcher = null; }
        request = MavenImportDefaults.online(normalized);
        defaultRepositoryPolicy = true;
        mavenExecutable = null;
        panel.setVisible(true);
        long opening = requests.incrementAndGet(); busy = true; summary.setText(" Loading Maven configuration… "); host.stateChanged();
        worker.execute(() -> {
            Preferences preferences = readPreferences(normalized);
            SwingUtilities.invokeLater(() -> {
                if (closed || requests.get() != opening) return;
                if (preferences != null) {
                    defaultRepositoryPolicy = Boolean.TRUE.equals(preferences.defaultRepositoryPolicy());
                    request = new ImportRequest(normalized, preferencePath(preferences.userSettings()), preferencePath(preferences.globalSettings()), preferencePath(preferences.localRepository()),
                            nonnull(preferences.activeProfiles()), nonnull(preferences.inactiveProfiles()), Map.of(), preferences.offline(), nonnull(preferences.repositories()), false,
                            preferencePath(preferences.javaHome()), Map.of());
                    mavenExecutable = preferencePath(preferences.mavenExecutable());
                }
                reload(false);
            });
        });
    }

    public void reload(boolean refreshDependencies) {
        requireEdt();
        if (request == null || closed) return;
        ImportRequest base = request;
        ImportRequest capture = new ImportRequest(base.project(), base.userSettings(), base.globalSettings(), base.localRepository(),
                base.activeProfiles(), base.inactiveProfiles(), base.userProperties(), base.offline(), base.allowedRepositoryUrls(),
                refreshDependencies, base.javaHome(), host.pomOverlays());
        boolean useUserRepositories = defaultRepositoryPolicy;
        long ticket = requests.incrementAndGet();
        busy = true;
        summary.setText(" Synchronizing… " ); host.stateChanged();
        worker.execute(() -> {
            try {
                ImportRequest effectiveRequest = useUserRepositories ? MavenImportDefaults.withUserRepositories(capture) : capture;
                Workspace imported = engine().open(effectiveRequest, () -> closed || ticket != requests.get());
                var selected = Toolchains.select(imported, Path.of(System.getProperty("user.home"), ".m2", "toolchains.xml"));
                Workspace value = selected.workspace();
                Workspace publication = value;
                Set<Path> directories = publication.modules().stream().flatMap(module -> Stream.of(module.sourceRoots(), module.testRoots(), module.generatedSourceRoots(), module.generatedTestRoots(),
                        module.resources().stream().map(ResourceRoot::directory).toList(), module.testResources().stream().map(ResourceRoot::directory).toList()).flatMap(Collection::stream))
                        .filter(Files::isDirectory).collect(java.util.stream.Collectors.toUnmodifiableSet());
                SwingUtilities.invokeLater(() -> {
                    if (closed || requests.get() != ticket) return;
                    if (useUserRepositories) request = new ImportRequest(base.project(), base.userSettings(), base.globalSettings(), base.localRepository(),
                            base.activeProfiles(), base.inactiveProfiles(), base.userProperties(), base.offline(), effectiveRequest.allowedRepositoryUrls(),
                            false, base.javaHome(), Map.of());
                    workspace = publication; busy = false; existingRoots = directories;
                    buildTree(publication);
                    host.published(publication);
                    host.notice(statusText(publication) + "\n", false);
                    var diagnostics = new LinkedHashSet<String>();
                    for (Diagnostic diagnostic : diagnostics(publication).toList()) {
                        String key = diagnostic.code() + ": " + diagnostic.message();
                        if (diagnostics.add(key) && !reportedDiagnostics.contains(key)) host.notice(key + "\n", diagnostic.severity() == Severity.ERROR);
                    }
                    reportedDiagnostics = Set.copyOf(diagnostics);
                    if (!publication.resolved()) host.notice(publication.offline()
                            ? "Dependencies are incomplete while Maven is offline. Check Offline in Project → Maven Settings and any offline option in settings.xml or .mvn/maven.config, then Refresh Dependencies.\n"
                            : "Dependencies are incomplete. Check the messages above and Project → Maven Settings (repositories, mirrors and credentials), then Refresh Dependencies.\n", true);
                    host.stateChanged();
                    worker.execute(() -> updateWatcher(publication, ticket));
                });
            } catch (CancellationException ignored) {
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != requests.get()) return;
                    busy = false; summary.setText(" Import failed — see messages ");
                    host.notice("Maven import: " + message(failure) + "\n", true); host.stateChanged();
                });
            }
        });
    }

    private synchronized void updateWatcher(Workspace value, long ticket) {
        if (closed || ticket != requests.get()) return;
        try {
            if (watcher == null) watcher = new MetadataWatcher(value.observedFiles(), Duration.ofMillis(450), () -> SwingUtilities.invokeLater(() -> reload(false)));
            else watcher.update(value.observedFiles());
            if (closed) watcher.close();
        } catch (Exception failure) { host.notice("Maven file watch: " + message(failure) + "\n", true); }
    }

    /** Called only for configuration buffers. Ordinary Java edits never resolve dependencies. */
    public void configurationChanged(Path file) {
        if (request == null || file == null || !file.getFileName().toString().equals("pom.xml")) return;
        if (pending != null) pending.cancel(false);
        pending = worker.schedule(() -> SwingUtilities.invokeLater(() -> reload(false)), 550, TimeUnit.MILLISECONDS);
    }

    public static String statusText(Workspace value) {
        String java = value.modules().stream().filter(module -> !module.packaging().equals("pom")).map(module -> module.mainJava().languageLevel())
                .filter(level -> !level.isBlank()).distinct().sorted().reduce((a,b) -> a + "/" + b).orElse("unspecified");
        return "MAVEN PROJECT — " + value.root().getFileName() + " | Modules " + value.modules().size() + " | Java " + java
                + " | Dependencies " + (value.resolved() ? "Resolved" : "Incomplete") + " | TurboJ Native"
                + (value.modules().stream().anyMatch(module -> module.fastPath() != FastPath.SUPPORTED) ? " (Maven verification required)" : "")
                + (value.offline() ? " | Offline" : "");
    }

    private static Stream<Diagnostic> diagnostics(Workspace value) {
        return Stream.concat(value.diagnostics().stream(), value.modules().stream().flatMap(module -> module.diagnostics().stream())).distinct();
    }

    private void buildTree(Workspace value) {
        if (theme != null) panel.setBorder(new AsciiBorder("Project", AsciiChrome.editor(theme), AsciiBorder.Line.SINGLE));
        summary.setText(" " + value.modules().size() + " modules · " + (value.resolved() ? "Dependencies resolved" : "Dependencies incomplete") + " ");
        summary.setToolTipText(statusText(value));
        var root = new DefaultMutableTreeNode(new Entry(value.root().getFileName().toString(), value.rootPom()));
        for (var module : value.modules()) {
            if (module.pom().equals(value.rootPom()) && module.packaging().equals("pom")) continue;
            var branch = new DefaultMutableTreeNode(new Entry(module.coordinates().artifactId() + " · Java " + module.mainJava().languageLevel(), module.pom()));
            root.add(branch);
            if (!module.packaging().equals("pom")) {
                addRoots(branch, "Sources", module.sourceRoots(), module.directory()); addRoots(branch, "Tests", module.testRoots(), module.directory());
                addRoots(branch, "Generated sources", module.generatedSourceRoots(), module.directory()); addRoots(branch, "Generated tests", module.generatedTestRoots(), module.directory());
                addRoots(branch, "Resources", module.resources().stream().map(ResourceRoot::directory).toList(), module.directory());
                addRoots(branch, "Test resources", module.testResources().stream().map(ResourceRoot::directory).toList(), module.directory());
            }
            var dependencies = new DefaultMutableTreeNode("Dependencies"); branch.add(dependencies);
            for (Dependency dependency : module.dependencies()) dependencies.add(new DefaultMutableTreeNode(dependency.coordinates().gav() + " [" + dependency.scope() + "]"));
        }
        tree.setModel(new DefaultTreeModel(root));
        tree.expandRow(0);
        if (root.getChildCount() <= 5) for (int index = 0; index < root.getChildCount(); index++)
            tree.expandPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(index)).getPath()));
    }

    private void addRoots(DefaultMutableTreeNode parent, String label, List<Path> paths, Path module) {
        if (paths.isEmpty()) return;
        var group = new DefaultMutableTreeNode(label); parent.add(group);
        for (Path path : paths) {
            boolean exists = existingRoots.contains(path);
            String display = path.startsWith(module) ? module.relativize(path).toString() : path.toString();
            var root = new DefaultMutableTreeNode(new Entry(display + (exists ? "" : " (not created)"), path, true));
            if (exists) root.add(new DefaultMutableTreeNode("…")); group.add(root);
        }
    }

    private void loadDirectory(DefaultMutableTreeNode node, Path path) {
        long ticket = requests.get();
        worker.execute(() -> {
            var children = new ArrayList<DefaultMutableTreeNode>();
            try (var files = Files.list(path)) {
                for (Path child : files.sorted(Comparator.comparing((Path entry) -> !Files.isDirectory(entry)).thenComparing(entry -> entry.getFileName().toString())).limit(10000).toList()) {
                    boolean directory = Files.isDirectory(child);
                    var entry = new DefaultMutableTreeNode(new Entry(child.getFileName().toString(), child, directory));
                    if (directory && !Files.isSymbolicLink(child)) entry.add(new DefaultMutableTreeNode("…"));
                    children.add(entry);
                }
            } catch (Exception failure) { children.add(new DefaultMutableTreeNode("Unavailable: " + message(failure))); }
            SwingUtilities.invokeLater(() -> {
                if (closed || ticket != requests.get()) return;
                node.removeAllChildren(); children.forEach(node::add);
                ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
            });
        });
    }

    private void activateTree() {
        var selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof Entry entry)) return;
        // Only concrete files are opened; roots are expanded asynchronously through the tree.
        if (!entry.directory()) host.openFile(entry.path());
        else if (node.getChildCount() > 0) tree.expandPath(new TreePath(node.getPath()));
    }

    public void showStructure() {
        Workspace value = workspace; if (value == null) return;
        StringBuilder text = new StringBuilder(statusText(value)).append("\n\n");
        for (var module : value.modules()) text.append(module.id()).append(" [").append(module.packaging()).append("]\n")
                .append("  POM: ").append(module.pom()).append("\n  Java: ").append(module.mainJava().languageLevel()).append("\n  JDK: ").append(module.mainJava().javaHome())
                .append("\n  Encoding: ").append(module.mainJava().encoding()).append("\n  Main roots: ").append(module.sourceRoots())
                .append("\n  Test roots: ").append(module.testRoots()).append("\n  Fast path: ").append(module.fastPath()).append("\n  Profiles: ").append(module.activeProfiles()).append("\n\n");
        text.append("Build order: ").append(value.buildOrder()).append("\n\n");
        diagnostics(value).forEach(diagnostic -> text.append(diagnostic.severity()).append(" ").append(diagnostic.code()).append(": ").append(diagnostic.message()).append('\n'));
        showText("Maven Project Structure", text.toString());
    }

    public void showDependencies() {
        Workspace value = workspace; if (value == null) return;
        StringBuilder text = new StringBuilder("Local repository: ").append(value.localRepository()).append("\n\n");
        for (var module : value.modules()) {
            text.append(module.id()).append('\n');
            for (Dependency dependency : module.dependencies()) text.append("  ").append(dependency.coordinates().key()).append(" [").append(dependency.scope()).append("]")
                    .append(dependency.optional() ? " optional" : "").append("\n    ").append(dependency.reactorModule().isBlank() ? dependency.file() : "Reactor: " + dependency.reactorModule())
                    .append("\n    Children: ").append(dependency.children()).append('\n');
            for (ClasspathScope scope : ClasspathScope.values()) text.append("  ").append(scope).append(": ").append(module.classpaths().forScope(scope)).append("\n  Module path: ").append(module.classpaths().modulesForScope(scope)).append('\n');
            text.append('\n');
        }
        showText("Maven Dependencies", text.toString());
    }

    public void showProfiles() {
        if (request == null) return;
        String value = EmbeddedOptionPane.showInputDialog(host.owner(), "Active profiles (comma separated; prefix ! to deactivate):", String.join(",", request.activeProfiles()));
        if (value == null) return;
        Set<String> active = new LinkedHashSet<>(), inactive = new LinkedHashSet<>();
        for (String part : value.split("[,\\s]+")) if (!part.isBlank()) { if (part.startsWith("!")) inactive.add(part.substring(1)); else active.add(part); }
        request = new ImportRequest(request.project(), request.userSettings(), request.globalSettings(), request.localRepository(), active, inactive,
                request.userProperties(), request.offline(), request.allowedRepositoryUrls(), false, request.javaHome(), Map.of());
        savePreferences();
        reload(false);
    }

    public void showSettings() {
        if (request == null) return;
        var form = new JPanel(new GridLayout(0, 2, 8, 8));
        JTextField user = field(form, "User settings.xml", request.userSettings());
        JTextField global = field(form, "Global settings.xml", request.globalSettings());
        JTextField local = field(form, "Local repository", request.localRepository());
        JTextField home = field(form, "Project JDK home (optional)", request.javaHome());
        JTextField executable = field(form, "Maven executable (wrapper takes precedence)", mavenExecutable);
        JCheckBox offline = new JCheckBox("Offline", request.offline()); form.add(offline); form.add(new JLabel("Uses Maven's local repository"));
        JTextField repositories = field(form, "Allowed repository URLs (comma separated)", String.join(",", request.allowedRepositoryUrls()));
        form.add(new JLabel("Repository permission does not trust project code.")); form.add(new JLabel("No credentials are stored here."));
        if (EmbeddedOptionPane.showConfirmDialog(host.owner(), form, "Maven Settings", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            request = new ImportRequest(request.project(), path(user), path(global), path(local), request.activeProfiles(), request.inactiveProfiles(), request.userProperties(),
                    offline.isSelected(), repositoryPermissions(repositories.getText()), false, path(home), Map.of());
            defaultRepositoryPolicy = false;
            mavenExecutable = path(executable); savePreferences();
            reload(false);
        } catch (RuntimeException error) { host.notice("Maven settings: " + message(error) + "\n", true); }
    }

    public boolean requireTrust() {
        if (trusted) return true;
        if (request == null) return false;
        int choice = EmbeddedOptionPane.showConfirmDialog(host.owner(), "Trust this project for this session?\n" + request.project()
                + "\n\nRunning Java, tests, Maven plugins, wrappers or annotation processors executes project-controlled code.\nPassive import and dependency inspection do not need this permission.",
                "Project Trust", JOptionPane.OK_CANCEL_OPTION);
        trusted = choice == JOptionPane.OK_OPTION;
        host.stateChanged(); return trusted;
    }

    public void runLifecycle(String goal) {
        if (request == null || lifecycleRunning()) return;
        String actual = goal;
        if (actual == null) actual = EmbeddedOptionPane.showInputDialog(host.owner(), "Maven goals (space separated):", "verify");
        if (actual == null || actual.isBlank() || !requireTrust()) return;
        List<String> goals = List.of(actual.trim().split("\\s+"));
        Workspace value = workspace;
        Path root = value == null ? (request.project().getFileName().toString().equals("pom.xml") ? request.project().getParent() : request.project()) : value.root();
        Path javaHome = request.javaHome();
        if (javaHome == null && value != null) javaHome = value.modules().stream().map(module -> module.mainJava().javaHome()).filter(Objects::nonNull).findFirst().orElse(null);
        var lifecycle = new MavenLifecycle.Request(root, goals, List.copyOf(request.activeProfiles()), javaHome, mavenExecutable, true, request.offline(), Duration.ofMinutes(30), request.userProperties(),
                request.userSettings(), request.globalSettings(), request.localRepository(), List.copyOf(request.inactiveProfiles()));
        lifecycleStarting = true; host.stateChanged();
        worker.execute(() -> {
            try {
                var description = MavenLifecycle.inspect(lifecycle);
                SwingUtilities.invokeLater(() -> {
                    if (closed) { lifecycleStarting = false; return; }
                    String details = "Command: " + String.join(" ", description.command()) + "\nDirectory: " + description.workingDirectory()
                            + "\nMaven: " + description.distribution() + "\nJDK: " + description.javaHome() + "\nProfiles: " + description.profiles();
                    for (Diagnostic diagnostic : description.diagnostics()) host.notice(diagnostic.code() + ": " + diagnostic.message() + "\n", diagnostic.severity() == Severity.ERROR);
                    if (EmbeddedOptionPane.showConfirmDialog(host.owner(), details, "Run Maven Build", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                        lifecycleStarting = false; host.stateChanged(); return;
                    }
                    host.notice("MAVEN BUILD\n" + details + "\n", false);
                    worker.execute(() -> {
                        try {
                            if (closed) return;
                            var running = MavenLifecycle.start(lifecycle, text -> host.notice(text, false));
                            execution = running;
                            if (closed) { running.cancel(); return; }
                            SwingUtilities.invokeLater(() -> { if (closed) { running.cancel(); return; } lifecycleStarting = false; host.stateChanged(); });
                            running.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                                if (closed) return;
                                host.notice(failure == null ? "Maven exit " + result.exitCode() + " · " + result.elapsed().toMillis() + " ms"
                                        + (result.cancelled() ? " · cancelled" : "") + (result.timedOut() ? " · timeout" : "") + "\n" : "Maven failed: " + message(failure) + "\n", failure != null || result != null && !result.success());
                                if (execution == running) execution = null;
                                host.stateChanged(); reload(false);
                            }));
                        } catch (Exception error) { SwingUtilities.invokeLater(() -> { lifecycleStarting = false; host.stateChanged(); }); host.notice("Maven build: " + message(error) + "\n", true); }
                    });
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { lifecycleStarting = false; host.stateChanged(); }); host.notice("Maven command: " + message(error) + "\n", true); }
        });
    }

    public void cancelLifecycle() { if (execution != null) execution.cancel(); }

    public void showEffectivePom() {
        Workspace value = workspace; if (value == null) return;
        var module = value.moduleFor(host.activeFile() == null ? value.rootPom() : host.activeFile()).orElse(value.modules().getFirst());
        showText("Effective POM — " + module.coordinates().artifactId(), module.effectivePom());
    }

    public void openParentPom() {
        Workspace value = workspace; if (value == null) return;
        var module = value.moduleFor(host.activeFile() == null ? value.rootPom() : host.activeFile()).orElse(value.modules().getFirst());
        var parent = value.module(module.parentId());
        if (parent.isPresent()) { host.navigate(parent.get().pom(), 0, false); return; }
        worker.execute(() -> {
            Path found = externalPom(value, module.parentId());
            SwingUtilities.invokeLater(() -> {
                if (closed || workspace != value) return;
                if (found != null) host.navigate(found, 0, true);
                else host.notice(module.parentId().isBlank() ? "This POM has no parent.\n" : "Parent POM is not in the resolved local repository: " + module.parentId() + "\n", false);
            });
        });
    }

    private static Path externalPom(Workspace workspace, String gav) {
        String[] parts = gav.split(":", -1); if (parts.length != 3 || workspace.localRepository() == null) return null;
        Path directory = workspace.localRepository().resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(parts[2]).normalize();
        if (!directory.startsWith(workspace.localRepository())) return null;
        Path direct = directory.resolve(parts[1] + "-" + parts[2] + ".pom");
        if (Files.isRegularFile(direct)) return direct;
        return workspace.observedFiles().stream().filter(path -> path.getParent().equals(directory) && path.getFileName().toString().endsWith(".pom") && Files.isRegularFile(path)).sorted().findFirst().orElse(null);
    }

    /** Resolves POM navigation using validated XML ranges and the effective Maven model. */
    public void navigatePom(Path pom, String xml, int offset) {
        Workspace value = workspace; if (value == null) return;
        try {
            var elements = PomEdits.elements(xml);
            int propertyStart = xml.lastIndexOf("${", Math.min(offset, xml.length()));
            int propertyEnd = propertyStart < 0 ? -1 : xml.indexOf('}', propertyStart);
            if (propertyStart >= 0 && propertyEnd >= offset) {
                String name = xml.substring(propertyStart + 2, propertyEnd);
                var declaration = elements.stream().filter(element -> element.path().size() >= 2 && element.path().get(element.path().size() - 2).equals("properties")
                        && element.path().getLast().equals(name)).findFirst();
                if (declaration.isPresent()) { navigate(value, pom, declaration.get().contentStart(), false); return; }
                var module = value.moduleFor(pom);
                if (module.isPresent()) {
                    var parent = value.module(module.get().parentId());
                    Path parentPom = parent.map(dev.turboj.mavenj.api.MavenModels.Module::pom).orElseGet(() -> externalPom(value, module.get().parentId()));
                    if (parentPom != null) {
                        String parentXml = SafeFiles.readXmlSnapshot(parentPom).text();
                        var inherited = PomEdits.elements(parentXml).stream().filter(element -> element.path().size() >= 2 && element.path().get(element.path().size() - 2).equals("properties")
                                && element.path().getLast().equals(name)).findFirst();
                        if (inherited.isPresent()) { navigate(value, parentPom, inherited.get().contentStart(), parent.isEmpty()); return; }
                    }
                }
                host.notice("Property ${" + name + "} is inherited, supplied by settings/profiles, or has no writable source declaration in this POM.\n", false); return;
            }
            var selected = elements.stream().filter(element -> element.start() <= offset && element.end() >= offset)
                    .min(Comparator.comparingInt(element -> element.end() - element.start()));
            if (selected.isPresent() && selected.get().path().getLast().equals("module")) {
                String configured = selected.get().text();
                Path target = pom.getParent().resolve(configured).normalize();
                if (!target.getFileName().toString().equals("pom.xml")) target = target.resolve("pom.xml");
                Path modulePom = target;
                if (value.modules().stream().anyMatch(module -> module.pom().equals(modulePom))) { navigate(value, modulePom, 0, false); return; }
                host.notice("Module has not resolved: " + target + "\n", true); return;
            }
            var dependency = elements.stream().filter(element -> element.path().getLast().equals("dependency") && element.start() <= offset && element.end() >= offset).findFirst();
            if (dependency.isPresent()) {
                var fields = new HashMap<String, String>();
                elements.stream().filter(element -> element.start() > dependency.get().start() && element.end() < dependency.get().end() && element.path().size() == dependency.get().path().size() + 1)
                        .forEach(element -> fields.put(element.path().getLast(), element.text()));
                var module = value.moduleFor(pom);
                if (module.isPresent()) {
                    String group = interpolate(fields.getOrDefault("groupId", ""), module.get().properties());
                    String artifact = interpolate(fields.getOrDefault("artifactId", ""), module.get().properties());
                    var resolved = module.get().dependencies().stream().filter(candidate -> candidate.coordinates().groupId().equals(group) && candidate.coordinates().artifactId().equals(artifact)).findFirst();
                    if (resolved.isPresent()) {
                        var reactor = value.module(resolved.get().reactorModule());
                        Path target = reactor.map(dev.turboj.mavenj.api.MavenModels.Module::pom).orElseGet(() -> externalPom(value, resolved.get().coordinates().gav()));
                        if (target != null) { navigate(value, target, 0, reactor.isEmpty()); return; }
                    }
                }
                host.notice("Dependency has no resolved POM attachment in the current model.\n", false); return;
            }
            if (selected.isPresent() && selected.get().path().contains("parent")) { SwingUtilities.invokeLater(this::openParentPom); return; }
            host.notice("Place the caret in a property reference, module path, parent or dependency declaration.\n", false);
        } catch (Exception failure) { host.notice("POM navigation: " + message(failure) + "\n", true); }
    }

    private void navigate(Workspace expected, Path file, int offset, boolean readOnly) {
        SwingUtilities.invokeLater(() -> { if (!closed && workspace == expected) host.navigate(file, offset, readOnly); });
    }
    private static String interpolate(String source, Map<String, String> properties) {
        String result = source;
        for (var property : properties.entrySet()) result = result.replace("${" + property.getKey() + "}", property.getValue());
        return result;
    }

    public void downloadAttachment(String classifier) {
        Workspace value = workspace; if (value == null) return;
        var module = value.moduleFor(host.activeFile() == null ? value.rootPom() : host.activeFile()).orElse(value.modules().getFirst());
        List<Dependency> candidates = module.dependencies().stream().filter(dependency -> dependency.reactorModule().isBlank()).toList();
        if (candidates.isEmpty()) { host.notice("No external dependencies in this module.\n", false); return; }
        Object choice = EmbeddedOptionPane.showInputDialog(host.owner(), "Dependency " + classifier + " attachment:", "Dependency Attachment", JOptionPane.PLAIN_MESSAGE,
                null, candidates.stream().map(dependency -> dependency.coordinates().key()).toArray(), candidates.getFirst().coordinates().key());
        if (choice == null) return;
        Dependency dependency = candidates.stream().filter(candidate -> candidate.coordinates().key().equals(choice.toString())).findFirst().orElseThrow();
        ImportRequest options = request; long ticket = requests.get();
        worker.execute(() -> {
            try {
                Attachment result = engine().attachment(options, module.id(), dependency.coordinates(), classifier, () -> closed || ticket != requests.get());
                SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != requests.get()) return;
                    if (result.file() != null) host.notice("Attached " + result.file() + "\n", false);
                    result.diagnostics().forEach(diagnostic -> host.notice(diagnostic.message() + "\n", diagnostic.severity() == Severity.ERROR));
                    reload(false);
                });
            } catch (Exception error) { host.notice("Dependency attachment: " + message(error) + "\n", true); }
        });
    }

    public void editPom(String operation) {
        if (workspace == null) return;
        if (host.activeFile() == null || !host.activeFile().getFileName().toString().equals("pom.xml")) {
            host.openFile(workspace.rootPom());
            host.notice("POM opened. Invoke " + operation + " again to edit its current buffer.\n", false); return;
        }
        if (operation.equals("Add Module")) {
            String module = EmbeddedOptionPane.showInputDialog(host.owner(), "Module path relative to this POM:");
            if (module != null && !module.isBlank()) host.previewPom(operation, xml -> PomEdits.addModule(xml, module).result());
            return;
        }
        var form = new JPanel(new GridLayout(0, 2, 8, 8));
        JTextField group = field(form, "Group ID", ""); JTextField artifact = field(form, "Artifact ID", "");
        JTextField version = operation.equals("Remove Dependency") ? null : field(form, "Version (property references preserved)", "");
        JTextField scope = operation.equals("Add Dependency") ? field(form, "Scope", "compile") : null;
        if (EmbeddedOptionPane.showConfirmDialog(host.owner(), form, operation, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        host.previewPom(operation, xml -> switch (operation) {
            case "Add Dependency" -> PomEdits.addDependency(xml, group.getText().trim(), artifact.getText().trim(), version.getText().trim(), scope.getText().trim()).result();
            case "Update Dependency Version" -> PomEdits.updateDependencyVersion(xml, group.getText().trim(), artifact.getText().trim(), version.getText().trim()).result();
            case "Remove Dependency" -> PomEdits.removeDependency(xml, group.getText().trim(), artifact.getText().trim()).result();
            default -> throw new IllegalArgumentException("Unknown POM action: " + operation);
        });
    }

    public void findFile(boolean modulesOnly) {
        Workspace value = workspace; if (value == null) return;
        String query = EmbeddedOptionPane.showInputDialog(host.owner(), modulesOnly ? "Find module:" : "Find project file:");
        if (query == null) return;
        String match = query.toLowerCase(Locale.ROOT);
        worker.execute(() -> {
            List<Path> results;
            try {
                if (modulesOnly) results = value.modules().stream().filter(module -> module.id().toLowerCase(Locale.ROOT).contains(match)).map(dev.turboj.mavenj.api.MavenModels.Module::pom).toList();
                else try (var files = Files.walk(value.root(), 30)) {
                    results = files.limit(50000).filter(Files::isRegularFile).filter(path -> !path.toString().contains("/.git/") && !path.toString().contains("/target/"))
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(match)).limit(500).sorted().toList();
                }
                SwingUtilities.invokeLater(() -> {
                    if (closed || workspace != value) return;
                    Object selected = EmbeddedOptionPane.showInputDialog(host.owner(), results.isEmpty() ? "No matches" : "Choose file:", "Project Search", JOptionPane.PLAIN_MESSAGE,
                            null, results.toArray(), results.isEmpty() ? null : results.getFirst());
                    if (selected instanceof Path path) host.openFile(path);
                });
            } catch (Exception error) { host.notice("Project search: " + message(error) + "\n", true); }
        });
    }

    public void setTheme(Theme value) {
        theme = value;
        AsciiPalette palette = AsciiChrome.editor(value);
        AsciiStyle.apply(panel, palette);
        panel.setBorder(new AsciiBorder("Project", palette, AsciiBorder.Line.SINGLE));
        tree.setFont(palette.font());
        tree.setRowHeight(tree.getFontMetrics(tree.getFont()).getHeight());
        Insets edge = panel.getInsets();
        var layout = (BorderLayout) panel.getLayout();
        var scroll = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
        int footerHeight = layout.getLayoutComponent(BorderLayout.SOUTH).getPreferredSize().height;
        panel.setMinimumSize(new Dimension(130, edge.top + edge.bottom + footerHeight
                + tree.getRowHeight() + scroll.getHorizontalScrollBar().getPreferredSize().height));
        if (tree.getCellRenderer() instanceof DefaultTreeCellRenderer renderer) {
            renderer.setLeafIcon(null); renderer.setOpenIcon(null); renderer.setClosedIcon(null);
            renderer.setBackgroundNonSelectionColor(palette.background()); renderer.setTextNonSelectionColor(palette.foreground());
            renderer.setBackgroundSelectionColor(palette.selectionBackground()); renderer.setTextSelectionColor(palette.selectionForeground());
            renderer.setBorderSelectionColor(palette.selectionBackground());
        }
    }

    private void showText(String title, String text) {
        var dialog = new EmbeddedDialog(host.owner(), title, false);
        var area = new JTextArea(text); area.setEditable(false); area.setCaretPosition(0); area.setFont(AsciiChrome.font());
        dialog.setContentPane(new JScrollPane(area)); dialog.setSize(760, 500); dialog.setLocationRelativeTo(host.owner()); dialog.setVisible(true);
    }

    private static JTextField field(JPanel panel, String label, Object value) {
        panel.add(new JLabel(label)); var input = new JTextField(value == null ? "" : value.toString(), 28); panel.add(input); return input;
    }
    private static Path path(JTextField value) { return value.getText().isBlank() ? null : Path.of(value.getText().trim()).toAbsolutePath().normalize(); }
    private static Set<String> split(String value) { return new LinkedHashSet<>(Arrays.stream(value.split("[,\\s]+")).filter(text -> !text.isBlank()).toList()); }
    private static Set<String> repositoryPermissions(String text) {
        Set<String> values = split(text);
        for (String value : values) {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getScheme() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Use repository base URLs without embedded credentials or query tokens; configure credentials in Maven settings.xml");
        }
        return values;
    }
    private static String message(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()); }
    private static void requireEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture project state on EDT"); }

    private synchronized MavenJ engine() {
        if (closed) throw new CancellationException();
        if (engine == null) engine = new DefaultMavenJ();
        if (closed) { engine.close(); throw new CancellationException(); }
        return engine;
    }

    private static Path preferencesFile(Path project) {
        Path root = project.getFileName().toString().equals("pom.xml") ? project.getParent() : project;
        try {
            String key = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return Settings.directory().resolve("maven-projects").resolve(key + ".json");
        } catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
    private Preferences readPreferences(Path project) {
        try { Path file = preferencesFile(project); return Files.isRegularFile(file) && Files.size(file) < 65536 ? new Gson().fromJson(Files.readString(file), Preferences.class) : null; }
        catch (Exception failure) { host.notice("Maven preferences ignored: " + message(failure) + "\n", true); return null; }
    }
    private void savePreferences() {
        ImportRequest value = request; Path executable = mavenExecutable;
        var preferences = new Preferences(string(value.userSettings()), string(value.globalSettings()), string(value.localRepository()), string(value.javaHome()),
                value.offline(), value.allowedRepositoryUrls(), value.activeProfiles(), value.inactiveProfiles(), string(executable), defaultRepositoryPolicy);
        worker.execute(() -> {
            try { Path file = preferencesFile(value.project()); Files.createDirectories(file.getParent()); SafeFiles.atomicWrite(file, new Gson().toJson(preferences)); }
            catch (Exception failure) { host.notice("Maven preferences: " + message(failure) + "\n", true); }
        });
    }
    private static String string(Path path) { return path == null ? "" : path.toString(); }
    private static Path preferencePath(String value) { return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize(); }
    private static Set<String> nonnull(Set<String> values) { return values == null ? Set.of() : values; }

    @Override public void close() {
        closed = true; requests.incrementAndGet();
        if (pending != null) pending.cancel(false);
        if (watcher != null) watcher.close();
        if (execution != null) execution.cancel();
        worker.shutdownNow();
        MavenJ current = engine;
        if (current != null) Thread.ofPlatform().daemon().name("mavenj-close").start(current::close);
    }
}
