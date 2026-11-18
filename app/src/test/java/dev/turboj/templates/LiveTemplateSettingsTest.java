package dev.turboj.templates;

import com.google.gson.*;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveTemplateSettingsTest {
    private static final String CUSTOM = """
        {"schemaVersion":1,"extension":{"keep":true},"templates":[
          {"id":"user/log","name":"Log a value","description":"Readable debug output","abbreviation":"log",
           "body":"System.out.println($VALUE$);$END$","contexts":["JAVA_STATEMENT"],"futureProperty":42,
           "variables":[{"name":"VALUE","expression":"","defaultValue":"total","alwaysStop":true,"hint":"keep"}]},
          {"id":"user/untouched","abbreviation":"other","body":"other();","contexts":["JAVA_STATEMENT"]}]}
        """;
    @Test void legacyCatalogLoadsAndMetadataIsSearchableWithoutEnablingDisabledEntries() throws Exception {
        var model = new LiveTemplateSettings(LiveTemplateCatalog.builtins(), CUSTOM);
        assertEquals(List.of("user/log"), model.matching("debug log"));
        assertEquals(List.of("user/log"), model.matching("LOG A VALUE"));
        assertTrue(model.matching("println").contains("Java/sout"));
        assertTrue(model.matching("nothing matches at all").isEmpty());
        var entry = model.definition("user/log"); entry.addProperty("disabled", true); model.put("user/log", entry);
        assertEquals(List.of("user/log"), model.matching("debug"));
        var catalog = new LiveTemplateCatalog(LiveTemplateCatalog.builtins(), LiveTemplateCatalog.parse(model.json()));
        assertTrue(catalog.matching("log").isEmpty());
        assertEquals("sout", LiveTemplatesTest.builtin("sout").name());
    }
    @Test void editingAnOverridePreservesUnknownAndUntouchedDataAndRestoringUsesTheBuiltin() throws Exception {
        var model = new LiveTemplateSettings(LiveTemplateCatalog.builtins(), CUSTOM);
        var original = JsonParser.parseString(CUSTOM).getAsJsonObject();
        var log = model.definition("user/log"); log.addProperty("name", "My logger"); model.put("user/log", log);
        var current = JsonParser.parseString(model.json()).getAsJsonObject();
        assertEquals(original.get("extension"), current.get("extension"));
        assertEquals(original.getAsJsonArray("templates").get(1), current.getAsJsonArray("templates").get(1));
        assertEquals(42, model.definition("user/log").get("futureProperty").getAsInt());
        assertEquals("keep", model.definition("user/log").getAsJsonArray("variables").get(0).getAsJsonObject().get("hint").getAsString());
        var builtin = model.definition("Java/sout"); builtin.addProperty("disabled", true); model.put("Java/sout", builtin);
        assertTrue(model.overridden("Java/sout")); model.remove("Java/sout");
        assertFalse(model.overridden("Java/sout")); assertFalse(LiveTemplateSettings.template(model.definition("Java/sout")).disabled());
        model.remove("user/log"); assertNull(model.definition("user/log"));
    }
    @Test void duplicateGetsAStableUniqueIdentityAndInvalidJsonCannotReplaceTheDraft() throws Exception {
        var model = new LiveTemplateSettings(LiveTemplateCatalog.builtins(), CUSTOM);
        String one = model.add(model.definition("Java/sout")), two = model.add(null);
        assertNotEquals(one, two); assertEquals("user/template", one);
        var duplicate = model.definition(one); duplicate.addProperty("name", "Print"); model.put(one, duplicate);
        assertEquals(one, LiveTemplateSettings.template(model.definition(one)).id());
        String before = model.json(); assertThrows(IllegalArgumentException.class, () -> model.load("{}")); assertEquals(before, model.json());
        duplicate.addProperty("id", "other"); assertThrows(IllegalArgumentException.class, () -> model.put(one, duplicate));
    }
    @Test void invalidFieldsNeverReachTheSaverAndCancelDoesNotWrite() throws Exception {
        edt(() -> {
            var saved = new AtomicReference<String>(); var editor = new EditorComponent(); editor.setText("class C {}\n");
            var dialog = new LiveTemplateSettingsDialog(editor, List.of(), CUSTOM, (json, done) -> saved.set(json));
            find(dialog, JTextField.class, "live-template-search").setText("debug");
            find(dialog, JTextField.class, "live-template-name").setText("");
            find(dialog, JButton.class, "live-template-save").doClick(); assertNull(saved.get());
            assertTrue(find(dialog, JLabel.class, "live-template-error").getText().contains("name"));
            find(dialog, JTextField.class, "live-template-name").setText("Logger");
            find(dialog, JTextArea.class, "live-template-body").setText("$UNCLOSED");
            find(dialog, JButton.class, "live-template-save").doClick(); assertNull(saved.get());
            assertTrue(find(dialog, JLabel.class, "live-template-error").getText().contains("Unclosed"));
            find(dialog, JButton.class, "live-template-cancel").doClick(); assertTrue(dialog.isClosed()); assertNull(saved.get());
            assertEquals("class C {}\n", editor.getText());
        });
    }
    @Test void saveFreezesDraftUntilCompletionAndFailureAllowsCorrection() throws Exception {
        edt(() -> {
            var saved = new AtomicReference<String>(); var completion = new AtomicReference<Consumer<Exception>>();
            var dialog = new LiveTemplateSettingsDialog(new EditorComponent(), List.of(), CUSTOM, (json, done) -> { saved.set(json); completion.set(done); });
            find(dialog, JTextField.class, "live-template-search").setText("debug");
            find(dialog, JTextField.class, "live-template-name").setText("My logger");
            find(dialog, JButton.class, "live-template-save").doClick();
            assertNotNull(saved.get()); assertFalse(find(dialog, JTextField.class, "live-template-name").isEnabled());
            dialog.doDefaultCloseAction(); assertFalse(dialog.isClosed());
            completion.get().accept(new java.io.IOException("read-only destination"));
            assertTrue(find(dialog, JTextField.class, "live-template-name").isEnabled());
            assertTrue(find(dialog, JLabel.class, "live-template-error").getText().contains("read-only destination"));
            find(dialog, JButton.class, "live-template-save").doClick(); completion.get().accept(null); assertTrue(dialog.isClosed());
        });
    }
    @Test void corruptExistingJsonCannotBeSilentlyOverwrittenWithEmptyDefaults() throws Exception {
        edt(() -> {
            var saved = new AtomicReference<String>();
            var dialog = new LiveTemplateSettingsDialog(new EditorComponent(), List.of(), "{broken", (json, done) -> saved.set(json));
            assertFalse(find(dialog, JButton.class, "live-template-save").isEnabled());
            find(dialog, JButton.class, "live-template-save").doClick(); assertNull(saved.get()); dialog.dispose();
        });
    }
    @Test void switchingAndFilteringPreserveUnsavedDraftsAndDoNotCreateUnrequestedOverrides() throws Exception {
        var defaults = LiveTemplateCatalog.builtins();
        edt(() -> {
            var saved = new AtomicReference<String>();
            var dialog = new LiveTemplateSettingsDialog(new EditorComponent(), defaults, CUSTOM, (json, done) -> { saved.set(json); done.accept(null); });
            var filter = find(dialog, JTextField.class, "live-template-search");
            filter.setText("debug"); find(dialog, JTextField.class, "live-template-name").setText("Changed name");
            filter.setText("no matches at all"); assertFalse(find(dialog, JTextField.class, "live-template-name").isEnabled());
            filter.setText("sout"); filter.setText("Changed name");
            assertEquals("Changed name", find(dialog, JTextField.class, "live-template-name").getText());
            find(dialog, JButton.class, "live-template-save").doClick();
            var users = LiveTemplateCatalog.parse(saved.get()); assertEquals(2, users.size()); assertEquals("Changed name", users.getFirst().name());
        });
    }
    @Test void previewUsesRealExpansionKeepsSourceUntouchedAndTracksFontThemeAtBothScales() throws Exception {
        var t = LiveTemplatesTest.builtin("sout");
        edt(() -> {
            var editor = new EditorComponent(); editor.setText("class Unchanged {}");
            try (var preview = new LiveTemplatePreview(editor)) {
                for (String theme : List.of("classic", "vscode-monokai")) for (int size : List.of(10, 16, 48)) {
                    editor.setTheme(Theme.bundled(theme)); editor.setFontSize(size); preview.showTemplate(t);
                    assertTrue(preview.code.isReadOnly()); assertEquals(editor.theme(), preview.code.theme());
                    assertEquals(size, preview.code.getFont().getSize()); assertEquals("System.out.println();", preview.code.getText());
                    assertTrue(preview.code.getAccessibleContext().getAccessibleDescription().contains("END 1:20"));
                    preview.code.replaceSelection("forbidden"); assertEquals("System.out.println();", preview.code.getText());
                    preview.setSize(640, 220); layout(preview);
                    for (int scale : List.of(1, 2)) {
                        var image = new BufferedImage(640 * scale, 220 * scale, BufferedImage.TYPE_INT_ARGB);
                        var g = image.createGraphics(); g.scale(scale, scale); preview.paint(g); g.dispose();
                    }
                }
                assertEquals("class Unchanged {}", editor.getText()); preview.clear("invalid"); assertTrue(preview.code.isReadOnly());
            }
        });
    }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static <T extends Component> T find(Container c, Class<T> type, String name) {
        for (Component child : c.getComponents()) { if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) { T result = findOrNull(nested, type, name); if (result != null) return result; } }
        throw new AssertionError("Missing " + name);
    }
    private static <T extends Component> T findOrNull(Container c, Class<T> type, String name) {
        for (Component child : c.getComponents()) { if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) { T result = findOrNull(nested, type, name); if (result != null) return result; } }
        return null;
    }
    private static void edt(Runnable action) throws Exception {
        var failure = new AtomicReference<Throwable>(); SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable ex) { failure.set(ex); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
