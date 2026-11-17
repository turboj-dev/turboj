package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.find;
import dev.turboj.config.Settings;
import dev.turboj.ui.EditorComponent.InlineCompletion;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Real typing, CommandJ and asynchronous template context. AI text is an explicit deterministic ghost fixture. */
public final class LiveTemplatePrecedenceSmoke {
    public static void main(String[] args) throws Exception {
        output = Files.createDirectories(Path.of("app/target/live-template-precedence"));
        work = Files.createTempDirectory("turboj-template-precedence-").toRealPath();
        System.setProperty("turboj.config", work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path source = Files.writeString(work.resolve("C.java"), "class C {}\n");
        settings = new Settings(); settings.theme = "monokai";
        frame = edt(() -> { var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront(); Desktop.getDesktop().requestForeground(true); f.openPath(source); return f; });
        try {
            await(() -> source.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "source");
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
                action("keymap.profile." + profile);
                emptyStatement(); LiveTemplateWorkflowSmoke.type("sout");
                ghost("AI_CHOSEN"); key(KeyEvent.VK_TAB, 0);
                await(() -> frame.editor().getText().contains("System.out.println();"), "template wins Tab in " + profile);
                check(!edt(() -> frame.editor().getText()).contains("AI_CHOSEN"), "AI text was not accepted");
                check(edt(() -> frame.editor().getText().substring(0, frame.editor().caretOffset())).endsWith("println("), "template exit caret");
                action("undo"); await(() -> frame.editor().getText().contains("sout"), "expansion undo");
                pass("template.ai-precedence", profile + ": typed exact abbreviation + visible AI ghost + Tab expands template, correct caret and Undo");
            }
            emptyStatement(); LiveTemplateWorkflowSmoke.type("sou");
            await(LiveTemplatePrecedenceSmoke::popup, "matching templates while typing");
            JList<?> list = edt(() -> find(frame, JList.class, "live-template-suggestion-list"));
            check(edt(() -> frame.editor().isFocusOwner()), "list does not steal editor focus");
            check(edt(() -> list.getModel().getSize()) >= 3, "multiple applicable prefix matches");
            int selected = edt(list::getSelectedIndex); key(KeyEvent.VK_DOWN, 0);
            await(() -> list.getSelectedIndex() == selected + 1, "Down selects next template");
            key(KeyEvent.VK_UP, 0); await(() -> list.getSelectedIndex() == selected, "Up selects previous template");
            String before = edt(() -> frame.editor().getText()); key(KeyEvent.VK_ESCAPE, 0);
            await(() -> !popup(), "Escape closes list"); Thread.sleep(250);
            check(edt(() -> frame.editor().getText()).equals(before) && !edt(LiveTemplatePrecedenceSmoke::popup), "Escape preserves source and stays closed");
            LiveTemplateWorkflowSmoke.type("t"); await(LiveTemplatePrecedenceSmoke::popup, "list refilters after typing");
            check(edt(() -> list.getSelectedValue().toString()).startsWith("sout —"), "exact match ranked first");
            key(KeyEvent.VK_ENTER, 0); await(() -> frame.editor().getText().contains("System.out.println();"), "Enter expands selected template");
            pass("template.prefix-list", "Monokai; actual typing filters applicable definitions; arrows/Enter/Escape; no focus theft or extra newline");

            String comment = "class C { void f(){ // sout\n} }\n";
            int at = comment.indexOf("sout") + 4; fixture(comment, at, at); ghost(" AI comment"); key(KeyEvent.VK_TAB, 0);
            await(() -> frame.editor().getText().contains("sout AI comment"), "AI still accepted in unsupported template context");
            check(!edt(() -> frame.editor().getText()).contains("println"), "no template in comment");
            emptyStatement(); LiveTemplateWorkflowSmoke.type("xyz"); ghost("AI_FALLBACK"); key(KeyEvent.VK_TAB, 0);
            await(() -> frame.editor().getText().contains("xyzAI_FALLBACK"), "ordinary AI acceptance preserved");
            pass("template.ai-fallback", "same abbreviation in a comment falls back to AI; unmatched source still accepts AI with Tab");

            emptyStatement(); LiveTemplateWorkflowSmoke.type("sout"); await(LiveTemplatePrecedenceSmoke::popup, "focus fixture");
            action("view.focusConsole"); await(() -> !popup(), "panel focus dismisses templates");
            action("view.focusEditor"); Thread.sleep(250); check(!edt(LiveTemplatePrecedenceSmoke::popup), "focus return does not revive stale list");
            pass("template.focus", "tool panel shortcut closes popup; returning preserves normal focus without stale results");
        } finally {
            Files.writeString(output.resolve("effects.tsv"), report);
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }
    static void emptyStatement() throws Exception {
        String code = "class C { void f(){\n    \n} }\n"; int at = code.indexOf("    ") + 4; fixture(code, at, at);
    }
    static boolean popup() { var p = find(frame, JComponent.class, "live-template-suggestions"); return p != null && p.isShowing(); }
    static void ghost(String text) throws Exception {
        edt(() -> {
            int at = frame.editor().caretOffset();
            check(frame.editor().setAIInlineCompletion(new InlineCompletion(frame.editor().version(), at, at, at, text, text.length())), "fixture AI ghost visible");
            return null;
        });
    }
}
