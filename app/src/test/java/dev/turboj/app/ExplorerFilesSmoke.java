package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.project.ProjectFiles;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * Real window, plain Java project: the explorer's file operations. Java classes and packages are renamed, moved and
 * safely deleted by RefactorJ, so the code still compiles; other files and folders move and are deleted as one
 * transaction; ⌘Z takes each one back.
 */
public final class ExplorerFilesSmoke {
    private static TurboFrame frame;
    private static final int CMD = KeyEvent.META_DOWN_MASK;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-explorer-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("shop"));
        Path src = Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=Shop\n");
        Path model = Files.createDirectories(src.resolve("com/shop/model"));
        Path app = Files.createDirectories(src.resolve("com/shop/app"));
        Files.writeString(model.resolve("Cart.java"), "package com.shop.model;\n\npublic class Cart {\n    public int items() { return 0; }\n}\n");
        Files.writeString(model.resolve("Unused.java"), "package com.shop.model;\n\npublic class Unused {}\n");
        Files.writeString(app.resolve("Main.java"), "package com.shop.app;\n\nimport com.shop.model.Cart;\n\npublic class Main {\n"
                + "    public static void main(String[] args) { System.out.println(new Cart().items()); }\n}\n");
        Path config = Files.createDirectories(project.resolve("config"));
        Files.writeString(config.resolve("shop.properties"), "currency=EUR\n");
        try {
            frame = edt(() -> { var f = new TurboFrame(new Settings(), Map.of()); f.setVisible(true); f.toFront(); return f; });
            edt(() -> { frame.openPath(project); return null; });
            await("plain project", () -> frame.projectStatus().contains("shop"));
            edt(() -> { frame.openPath(app.resolve("Main.java")); return null; });
            await("Main open", () -> app.resolve("Main.java").equals(frame.currentFile()));

            // The context menu: what it offers for a Java file, a package and a plain folder.
            check(menu(model.resolve("Cart.java"), false).equals("New ▸ [Java Class…, Package…, Folder…, File…] | Rename… Move… Safe Delete… | Copy Path Reveal in Finder"),
                    "Java file menu: " + menu(model.resolve("Cart.java"), false));
            check(menu(model, true).contains("Safe Delete…"), "package menu offers Safe Delete");
            check(menu(config, true).startsWith("New ▸ [Folder…, File…] | Rename… Move… Delete…"), "folder menu: " + menu(config, true));
            check(!menu(src, true).contains("Rename…"), "a source root cannot be renamed from the explorer");

            // Rename a class: the file, the declaration and the import in Main follow.
            act(() -> frame.explorerActions().rename(node(model.resolve("Cart.java"), false), "Basket", true));
            await("Cart renamed", () -> Files.exists(model.resolve("Basket.java")) && !Files.exists(model.resolve("Cart.java"))
                    && text(app.resolve("Main.java")).contains("new Basket().items()"));
            check(text(app.resolve("Main.java")).contains("import com.shop.model.Basket;"), "the import follows the rename");
            check(Files.readString(model.resolve("Basket.java")).contains("public class Basket"), "the declaration is renamed");
            System.out.println("PASS rename class: Cart.java → Basket.java, Main updated");

            // Move it to another package.
            act(() -> frame.explorerActions().moveToPackage(node(model.resolve("Basket.java"), false), "com.shop.cart"));
            Path moved = src.resolve("com/shop/cart/Basket.java");
            await("Basket moved", () -> Files.exists(moved) && text(app.resolve("Main.java")).contains("import com.shop.cart.Basket;"));
            check(text(moved).startsWith("package com.shop.cart;"), "the package declaration follows the move: [" + text(moved) + "] disk ["
                    + Files.readString(moved) + "]");
            System.out.println("PASS move class: com.shop.model.Basket → com.shop.cart.Basket");

            // Safe Delete refuses a class still in use, and deletes an unused one; ⌘Z brings it back.
            act(() -> frame.explorerActions().delete(node(moved, false), true));
            await("refusal", () -> frame.statusMessages().contains("reference"));
            check(Files.exists(moved), "a class still in use is not deleted: " + lastMessage());
            System.out.println("PASS safe delete refused: " + lastMessage());
            act(() -> frame.explorerActions().delete(node(model.resolve("Unused.java"), false), true));
            await("Unused deleted", () -> !Files.exists(model.resolve("Unused.java")));
            undo();
            await("Unused restored", () -> Files.exists(model.resolve("Unused.java")));
            System.out.println("PASS safe delete of an unused class, ⌘Z restores it");

            // Rename a package that holds no class itself: every package tree below it follows.
            act(() -> frame.explorerActions().rename(node(src.resolve("com/shop"), true), "org.store", true));
            await("packages renamed", () -> Files.exists(src.resolve("org/store/app/Main.java")) && Files.exists(src.resolve("org/store/cart/Basket.java"))
                    && Files.exists(src.resolve("org/store/model/Unused.java")));
            check(text(src.resolve("org/store/app/Main.java")).contains("package org.store.app;")
                    && text(src.resolve("org/store/app/Main.java")).contains("import org.store.cart.Basket;"), "declarations and imports follow");
            check(!Files.exists(src.resolve("com")), "the old folders are gone");
            System.out.println("PASS rename package com.shop → org.store across 3 package trees");

            // New package, folder and file.
            act(() -> frame.explorerActions().newPackage(node(src, true), "org.store.util"));
            await("package created", () -> Files.isDirectory(src.resolve("org/store/util")));
            act(() -> frame.explorerActions().newFolder(node(project, true), "docs"));
            await("folder created", () -> Files.isDirectory(project.resolve("docs")));
            act(() -> frame.explorerActions().newFile(node(project.resolve("docs"), true), "notes.md"));
            await("file created and opened", () -> project.resolve("docs/notes.md").equals(frame.currentFile()));
            System.out.println("PASS new package, folder and file");

            // Plain folder: rename, move, delete, each undone with ⌘Z.
            act(() -> frame.explorerActions().rename(node(config, true), "settings", true));
            await("folder renamed", () -> Files.exists(project.resolve("settings/shop.properties")) && !Files.exists(config));
            undo();
            await("rename undone", () -> Files.exists(config.resolve("shop.properties")) && !Files.exists(project.resolve("settings/shop.properties")));
            act(() -> frame.explorerActions().moveToFolder(node(config, true), project.resolve("docs"), true));
            await("folder moved", () -> Files.exists(project.resolve("docs/config/shop.properties")));
            act(() -> frame.explorerActions().delete(node(project.resolve("docs/config"), true), true));
            await("folder deleted", () -> !Files.exists(project.resolve("docs/config")));
            undo();
            await("delete undone", () -> Files.exists(project.resolve("docs/config/shop.properties")));
            check(Files.readString(project.resolve("docs/config/shop.properties")).equals("currency=EUR\n"), "content restored exactly");
            System.out.println("PASS folder rename, move, delete; ⌘Z restores each");

            // The code still compiles and runs after all of it.
            edt(() -> { frame.openPath(src.resolve("org/store/app/Main.java")); return null; });
            await("Main reopened", () -> src.resolve("org/store/app/Main.java").equals(frame.currentFile()));
            edt(() -> { frame.editor().requestFocusInWindow(); return null; });
            await("editor focus for Run", () -> frame.editor().isFocusOwner());
            edt(() -> { frame.runCommand("file.saveAll"); return null; });
            await("everything saved", () -> frame.unsavedFiles().isEmpty());
            key(KeyEvent.VK_R, CMD);
            await("program runs", () -> frame.console().text().contains("0") && frame.statusMessages().contains(" done · exit 0"));
            System.out.println("PASS the project still compiles and runs: " + frame.console().text().strip());
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("Status history:\n" + edt(() -> frame.statusMessages()));
            System.exit(1);
        }
    }

    private static ProjectFiles.Node node(Path path, boolean directory) throws Exception {
        return edt(() -> frame.explorerActions().node(path, directory));
    }

    /** The menu's items, as text: submenus in brackets, separators as |. */
    private static String menu(Path path, boolean directory) throws Exception {
        return edt(() -> {
            frame.explorerActions().showMenu(path, directory, frame.getContentPane(), 10, 10);
            var popup = findPopup();
            var out = new StringBuilder();
            for (var component : popup.getComponents()) {
                if (component instanceof javax.swing.JSeparator) { out.append(" |"); continue; }
                if (component instanceof javax.swing.JMenu submenu) {
                    var items = new java.util.ArrayList<String>();
                    for (var child : submenu.getMenuComponents()) if (child instanceof javax.swing.JMenuItem item) items.add(item.getText());
                    out.append(out.isEmpty() ? "" : " ").append(submenu.getText()).append(" ▸ ").append(items);
                } else if (component instanceof javax.swing.JMenuItem item) out.append(out.isEmpty() ? "" : " ").append(item.getText());
            }
            popup.setVisible(false);
            return out.toString().replace("  ", " ");
        });
    }

    private static JPopupMenu findPopup() {
        for (var window : java.awt.Window.getWindows())
            for (var component : allComponents(window))
                if (component instanceof JPopupMenu popup && popup.isVisible() && "explorer-menu".equals(popup.getName())) return popup;
        throw new AssertionError("No explorer menu is showing");
    }

    private static java.util.List<java.awt.Component> allComponents(java.awt.Container container) {
        var all = new java.util.ArrayList<java.awt.Component>();
        for (var child : container.getComponents()) {
            all.add(child);
            if (child instanceof java.awt.Container inner) all.addAll(allComponents(inner));
        }
        return all;
    }

    private static String text(Path file) throws Exception {
        return edt(() -> frame.bufferText(file).isEmpty() ? Files.readString(file) : frame.bufferText(file));
    }

    private static String lastMessage() throws Exception { return edt(() -> frame.statusBar().lastMessage()); }

    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static void act(Action action) throws Exception { edt(() -> { action.run(); return null; }); }

    private static void undo() throws Exception {
        edt(() -> { frame.editor().requestFocusInWindow(); return null; });
        await("editor focus", () -> frame.editor().isFocusOwner());
        key(KeyEvent.VK_Z, CMD);
    }

    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(frame.editor(), KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(200);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("PASS " + message.split(":")[0]);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) {
            if (edt(condition)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last message: " + lastMessage() + "; dialogs "
                + edt(() -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream().map(d -> d.getTitle()).toList())
                + "; output [" + edt(() -> frame.console().text()) + "]");
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return task.call();
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
