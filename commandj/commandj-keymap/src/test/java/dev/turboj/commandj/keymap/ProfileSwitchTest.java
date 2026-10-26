package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.Command;
import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProfileSwitchTest {
    @TempDir Path temporary;

    @Test void eachBundledProfilesCustomBindingsAndExplicitUnbindingsSurviveSwitchingAndReload() throws Exception {
        var maps = new KeymapManager();
        var ids = List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos");
        var expected = new LinkedHashMap<String,Map<String,List<KeyBinding>>>();
        for (int index = 0; index < ids.size(); index++) {
            maps.select(ids.get(index));
            assertTrue(maps.replaceBindings("save", List.of(KeyBinding.editor("CTRL+ALT+SHIFT+F" + (13+index)))).applied());
            assertTrue(maps.replaceBindings("format.document", List.of()).applied());
            expected.put(maps.selectedId(), maps.bindings());
        }
        for (String id : ids.reversed()) {
            maps.select(id);
            assertEquals(expected.get(id), maps.bindings(), "switching must not reset " + id);
            assertTrue(maps.selected().overrides().containsKey("format.document"));
            assertTrue(maps.bindings("format.document").isEmpty(), "explicitly removed shortcuts stay removed");
        }
        maps.select("eclipse-macos");
        Path settings = temporary.resolve("preferences/commandj-keymap.json");
        KeymapJson.save(settings,maps);
        var loaded = new KeymapManager();
        KeymapJson.load(settings,loaded);
        assertEquals("eclipse-macos",loaded.selectedId(),"restart restores the last selected profile");
        for (String id : ids) {
            loaded.select(id);
            assertEquals(expected.get(id),loaded.bindings(),"persisted independent bindings for " + id);
        }
    }

    @Test void savedTurboJClassicSelectionMovesToTurboJMacosAndItsStoredCopyIsDropped() {
        String saved = "{\"schemaVersion\":1,\"selected\":\"turboj-classic\",\"profiles\":[{\"id\":\"turboj-classic\",\"name\":\"TurboJ Classic\","
                + "\"bindings\":{\"run\":[{\"keys\":\"CTRL+F9\",\"required\":[],\"forbidden\":[]}]}},"
                + "{\"id\":\"mine\",\"name\":\"Mine\",\"parent\":\"turboj-classic\",\"bindings\":{}}]}";
        var maps = new KeymapManager();
        KeymapJson.read(maps, saved);
        assertEquals("turboj-macos", maps.selectedId());
        assertTrue(maps.profiles().stream().noneMatch(profile -> profile.id().equals("turboj-classic")));
        assertEquals("turboj-macos", maps.profiles().stream().filter(profile -> profile.id().equals("mine")).findFirst().orElseThrow().parentId());
        assertEquals(KeySequence.parse("META+R"), maps.bindings("run").getFirst().sequence());
    }

    @Test void returningToInheritedUserProfilePreservesOverridesAndNotifiesWithResolvedState() throws Exception {
        var maps = new KeymapManager();
        Map<String,List<KeyBinding>> original = maps.bindings();
        maps.duplicate("personal-intellij","Personal IntelliJ");
        assertTrue(maps.replaceBindings("save",List.of(KeyBinding.editor("CTRL+ALT+F12"))).applied());
        assertTrue(maps.replaceBindings("format.document",List.of()).applied());
        Map<String,List<KeyBinding>> personal = maps.bindings();
        var notifications = new ArrayList<String>();
        try (var listener = maps.onChange(() -> {
            assertEquals(maps.bindingsForProfile(maps.selectedId()),maps.bindings(),"listeners see complete new state");
            notifications.add(maps.selectedId());
        })) {
            maps.select("eclipse-macos");
            maps.select("vscode-macos");
            maps.select("turboj-macos");
            maps.select("intellij-macos");
            assertEquals(original,maps.bindings(),"personal overrides do not mutate the bundled parent");
            maps.select("personal-intellij");
        }
        assertEquals(List.of("eclipse-macos","vscode-macos","turboj-macos","intellij-macos","personal-intellij"),notifications);
        assertEquals(personal,maps.bindings());
        var loaded = new KeymapManager(); KeymapJson.read(loaded,KeymapJson.write(maps));
        assertEquals("personal-intellij",loaded.selectedId());
        assertEquals("intellij-macos",loaded.selected().parentId());
        assertEquals(personal,loaded.bindings());
        loaded.select("intellij-macos"); assertEquals(original,loaded.bindings());
        loaded.select("personal-intellij"); assertEquals(personal,loaded.bindings());
    }

    @Test void sameShortcutInvokesEachProfilesActualCommandAndSwitchingDiscardsOldChord() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            var invoked = new LinkedHashMap<String,AtomicInteger>();
            for (String id : List.of("duplicateLine","deleteLine","editor.selectNextOccurrence","keymap")) {
                var count = new AtomicInteger(); invoked.put(id,count);
                registry.register(Command.action(id,id,"Test",count::incrementAndGet));
            }
            var resolver = new ShortcutResolver(maps,registry);
            var context = CommandContext.of("editor");
            var expectations = Map.of("intellij-macos","duplicateLine","eclipse-macos","deleteLine","vscode-macos","editor.selectNextOccurrence");
            for (String profile : List.of("intellij-macos","eclipse-macos","vscode-macos")) {
                maps.select(profile);
                var match = resolver.accept(KeyStrokeSpec.parse("META+D"),context,10);
                assertEquals(ShortcutResolver.Kind.MATCH,match.kind());
                assertEquals(expectations.get(profile),match.command());
                registry.execute(match.command(),context).stage().toCompletableFuture().join();
            }
            assertEquals(ShortcutResolver.Kind.PENDING,resolver.accept(KeyStrokeSpec.parse("META+K"),context,11).kind());
            maps.select("turboj-macos");
            assertEquals(ShortcutResolver.Kind.NONE,resolver.accept(KeyStrokeSpec.parse("META+D"),context,12).kind());
            assertFalse(resolver.pending(),"a profile switch clears the previous profile's pending chord");
            maps.select("intellij-macos");
            assertEquals("duplicateLine",resolver.accept(KeyStrokeSpec.parse("META+D"),context,13).command());
            assertEquals(1,invoked.get("duplicateLine").get());
            assertEquals(1,invoked.get("deleteLine").get());
            assertEquals(1,invoked.get("editor.selectNextOccurrence").get());
            assertEquals(0,invoked.get("keymap").get());
        }
    }
}
