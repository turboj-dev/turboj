package dev.turboj.app;

import dev.turboj.config.Settings;

import java.awt.Image;
import java.awt.Taskbar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;

public final class TurboJ {
    public static final long START_NANOS = System.nanoTime();

    public static void main(String[] args) {
        if (Runtime.version().feature() < 25) {
            System.err.println("TurboJ requires Java 25 or newer.");
            System.exit(2);
        }
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        System.setProperty("apple.awt.application.name", "TurboJ");
        Settings settings = Settings.load(); // File I/O before entering the EDT.
        var keymap = Settings.keymap();
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                    } catch (Exception ignored) {
                    }
                    var frame = new TurboFrame(settings, keymap);
                    var icons = icons();
                    frame.setIconImages(icons);
                    // A packaged app gets its Dock icon from TurboJ.icns; java -jar would show Java's otherwise.
                    if (!icons.isEmpty() && Taskbar.isTaskbarSupported()
                            && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                        Taskbar.getTaskbar().setIconImage(icons.getLast());
                    frame.setVisible(true);
                    frame.editor().requestFocusInWindow();
                    if (args.length > 0) frame.openPath(Path.of(args[0]));
                    else { frame.offerRecovery(); frame.startFirstLaunch(); }
                });
    }

    /** The app icon in every bundled size, smallest first (scripts/icons.sh writes them). */
    public static List<Image> icons() {
        var images = new ArrayList<Image>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256, 512, 1024}) {
            var url = TurboJ.class.getResource("icons/turboj-" + size + ".png");
            if (url != null) images.add(new javax.swing.ImageIcon(url).getImage());
        }
        return images;
    }
}
