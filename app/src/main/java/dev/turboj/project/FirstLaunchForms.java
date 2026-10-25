package dev.turboj.project;

import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.EmbeddedFileChooser;
import dev.turboj.refactorj.api.JavaQuickActions;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.*;

/** Compact native forms; keyboard actions belong to their own overlay, never a global dispatcher. */
public final class FirstLaunchForms {
    private FirstLaunchForms() {}
    public record WorkspaceSelection(Path directory, boolean remember) {}

    /** The unresolved reference fixes the name/package; no arbitrary unrelated type can be created. */
    public static boolean confirmQuickClass(Component owner, JavaQuickActions.Proposal proposal) {
        if (proposal.action() != JavaQuickActions.Action.CREATE_CLASS) throw new IllegalArgumentException("Expected Create Class proposal");
        Form form = new Form(owner, "New Java Class", "quick-class");
        var parameters = proposal.parameters();
        JTextField root = form.text("Source folder", "source-root", parameters.getOrDefault("sourceRoot", "")); root.setEditable(false);
        JTextField pkg = form.text("Package", "package", parameters.getOrDefault("packageName", "")); pkg.setEditable(false);
        JTextField name = form.text("Name", "name", parameters.getOrDefault("className", parameters.getOrDefault("name", ""))); name.setEditable(false);
        form.note(proposal.explanation());
        form.note("These values are inferred from the unresolved reference. No main method is added.");
        form.initial = null;
        return Boolean.TRUE.equals(form.show("Finish", "finish", () -> Boolean.TRUE));
    }

    public static WorkspaceSelection selectWorkspace(Component owner, Path initial) {
        Form form = new Form(owner, "Select Workspace", "workspace");
        JTextField path = form.text("Workspace", "path", initial.toString());
        JButton browse = new JButton("Browse…"); browse.setName("first-launch-workspace-browse");
        browse.addActionListener(event -> {
            EmbeddedFileChooser chooser = new EmbeddedFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            try { chooser.setCurrentDirectory(Path.of(path.getText()).toFile()); } catch (RuntimeException ignored) { }
            if (chooser.showOpenDialog(form.dialog) == JFileChooser.APPROVE_OPTION) path.setText(chooser.getSelectedFile().toString());
        });
        form.row("", browse);
        JCheckBox remember = form.check("Use this as the default workspace", "remember", false);
        form.note("Projects are created inside this workspace unless you choose another location.");
        return form.show("Launch", "launch", () -> new WorkspaceSelection(requiredPath(path), remember.isSelected()));
    }

    public static FirstLaunchProjects.ProjectSpec project(Component owner, Path workspace, boolean maven) {
        Form form = new Form(owner, maven ? "New Maven Project" : "New Java Project", "project");
        JTextField name = form.text("Project name", "name", "");
        JTextField location = form.text("Location", "location", workspace.toString());
        JLabel kind = new JLabel(maven ? "Maven" : "TurboJ Native"); kind.setName("first-launch-project-kind");
        form.row("Build system", kind);
        form.row("Java version", new JLabel("Java 25"));
        JTextField group = maven ? form.text("Group ID", "group", "example") : null;
        JTextField artifact = maven ? form.text("Artifact ID", "artifact", "") : null;
        final String[] previous = {workspace.toString(), ""};
        name.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { update(); }
            public void removeUpdate(DocumentEvent event) { update(); }
            public void changedUpdate(DocumentEvent event) { update(); }
            private void update() {
                String next = workspace.resolve(name.getText().strip()).toString();
                if (location.getText().equals(previous[0])) location.setText(next);
                previous[0] = next;
                if (artifact != null && artifact.getText().equals(previous[1])) artifact.setText(name.getText().strip());
                previous[1] = name.getText().strip();
            }
        });
        form.note("Main sources: src/main/java     Test sources: src/test/java");
        return form.show("Create", "create", () -> {
            String value = name.getText().strip();
            if (value.isEmpty()) throw new IllegalArgumentException("Enter a project name.");
            Path destination = requiredPath(location);
            if (destination.equals(workspace.toAbsolutePath().normalize())) throw new IllegalArgumentException("Choose a new project folder inside the workspace.");
            return new FirstLaunchProjects.ProjectSpec(workspace, value, destination,
                    maven ? FirstLaunchProjects.Kind.MAVEN : FirstLaunchProjects.Kind.JAVA,
                    group == null ? "" : group.getText().strip(), artifact == null ? "" : artifact.getText().strip(), "25");
        });
    }

    public static FirstLaunchProjects.MainSpec mainClass(Component owner, Path sourceRoot, String packageName) {
        Form form = new Form(owner, "New Java Class", "main");
        JTextField root = form.text("Source folder", "source-root", sourceRoot.toString());
        JTextField pkg = form.text("Package", "package", packageName);
        JTextField name = form.text("Name", "name", "HelloWorld");
        JCheckBox main = form.check("public static void main(String[] args)", "method", true);
        form.initial = name;
        return form.show("Create", "create", () -> new FirstLaunchProjects.MainSpec(requiredPath(root), pkg.getText().strip(),
                name.getText().strip(), main.isSelected()));
    }

    public static FirstLaunchProjects.TestSpec junitTest(Component owner, FirstLaunchProjects.TestContext context) {
        Form form = new Form(owner, "New JUnit Test Case", "test");
        form.row("Test framework", new JLabel("JUnit Jupiter " + context.junitDependencyVersion()));
        JTextField root = form.text("Source folder", "root", context.testRoot().toString()); root.setEditable(false);
        JTextField pkg = form.text("Package", "package", context.packageName());
        JTextField name = form.text("Name", "name", context.className() + "Test");
        form.row("Class under test", new JLabel(context.qualifiedName()));
        JTextField method = form.text("Test method", "method", "test");
        JCheckBox before = form.check("@BeforeEach setUp()", "before-each", false);
        JCheckBox after = form.check("@AfterEach tearDown()", "after-each", false);
        JCheckBox beforeAll = form.check("@BeforeAll setUpBeforeClass()", "before-all", false);
        JCheckBox afterAll = form.check("@AfterAll tearDownAfterClass()", "after-all", false);
        form.note("Creates a compiling test with fail(\"Not yet implemented\"). Implement the assertion before expecting green.");
        form.initial = name;
        var result = form.show("Finish", "create", () -> new FirstLaunchProjects.TestSpec(requiredPath(root), pkg.getText().strip(),
                name.getText().strip(), method.getText().strip(), before.isSelected(), after.isSelected(),
                beforeAll.isSelected(), afterAll.isSelected(), false));
        if (result == null || context.junitAvailable()) return result;
        Form dependency = new Form(owner, "Add JUnit Library", "junit");
        dependency.note(context.maven()
                ? "This project needs a JUnit test runtime. Add org.junit.jupiter:" + context.junitDependencyArtifact() + ":" + context.junitDependencyVersion() + " with test scope to pom.xml?"
                : "JUnit is missing from this project's classpath. Add TurboJ's bundled JUnit Jupiter " + FirstLaunchProjects.JUNIT_VERSION + " library?");
        dependency.note(context.maven() ? "Maven dependencies will be resolved by the normal project import." : "No download is required. The selection is saved in the project configuration.");
        Boolean add = dependency.show("Add JUnit Library", "add", () -> Boolean.TRUE);
        return add == null ? null : new FirstLaunchProjects.TestSpec(result.testRoot(), result.packageName(), result.className(),
                result.methodName(), result.beforeEach(), result.afterEach(), result.beforeAll(), result.afterAll(), true);
    }

    private static Path requiredPath(JTextField field) {
        if (field.getText().isBlank()) throw new IllegalArgumentException("Enter a directory.");
        return Path.of(field.getText().strip()).toAbsolutePath().normalize();
    }

    private static final class Form {
        final EmbeddedDialog dialog;
        final JPanel fields = new FormPanel();
        final JLabel error = new JLabel(" ");
        final String prefix;
        JComponent initial;
        int row;
        Form(Component owner, String title, String name) {
            dialog = new EmbeddedDialog(owner, title, true); dialog.setName("first-launch-" + name + "-dialog");
            prefix = "first-launch-" + name + "-";
            fields.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        }
        JTextField text(String label, String name, String value) {
            JTextField field = new JTextField(value, 24); field.setName(prefix + name); field.getAccessibleContext().setAccessibleName(label);
            row(label, field); if (initial == null) initial = field; return field;
        }
        JCheckBox check(String title, String name, boolean selected) {
            JCheckBox check = new JCheckBox(title, selected); check.setName(prefix + name); row("", check); return check;
        }
        void row(String label, JComponent component) {
            GridBagConstraints constraints = new GridBagConstraints(); constraints.gridy = row++;
            constraints.insets = new Insets(3, 3, 3, 5); constraints.anchor = GridBagConstraints.LINE_START;
            JLabel caption = new JLabel(label); caption.setLabelFor(component); fields.add(caption, constraints);
            constraints.gridx = 1; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; fields.add(component, constraints);
            component.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) {
                    component.scrollRectToVisible(new Rectangle(0, 0, component.getWidth(), component.getHeight()));
                }
            });
        }
        void note(String value) {
            JTextArea note = new JTextArea(value); note.setEditable(false); note.setFocusable(false);
            note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false); note.setColumns(46);
            GridBagConstraints constraints = new GridBagConstraints(); constraints.gridy = row++; constraints.gridwidth = 2;
            constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; constraints.insets = new Insets(6, 3, 3, 3);
            fields.add(note, constraints);
        }
        <T> T show(String buttonLabel, String buttonName, Supplier<T> value) {
            Object[] result = {null};
            JPanel content = new JPanel(new BorderLayout());
            JScrollPane scroll = new JScrollPane(fields); scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            Dimension preferred = fields.getPreferredSize();
            scroll.setPreferredSize(new Dimension(Math.min(720, preferred.width + 20), Math.min(420, preferred.height + 4)));
            content.add(scroll, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout()); error.setName(prefix + "error"); error.getAccessibleContext().setAccessibleName("Validation error");
            footer.add(error, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
            JButton cancel = new JButton("Cancel"), accept = new JButton(buttonLabel);
            cancel.setName(prefix + "cancel"); accept.setName(prefix + buttonName);
            cancel.addActionListener(event -> dialog.dispose());
            accept.addActionListener(event -> {
                try { result[0] = value.get(); dialog.dispose(); }
                catch (RuntimeException invalid) { error.setText(invalid.getMessage()); dialog.revalidate(); }
            });
            buttons.add(cancel); buttons.add(accept); footer.add(buttons, BorderLayout.SOUTH); content.add(footer, BorderLayout.SOUTH);
            dialog.setContentPane(content); dialog.getRootPane().setDefaultButton(accept);
            dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.onOpened(() -> SwingUtilities.invokeLater(() -> {
                JComponent target = initial == null ? accept : initial;
                target.requestFocusInWindow(); if (target instanceof JTextField field) field.selectAll();
            }));
            dialog.pack(); dialog.setLocationRelativeTo(dialog.getOwner()); dialog.setVisible(true);
            @SuppressWarnings("unchecked") T typed = (T) result[0]; return typed;
        }
    }

    private static final class FormPanel extends JPanel implements Scrollable {
        FormPanel() { super(new GridBagLayout()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, getFontMetrics(getFont()).getHeight());
        }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - getScrollableUnitIncrement(visible, orientation, direction));
        }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
