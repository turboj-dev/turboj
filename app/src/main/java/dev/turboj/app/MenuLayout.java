package dev.turboj.app;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu bar as data (docs/menus-turboj.md): menus follow the work loop, as in Turbo C, and every item is a registry
 * command, so menus, Find Action, the keymap and the cheat sheet agree. Problem-driven operations stay in Quick Fix.
 */
final class MenuLayout {
    sealed interface Item permits Command, Separator, Submenu {}
    enum Kind { PLAIN, CHECK, RADIO }
    /** A command; RADIO items of the same {@code group} exclude each other. */
    record Command(String id, Kind kind, String group, String text) implements Item {}
    record Separator() implements Item {}
    record Submenu(String title, List<Item> items) implements Item {}
    record Menu(String title, String accessibleName, char mnemonic, List<Item> items) {}

    static final String THEMES = "themes", PROFILES = "profiles";

    private MenuLayout() {}

    /** The bar for these themes and keyboard profiles (ids, then display names for profiles). */
    static List<Menu> menus(List<String> themes, List<String> profileIds, List<String> profileNames) {
        var themeItems = new ArrayList<Item>();
        for (String theme : themes) themeItems.add(radio(THEMES, "theme." + theme));
        themeItems.add(SEPARATOR); themeItems.add(c("theme.load")); themeItems.add(c("theme.more"));
        var profileItems = new ArrayList<Item>();
        for (int i = 0; i < profileIds.size(); i++)
            profileItems.add(new Command("keymap.profile." + profileIds.get(i), Kind.RADIO, PROFILES, profileNames.get(i)));
        return List.of(
                // Turbo C's ≡ menu, and the Mac application menu.
                new Menu("≡", "TurboJ", '\0', List.of(
                        c("metrics"), SEPARATOR,
                        c("settings.general"), c("keymap"), new Submenu("Keyboard Profile", profileItems),
                        new Submenu("Themes", themeItems),
                        new Submenu("AI Completion", items(c("ai.disabled"), c("ai.local"), c("ai.cloud"), SEPARATOR, c("ai.settings"))),
                        SEPARATOR, c("quit"))),
                new Menu("File", "File", 'F', List.of(
                        new Submenu("New", items(c("file.newJavaClass"), c("file.newFile"), SEPARATOR,
                                c("file.newJavaProject"), c("file.newMavenProject"))),
                        c("open"), c("openFolder"), c("workspace.select"), SEPARATOR,
                        c("save"), c("saveAs"), c("file.saveAll"), SEPARATOR,
                        c("file.close"), c("file.reopenClosed"))),
                new Menu("Edit", "Edit", 'E', List.of(
                        c("undo"), c("redo"), SEPARATOR,
                        c("cut"), c("copy"), c("paste"), c("selectAll"), SEPARATOR,
                        c("comment"), c("editor.toggleBlockComment"), SEPARATOR,
                        new Submenu("Line", items(c("duplicateLine"), c("deleteLine"), c("moveLineUp"), c("moveLineDown"),
                                c("editor.copyLineUp"), c("editor.copyLineDown"), SEPARATOR, c("editor.insertLineBelow"),
                                c("editor.insertLineAbove"), c("editor.joinLines"), c("editor.splitLine"), c("editor.sortSelectedLines"),
                                c("editor.reverseSelectedLines"), c("editor.trimTrailingWhitespace"))),
                        new Submenu("Selection", items(c("editor.expandSelection"), c("editor.shrinkSelection"), SEPARATOR,
                                c("editor.selectNextOccurrence"), c("editor.selectAllOccurrences"), c("editor.toggleColumnSelection"),
                                c("editor.addCaretAbove"), c("editor.addCaretBelow"), c("editor.removeSecondaryCarets"))),
                        new Submenu("Case and Spacing", items(c("editor.changeCase"), c("editor.upperCase"), c("editor.lowerCase"),
                                SEPARATOR, c("editor.convertTabsToSpaces"), c("editor.convertSpacesToTabs"))),
                        c("indent"), c("unindent"))),
                new Menu("Search", "Search", 'S', List.of(
                        c("find"), c("findNext"), c("findPrevious"), c("replace"), SEPARATOR, c("goto"))),
                new Menu("Navigate", "Navigate", 'N', List.of(
                        c("navigation.searchEverywhere"), c("navigation.goToType"), c("navigation.goToFile"), c("navigation.goToSymbol"),
                        SEPARATOR,
                        c("declaration"), c("navigation.goToImplementation"), c("navigation.findReferences"),
                        c("navigation.typeHierarchy"), c("navigation.callHierarchy"), c("navigation.goToTest"), c("navigation.testFailure"),
                        SEPARATOR,
                        c("back"), c("forward"), c("navigation.previousEditLocation"), c("navigation.recentFiles"),
                        c("navigation.recentLocations"), SEPARATOR,
                        c("navigation.nextError"), c("navigation.previousError"), c("navigation.nextMethod"),
                        c("navigation.previousMethod"), c("navigation.matchingBrace"))),
                new Menu("Code", "Code", 'C', List.of(
                        c("smart.action"), c("complete"), c("completion.smart"), c("java.completeStatement"), SEPARATOR,
                        c("refactor.context-actions"), c("documentation"), c("completion.parameterInformation"), SEPARATOR,
                        new Submenu("Generate", items(c("java.generateConstructor"), c("java.generateGetters"), c("java.generateSetters"),
                                c("java.generateGetterSetter"), SEPARATOR, c("java.generateToString"), c("java.generateEquals"),
                                c("java.generateHashCode"), SEPARATOR, c("java.implementMethods"), c("java.overrideMethods"),
                                c("java.generateJavadoc"))),
                        new Submenu("Surround With", items(c("java.surroundIf"), c("java.surroundTryFinally"))),
                        SEPARATOR,
                        c("format.document"), c("format.selection"), c("organizeImports"), check("format.onSave"),
                        new Submenu("Formatter", formatters()), SEPARATOR,
                        new Submenu("AI", items(c("ai.request"), c("ai.another"), c("ai.larger"))))),
                new Menu("Refactor", "Refactor", 'T', List.of(
                        c("refactor.this"), SEPARATOR,
                        c("refactor.rename"), c("refactor.change-signature"), c("refactor.move"), c("refactor.safe-delete"), SEPARATOR,
                        new Submenu("Extract", refactors("extract-method", "extract-variable", "extract-constant", "extract-field",
                                "extract-parameter", "extract-interface")),
                        new Submenu("Inline", items(c("refactor.inline"), c("refactor.inline-variable"), c("refactor.inline-method"))),
                        new Submenu("Hierarchy", refactors("introduce-interface", "pull-up", "push-down")),
                        new Submenu("Encapsulate", refactors("encapsulate-field", "constructor-to-factory")),
                        new Submenu("Modernize", refactors("anonymous-to-lambda", "lambda-to-method-reference",
                                "method-reference-to-lambda", "switch-to-expression", "type-to-var", "var-to-type")))),
                // Turbo C's Run and Compile, and Xcode's Product: the whole loop in one place.
                new Menu("Run", "Run", 'R', List.of(
                        c("run"), c("isolated"), c("run.configuration"), c("stop"), c("eof"), SEPARATOR,
                        c("make"), c("compile"), c("compileProject"), c("error"), SEPARATOR,
                        c("tests.current"), c("tests.class"), c("tests.all"), c("tests.failed"), c("tests.stop"), c("tests.results"),
                        SEPARATOR,
                        check("run.onSave"), check("tests.onSave"), check("tests.auto"), SEPARATOR,
                        new Submenu("Java REPL", items(c("repl"), c("replReset"), c("evaluateSelection"), c("evaluateStatement"))),
                        c("console.commandLine"), c("console.cancelCommand"), c("output"), c("clear"), check("run.clearOutputBefore"))),
                new Menu("Project", "Project", 'P', List.of(
                        c("project.open"), c("project.reload"), c("project.findFile"), c("project.findModule"), SEPARATOR,
                        c("project.structure"), c("project.dependencies"), c("project.dependencyTree"), c("project.profiles"),
                        c("project.settings"), SEPARATOR,
                        new Submenu("POM", items(c("project.effectivePom"), c("project.parentPom"), c("project.pomDeclaration"), SEPARATOR,
                                c("project.pom.add-dependency"), c("project.pom.update-dependency-version"),
                                c("project.pom.remove-dependency"), c("project.pom.add-module"))),
                        new Submenu("Maven", items(c("maven.build.validate"), c("maven.build.compile"), c("maven.build.test"),
                                c("maven.build.package"), c("maven.build.verify"), c("maven.build.install"), c("maven.build.clean"),
                                SEPARATOR, c("maven.build.dependency:tree"), c("maven.build.help:effective-pom"), c("maven.build.custom"),
                                SEPARATOR, c("maven.refresh"), c("maven.stop"))),
                        SEPARATOR, c("project.sources"), c("project.javadoc"), c("project.processors"))),
                new Menu("View", "View", 'V', List.of(
                        c("view.toggleExplorer"), c("view.toggleConsole"), c("view.toggleTestPanel"), SEPARATOR,
                        c("view.focusEditor"), c("view.focusExplorer"), c("view.focusConsole"), c("view.focusDiagnostics"),
                        c("view.focusTestResults"), c("view.focusJShell"), c("view.focusNextPanel"), c("view.focusPreviousPanel"),
                        SEPARATOR,
                        c("zoom"), c("view.maximizeEditor"), c("view.restoreLayout"), c("view.resizeOutput"), c("fullscreen"), SEPARATOR,
                        c("fontUp"), c("fontDown"), check("showPressedShortcuts"), check("settings.shortcutSymbols"))),
                new Menu("Help", "Help", 'H', List.of(
                        c("palette"), c("help"), c("help.cheatSheet"))));
    }

    /** Every command id the layout places, in menu order. */
    static List<String> commandIds(List<Menu> menus) {
        var ids = new ArrayList<String>();
        for (Menu menu : menus) collect(menu.items(), ids);
        return ids;
    }

    private static void collect(List<Item> items, List<String> ids) {
        for (Item item : items) {
            if (item instanceof Command command) ids.add(command.id());
            else if (item instanceof Submenu submenu) collect(submenu.items(), ids);
        }
    }

    private static final Separator SEPARATOR = new Separator();
    private static Command c(String id) { return new Command(id, Kind.PLAIN, null, null); }
    private static Command check(String id) { return new Command(id, Kind.CHECK, null, null); }
    private static Command radio(String group, String id) { return new Command(id, Kind.RADIO, group, null); }
    private static List<Item> items(Item... items) { return List.of(items); }

    /** The standard Java styles, one chosen at a time. */
    private static List<Item> formatters() {
        var items = new ArrayList<Item>();
        // google-java-format's presets, then the other engines; engines on hold are not offered.
        var group = dev.turboj.formatting.StandardFormatters.Group.GOOGLE_JAVA_FORMAT;
        for (var style : dev.turboj.formatting.StandardFormatters.values()) {
            if (!style.offered()) continue;
            if (style.group() != group) { items.add(SEPARATOR); group = style.group(); }
            items.add(radio("formatters", "format.style." + style.id()));
        }
        return items;
    }

    private static List<Item> refactors(String... operations) {
        var items = new ArrayList<Item>();
        for (String operation : operations) items.add(c("refactor." + operation));
        return items;
    }
}
