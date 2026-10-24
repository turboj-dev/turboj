package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.*;
import javax.swing.plaf.FileChooserUI;

/** A small internal file browser. Directory reads run off EDT; no platform file-dialog windows. */
public final class EmbeddedFileChooser extends JFileChooser {
    public EmbeddedFileChooser() {
        super();
    }

    public EmbeddedFileChooser(File directory) {
        super(directory);
    }

    @Override
    public void updateUI() {
        setUI(new BrowserMetadataUI());
    }

    @Override
    public int showOpenDialog(Component owner) {
        setDialogType(OPEN_DIALOG);
        return showDialog(owner, null);
    }

    @Override
    public int showSaveDialog(Component owner) {
        setDialogType(SAVE_DIALOG);
        return showDialog(owner, null);
    }

    @Override
    public int showDialog(Component owner, String approveText) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("File selection requires EDT");
        boolean saving = getDialogType() == SAVE_DIALOG;
        String title = getDialogTitle()
                == null ? saving ? "Save file" : "Open file" : getDialogTitle();
        var dialog = new EmbeddedDialog(owner, title, true);
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JTextField directory = new JTextField(getCurrentDirectory().getAbsolutePath());
        directory.getAccessibleContext().setAccessibleName("Directory path");
        JTextField filename = new JTextField(getSelectedFile() == null ? "" : getSelectedFile()
                .getName());
        filename.getAccessibleContext().setAccessibleName("File name");
        DefaultListModel<Entry> model = new DefaultListModel<> ();
        JList<Entry> list = new JList<> (model);
        list
                .setSelectionMode(isMultiSelectionEnabled() ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Files and directories");
        JLabel status = new JLabel(" ");
        JButton approve = new JButton(approveText == null ? saving ? "Save" : "Open" : approveText);
        JButton cancel = new JButton("Cancel"), up = new JButton("Up"),
                folder = new JButton("New folder…");
        JPanel heading = new JPanel(new BorderLayout(5, 0));
        heading.add(directory, BorderLayout.CENTER);
        heading.add(up, BorderLayout.WEST);
        heading.add(folder, BorderLayout.EAST);
        body.add(heading, BorderLayout.NORTH);
        body.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.add(filename, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(approve);
        bottom.add(buttons, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);
        int[] result = {CANCEL_OPTION}, generation = {0};
        Path[] current = {getCurrentDirectory().toPath().toAbsolutePath().normalize()};
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            final int request = ++generation[0];
            final Path target;

            try {
                target = Path.of(directory.getText()).toAbsolutePath().normalize();
            } catch (InvalidPathException error) {
                status.setText("Invalid directory path");
                return;
            }
            status.setText("Reading directory…");
            approve.setEnabled(false);
            var filter = getFileFilter();
            boolean hide = isFileHidingEnabled();
            new SwingWorker<List<Entry>, Void> () {
                protected List<Entry> doInBackground() throws Exception {
                    try (var files = Files.list(target)) {
                        return files.filter(path -> !hide || !path.getFileName().toString()
                                .startsWith(".")).map(path -> new Entry(path.toFile(), Files
                                .isDirectory(path))).filter(entry -> entry.directory || filter
                                == null || filter.accept(entry.file)).sorted(Comparator
                                .comparing((Entry entry) -> !entry.directory)
                                .thenComparing(entry -> entry.file.getName(),
                                String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.file
                                .getName())).toList();
                    }
                }

                protected void done() {
                    if (request != generation[0])
                        return;
                    try {
                        var entries = get();
                        current[0] = target;
                        setCurrentDirectory(target.toFile());
                        model.clear();
                        model.addAll(entries);
                        directory.setText(target.toString());
                        status.setText(entries.size() + " entries" + (filter == null ? "" : " · "
                                + filter.getDescription()));
                        approve.setEnabled(true);
                    } catch (Exception error) {
                        status.setText("Cannot read directory: " + target);
                    }
                }
            }.execute();
        };
        Runnable open = () -> {
            int request = generation[0];
            String chosenName = filename.getText();
            Path path;

            try {
                path = filename.getText().isBlank() ? current[0] : current[0].resolve(filename
                        .getText()).normalize();
            } catch (InvalidPathException error) {
                status.setText("Invalid file name");
                return;
            }
            // Existence and type checks may involve network-mounted directories; keep them off EDT.
            approve.setEnabled(false);
            new SwingWorker<int[], Void> () {
                protected int[] doInBackground() {
                    return new int[] {Files.isDirectory(path) ? 1 : 0, Files.exists(path) ? 1 : 0};
                }

                protected void done() {
                    if (!dialog.isVisible())
                        return;
                    approve.setEnabled(true);
                    if (request != generation[0] || !chosenName.equals(filename.getText())) return;
                    try {
                        int[] state = get();

                        if (state[0] == 1 && getFileSelectionMode() != DIRECTORIES_ONLY && !filename
                                .getText().isBlank()) {
                            directory.setText(path.toString());
                            filename.setText("");
                            reload[0].run();
                            return;
                        }
                        if (!saving && state[1] == 0) {
                            status.setText("The selected file does not exist");
                            return;
                        }
                        if (getFileSelectionMode() == DIRECTORIES_ONLY && state[0] == 0) {
                            status.setText("Choose a directory");
                            return;
                        }
                        if (getFileSelectionMode() == FILES_ONLY && state[0] == 1) {
                            status.setText("Choose a file");
                            return;
                        }
                        if (saving && state[1] == 1 && EmbeddedOptionPane.showConfirmDialog(dialog,
                                "Replace " + path.getFileName() + "?", "Existing file",
                                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
                            return;
                        setSelectedFile(path.toFile());
                        if (isMultiSelectionEnabled())
                            setSelectedFiles(list.getSelectedValuesList().stream()
                                    .filter(entry -> !entry.directory).map(Entry::file)
                                    .toArray(File[]::new));
                        result[0] = APPROVE_OPTION;
                        dialog.dispose();
                    } catch (Exception error) {
                        status.setText("Cannot inspect the selected path");
                    }
                }
            }.execute();
        };
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && list.getSelectedValue() != null)
                filename.setText(list.getSelectedValue().file.getName());
        });
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2)
                    open.run();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        list.getActionMap().put("open", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                open.run();
            }
        });
        directory.addActionListener(event -> reload[0].run());
        filename.addActionListener(event -> open.run());
        up.addActionListener(event -> {
            Path parent = current[0].getParent();

            if (parent != null) {
                directory.setText(parent.toString());
                filename.setText("");
                reload[0].run();
            }
        });
        folder.addActionListener(event -> {
            String name = EmbeddedOptionPane.showInputDialog(dialog, "Folder name:");

            if (name == null || name.isBlank())
                return;
            Path target;

            try {
                target = current[0].resolve(name).normalize();
                if (!Objects.equals(target.getParent(), current[0])) {
                    status.setText("Enter one folder name");
                    return;
                }
            } catch (InvalidPathException error) {
                status.setText("Invalid folder name");
                return;
            }
            new SwingWorker<Void, Void> () {
                protected Void doInBackground() throws Exception {
                    Files.createDirectory(target);
                    return null;
                }

                protected void done() {
                    try {
                        get();
                        reload[0].run();
                    } catch (Exception error) {
                        status.setText("Could not create folder: " + name);
                    }
                }
            }.execute();
        });
        approve.addActionListener(event -> open.run());
        cancel.addActionListener(event -> dialog.dispose());
        dialog.setContentPane(body);
        dialog.setSize(680, 440);
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke
                .getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        reload[0].run();
        dialog.setVisible(true);
        generation[0]++;
        return result[0];
    }

    private record Entry(File file, boolean directory) {
        @Override
        public String toString() {
            return (directory ? "[+] " : "    ") + file.getName();
        }
    }

    /** JFileChooser is used for its public selection/filter API, never its platform UI delegates. */
    private static final class BrowserMetadataUI extends FileChooserUI {
        private final FileFilter all = new FileFilter() {
            public boolean accept(File file) {
                return true;
            }

            public String getDescription() {
                return "All files";
            }
        };

        public FileFilter getAcceptAllFileFilter(JFileChooser chooser) {
            return all;
        }

        public FileView getFileView(JFileChooser chooser) {
            return null;
        }

        public String getApproveButtonText(JFileChooser chooser) {
            return chooser.getDialogType() == SAVE_DIALOG ? "Save" : "Open";
        }

        public String getDialogTitle(JFileChooser chooser) {
            return getApproveButtonText(chooser);
        }

        public void rescanCurrentDirectory(JFileChooser chooser) {}

        public void ensureFileIsVisible(JFileChooser chooser, File file) {}
    }
}
