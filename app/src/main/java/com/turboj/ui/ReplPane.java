package com.turboj.ui;

import com.turboj.repl.JavaRepl;
import com.turboj.ui.themes.Theme;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import javax.swing.*;

/**
 * Explicit-submit GUI console. JShell compiler/controller stay off the EDT and code runs remotely.
 */
public final class ReplPane extends JPanel implements AutoCloseable {
    private final ConsolePane console;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon().name("turboj-repl-controller").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private volatile JavaRepl repl;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();
    private boolean busy;
    private String pending = "";
    private final List<String> history = new ArrayList<>();
    private int historyIndex;

    public ReplPane() {
        super(new BorderLayout());
        console = new ConsolePane(this::evaluate);
        add(console, BorderLayout.CENTER);
        setTheme(Theme.bundled("classic"));
        console.append("Java 25 REPL · Enter submits · ↑/↓ history · Tab completion\n", false);
        var input = console.inputField();
        input.setToolTipText(
                "Java REPL — explicit Enter to evaluate; incomplete input continues on the next"
                    + " line");
        input.setFocusTraversalKeysEnabled(false);
        input.addKeyListener(
                new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_UP) {
                            if (!history.isEmpty()) {
                                historyIndex = Math.max(0, historyIndex - 1);
                                input.setText(history.get(historyIndex));
                            }
                            e.consume();
                        } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                            historyIndex = Math.min(history.size(), historyIndex + 1);
                            input.setText(
                                    historyIndex == history.size()
                                            ? ""
                                            : history.get(historyIndex));
                            e.consume();
                        } else if (e.getKeyCode() == KeyEvent.VK_TAB) {
                            complete();
                            e.consume();
                        }
                    }
                });
    }

    public void setTheme(Theme theme) {
        console.setTheme(theme);
        setBackground(theme.messagesBackground());
    }

    public void focusInput() {
        console.focusInput();
    }

    public boolean hasConsoleFocus() {
        return console.hasConsoleFocus();
    }

    public String text() {
        return console.text();
    }

    public void clear() {
        console.clear();
    }

    public void evaluate(String input) {
        if (closed) return;
        if (busy) {
            console.append("Evaluation in progress; Stop or wait before submitting again.\n", true);
            return;
        }
        String source = pending + input;
        long request = generation.incrementAndGet();
        console.append((pending.isEmpty() ? "> " : "… ") + input.stripTrailing() + "\n", false);
        setBusy(true);
        enqueue(
                () -> {
                    try {
                        JavaRepl current = session();
                        if (!active(request)) {
                            current.cancel();
                            return;
                        }
                        JavaRepl.Evaluation result = current.submit(source);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!active(request)) return;
                                    setBusy(false);
                                    if (!result.complete()) {
                                        pending = source + "\n";
                                        return;
                                    }
                                    pending = "";
                                    history.add(source.stripTrailing());
                                    if (history.size() > 1000) history.removeFirst();
                                    historyIndex = history.size();
                                    result.values().forEach(s -> console.append(s + "\n", false));
                                    result.diagnostics()
                                            .forEach(s -> console.append(s + "\n", true));
                                });
                    } catch (Throwable error) {
                        reportFailure(request, error);
                    }
                });
    }

    public void reset() {
        if (closed) return;
        long request = generation.incrementAndGet();
        executor.getQueue().clear();
        JavaRepl current = repl;
        if (current != null) current.cancel();
        setBusy(true);
        pending = "";
        enqueue(
                () -> {
                    try {
                        if (!active(request)) return;
                        if (repl != null) repl.reset();
                        else session();
                        if (!active(request)) {
                            if (repl != null) repl.cancel();
                            return;
                        }
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!active(request)) return;
                                    setBusy(false);
                                    console.append("Session reset.\n", false);
                                });
                    } catch (Throwable e) {
                        reportFailure(request, e);
                    }
                });
    }

    public void cancel() {
        generation.incrementAndGet();
        executor.getQueue().clear();
        JavaRepl current = repl;
        if (current != null) current.cancel();
        pending = "";
        if (!closed) {
            setBusy(false);
            console.append("Evaluation cancelled. Reset the Java REPL to continue.\n", false);
        }
    }

    private void complete() {
        if (busy || closed) return;
        long request = generation.incrementAndGet();
        String prefix = console.inputField().getText();
        setBusy(true);
        enqueue(
                () -> {
                    try {
                        JavaRepl current = session();
                        if (!active(request)) {
                            current.cancel();
                            return;
                        }
                        List<String> items = current.complete(prefix);
                        SwingUtilities.invokeLater(
                                () -> {
                                    if (!active(request)) return;
                                    setBusy(false);
                                    if (items.size() == 1)
                                        console.inputField().setText(items.getFirst());
                                    else if (!items.isEmpty())
                                        console.append(String.join("  ", items) + "\n", false);
                                });
                    } catch (Throwable e) {
                        reportFailure(request, e);
                    }
                });
    }

    private boolean active(long request) {
        return !closed && generation.get() == request;
    }

    private void reportFailure(long request, Throwable error) {
        SwingUtilities.invokeLater(
                () -> {
                    if (!active(request)) return;
                    setBusy(false);
                    console.append("REPL: " + error + "\n", true);
                });
    }

    private JavaRepl session() {
        if (closed) throw new IllegalStateException("REPL closed");
        if (repl == null) {
            var created =
                    new JavaRepl(
                            new PrintStream(new TextStream(false), true, StandardCharsets.UTF_8),
                            new PrintStream(new TextStream(true), true, StandardCharsets.UTF_8));
            repl = created;
            if (closed) {
                created.close();
                throw new IllegalStateException("REPL closed");
            }
        }
        return repl;
    }

    private void setBusy(boolean value) {
        busy = value;
        console.inputField().setEnabled(!value);
        if (!value) focusInput();
    }

    private void enqueue(Runnable action) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException e) {
            if (!closed) {
                setBusy(false);
                console.append("REPL queue is full or closed.\n", true);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancel();
        executor.getQueue().clear();
        try {
            executor.execute(
                    () -> {
                        if (repl != null) repl.close();
                    });
        } finally {
            executor.shutdown();
        }
    }

    private final class TextStream extends OutputStream {
        private final boolean error;
        private final CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] tail = new byte[0];

        TextStream(boolean error) {
            this.error = error;
        }

        @Override
        public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ByteBuffer input = ByteBuffer.allocate(tail.length + length);
            input.put(tail).put(bytes, offset, length).flip();
            CharBuffer out = CharBuffer.allocate(input.remaining() + 2);
            decoder.decode(input, out, false);
            tail = new byte[input.remaining()];
            input.get(tail);
            out.flip();
            if (out.hasRemaining()) console.append(out.toString(), error);
        }
    }
}
