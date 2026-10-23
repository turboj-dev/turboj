package com.turboj.app;

import com.turboj.config.Settings;

import java.nio.file.Path;

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
                    frame.setVisible(true);
                    frame.editor().requestFocusInWindow();
                    if (args.length > 0) frame.openPath(Path.of(args[0]));
                    else frame.offerRecovery();
                });
    }
}
