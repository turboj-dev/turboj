package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaQuickActions.Proposal;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Real Swing key events and CommandJ, real catalog/JDT; fixtures only prepare source and isolated preferences. */
public final class LiveTemplateWorkflowSmoke {
    static Path source;
    public static void main(String[] args) throws Exception {
        output = Files.createDirectories(Path.of(args.length > 0 ? "app/target/inline-semantic-template" : "app/target/live-template-workflow"));
        work = Files.createTempDirectory("turboj-live-template-workflow-").toRealPath();
        System.setProperty("turboj.config", work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path project = Files.createDirectories(work.resolve("project")); Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=Templates\n");
        source = Files.createDirectories(project.resolve("src/main/java")).resolve("C.java"); Files.writeString(source, "public class C {}\n");
        settings = new Settings();
        frame = edt(() -> { var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront(); Desktop.getDesktop().requestForeground(true); f.openPath(project); return f; });
        try {
            await(() -> frame.mavenProjects().hasProject(), "project"); edt(() -> { frame.openPath(source); return null; });
            await(() -> source.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "source");
            if (args.length > 0 && args[0].equals("--qfx08")) { semanticGeneratedFields(); extractedInlineFields(); }
            else { builtins(); custom(); lifecycle(); invalidGeneratedFields(); generatedLifecycle(); printCompletion(); restartPersistence(); }
        } finally {
            Files.writeString(output.resolve("effects.tsv"), report);
            edt(() -> { for (var d : List.copyOf(OverlayHost.dialogs(frame))) d.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }
    static void nextField(int index) throws Exception {
        key(KeyEvent.VK_TAB, 0);
        await(() -> frame.editor().templateActive() && frame.editor().templateIndex() == index, "inline field " + index);
    }
    static void semanticGeneratedFields() throws Exception {
        String code = "public class C { void f(){ int value = calculate(1, 2); } }\n";
        generate(code);
        nextField(1); nextField(2);
        type("String"); key(KeyEvent.VK_ENTER, 0);
        await(() -> !frame.editor().templateErrors().isEmpty(), "incompatible call argument rejected");
        check(edt(() -> frame.editor().templateActive()), "invalid signature remains editable");
        selectTyped("String"); type("long"); nextField(3);
        check(edt(() -> frame.editor().getText()).contains("long "), "widened parameter retained");
        nextField(4); nextField(5); nextField(6);
        type("true"); key(KeyEvent.VK_ENTER, 0);
        await(() -> !frame.editor().templateErrors().isEmpty(), "boolean expression rejected for int return");
        String saved = Files.readString(source);
        action("save"); await(() -> !frame.editor().templateErrors().isEmpty(), "Save checks expression type");
        check(Files.readString(source).equals(saved), "invalid expression never saved");
        selectTyped("true"); type("42"); action("save");
        await(() -> !frame.editor().templateActive() && Files.readString(source).equals(frame.editor().getText()), "semantic confirmation resumes Save");
        String result = edt(() -> frame.editor().getText()); compile(result);
        action("undo"); await(() -> frame.editor().getText().contains("return 0;"), "Undo semantic field edits");
        action("redo"); await(() -> frame.editor().getText().equals(result), "Redo semantic field edits");
        pass("qfx08.signature", "real Tab parameter types; incompatible caller rejected; widening accepted; mismatched return and Save rejected; corrected Save, javac, Undo/Redo");
        generate(code); nextField(1); nextField(2); type("long"); nextField(3);
        key(KeyEvent.VK_ESCAPE, 0); await(() -> !frame.editor().templateActive() && frame.editor().getText().equals(code), "type-edit cancellation rolls back generation");
        pass("qfx08.cancel", "Escape restores original caller after a semantically validated parameter edit");
    }
    static void extractedInlineFields() throws Exception {
        for (String id : List.of("refactor.extract-variable", "refactor.extract-method", "refactor.create-field")) {
            boolean field = id.endsWith("create-field"), method = id.endsWith("extract-method");
            String code = field ? "public class C { int f(){ return missing; } }\n"
                    : "public class C { int f(int x){ return x + 1; } }\n";
            String selection = field ? "missing" : "x + 1";
            int at = code.indexOf(selection); fixture(code, at, field ? at : at + selection.length());
            action(id); dialog();
            if (!top().getTitle().startsWith("RefactorJ")) button("OK");
            await(() -> OverlayHost.active(frame) && top().getTitle().startsWith("RefactorJ"), "preview " + id);
            check(edt(() -> frame.editor().getText()).equals(code), "preview hasn't changed source");
            key(KeyEvent.VK_ESCAPE, 0); await(() -> !OverlayHost.active(frame), "preview cancel");
            check(edt(() -> frame.editor().getText()).equals(code), "preview cancel preserves source");
            action(id); dialog(); if (!top().getTitle().startsWith("RefactorJ")) button("OK");
            await(() -> OverlayHost.active(frame) && top().getTitle().startsWith("RefactorJ"), "second preview");
            button("Apply changes"); await(() -> frame.editor().templateActive() && !OverlayHost.active(frame), "inline fields " + id);
            if (method) nextField(1);
            type("renamed"); key(KeyEvent.VK_ENTER, 0);
            await(() -> !frame.editor().templateActive(), "confirmed " + id);
            String result = edt(() -> frame.editor().getText()); compile(result);
            check(result.contains("renamed"), "declaration renamed");
            check(method ? result.contains("renamed(x)") : result.contains("return renamed;"), "bound usage renamed");
            action("undo"); await(() -> !frame.editor().getText().contains("renamed"), "Undo linked rename");
            action("undo"); await(() -> frame.editor().getText().equals(code), "Undo extraction/generation");
            action("redo"); await(() -> !frame.editor().getText().equals(code), "Redo extraction/generation");
            action("redo"); await(() -> frame.editor().getText().equals(result), "Redo linked rename");
            pass(id + ".inline", "keyboard preview/cancel/apply; inline semantic rename of declaration and uses; javac; two transaction Undo/Redo");
        }
    }

    static void snippet(String abbreviation) throws Exception {
        String code = "public class C { void f() {\n    " + abbreviation + "\n} }\n";
        int at = "public class C { void f() {\n    ".length() + abbreviation.length(); fixture(code, at, at);
    }
    static void builtins() throws Exception {
        for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
            action("keymap.profile." + profile); snippet("sout"); key(KeyEvent.VK_TAB, 0);
            await(() -> frame.editor().getText().contains("System.out.println();"), "sout expansion " + profile);
            check(edt(() -> frame.editor().getText().substring(0, frame.editor().caretOffset())).endsWith("println("), "caret inside println");
            check(!edt(() -> frame.editor().templateActive()), "simple template has no placeholder session");
            action("undo"); await(() -> frame.editor().getText().contains("    sout\n"), "sout undo");
            action("redo"); await(() -> frame.editor().getText().contains("System.out.println();"), "sout redo");
            pass("indent", "sout Tab, exact caret, Undo/Redo in " + profile);
        }
        snippet("sout"); action("editor.expandLiveTemplate"); await(() -> frame.editor().getText().contains("System.out.println();"), "explicit expansion");
        pass("editor.expandLiveTemplate", "actual default chord expands source");
        snippet("fori"); key(KeyEvent.VK_TAB, 0); await(() -> frame.editor().templateActive(), "fori fields");
        check(edt(() -> frame.editor().getSelectedText()).equals("i"), "first fori field"); type("index");
        check(edt(() -> frame.editor().getText()).contains("index++"), "all index occurrences linked");
        key(KeyEvent.VK_TAB, 0); type("3"); key(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
        check(edt(() -> frame.editor().getSelectedText()).equals("index"), "Shift Tab previous");
        key(KeyEvent.VK_TAB, 0); key(KeyEvent.VK_TAB, 0); await(() -> !frame.editor().templateActive(), "last Tab exits snippet");
        String after = edt(() -> frame.editor().getText()); compile(after);
        action("undo"); await(() -> frame.editor().getText().contains("    fori\n"), "one undo whole snippet");
        action("redo"); await(() -> frame.editor().getText().equals(after), "whole snippet redo");
        pass("live.fields", "linked index, Shift Tab, last Tab exits; Java compiles; one Undo/Redo");
    }
    static final String CUSTOM = """
            {"schemaVersion":1,"templates":[{"id":"user/twice","abbreviation":"twice",
             "body":"int $NAME$ = 1; System.out.println($NAME$);$END$", "contexts":["JAVA_STATEMENT"],
             "variables":[{"name":"NAME","defaultValue":"total"}]}]}
            """;
    static void custom() throws Exception {
        action("settings.liveTemplates"); dialog();
        tab(c -> "live-template-definitions".equals(c.getName())); replace(CUSTOM); button("Save");
        await(() -> !OverlayHost.active(frame), "settings saved");
        check(com.google.gson.JsonParser.parseString(Files.readString(Settings.directory().resolve("live-templates.json"))).equals(com.google.gson.JsonParser.parseString(CUSTOM)), "persisted exact definition");
        snippet("twice"); key(KeyEvent.VK_TAB, 0); await(() -> frame.editor().templateActive(), "custom template"); type("amount"); key(KeyEvent.VK_TAB, 0);
        await(() -> !frame.editor().templateActive(), "custom last field Tab");
        check(edt(() -> frame.editor().getText()).contains("int amount = 1; System.out.println(amount);"), "custom linked field"); compile(edt(() -> frame.editor().getText()));
        pass("settings.liveTemplates", "keyboard creates and saves user definition; actual expansion and linked edits compile");
        action("settings.liveTemplates"); dialog(); tab(c -> "live-template-definitions".equals(c.getName())); replace("{}"); button("Save");
        await(() -> find(frame, JLabel.class, "live-template-error").getText().contains("Expected"), "invalid JSON explained");
        check(com.google.gson.JsonParser.parseString(Files.readString(Settings.directory().resolve("live-templates.json"))).equals(com.google.gson.JsonParser.parseString(CUSTOM)), "invalid configuration cannot overwrite disk");
        key(KeyEvent.VK_ESCAPE, 0); await(() -> !OverlayHost.active(frame), "settings cancelled");
        snippet(""); action("editor.insertLiveTemplate"); dialog();
        tab(c -> "live-template-filter".equals(c.getName())); type("sout"); key(KeyEvent.VK_DOWN, 0); key(KeyEvent.VK_UP, 0); key(KeyEvent.VK_ENTER, 0);
        await(() -> frame.editor().getText().contains("System.out.println();") && !OverlayHost.active(frame), "picker applies");
        pass("editor.insertLiveTemplate", "filter, arrows and Enter insert real catalog entry");
    }
    static void lifecycle() throws Exception {
        snippet("twice"); String before = edt(() -> frame.editor().getText()); key(KeyEvent.VK_TAB, 0);
        await(() -> frame.editor().templateActive(), "cancel fixture"); type("renamed"); key(KeyEvent.VK_ESCAPE, 0);
        await(() -> frame.editor().getText().equals(before) && !frame.editor().templateActive(), "Escape rolls back whole snippet");
        snippet("twice"); key(KeyEvent.VK_TAB, 0); await(() -> frame.editor().templateActive(), "save fixture"); type("saved"); action("save");
        await(() -> !frame.editor().templateActive() && Files.readString(source).contains("int saved")
                && frame.getTitle().equals("C.java — TurboJ"), "Save commits template and publishes saved state");
        snippet("twice"); key(KeyEvent.VK_TAB, 0); await(() -> frame.editor().templateActive(), "format fixture"); type("formatted"); action("format.document");
        await(() -> !frame.editor().templateActive() && frame.editor().getText().contains("int formatted = 1;")
                && frame.editor().getText().contains("class C {\n"), "Format commits template and actually formats source");
        compile(edt(() -> frame.editor().getText()));
        Path other = Files.writeString(source.resolveSibling("Other.java"), "class Other {}\n");
        edt(() -> { frame.openPath(other); return null; }); await(() -> other.equals(frame.currentFile()), "second file opened");
        action("navigation.previousEditorTab"); await(() -> source.equals(frame.currentFile()), "source restored");
        snippet("twice"); key(KeyEvent.VK_TAB,0); await(() -> frame.editor().templateActive(), "switch fixture"); type("retained");
        action("navigation.nextEditorTab"); await(() -> other.equals(frame.currentFile()) && !frame.editor().templateActive(), "file switch ends fields");
        check(edt(() -> frame.bufferText(source)).contains("int retained"), "switch preserves unsaved field edits");
        action("navigation.previousEditorTab"); await(() -> source.equals(frame.currentFile()), "return to edited file");
        pass("live.lifecycle", "Escape rollback, Save writes edited template, Format confirms valid code; file switch ends fields and preserves edits");
    }
    static void invalidGeneratedFields() throws Exception {
        String code = "public class C { void f(){ int value = calculate(1, 2); } }\n";
        generate(code);
        String saved = Files.readString(source);
        key(KeyEvent.VK_BACK_SPACE,0); action("save");
        await(() -> !frame.editor().templateErrors().isEmpty(), "empty active type rejected on Save");
        check(edt(() -> frame.editor().templateActive()) && Files.readString(source).equals(saved), "invalid template cannot save");
        action("file.saveAll"); check(edt(() -> frame.editor().templateActive()) && Files.readString(source).equals(saved), "invalid template cannot Save All");
        type("List<int>"); key(KeyEvent.VK_ENTER,0); check(edt(() -> frame.editor().templateActive()), "illegal generic rejected");
        selectTyped("List<int>"); type("int"); key(KeyEvent.VK_TAB,0);
        type("bad.name"); key(KeyEvent.VK_ENTER,0); await(() -> !frame.editor().templateErrors().isEmpty(), "qualified method name rejected");
        action("format.document"); check(edt(() -> frame.editor().templateActive()), "Format cannot bypass name validation");
        selectTyped("bad.name"); type("calculate"); key(KeyEvent.VK_TAB,0); key(KeyEvent.VK_TAB,0);
        await(() -> frame.editor().templateIndex() == 3, "first parameter");
        String original = edt(() -> frame.editor().templateText(3)), duplicate = edt(() -> frame.editor().templateText(5));
        type(duplicate); key(KeyEvent.VK_ENTER,0);
        await(() -> frame.editor().templateErrors().values().contains("Duplicate parameter name"), "duplicate parameters rejected");
        selectTyped(duplicate); type(original);
        key(KeyEvent.VK_TAB,0); key(KeyEvent.VK_TAB,0); key(KeyEvent.VK_TAB,0); await(() -> frame.editor().templateIndex() == 6, "return expression");
        type("Math.ma"); action("complete"); await(() -> frame.intelligence().alternativesVisible(), "completion inside field");
        key(KeyEvent.VK_ENTER,0); await(() -> !frame.intelligence().alternativesVisible(), "Enter accepts completion first");
        check(edt(() -> frame.editor().templateActive() && frame.editor().templateStopCount() == 7), "completion retains placeholders");
        check(edt(() -> frame.editor().templateText(6)).contains("Math.max("), "real Math.max completion stays inside body field");
        key(KeyEvent.VK_ESCAPE,0); await(() -> !frame.editor().templateActive() && frame.editor().getText().equals(code), "generated session rollback");
        pass("qfx03.validation", "Save/Save All/Format validation; primitive generic, qualified name and duplicate parameters rejected; JDT completion retains fields; Escape restores caller");
    }
    static void generate(String code) throws Exception {
        int at = code.indexOf("calculate") + 2; fixture(code, at, at); action("refactor.context-actions");
        await(() -> find(frame, JList.class, "quick-actions-proposals") != null
                && focus() != null && "quick-actions-filter".equals(focus().getName()), "current contextual proposals own focus");
        JList<?> list = edt(() -> find(frame, JList.class, "quick-actions-proposals"));
        key(KeyEvent.VK_HOME, 0);
        int index = edt(() -> { for (int i=0;i<list.getModel().getSize();i++) if (list.getModel().getElementAt(i) instanceof Proposal p && p.title().startsWith("Create method 'calculate")) return i; return -1; });
        check(index >= 0, "real Create Method proposal"); for (int i=0;i<index;i++) key(KeyEvent.VK_DOWN,0); key(KeyEvent.VK_ENTER,0);
        await(() -> frame.editor().templateActive(), "generated fields");
    }
    static void generatedLifecycle() throws Exception {
        String code = "public class C { void f(){ int value = calculate(1, 2); } }\n";
        for (String command : List.of("save", "format.document")) {
            generate(code);
            for(int i=0;i<6;i++) key(KeyEvent.VK_TAB,0);
            String left = edt(() -> frame.editor().templateText(3)), right = edt(() -> frame.editor().templateText(5));
            type(left + " + " + right); action(command);
            await(() -> !frame.editor().templateActive(), command + " confirms generated template");
            if (command.equals("format.document")) await(() -> frame.editor().getText().contains("class C {\n"), "formatter result visible");
            String result = edt(() -> frame.editor().getText()); compile(result);
            if(command.equals("save")) {
                await(() -> Files.readString(source).equals(result) && frame.getTitle().equals("C.java — TurboJ"), "saved generated method");
                action("undo"); await(() -> frame.editor().getText().contains("return 0;"), "Undo field edits");
                action("undo"); await(() -> frame.editor().getText().equals(code), "Undo generated method");
                action("redo"); await(() -> frame.editor().getText().contains("return 0;"), "Redo generated method");
                action("redo"); await(() -> frame.editor().getText().equals(result), "Redo field edits");
            }
            pass("qfx03." + command, "valid generated template confirms; resulting Java compiles" + (command.equals("save") ? "; saved bytes and transaction Undo/Redo verified" : ""));
        }
        generate(code); key(KeyEvent.VK_TAB,0); // Switching files ends, rather than silently cancelling, generated code.
        action("navigation.nextEditorTab"); await(() -> !source.equals(frame.currentFile()) && !frame.editor().templateActive(), "switch during generated template");
        check(edt(() -> frame.bufferText(source)).contains("int calculate("), "generated code retained after switch");
        action("navigation.previousEditorTab"); await(() -> source.equals(frame.currentFile()), "return generated buffer");
        compile(edt(() -> frame.editor().getText())); pass("qfx03.switch", "buffer switch ends fields, preserves compiling generated method and restores editor");
    }
    static void printCompletion() throws Exception {
        String code = "public class C { void f(){ System.out.print; } }\n"; int at = code.indexOf("print;")+5;
        fixture(code,at,at); action("complete"); await(() -> frame.intelligence().alternativesVisible(), "PrintStream proposals");
        action("documentation"); await(() -> frame.intelligence().documentationVisible(), "PrintStream documentation");
        check(edt(() -> frame.intelligence().lastDocumentation().owner()).contains("PrintStream"), "actual PrintStream owner");
        check(edt(() -> frame.intelligence().lastDocumentation().signature()).contains("print"), "print method signature");
        key(KeyEvent.VK_ESCAPE,0); await(() -> frame.editor().isFocusOwner(), "documentation returns to source");
        action("complete"); await(() -> frame.intelligence().alternativesVisible(), "proposals after docs");
        JList<?> choices = edt(() -> find(frame,JList.class,"completion-choices"));
        check(edt(() -> frame.intelligence().lastCompletion().proposals().stream().anyMatch(p -> p.text().startsWith("println"))), "println proposal exists");
        int target = edt(() -> { var proposals=frame.intelligence().lastCompletion().proposals(); for(int i=0;i<proposals.size();i++) if(proposals.get(i).text().equals("println()"))return i; return -1; });
        if(target<0) target=edt(() -> { var proposals=frame.intelligence().lastCompletion().proposals();for(int i=0;i<proposals.size();i++)if(proposals.get(i).text().startsWith("println"))return i;return -1; });
        while(edt(choices::getSelectedIndex)<target) action("completion.next");
        action("completion.accept"); await(() -> frame.editor().getText().contains("System.out.println("), "println accepted");
        pass("qfx05.accept", "actual PrintStream documentation/owner/signature; Escape; println proposal selected with arrows and accepted");
    }
    static void restartPersistence() throws Exception {
        edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        frame = edt(() -> { var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront(); Desktop.getDesktop().requestForeground(true); f.openPath(source); return f; });
        await(() -> source.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "reopened app");
        // Opening the settings view observes completion of the asynchronous catalog load.
        Thread.sleep(250);
        snippet("twice"); key(KeyEvent.VK_TAB,0); await(() -> frame.editor().templateActive(), "custom definition survives restart");
        key(KeyEvent.VK_ESCAPE,0); pass("live.persistence", "new TurboFrame loads saved user catalog and expands custom abbreviation");
    }
    static void selectTyped(String text) throws Exception { for (int i=0;i<text.length();i++) key(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK); }
    static void type(String text) throws Exception {
        NativeWindowTestSupport.beforeKey(frame);
        edt(() -> { var queue = Toolkit.getDefaultToolkit().getSystemEventQueue(); for(char c : text.toCharArray()) queue.postEvent(new KeyEvent(focus(),KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,c)); return null; }); edt(() -> null);
    }
}
