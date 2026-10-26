package dev.turboj.app;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuLayoutTest {
    private final List<MenuLayout.Menu> menus = MenuLayout.menus(List.of("classic", "dark"), List.of("turboj-macos", "eclipse-macos"),
            List.of("TurboJ macOS", "Eclipse macOS"));

    @Test void menusFollowTheWorkLoopLikeTurboC() {
        assertEquals(List.of("≡", "File", "Edit", "Search", "Navigate", "Code", "Refactor", "Run", "Project", "View", "Help"),
                menus.stream().map(MenuLayout.Menu::title).toList());
        assertEquals("TurboJ", menus.getFirst().accessibleName(), "the ≡ menu reads as the application menu");
    }

    @Test void everyCommandHasOneHomeAndProblemFixesStayInQuickFix() {
        var ids = MenuLayout.commandIds(menus);
        assertEquals(ids.size(), new HashSet<>(ids).size(), "a command appears in one menu only: " + ids);
        assertFalse(ids.contains("refactor.create-method") || ids.contains("refactor.add-import"), "problem-driven fixes are Quick Fix only");
        assertTrue(ids.containsAll(List.of("run", "make", "compile", "tests.current", "tests.all")), "Run holds the whole loop");
        assertTrue(ids.containsAll(List.of("java.generateToString", "settings.general", "view.toggleExplorer", "keymap.profile.turboj-macos")));
    }
}
