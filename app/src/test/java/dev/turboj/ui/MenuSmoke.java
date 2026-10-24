package dev.turboj.ui;

import dev.turboj.ui.themes.Theme;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real Swing host smoke: themed nested/overflow menus and internal-dialog combo popups. */
public final class MenuSmoke {
    private static JFrame frame;
    private static JMenu options, submenu;
    private static JComboBox<String> combo;
    private static EmbeddedDialog dialog;
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("target", "menu-smoke"); Files.createDirectories(directory);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception error) { throw new IllegalStateException(error); }
                frame = new JFrame("TurboJ menu verification"); frame.setSize(850, 610);
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                InFramePopupFactory.install(frame.getRootPane()); frame.setVisible(true);
            });
            for (String id : Theme.BUNDLED) {
                SwingUtilities.invokeAndWait(() -> menu(Theme.bundled(id)));
                Thread.sleep(120);
                SwingUtilities.invokeAndWait(() -> submenu.doClick());
                Thread.sleep(120);
                capture(directory.resolve(id + "-submenu.png"));
                SwingUtilities.invokeAndWait(() -> controls(Theme.bundled(id)));
                Thread.sleep(120);
                SwingUtilities.invokeAndWait(() -> combo.setPopupVisible(true));
                Thread.sleep(120);
                capture(directory.resolve(id + "-combo.png"));
                SwingUtilities.invokeAndWait(() -> { combo.setPopupVisible(false); dialog.dispose(); });
            }
            System.out.println("PASS: three themes, nested scrollable menus and internal-dialog combo choices; one native window only");
        } finally { if (frame != null) SwingUtilities.invokeAndWait(() -> frame.dispose()); }
    }

    private static void menu(Theme theme) {
        MenuSelectionManager.defaultManager().clearSelectedPath(); MenuStyler.install(theme);
        frame.getContentPane().removeAll(); frame.getContentPane().setBackground(theme.background());
        var code = new JTextArea("public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println(\"Hello TurboJ!\");\n    }\n}\n");
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20)); code.setEditable(false);
        code.setBackground(theme.background()); code.setForeground(theme.foreground());
        code.setBorder(BorderFactory.createEmptyBorder(28, 22, 0, 0)); frame.add(code);
        var bar = new JMenuBar(); bar.setBackground(theme.menuBackground()); options = new JMenu("Options");
        var disabled = new JMenuItem("Unavailable command"); disabled.setAccelerator(KeyStroke.getKeyStroke("meta F6")); disabled.setEnabled(false);
        options.add(disabled); options.addSeparator(); options.add(new JCheckBoxMenuItem("Automatic completion", true));
        options.add(new JRadioButtonMenuItem("Current keymap", true)); submenu = new JMenu("Themes");
        for (int i = 0; i < 35; i++) submenu.add(new JMenuItem("Theme preview " + (i + 1)));
        options.add(submenu); bar.add(options); frame.setJMenuBar(bar); MenuStyler.style(bar, theme);
        frame.revalidate(); frame.repaint(); options.doClick();
    }

    private static void controls(Theme theme) {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        OverlayHost.forOwner(frame).setTheme(theme.color("ui", "surface"), theme.color("ui", "foreground"), theme.color("ui", "border"));
        dialog = new EmbeddedDialog(frame, "AI completion", false);
        var tabs = new JTabbedPane(); var local = new JPanel(new GridLayout(0, 1, 8, 8));
        local.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        local.add(new JLabel("Provider")); combo = new JComboBox<>(new String[] {"llama.cpp (local)", "Ollama (local)", "OpenAI Responses (cloud)"}); local.add(combo);
        local.add(new JCheckBox("Automatic suggestions", true));
        var disabled = new JButton("Cloud credentials unavailable"); disabled.setEnabled(false); local.add(disabled);
        tabs.addTab("Local", local); tabs.addTab("Cloud", new JPanel()); dialog.add(tabs);
        dialog.setSize(500, 300); dialog.setVisible(true);
    }

    private static void capture(Path target) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            long windows = java.util.Arrays.stream(Window.getWindows()).filter(Window::isShowing).count();
            if (windows != 1) throw new AssertionError("Popup escaped the application: " + windows + " windows");
            var layer = frame.getLayeredPane(); Component[] popups = layer.getComponentsInLayer(JLayeredPane.POPUP_LAYER);
            if (popups.length == 0) throw new AssertionError("Popup did not open");
            for (Component popup : popups) if (!new Rectangle(0, 0, layer.getWidth(), layer.getHeight()).contains(popup.getBounds()))
                throw new AssertionError("Popup overflow: " + popup.getBounds());
            var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); frame.paintAll(graphics); graphics.dispose();
            try { ImageIO.write(image, "png", target.toFile()); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        });
    }
}
