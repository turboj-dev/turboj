package dev.turboj.testing;

import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.ConsolePane.Role;
import dev.turboj.ui.themes.Theme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The Tests tab: one run at a time, as suites and their tests. Only problems carry detail: a failure's message and
 * the stack frames of the project's own code, compiler errors, engine errors, and the output the tests printed when
 * the run did not pass. Enter or a double click on a test opens it; Next and Previous Failure walk the failures.
 */
public final class TestResultsPane extends JPanel {
    /** A test's line in the pane, for selection and navigation. */
    private record Line(int start, int end, TestEngine.Test test) {}

    private static final int MAX_FRAMES = 6;
    private static final String ROLE = "turboj.test-role";
    private final JTextPane view = new JTextPane();
    private final EnumMap<Role, SimpleAttributeSet> styles = new EnumMap<>(Role.class);
    private final List<Line> tests = new ArrayList<>();
    private final List<Line> failures = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final List<String> problems = new ArrayList<>();
    private Consumer<TestEngine.Test> open = test -> {};
    private String title = "";
    private int selected = -1;

    public TestResultsPane() {
        super(new BorderLayout());
        setName("test-results");
        view.setName("test-results-view");
        view.setEditable(false);
        view.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        view.getAccessibleContext().setAccessibleName("Test results");
        var scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        for (Role role : Role.values()) {
            var style = new SimpleAttributeSet();
            style.addAttribute(ROLE, role);
            styles.put(role, style);
        }
        view.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int offset = view.viewToModel2D(event.getPoint());
                for (int i = 0; i < tests.size(); i++)
                    if (offset >= tests.get(i).start() && offset <= tests.get(i).end()) {
                        select(tests.get(i));
                        if (event.getClickCount() == 2) open.accept(tests.get(i).test());
                        return;
                    }
            }
        });
        view.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-test");
        view.getActionMap().put("open-test", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                Line line = selectedLine();
                if (line != null) open.accept(line.test());
            }
        });
        view.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "next-test");
        view.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "previous-test");
        view.getActionMap().put("next-test", step(1));
        view.getActionMap().put("previous-test", step(-1));
        render();
    }

    /** What Enter, a double click, or Next/Previous Failure open. */
    public void onOpen(Consumer<TestEngine.Test> action) { open = Objects.requireNonNull(action); }

    public JTextPane view() { return view; }

    /** A new run: {@code Test all tests · hello-world}. Its compile phase may report problems before the tests. */
    public void begin(String title) {
        this.title = title == null ? "" : title;
        tests.clear();
        failures.clear();
        problems.clear();
        output.setLength(0);
        selected = -1;
        setText(List.of(new Object[] {this.title + " — running…", Role.HEADING}));
    }

    /** A compiler error or engine error, shown under the run's title. */
    public void problem(String text) {
        problems.add(text);
        renderProblems(this.title + " — running…");
    }

    /** What the tests printed; shown only if the run does not pass. */
    public void output(String text) {
        if (output.length() < 200_000) output.append(text);
    }

    /** The run ended without a report: a compile failure, compile-only, or a setup error. */
    public void completed(String message, boolean success) {
        if (message != null && !message.isBlank() && !success) problems.add(message);
        renderProblems(title + " — " + (success ? message : "failed"));
    }

    /** The run's report: suites, their tests, and the detail of what failed. */
    public void report(TestEngine.Report report) {
        tests.clear();
        failures.clear();
        selected = -1;
        var parts = new ArrayList<Object>();
        String verdict = report.successful()
                ? (report.tests().isEmpty() ? "no tests" : report.passed() + " of " + report.tests().size() + " passed")
                : (report.failed() + report.engineFailures().size()) + " failed · " + report.passed() + " of " + report.tests().size() + " passed";
        parts.add(title + " — " + verdict + " · " + TestOutput.duration(report.durationNanos()) + "\n");
        parts.add(TestOutput.summaryRole(report));
        for (String problem : problems) { parts.add("  " + problem + "\n"); parts.add(Role.ERROR); }
        var suites = new LinkedHashMap<String, List<TestEngine.Test>>();
        for (var test : report.tests())
            suites.computeIfAbsent(test.className() == null || test.className().isBlank() ? test.name() : test.className(),
                    name -> new ArrayList<>()).add(test);
        var pending = new ArrayList<Object[]>(); // test lines are located after the text is in place
        suites.forEach((suite, members) -> {
            long failed = members.stream().filter(t -> t.status() == TestEngine.Status.FAILED).count();
            long elapsed = members.stream().mapToLong(TestEngine.Test::durationNanos).sum();
            parts.add(suite + "  " + members.size() + (members.size() == 1 ? " test" : " tests")
                    + (failed > 0 ? " · " + failed + " failed" : "") + " · " + TestOutput.duration(elapsed) + "\n");
            parts.add(failed > 0 ? Role.ERROR : Role.HEADING);
            for (var test : members) {
                String mark = switch (test.status()) { case PASSED -> "✔"; case FAILED -> "✖"; case ABORTED -> "!"; case SKIPPED -> "○"; };
                String text = "  " + mark + " " + displayName(test) + "  " + TestOutput.duration(test.durationNanos()) + "\n";
                pending.add(new Object[] {parts.size(), text.length(), test});
                parts.add(text);
                parts.add(TestOutput.role(test.status()));
                if (test.status() == TestEngine.Status.FAILED || test.status() == TestEngine.Status.ABORTED) {
                    String message = test.failure() == null ? "" : test.failure().strip();
                    if (!message.isEmpty()) {
                        for (String line : message.lines().limit(8).toList()) { parts.add("      " + line + "\n"); parts.add(Role.ERROR); }
                    }
                    for (String frame : frames(test.trace(), suite)) { parts.add("      " + frame + "\n"); parts.add(Role.MUTED); }
                } else if (test.status() == TestEngine.Status.SKIPPED && test.failure() != null && !test.failure().isBlank()) {
                    parts.add("      " + test.failure().lines().findFirst().orElse("") + "\n");
                    parts.add(Role.SKIPPED);
                }
            }
        });
        if (!report.engineFailures().isEmpty()) {
            parts.add("Errors\n"); parts.add(Role.ERROR);
            for (String failure : report.engineFailures()) { parts.add("  " + failure.lines().findFirst().orElse("") + "\n"); parts.add(Role.ERROR); }
        }
        if (report.tests().isEmpty() && report.engineFailures().isEmpty()) { parts.add("No tests to run.\n"); parts.add(Role.WARNING); }
        if (!report.successful() && !output.toString().isBlank()) {
            parts.add("Output\n"); parts.add(Role.HEADING);
            parts.add(output.toString().endsWith("\n") ? output.toString() : output + "\n"); parts.add(Role.NORMAL);
        }
        int[] offsets = setText(parts);
        for (Object[] entry : pending) {
            int index = (int) entry[0] / 2;
            var test = (TestEngine.Test) entry[2];
            var line = new Line(offsets[index], offsets[index] + (int) entry[1] - 1, test);
            tests.add(line);
            if (test.status() == TestEngine.Status.FAILED || test.status() == TestEngine.Status.ABORTED) failures.add(line);
        }
        view.setCaretPosition(0);
    }

    /** The failed tests, in the order shown. */
    public List<TestEngine.Test> failures() { return failures.stream().map(Line::test).toList(); }

    /**
     * Selects the next ({@code direction} 1) or previous (-1) failure after the selection, wrapping around, and
     * returns it; null when nothing failed.
     */
    public TestEngine.Test nextFailure(int direction) {
        if (failures.isEmpty()) return null;
        Line current = selectedLine();
        int at = current == null ? -1 : failures.indexOf(current);
        int next;
        if (at < 0) {
            // From a passing test, the next failure below it, or the last above it.
            int position = current == null ? (direction > 0 ? -1 : Integer.MAX_VALUE) : current.start();
            next = -1;
            for (int i = 0; i < failures.size(); i++)
                if (direction > 0 ? failures.get(i).start() > position : failures.get(i).start() < position) {
                    next = i;
                    if (direction > 0) break;
                }
            if (next < 0) next = direction > 0 ? 0 : failures.size() - 1;
        } else next = Math.floorMod(at + direction, failures.size());
        Line line = failures.get(next);
        select(line);
        return line.test();
    }

    public TestEngine.Test selectedTest() {
        Line line = selectedLine();
        return line == null ? null : line.test();
    }

    public String text() {
        try {
            return view.getDocument().getText(0, view.getDocument().getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    public void clear() {
        title = "";
        tests.clear();
        failures.clear();
        problems.clear();
        output.setLength(0);
        selected = -1;
        render();
    }

    public void setTheme(Theme value) {
        Color background = value.messagesBackground();
        view.setBackground(background);
        view.setForeground(value.messagesForeground());
        view.setCaretColor(value.messagesForeground());
        view.setSelectionColor(value.selection());
        setBackground(background);
        for (Role role : Role.values()) {
            var style = styles.get(role);
            StyleConstants.setForeground(style, ConsolePane.colorFor(value, role, background));
            StyleConstants.setBold(style, role == Role.HEADING);
        }
        restyle();
    }

    @Override public void setFont(Font font) {
        super.setFont(font);
        if (view != null) view.setFont(font);
    }

    private javax.swing.Action step(int direction) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (tests.isEmpty()) return;
                int index = selected < 0 ? (direction > 0 ? 0 : tests.size() - 1) : Math.clamp(selected + direction, 0, tests.size() - 1);
                select(tests.get(index));
            }
        };
    }

    private Line selectedLine() { return selected < 0 || selected >= tests.size() ? null : tests.get(selected); }

    private void select(Line line) {
        selected = tests.indexOf(line);
        if (selected < 0) return;
        view.select(line.start(), line.end());
        view.getCaret().setSelectionVisible(true);
        try {
            var bounds = view.modelToView2D(line.start());
            if (bounds != null) view.scrollRectToVisible(bounds.getBounds());
        } catch (BadLocationException ignored) {
        }
    }

    private void renderProblems(String heading) {
        var parts = new ArrayList<Object>();
        parts.add(heading + "\n");
        parts.add(problems.isEmpty() ? Role.HEADING : Role.ERROR);
        for (String problem : problems) { parts.add("  " + problem + "\n"); parts.add(Role.ERROR); }
        setText(parts);
    }

    private void render() {
        setText(List.of("No test run yet. Run tests: Run ▸ Run All Tests.\n", Role.MUTED));
    }

    /** Text and role pairs; returns where each pair starts. */
    private int[] setText(List<?> parts) {
        var document = view.getStyledDocument();
        int[] offsets = new int[parts.size() / 2];
        try {
            document.remove(0, document.getLength());
            for (int i = 0; i + 1 < parts.size(); i += 2) {
                offsets[i / 2] = document.getLength();
                document.insertString(document.getLength(), (String) parts.get(i), styles.get((Role) parts.get(i + 1)));
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        return offsets;
    }

    private void restyle() {
        // Roles are attributes of the text already shown; a theme change repaints them with the new colors.
        StyledDocument document = view.getStyledDocument();
        for (int offset = 0; offset < document.getLength(); ) {
            var element = document.getCharacterElement(offset);
            var role = roleOf(element.getAttributes());
            if (role != null) document.setCharacterAttributes(element.getStartOffset(), element.getEndOffset() - element.getStartOffset(), styles.get(role), true);
            offset = element.getEndOffset();
        }
    }

    private static Role roleOf(javax.swing.text.AttributeSet attributes) {
        return attributes.getAttribute(ROLE) instanceof Role role ? role : null;
    }

    private static String displayName(TestEngine.Test test) {
        String method = test.methodName() == null || test.methodName().isBlank() ? test.name() : test.methodName();
        if (test.name() != null && !test.name().isBlank() && !test.name().equals(method) && !test.name().equals(method + "()"))
            return method + " [" + test.name() + "]";
        return method;
    }

    /**
     * The frames of the project's own code: JUnit, the JDK and reflection are dropped; the frame of the failing
     * test comes first as the stack has it.
     */
    static List<String> frames(String trace, String suite) {
        if (trace == null || trace.isBlank()) return List.of();
        var frames = new ArrayList<String>();
        for (String line : trace.lines().toList()) {
            String frame = line.strip();
            if (!frame.startsWith("at ")) continue;
            String target = frame.substring(3);
            if (target.startsWith("org.junit.") || target.startsWith("org.opentest4j.") || target.startsWith("java.")
                    || target.startsWith("jdk.") || target.startsWith("sun.") || target.startsWith("dev.turboj.")
                    || target.startsWith("org.apiguardian.")) continue;
            frames.add(frame);
            if (frames.size() == MAX_FRAMES) break;
        }
        return frames;
    }
}
