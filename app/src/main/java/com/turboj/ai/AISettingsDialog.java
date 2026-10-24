package com.turboj.ai;

import com.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.event.*;

/** Explicit settings transaction; networking and Keychain work never execute on the EDT. */
public final class AISettingsDialog extends JDialog {
    private final AIConfig original;
    private final CredentialStore credentials;
    private final Consumer<AIConfig> save;
    private final Consumer<String> notice;
    private final JComboBox<AIConfig.Mode> mode = new JComboBox<>(AIConfig.Mode.values());
    private final JComboBox<AIConfig.LocalBackend> backend =
            new JComboBox<>(AIConfig.LocalBackend.values());
    private final JTextField localEndpoint = new JTextField(), localModel = new JTextField();
    private final JTextField cloudEndpoint = new JTextField(), cloudModel = new JTextField();
    private final JPasswordField key = new JPasswordField();
    private final JCheckBox automaticLocal = new JCheckBox("Automatic local suggestions");
    private final JCheckBox automaticCloud =
            new JCheckBox("Automatic cloud suggestions (sends source after inactivity)");
    private final JCheckBox cloudConsent =
            new JCheckBox("I allow bounded Java source context to be sent to this endpoint.");
    private final JCheckBox deleteKey =
            new JCheckBox("Remove this endpoint's stored key when applying");
    private final JCheckBox diagnostics =
            new JCheckBox("Keep local AI timing and acceptance diagnostics (no telemetry)");
    private final JSpinner delay, timeout, maxTokens, contextChars;
    private final JTextArea exclusions = new JTextArea(5, 30);
    private final JLabel result = new JLabel("AI is optional. No model is downloaded by TurboJ.");
    private final JButton test = new JButton("Test connection"),
            unload = new JButton("Unload local model");
    private final JButton apply = new JButton("Apply"), cancel = new JButton("Cancel");
    private final JTabbedPane tabs = new JTabbedPane();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "turboj-ai-settings");
                        t.setDaemon(true);
                        return t;
                    });
    private Cancellation active;
    private long operation;
    private boolean busy, saving, closed;

    public AISettingsDialog(
            JFrame owner,
            AIConfig initial,
            CredentialStore credentials,
            Consumer<AIConfig> save,
            Consumer<String> notice) {
        super(owner, "AI Completion", ModalityType.DOCUMENT_MODAL);
        original = Objects.requireNonNull(initial).normalized();
        this.credentials = Objects.requireNonNull(credentials);
        this.save = Objects.requireNonNull(save);
        this.notice = notice == null ? value -> {} : notice;
        setName("ai-settings");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        delay = spinner(original.delayMillis(), 150, 2_000, 25, "ai-delay");
        timeout = spinner(original.timeoutSeconds(), 1, 120, 1, "ai-timeout");
        maxTokens = spinner(original.maxTokens(), 16, 2_048, 16, "ai-max-tokens");
        contextChars = spinner(original.contextChars(), 1_024, 32_768, 1_024, "ai-context-chars");
        mode.setSelectedItem(original.mode());
        backend.setSelectedItem(original.localBackend());
        localEndpoint.setText(original.localEndpoint());
        localModel.setText(original.localModel());
        cloudEndpoint.setText(original.cloudEndpoint());
        cloudModel.setText(original.cloudModel());
        automaticLocal.setSelected(original.automaticLocal());
        automaticCloud.setSelected(original.automaticCloud());
        cloudConsent.setSelected(original.cloudConsent());
        diagnostics.setSelected(original.diagnostics());
        exclusions.setText(original.exclusions());
        name(mode, "ai-mode");
        name(backend, "ai-backend");
        name(localEndpoint, "ai-local-endpoint");
        name(localModel, "ai-local-model");
        name(cloudEndpoint, "ai-cloud-endpoint");
        name(cloudModel, "ai-cloud-model");
        name(key, "ai-api-key");
        name(cloudConsent, "ai-cloud-consent");
        name(automaticCloud, "ai-cloud-automatic");
        name(automaticLocal, "ai-local-automatic");
        name(deleteKey, "ai-delete-key");
        name(exclusions, "ai-exclusions");
        name(diagnostics, "ai-diagnostics");
        name(result, "ai-settings-status");
        result.putClientProperty("html.disable", Boolean.TRUE);
        name(test, "ai-test");
        name(unload, "ai-unload");
        name(apply, "ai-apply");
        name(cancel, "ai-cancel");
        key.setToolTipText("Leave empty to keep the existing endpoint-bound Keychain credential.");
        key.getAccessibleContext()
                .setAccessibleDescription(
                        "Stored only in macOS Keychain. Existing keys are never shown.");
        var body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(body);
        var top = new JPanel(new BorderLayout(8, 0));
        JLabel modeLabel = new JLabel("AI Completion");
        modeLabel.setLabelFor(mode);
        top.add(modeLabel, BorderLayout.WEST);
        top.add(mode, BorderLayout.CENTER);
        body.add(top, BorderLayout.NORTH);
        tabs.addTab("Local", localPanel());
        tabs.addTab("Cloud", cloudPanel());
        tabs.addTab("Behavior & privacy", behaviorPanel());
        body.add(tabs, BorderLayout.CENTER);
        var bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(result, BorderLayout.NORTH);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        for (JButton button : new JButton[] {test, unload, cancel, apply}) buttons.add(button);
        bottom.add(buttons, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);
        apply.addActionListener(e -> apply());
        cancel.addActionListener(e -> close());
        test.addActionListener(e -> test(false));
        unload.addActionListener(e -> test(true));
        mode.addActionListener(
                e -> {
                    if (mode.getSelectedItem() == AIConfig.Mode.LOCAL) tabs.setSelectedIndex(0);
                    if (mode.getSelectedItem() == AIConfig.Mode.CLOUD) tabs.setSelectedIndex(1);
                    updateButtons();
                });
        backend.addActionListener(e -> updateButtons());
        tabs.addChangeListener(e -> updateButtons());
        DocumentListener consentInvalidation =
                new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void removeUpdate(DocumentEvent e) {
                        changed();
                    }

                    public void changedUpdate(DocumentEvent e) {
                        changed();
                    }

                    private void changed() {
                        cloudConsent.setSelected(false);
                        automaticCloud.setSelected(false);
                    }
                };
        cloudEndpoint.getDocument().addDocumentListener(consentInvalidation);
        cloudModel.getDocument().addDocumentListener(consentInvalidation);
        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        close();
                    }
                });
        getRootPane()
                .registerKeyboardAction(
                        e -> close(),
                        KeyStroke.getKeyStroke("ESCAPE"),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(apply);
        if (original.mode() == AIConfig.Mode.CLOUD) tabs.setSelectedIndex(1);
        setMinimumSize(new Dimension(660, 455));
        setSize(720, 490);
        setLocationRelativeTo(owner);
        updateButtons();
    }

    private JPanel localPanel() {
        JPanel panel = form();
        row(panel, 0, "Backend", backend);
        row(panel, 1, "Loopback endpoint", localEndpoint);
        row(panel, 2, "FIM model", localModel);
        wide(panel, 3, automaticLocal);
        wide(
                panel,
                4,
                explanation(
                        "Start an Ollama or llama.cpp FIM server separately. Local requests stay on"
                            + " this Mac. TurboJ neither downloads models nor starts an inference"
                            + " server. Unload is an explicit action because other apps may share"
                            + " the model."));
        return panel;
    }

    private JPanel cloudPanel() {
        JPanel panel = form();
        row(panel, 0, "Responses base URL", cloudEndpoint);
        row(panel, 1, "Model", cloudModel);
        row(panel, 2, "API key", key);
        wide(panel, 3, deleteKey);
        wide(panel, 4, cloudConsent);
        wide(panel, 5, automaticCloud);
        wide(
                panel,
                6,
                explanation(
                        "Your API key is stored in macOS Keychain, bound to this endpoint. Leave"
                            + " the key blank to keep it. Cloud completion shares bounded source"
                            + " context with the chosen provider and may incur API usage charges."
                            + " Nothing is sent to turboj.dev."));
        return panel;
    }

    private JPanel behaviorPanel() {
        JPanel panel = form();
        JPanel numbers = new JPanel(new GridLayout(2, 4, 8, 6));
        addNumber(numbers, "Delay (ms)", delay);
        addNumber(numbers, "Timeout (s)", timeout);
        addNumber(numbers, "Max tokens", maxTokens);
        addNumber(numbers, "Context chars", contextChars);
        wide(panel, 0, numbers);
        row(panel, 1, "Excluded file globs", new JScrollPane(exclusions));
        wide(panel, 2, diagnostics);
        wide(
                panel,
                3,
                explanation(
                        "One glob per line. Excluded files and detected secrets are blocked from AI"
                            + " context. Secret detection is a safeguard, not a guarantee."
                            + " Diagnostics remain local; generated code is never run"
                            + " automatically."));
        return panel;
    }

    private static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 8, 6, 8));
        return p;
    }

    private static void row(JPanel panel, int y, String label, JComponent input) {
        GridBagConstraints c = constraints(y);
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JLabel text = new JLabel(label);
        text.setLabelFor(input);
        panel.add(text, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(input, c);
    }

    private static void wide(JPanel panel, int y, JComponent input) {
        GridBagConstraints c = constraints(y);
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(input, c);
    }

    private static GridBagConstraints constraints(int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }

    private static JTextArea explanation(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setRows(4);
        return area;
    }

    private static void addNumber(JPanel p, String label, JSpinner spinner) {
        JLabel l = new JLabel(label);
        l.setLabelFor(spinner);
        p.add(l);
        p.add(spinner);
    }

    private static JSpinner spinner(int value, int min, int max, int step, String name) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        name(spinner, name);
        return spinner;
    }

    private static void name(JComponent component, String name) {
        component.setName(name);
        component
                .getAccessibleContext()
                .setAccessibleName(name.replace("ai-", "").replace('-', ' '));
    }

    public void setTheme(Theme theme) {
        style(getContentPane(), theme);
        result.setForeground(theme.color("ui", "muted"));
        getRootPane().setBorder(BorderFactory.createLineBorder(theme.color("ui", "border")));
    }

    private void style(Component component, Theme theme) {
        component.setBackground(theme.color("ui", "surface"));
        component.setForeground(theme.color("ui", "foreground"));
        component.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        if (component instanceof JTextField || component instanceof JTextArea) {
            component.setBackground(theme.color("editor", "background"));
            component.setForeground(theme.color("editor", "foreground"));
            if (component instanceof javax.swing.text.JTextComponent text)
                text.setCaretColor(theme.color("editor", "cursor"));
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) style(child, theme);
    }

    private AIConfig configuration(boolean checkOnly) {
        AIConfig.Mode selected = (AIConfig.Mode) mode.getSelectedItem();
        if (checkOnly)
            selected =
                    tabs.getSelectedIndex() == 1
                                    || (tabs.getSelectedIndex() == 2
                                            && selected == AIConfig.Mode.CLOUD)
                            ? AIConfig.Mode.CLOUD
                            : AIConfig.Mode.LOCAL;
        return new AIConfig(
                selected,
                (AIConfig.LocalBackend) backend.getSelectedItem(),
                localEndpoint.getText(),
                localModel.getText(),
                cloudEndpoint.getText(),
                cloudModel.getText(),
                automaticLocal.isSelected(),
                automaticCloud.isSelected(),
                checkOnly || cloudConsent.isSelected(),
                (int) delay.getValue(),
                (int) timeout.getValue(),
                (int) maxTokens.getValue(),
                (int) contextChars.getValue(),
                exclusions.getText(),
                diagnostics.isSelected());
    }

    static boolean needsCloudConfirmation(AIConfig before, AIConfig after) {
        return after.mode() == AIConfig.Mode.CLOUD
                && (!before.cloudConsent()
                        || before.mode() != AIConfig.Mode.CLOUD
                        || !CredentialStore.normalizeEndpoint(before.cloudEndpoint())
                                .equals(CredentialStore.normalizeEndpoint(after.cloudEndpoint()))
                        || !before.cloudModel().equals(after.cloudModel())
                        || (!before.automaticCloud() && after.automaticCloud()));
    }

    private void apply() {
        if (busy) return;
        final AIConfig config;
        try {
            config = configuration(false);
            config.validate();
            if (deleteKey.isSelected() && hasNewKey())
                throw new IllegalArgumentException(
                        "Choose either a new API key or removal of the stored key.");
            if (deleteKey.isSelected() && config.mode() == AIConfig.Mode.CLOUD)
                throw new IllegalArgumentException(
                        "Choose Disabled or Local before removing the cloud API key.");
        } catch (IllegalArgumentException failure) {
            showFailure(failure);
            return;
        }
        if (needsCloudConfirmation(original, config)) {
            String message =
                    "Enable cloud completion for "
                            + config.cloudEndpoint()
                            + "?\n\n"
                            + "Bounded Java source context will leave this Mac. The provider may"
                            + " charge API usage fees.\n"
                            + (config.automaticCloud()
                                    ? "Automatic suggestions will send context after typing"
                                          + " pauses.\n"
                                    : "Requests are sent only when you explicitly request a"
                                          + " completion.\n")
                            + "Generated code remains a preview until you accept it. No source or"
                            + " API key goes to turboj.dev.";
            if (JOptionPane.showConfirmDialog(
                            this,
                            message,
                            "Enable cloud AI",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE)
                    != JOptionPane.OK_OPTION) return;
        }
        char[] password = key.getPassword();
        key.setText("");
        boolean remove = deleteKey.isSelected();
        saving = true;
        long ticket = begin("Saving AI preferences…");
        worker.execute(
                () -> {
                    try {
                        if (remove) credentials.delete(config.cloudEndpoint());
                        else if (password.length > 0)
                            credentials.write(config.cloudEndpoint(), password);
                        if (config.mode() == AIConfig.Mode.CLOUD
                                && (remove || credentials.read(config.cloudEndpoint()).isBlank()))
                            throw new IOException(
                                    "Cloud AI needs an API key in macOS Keychain. Choose Disabled"
                                        + " to remove the key.");
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!current(ticket)) return;
                                    busy = false;
                                    saving = false;
                                    save.accept(config);
                                    close();
                                });
                    } catch (IOException | RuntimeException failure) {
                        fail(ticket, failure);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                });
    }

    private void test(boolean release) {
        if (busy) return;
        final AIConfig config;
        try {
            config = configuration(true);
            config.validate();
        } catch (IllegalArgumentException failure) {
            showFailure(failure);
            return;
        }
        if (release
                && JOptionPane.showConfirmDialog(
                                this,
                                "Unload this Ollama model? Other applications may also be using"
                                    + " it.",
                                "Unload local model",
                                JOptionPane.OK_CANCEL_OPTION)
                        != JOptionPane.OK_OPTION) return;
        char[] password = key.getPassword();
        long ticket =
                begin(
                        release
                                ? "Unloading local model…"
                                : "Testing provider availability without project source…");
        Cancellation cancellation = active;
        worker.execute(
                () -> {
                    try (AICompletionProvider provider =
                            AIProviders.create(
                                    config,
                                    () -> {
                                        if (password.length > 0) return new String(password);
                                        try {
                                            return credentials.read(config.cloudEndpoint());
                                        } catch (IOException failure) {
                                            throw new UncheckedIOException(failure);
                                        }
                                    })) {
                        String message;
                        if (release) {
                            provider.release(cancellation);
                            message = "Local unload request completed.";
                        } else {
                            AICompletionProvider.Availability availability =
                                    provider.check(cancellation);
                            message = availability.message();
                        }
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!current(ticket)) return;
                                    busy = false;
                                    result.setText(message);
                                    result.setToolTipText(message);
                                    updateButtons();
                                });
                    } catch (IOException | RuntimeException failure) {
                        fail(ticket, failure);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                });
    }

    private long begin(String message) {
        active = new Cancellation();
        busy = true;
        result.setText(message);
        updateButtons();
        return ++operation;
    }

    private boolean hasNewKey() {
        char[] value = key.getPassword();
        try {
            return value.length != 0;
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private boolean current(long ticket) {
        return !closed && ticket == operation;
    }

    private void fail(long ticket, Exception failure) {
        SwingUtilities.invokeLater(
                () -> {
                    if (!current(ticket)) return;
                    busy = false;
                    saving = false;
                    showFailure(failure);
                    updateButtons();
                });
    }

    private void showFailure(Exception failure) {
        String message =
                failure instanceof UncheckedIOException io
                        ? io.getCause().getMessage()
                        : failure.getMessage();
        if (message == null) message = "AI settings operation failed.";
        result.setText(message);
        result.setToolTipText(message);
    }

    private void updateButtons() {
        apply.setEnabled(!busy);
        test.setEnabled(!busy);
        cancel.setEnabled(!saving);
        unload.setEnabled(
                !busy
                        && tabs.getSelectedIndex() == 0
                        && backend.getSelectedItem() == AIConfig.LocalBackend.OLLAMA);
    }

    private void close() {
        if (closed || saving) return;
        closed = true;
        operation++;
        if (active != null) active.cancel();
        char[] current = key.getPassword();
        Arrays.fill(current, '\0');
        key.setText("");
        worker.shutdownNow();
        dispose();
    }

    @Override
    public void dispose() {
        if (!closed) {
            closed = true;
            operation++;
            if (active != null) active.cancel();
            worker.shutdownNow();
            key.setText("");
        }
        super.dispose();
    }
}
