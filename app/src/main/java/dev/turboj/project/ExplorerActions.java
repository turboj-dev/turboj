package dev.turboj.project;

import dev.turboj.commandj.swing.EmbeddedOptionPane;
import dev.turboj.refactorj.api.Model;
import dev.turboj.refactorj.api.Requests;
import dev.turboj.refactoring.NativeWorkspaceCommitter;
import dev.turboj.refactoring.RefactoringController;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * The explorer's context menu: New (class, package, folder, file), Rename, Move, Delete, Copy Path and Reveal in Finder,
 * for files, folders, packages and source roots. Java compilation units and packages go through RefactorJ, so
 * references, package declarations and imports follow; Delete of Java code is Safe Delete and refuses code still in
 * use. Other files and folders move or are deleted in one workspace transaction, which Undo takes back; binary content
 * cannot be journaled, so it is moved directly or sent to the Trash, and the confirmation says so.
 */
public final class ExplorerActions {
    /** What the actions need from the IDE. Every method runs on the EDT. */
    public interface Host {
        Component owner();
        List<Path> javaRoots();
        Path projectRoot();
        /** The text a file has now: its open buffer, or the disk. */
        String text(Path file) throws IOException;
        /** Files with unsaved changes. */
        List<Path> dirtyFiles();
        CompletableFuture<RefactoringController.Prepared> prepare(Requests.Request request);
        CompletableFuture<NativeWorkspaceCommitter.Result> apply(RefactoringController.Prepared prepared);
        NativeWorkspaceCommitter.Capture capture();
        /** References to {@code declarations} that lie outside {@code inside}; empty when nothing outside uses them. */
        CompletableFuture<List<Model.Location>> referencesOutside(List<Model.Location> declarations, Path inside);
        void newClass(Path sourceRoot, String packageName);
        void open(Path file);
        void refresh(Path select);
        void message(String text, boolean error);
        void styleMenu(JPopupMenu menu);
    }

    private final Host host;
    private boolean busy;

    public ExplorerActions(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    public ProjectFiles.Node node(Path path, boolean directory) {
        return ProjectFiles.classify(path, directory, host.javaRoots());
    }

    /** The context menu of an item, at {@code x, y} of {@code invoker}. */
    public void showMenu(Path path, boolean directory, Component invoker, int x, int y) {
        var node = node(path, directory);
        var menu = new JPopupMenu();
        menu.setName("explorer-menu");
        var create = new JMenu("New");
        if (node.sourceRoot() != null) create.add(item("Java Class…", () -> newClass(node)));
        if (node.sourceRoot() != null) create.add(item("Package…", () -> newPackage(node)));
        create.add(item("Folder…", () -> newFolder(node)));
        create.add(item("File…", () -> newFile(node)));
        menu.add(create);
        menu.addSeparator();
        boolean root = node.kind() == ProjectFiles.Kind.SOURCE_ROOT || node.path().equals(host.projectRoot());
        if (!root) {
            menu.add(item("Rename…", () -> rename(node)));
            menu.add(item("Move…", () -> move(node)));
            menu.add(item(node.kind() == ProjectFiles.Kind.JAVA_FILE || node.kind() == ProjectFiles.Kind.PACKAGE ? "Safe Delete…" : "Delete…",
                    () -> delete(node)));
            menu.addSeparator();
        }
        menu.add(item("Copy Path", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(node.path().toString()), null);
            host.message("Copied " + node.path(), false);
        }));
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR))
            menu.add(item("Reveal in Finder", () -> Desktop.getDesktop().browseFileDirectory(node.path().toFile())));
        host.styleMenu(menu);
        menu.show(invoker, x, y);
    }

    private static JMenuItem item(String title, Runnable action) {
        var item = new JMenuItem(title);
        item.addActionListener(event -> action.run());
        return item;
    }

    // ── New ───────────────────────────────────────────────────────────────────────────────────────────

    public void newClass(ProjectFiles.Node node) {
        if (node.sourceRoot() == null) { host.message("Java classes go in a source folder", true); return; }
        host.newClass(node.sourceRoot(), node.packageName());
    }

    public void newPackage(ProjectFiles.Node node) {
        if (node.sourceRoot() == null) { host.message("Packages go in a source folder", true); return; }
        String base = node.packageName();
        String name = ask("New package in " + (base.isEmpty() ? node.sourceRoot().getFileName() : base) + ":",
                base.isEmpty() ? "" : base + ".", ProjectFiles::packageProblem);
        if (name != null) newPackage(node, name);
    }

    public void newPackage(ProjectFiles.Node node, String name) {
        if (node.sourceRoot() == null || ProjectFiles.packageProblem(name) != null) { host.message("Not a package name: " + name, true); return; }
        Path folder = ProjectFiles.packageFolder(node.sourceRoot(), name);
        if (Files.exists(folder)) { host.message("Package " + name + " already exists", true); return; }
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            host.message("Cannot create package " + name + ": " + failure.getMessage(), true);
            return;
        }
        host.refresh(folder);
        host.message("Created package " + name, false);
    }

    public void newFolder(ProjectFiles.Node node) {
        String name = ask("New folder in " + node.folder().getFileName() + ":", "", ProjectFiles::fileNameProblem);
        if (name != null) newFolder(node, name);
    }

    public void newFolder(ProjectFiles.Node node, String name) {
        if (ProjectFiles.fileNameProblem(name) != null) { host.message(ProjectFiles.fileNameProblem(name), true); return; }
        Path folder = node.folder().resolve(name);
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) { host.message(name + " already exists", true); return; }
        try {
            Files.createDirectory(folder);
        } catch (IOException failure) {
            host.message("Cannot create " + name + ": " + failure.getMessage(), true);
            return;
        }
        host.refresh(folder);
        host.message("Created folder " + name, false);
    }

    public void newFile(ProjectFiles.Node node) {
        String name = ask("New file in " + node.folder().getFileName() + ":", "", ProjectFiles::fileNameProblem);
        if (name != null) newFile(node, name);
    }

    public void newFile(ProjectFiles.Node node, String name) {
        if (ProjectFiles.fileNameProblem(name) != null) { host.message(ProjectFiles.fileNameProblem(name), true); return; }
        Path file = node.folder().resolve(name);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { host.message(name + " already exists", true); return; }
        try {
            Files.createFile(file);
        } catch (IOException failure) {
            host.message("Cannot create " + name + ": " + failure.getMessage(), true);
            return;
        }
        host.refresh(file);
        host.open(file);
    }

    // ── Rename ────────────────────────────────────────────────────────────────────────────────────────

    public void rename(ProjectFiles.Node node) {
        if (!ready(node)) return;
        boolean javaType = node.kind() == ProjectFiles.Kind.JAVA_FILE && primaryType(node.path()) != null;
        String old = javaType ? ProjectFiles.baseName(node.path()) : node.kind() == ProjectFiles.Kind.PACKAGE ? node.packageName()
                : node.path().getFileName().toString();
        String prompt = javaType ? "Rename class " + old + " and its file to:" : node.kind() == ProjectFiles.Kind.PACKAGE
                ? "Rename package " + old + " and its subpackages to:" : "Rename " + old + " to:";
        String name = ask(prompt, old, javaType ? ProjectFiles::typeProblem
                : node.kind() == ProjectFiles.Kind.PACKAGE ? ProjectFiles::packageProblem : ProjectFiles::fileNameProblem);
        if (name != null && !name.equals(old)) rename(node, name, true);
    }

    /**
     * Renames to {@code name}: a class name for a Java file (the class, its file and every reference), a full package
     * name for a package, a file or folder name otherwise.
     */
    public void rename(ProjectFiles.Node node, String name, boolean confirmed) {
        if (!ready(node)) return;
        switch (node.kind()) {
            case JAVA_FILE -> {
                var type = primaryType(node.path());
                if (type == null) { plainMove(node, node.path().resolveSibling(name), "Renamed " + node.path().getFileName() + " to " + name, confirmed); return; }
                if (ProjectFiles.typeProblem(name) != null) { host.message(ProjectFiles.typeProblem(name), true); return; }
                semantic(new Requests.Rename(type, name), "Renamed " + ProjectFiles.baseName(node.path()) + " to " + name,
                        node.path().resolveSibling(name + ".java"), null);
            }
            case PACKAGE -> {
                if (ProjectFiles.packageProblem(name) != null) { host.message(ProjectFiles.packageProblem(name), true); return; }
                renamePackage(node, name, confirmed);
            }
            default -> {
                if (ProjectFiles.fileNameProblem(name) != null) { host.message(ProjectFiles.fileNameProblem(name), true); return; }
                plainMove(node, node.path().resolveSibling(name), "Renamed " + node.path().getFileName() + " to " + name, confirmed);
            }
        }
    }

    /**
     * Renames a package with its subpackages. A folder that holds no class itself (only {@code com} above
     * {@code com.example}) is renamed through each package tree below it, one undo step per tree.
     */
    private void renamePackage(ProjectFiles.Node node, String name, boolean confirmed) {
        String old = node.packageName();
        List<Path> trees;
        try {
            trees = ProjectFiles.packageTrees(node.path());
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
            return;
        }
        if (trees.isEmpty()) {
            // No Java code: the folder is renamed as a folder.
            plainMove(node, ProjectFiles.packageFolder(node.sourceRoot(), name), "Renamed package " + old + " to " + name, confirmed);
            return;
        }
        var steps = new ArrayList<Supplier<Requests.Request>>();
        for (Path tree : trees) {
            String treeName = ProjectFiles.packageName(node.sourceRoot(), tree);
            String target = name + treeName.substring(old.length());
            steps.add(() -> {
                var declaration = anyPackageDeclaration(tree);
                if (declaration == null) throw new IllegalStateException("No package declaration in " + treeName);
                return new Requests.Rename(declaration, target, true);
            });
        }
        chain(steps, 0, "Renamed package " + old + " to " + name + (steps.size() > 1 ? " (" + steps.size() + " undo steps)" : ""),
                ProjectFiles.packageFolder(node.sourceRoot(), name), node.path());
    }

    // ── Move ──────────────────────────────────────────────────────────────────────────────────────────

    public void move(ProjectFiles.Node node) {
        if (!ready(node)) return;
        switch (node.kind()) {
            case JAVA_FILE -> {
                if (primaryType(node.path()) == null) { moveToFolder(node); return; }
                String destination = choosePackage(node, "Move " + node.path().getFileName() + " to package:", node.packageName());
                if (destination != null && !destination.equals(node.packageName())) moveToPackage(node, destination);
            }
            case PACKAGE -> {
                String old = node.packageName();
                String parent = old.contains(".") ? old.substring(0, old.lastIndexOf('.')) : "";
                String destination = choosePackage(node, "Move package " + old + " into:", parent);
                if (destination != null && !destination.equals(parent)) moveToPackage(node, destination);
            }
            default -> moveToFolder(node);
        }
    }

    /** A Java file to another package of its source root, or a package into another package ("" is the root). */
    public void moveToPackage(ProjectFiles.Node node, String destination) {
        if (!ready(node)) return;
        if (!destination.isEmpty() && ProjectFiles.packageProblem(destination) != null) { host.message(ProjectFiles.packageProblem(destination), true); return; }
        switch (node.kind()) {
            case JAVA_FILE -> {
                var type = primaryType(node.path());
                if (type == null) { host.message(node.path().getFileName() + " declares no class of that name", true); return; }
                semantic(new Requests.Move(type, Requests.MoveKind.TYPE, destination, ""),
                        "Moved " + ProjectFiles.baseName(node.path()) + " to " + (destination.isEmpty() ? "the default package" : destination),
                        ProjectFiles.packageFolder(node.sourceRoot(), destination).resolve(node.path().getFileName()), null);
            }
            case PACKAGE -> {
                String old = node.packageName();
                String simple = node.path().getFileName().toString();
                if ((destination + ".").startsWith(old + ".")) { host.message("A package cannot move into itself", true); return; }
                renamePackage(node, destination.isEmpty() ? simple : destination + "." + simple, true);
            }
            default -> host.message("Only Java files and packages move to a package", true);
        }
    }

    private void moveToFolder(ProjectFiles.Node node) {
        var chooser = new dev.turboj.commandj.swing.EmbeddedFileChooser(node.path().getParent().toFile());
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Move " + node.path().getFileName() + " to folder");
        if (chooser.showDialog(host.owner(), "Move") != javax.swing.JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) return;
        moveToFolder(node, chooser.getSelectedFile().toPath(), false);
    }

    /** A file or folder into another folder. */
    public void moveToFolder(ProjectFiles.Node node, Path destination, boolean confirmed) {
        if (!ready(node)) return;
        Path folder = destination.toAbsolutePath().normalize();
        if (folder.startsWith(node.path())) { host.message("A folder cannot move into itself", true); return; }
        if (folder.equals(node.path().getParent())) return;
        plainMove(node, folder.resolve(node.path().getFileName()), "Moved " + node.path().getFileName() + " to " + folder.getFileName(), confirmed);
    }

    /** A destination package in the item's source root: an existing one from the list, or a new name. */
    private String choosePackage(ProjectFiles.Node node, String prompt, String current) {
        List<String> packages;
        try {
            packages = ProjectFiles.packages(node.sourceRoot());
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
            return null;
        }
        var choices = new JComboBox<>(packages.stream().map(name -> name.isEmpty() ? "(default package)" : name).toArray(String[]::new));
        choices.setEditable(true);
        choices.setSelectedItem(current.isEmpty() ? "(default package)" : current);
        var panel = new JPanel(new java.awt.BorderLayout(0, 6));
        panel.add(new JLabel(prompt), java.awt.BorderLayout.NORTH);
        panel.add(choices, java.awt.BorderLayout.CENTER);
        while (true) {
            if (EmbeddedOptionPane.showConfirmDialog(host.owner(), panel, "Move", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
            Object chosen = choices.getEditor().getItem();
            String name = chosen == null ? "" : chosen.toString().strip();
            if (name.equals("(default package)")) name = "";
            String problem = name.isEmpty() ? null : ProjectFiles.packageProblem(name);
            if (problem == null) return name;
            host.message(problem, true);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────────────────────────────

    public void delete(ProjectFiles.Node node) { delete(node, false); }

    /** Safe Delete for Java code, Delete otherwise; {@code confirmed} skips the confirmation. */
    public void delete(ProjectFiles.Node node, boolean confirmed) {
        if (!ready(node)) return;
        if (node.kind() == ProjectFiles.Kind.JAVA_FILE) {
            var type = primaryType(node.path());
            if (type != null) {
                if (!confirmed && !confirm("Safe Delete " + node.path().getFileName() + "?\n\nTurboJ deletes it only if no other code uses "
                        + ProjectFiles.baseName(node.path()) + ". Undo brings it back.")) return;
                semantic(new Requests.SafeDelete(type), "Deleted " + node.path().getFileName(), null, node.path().getParent());
                return;
            }
        }
        List<Path> files;
        try {
            files = ProjectFiles.files(node.path());
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
            return;
        }
        var declarations = new ArrayList<Model.Location>();
        for (Path file : files) {
            if (!file.getFileName().toString().endsWith(".java") || node.sourceRoot() == null) continue;
            var type = primaryType(file);
            if (type != null) declarations.add(type);
        }
        String what = node.directory() ? node.path().getFileName() + " and its " + files.size() + (files.size() == 1 ? " file" : " files")
                : node.path().getFileName().toString();
        Runnable proceed = () -> deleteChecked(node, what, confirmed);
        if (declarations.isEmpty()) { proceed.run(); return; }
        busy = true;
        host.message("Checking that no other code uses the " + declarations.size() + " classes in " + node.path().getFileName() + "…", false);
        host.referencesOutside(declarations, node.path()).whenComplete((references, failure) -> SwingUtilities.invokeLater(() -> {
            busy = false;
            if (failure != null) { host.message("Cannot check usages: " + cause(failure).getMessage(), true); return; }
            if (!references.isEmpty()) {
                var first = references.getFirst();
                host.message("Not deleted: " + references.size() + (references.size() == 1 ? " reference" : " references")
                        + " from outside, first in " + first.file().getFileName() + " at offset " + first.start(), true);
                return;
            }
            proceed.run();
        }));
    }

    private void deleteChecked(ProjectFiles.Node node, String what, boolean confirmed) {
        boolean text;
        try {
            text = ProjectFiles.allText(ProjectFiles.files(node.path()));
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
            return;
        }
        if (!text) {
            if (!confirmed && !confirm("Move " + what + " to the Trash?\n\nIt contains binary files, which Undo cannot restore; Finder's Put Back can.")) return;
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                host.message("This system has no Trash; nothing was deleted", true);
                return;
            }
            if (!Desktop.getDesktop().moveToTrash(node.path().toFile())) { host.message("Could not move " + what + " to the Trash", true); return; }
            host.refresh(node.path().getParent());
            host.message("Moved " + what + " to the Trash", false);
            return;
        }
        if (!confirmed && !confirm("Delete " + what + "?\n\nUndo brings it back.")) return;
        try {
            var plan = ProjectFiles.deletePlan(host.projectRoot() == null ? node.path().getParent() : host.projectRoot(), node.path(),
                    "Delete " + node.path().getFileName());
            applyPlain(plan, "Deleted " + what, node.path().getParent(), node.directory() ? node.path() : null);
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────────────────

    /** No action while another one runs, and the files it touches must be saved. */
    private boolean ready(ProjectFiles.Node node) {
        if (busy) { host.message("Another file operation is still running", true); return false; }
        var dirty = host.dirtyFiles().stream().filter(file -> file.startsWith(node.path())).toList();
        if (!dirty.isEmpty() && node.kind() != ProjectFiles.Kind.JAVA_FILE && node.kind() != ProjectFiles.Kind.PACKAGE) {
            host.message("Save " + dirty.getFirst().getFileName() + (dirty.size() > 1 ? " and " + (dirty.size() - 1) + " more" : "") + " first", true);
            return false;
        }
        return true;
    }

    private Model.Location primaryType(Path file) {
        try {
            return ProjectFiles.primaryType(file, host.text(file)).orElse(null);
        } catch (IOException failure) {
            return null;
        }
    }

    private Model.Location anyPackageDeclaration(Path folder) {
        try (var children = Files.list(folder)) {
            for (Path file : children.filter(child -> child.getFileName().toString().endsWith(".java")).sorted().toList()) {
                var declaration = ProjectFiles.packageDeclaration(file, host.text(file));
                if (declaration.isPresent()) return declaration.get();
            }
        } catch (IOException failure) {
            return null;
        }
        return null;
    }

    /** One RefactorJ operation, applied when valid; its diagnostics otherwise. */
    private void semantic(Requests.Request request, String done, Path select, Path pruneFrom) {
        chain(List.of(() -> request), 0, done, select, pruneFrom);
    }

    /** RefactorJ operations one after the other, each prepared on the workspace the previous one left. */
    private void chain(List<Supplier<Requests.Request>> steps, int index, String done, Path select, Path pruneFrom) {
        if (index == steps.size()) {
            busy = false;
            if (pruneFrom != null) {
                ProjectFiles.pruneTree(pruneFrom);
                ProjectFiles.pruneEmpty(pruneFrom.getParent(), host.projectRoot() == null ? pruneFrom.getRoot() : host.projectRoot(), host.javaRoots());
            }
            host.refresh(select);
            host.message(done + " · ⌘Z undoes", false);
            return;
        }
        busy = true;
        Requests.Request request;
        try {
            request = steps.get(index).get();
        } catch (RuntimeException failure) {
            busy = false;
            host.message(failure.getMessage(), true);
            return;
        }
        // Preparation runs on the semantic worker; applying publishes buffers, so it starts on the EDT.
        java.util.function.Consumer<Throwable> stop = failure -> {
            busy = false;
            host.message((index > 0 ? "Stopped after " + index + " of " + steps.size() + " steps: " : "") + cause(failure).getMessage(), true);
            host.refresh(null);
        };
        host.prepare(request).whenComplete((prepared, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) { stop.accept(failure); return; }
            if (!prepared.plan().valid()) { stop.accept(new IllegalStateException(problems(prepared.plan()))); return; }
            host.apply(prepared).whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) stop.accept(error);
                else chain(steps, index + 1, done, select, pruneFrom);
            }));
        }));
    }

    private void plainMove(ProjectFiles.Node node, Path destination, String done, boolean confirmed) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { host.message(destination.getFileName() + " already exists there", true); return; }
        try {
            if (Files.isDirectory(node.path()) && ProjectFiles.files(node.path()).isEmpty()) {
                // An empty folder has nothing to journal.
                Files.createDirectories(destination.getParent());
                Files.move(node.path(), destination);
                host.refresh(destination);
                host.message(done, false);
                return;
            }
            if (!ProjectFiles.allText(ProjectFiles.files(node.path()))) {
                if (!confirmed && !confirm(node.path().getFileName() + " contains binary files: Undo cannot take this back.\n\nContinue?")) return;
                Files.createDirectories(destination.getParent());
                Files.move(node.path(), destination);
                host.refresh(destination);
                host.message(done, false);
                return;
            }
            Path root = host.projectRoot() == null ? node.path().getParent() : host.projectRoot();
            applyPlain(ProjectFiles.movePlan(root, node.path(), destination, done), done, destination,
                    node.directory() ? node.path() : null);
        } catch (IOException failure) {
            host.message(failure.getMessage(), true);
        }
    }

    private void applyPlain(Model.Plan plan, String done, Path select, Path emptied) {
        busy = true;
        host.apply(new RefactoringController.Prepared(plan, host.capture())).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            busy = false;
            if (failure != null) { host.message(cause(failure).getMessage(), true); return; }
            if (emptied != null) ProjectFiles.pruneTree(emptied);
            host.refresh(select);
            host.message(done + " · ⌘Z undoes", false);
        }));
    }

    private String ask(String prompt, String initial, java.util.function.Function<String, String> problem) {
        String value = initial;
        while (true) {
            value = EmbeddedOptionPane.showInputDialog(host.owner(), prompt, value);
            if (value == null) return null;
            value = value.strip();
            String why = problem.apply(value);
            if (why == null) return value;
            host.message(why, true);
        }
    }

    private boolean confirm(String message) {
        return EmbeddedOptionPane.showConfirmDialog(host.owner(), message, "TurboJ", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private static String problems(Model.Plan plan) {
        return plan.diagnostics().stream().filter(d -> d.severity().ordinal() >= Model.Severity.ERROR.ordinal())
                .map(Model.Diagnostic::message).findFirst().orElse("The operation is not possible here");
    }

    private static Throwable cause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        return cause;
    }
}
