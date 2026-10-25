package dev.turboj.ui;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.SwingUtilities;

/** Reproducible EDT command cost, excluding OS input, painting and host callbacks. */
public final class KeyboardEditingBench {
    private static final int WARMUP = 2_000, SAMPLES = 5_000;
    public static void main(String[] args) throws Exception {
        boolean baseline = args.length > 0 && args[0].equals("baseline");
        try (var bytes = EditorComponent.class.getResourceAsStream("EditorComponent.class")) {
            System.out.println("EditorComponent.class SHA-256 " + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        }
        System.out.println("UTC " + java.time.Instant.now());
        System.out.printf("JDK %s; %s %s %s; CPUs %d; maxHeap %d%n", System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
        System.out.printf("1000 lines; %d warmup + %d samples per operation; EDT; headless=%s%n",
                WARMUP, SAMPLES, java.awt.GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            measure("duplicateLine", false, e -> e.duplicateLine());
            measure("deleteLine", false, e -> e.deleteLine());
            measure("typeCharacter", false, e -> e.processKeyEvent(new java.awt.event.KeyEvent(e,
                    java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'x')));
            if (!baseline) {
                measure("multicaretType8", true, e -> e.replaceSelection("x"));
                measure("multicaretBackspace8", true, e -> e.deleteBackward());
                measure("multicaretMoveLines8", true, e -> e.moveLine(1));
            }
        });
    }
    private static void measure(String name, boolean multiple, java.util.function.Consumer<EditorComponent> action) {
        var editor = new EditorComponent();
        editor.setSize(1000, 700);
        editor.setText("    int sample = 42; // plain text\n".repeat(1000));
        editor.gotoOffset(editor.document().lineStart(500) + 8);
        if (multiple) for (int i = 1; i < 8; i++) editor.addCaretBelow();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported() && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        long[] nanos = new long[SAMPLES], bytes = new long[SAMPLES];
        for (int i = -WARMUP; i < SAMPLES; i++) {
            long allocated = bean.getThreadAllocatedBytes(tid), start = System.nanoTime();
            action.accept(editor);
            long elapsed = System.nanoTime() - start, allocation = bean.getThreadAllocatedBytes(tid) - allocated;
            if (i >= 0) { nanos[i] = elapsed; bytes[i] = allocation; }
            editor.undo();
            // Older implementation did not restore the exact caret on undo; normalize the fixture.
            if (!multiple) editor.gotoOffset(editor.document().lineStart(500) + 8);
        }
        Arrays.sort(nanos); Arrays.sort(bytes);
        System.out.printf(Locale.ROOT, "%s p50=%.3f us p95=%.3f us p99=%.3f us; allocated p50=%d p95=%d p99=%d bytes%n",
                name, nanos[SAMPLES / 2] / 1000.0, nanos[(int)(SAMPLES * .95)] / 1000.0,
                nanos[(int)(SAMPLES * .99)] / 1000.0, bytes[SAMPLES / 2], bytes[(int)(SAMPLES * .95)], bytes[(int)(SAMPLES * .99)]);
    }
}
