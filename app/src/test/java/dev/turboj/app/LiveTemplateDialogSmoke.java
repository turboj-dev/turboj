package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.templates.LiveTemplateCatalog;
import dev.turboj.ui.EditorComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Isolated native dialog, real CommandJ/Swing keys, actual persistence and subsequent Java expansion. */
public final class LiveTemplateDialogSmoke {
    private static Path source, definitions;
    private static String initialSource;
    private static final StringBuilder events = new StringBuilder();
    public static void main(String[] args) throws Exception {
        output = Files.createDirectories(Path.of("app/target/live-template-dialog"));
        work = Files.createTempDirectory("turboj-template-dialog-").toRealPath();
        System.setProperty("turboj.config", work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path project = Files.createDirectories(work.resolve("project")); Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=Templates\n");
        source = Files.createDirectories(project.resolve("src/main/java")).resolve("C.java");
        Files.writeString(source, "public class C {}\n");
        settings = new Settings(); settings.theme = "vscode-monokai";
        definitions = Settings.directory().resolve("live-templates.json");
        frame = edt(() -> {
            var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront(); Desktop.getDesktop().requestForeground(true); f.openPath(project); return f;
        });
        try {
            await(() -> frame.mavenProjects().hasProject(), "project"); edt(() -> { frame.openPath(source); return null; });
            await(() -> source.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "source");
            initialSource = edt(() -> frame.editor().getText());
            check(initialSource.equals(Files.readString(source)), "initial editor agrees with fixture");
            edt(() -> {
                frame.editor().getAccessibleContext().addPropertyChangeListener(event -> {
                    if (event.getPropertyName().equals(javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY)) {
                        events.append("SOURCE CHANGE; overlay=").append(OverlayHost.active(frame)).append("; focus=").append(focus()).append('\n');
                        for (var call : Thread.currentThread().getStackTrace())
                            if (call.getClassName().startsWith("dev.turboj")) events.append("  ").append(call).append('\n');
                    }
                }); return null;
            });
            searchAndCreate(); validateAndSave(); expand(); restoreAndCancel();
            Files.copy(definitions, output.resolve("live-templates.json"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(output.resolve("editor.java.txt"), edt(() -> frame.editor().getText()));
        } catch (Throwable failure) { shot("failure"); throw failure; }
        finally {
            Files.writeString(output.resolve("effects.tsv"), report);
            Files.writeString(output.resolve("source-events.txt"), events);
            edt(() -> { for (var d : List.copyOf(OverlayHost.dialogs(frame))) d.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }
    private static void open() throws Exception {
        action("settings.liveTemplates"); dialog();
        await(() -> "live-template-search".equals(focus().getName()), "initial search focus");
    }
    private static void input(String id, String value) throws Exception { named(id); replace(value); }
    private static EditorComponent preview() { return find(top(), EditorComponent.class, "live-template-preview"); }
    private static void page(int direction) throws Exception {
        named("live-template-tabs"); key(direction > 0 ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT, 0);
        await(() -> find(top(), JTabbedPane.class, "live-template-tabs").getSelectedIndex() == (direction > 0 ? 1 : 0), "configuration/code tab selected");
    }
    private static void searchAndCreate() throws Exception {
        open(); unchanged("open"); replace("Java/sout");
        await(() -> preview().getText().equals("System.out.println();"), "native sout preview");
        check(edt(() -> preview().isReadOnly() && preview().theme().equals(frame.editor().theme())
                && preview().getFont().equals(frame.editor().getFont())), "same renderer/theme/font and read-only preview");
        key(KeyEvent.VK_DOWN, 0); key(KeyEvent.VK_UP, 0); key(KeyEvent.VK_ENTER, 0);
        await(() -> "live-template-name".equals(focus().getName()), "Enter edits search result, not Save");
        check(!Files.exists(definitions), "search/preview never creates user configuration");
        unchanged("search Enter");
        shot("builtin-monokai");
        button("Duplicate"); replace("Log a counter");
        input("live-template-abbreviation", "traceCount"); input("live-template-description", "Print an integer counter");
        input("live-template-body", "int $VALUE$ = 1;"); key(KeyEvent.VK_ENTER, 0); LiveTemplateWorkflowSmoke.type("System.out.println($VALUE$);$END$");
        unchanged("multiline definition");
        page(1); button("Add variable");
        await(() -> focus() instanceof JTable, "variable table focus");
        key(KeyEvent.VK_HOME, 0); key(KeyEvent.VK_RIGHT, 0); key(KeyEvent.VK_F2, 0); replace("futureMacro()"); key(KeyEvent.VK_ENTER, 0);
        key(KeyEvent.VK_RIGHT, 0); key(KeyEvent.VK_F2, 0); replace("total"); key(KeyEvent.VK_ENTER, 0);
        check(edt(() -> find(top(), JTable.class, "live-template-variables").getValueAt(0, 2).equals("total")), "keyboard variable default edited");
        unchanged("variable editing");
        key(KeyEvent.VK_TAB, 0);
        check(edt(() -> !(focus() instanceof JTable)), "Tab leaves variable table");
        shot("configuration-monokai"); page(-1);
        await(() -> preview().getText().contains("int total = 1;") && preview().getText().contains("println(total)"), "expanded linked example");
        named("live-template-preview"); String before = edt(() -> preview().getText());
        LiveTemplateWorkflowSmoke.type("cannot edit");
        check(edt(() -> preview().getText()).equals(before), "preview rejects typing");
        check(edt(() -> preview().getAccessibleContext().getAccessibleDescription()).contains("VALUE"), "placeholder explanation accessible");
        key(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
        check(!edt(() -> preview().isFocusOwner()), "Shift+Tab leaves preview");
        unchanged("preview typing");
        shot("custom-monokai");
        pass("dialog.search-create", "search/arrows/Enter, duplicate, named fields, code, variable expression/default, native read-only expansion, Tab traversal");
    }
    private static void validateAndSave() throws Exception {
        input("live-template-abbreviation", "invalid abbreviation"); button("Save");
        await(() -> find(top(), JLabel.class, "live-template-error").getText().contains("Invalid abbreviation"), "invalid abbreviation explained");
        check(!Files.exists(definitions), "invalid draft never saved"); input("live-template-abbreviation", "traceCount");
        page(1); named("live-template-context-JAVA_STATEMENT"); key(KeyEvent.VK_SPACE, 0); button("Save");
        await(() -> find(top(), JLabel.class, "live-template-error").getText().contains("Choose a context"), "missing context explained");
        check(!Files.exists(definitions), "missing context never saved");
        named("live-template-context-JAVA_STATEMENT"); key(KeyEvent.VK_SPACE, 0); button("Save");
        await(() -> !OverlayHost.active(frame) && Files.exists(definitions) && frame.editor().isFocusOwner(), "saved and source focus restored");
        var users = LiveTemplateCatalog.parse(Files.readString(definitions));
        check(users.size() == 1 && users.getFirst().name().equals("Log a counter") && users.getFirst().abbreviation().equals("traceCount"), "structured fields persisted");
        check(users.getFirst().variables().getFirst().expression().equals("futureMacro()"), "expression retained without script evaluation");
        unchanged("Save");
        pass("dialog.validation-save", "invalid abbreviation/context rejected; real file saved; metadata/configuration preserved; source and focus preserved");
    }
    private static void expand() throws Exception {
        LiveTemplateWorkflowSmoke.snippet("traceCount"); String before = edt(() -> frame.editor().getText());
        key(KeyEvent.VK_TAB, 0); await(() -> frame.editor().templateActive(), "custom live expansion");
        LiveTemplateWorkflowSmoke.type("amount"); key(KeyEvent.VK_ENTER, 0);
        await(() -> !frame.editor().templateActive() && LiveTemplateWorkflowSmoke.templateSettled(), "linked edit and formatting confirmed");
        String result = edt(() -> frame.editor().getText());
        check(result.contains("int amount = 1;") && result.contains("println(amount)"), "both linked occurrences changed"); compile(result);
        action("undo"); await(() -> frame.editor().getText().equals(before), "one Undo restores abbreviation");
        action("redo"); await(() -> frame.editor().getText().equals(result), "Redo restores expansion");
        pass("dialog.real-expansion", "created template expands, linked edits compile, one Undo and Redo");
    }
    private static void restoreAndCancel() throws Exception {
        open(); input("live-template-search", "integer counter");
        await(() -> "Log a counter".equals(find(top(), JTextField.class, "live-template-name").getText()), "description search after reopening");
        String disk = Files.readString(definitions);
        input("live-template-name", "Discarded change"); button("New"); replace("Discarded new entry");
        key(KeyEvent.VK_ESCAPE, 0); await(() -> !OverlayHost.active(frame), "cancel draft");
        check(Files.readString(definitions).equals(disk), "Cancel discards both entry edits and new entries");
        open(); replace("Java/sout"); named("live-template-enabled"); key(KeyEvent.VK_SPACE, 0); button("Save");
        await(() -> !OverlayHost.active(frame), "builtin override saved");
        check(LiveTemplateCatalog.parse(Files.readString(definitions)).stream().anyMatch(t -> t.id().equals("Java/sout") && t.disabled()), "builtin disabling persisted");
        open(); replace("Java/sout"); button("Restore default"); button("Save"); await(() -> !OverlayHost.active(frame), "default restored");
        check(LiveTemplateCatalog.parse(Files.readString(definitions)).stream().noneMatch(t -> t.id().equals("Java/sout")), "override removed rather than copied");
        LiveTemplateWorkflowSmoke.snippet("sout"); key(KeyEvent.VK_TAB, 0);
        await(() -> frame.editor().getText().contains("System.out.println();"), "restored sout really expands");
        pass("dialog.restore-cancel", "persistent metadata search, Cancel preserves disk, enable/disable override and Restore default, real restored expansion");
    }
    private static void shot(String name) throws Exception {
        var image = edt(() -> { var i = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB); var g = i.createGraphics(); frame.paint(g); g.dispose(); return i; });
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }
    private static void unchanged(String stage) throws Exception {
        // Replay the initial rapid-injection control without intermediate EDT inspection barriers.
        if ("1".equals(System.getenv("TURBOJ_DIALOG_RAPID_KEYS")) && !stage.equals("Save")) return;
        String actual = edt(() -> frame.editor().getText());
        check(actual.equals(initialSource), stage + " changed source: " + actual.replace("\n", "\\n"));
    }
}
