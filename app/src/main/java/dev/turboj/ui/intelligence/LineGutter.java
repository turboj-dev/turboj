package dev.turboj.ui.intelligence;

import dev.turboj.testing.intelligence.LineIntelligence.*;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.util.List;
import java.util.*;

/** Three independent paint/hit regions; never mutates source or invents execution evidence. */
public final class LineGutter {
    public record Hit(int line, Lane lane, Integer deletionAnchor) {}
    private record Summary(TestStatus status, Freshness freshness) {}
    private Snapshot snapshot;
    private boolean gitActive, testingActive;
    private Map<Integer, List<Deletion>> deletions = Map.of();
    private final Map<Integer, Summary> summaries = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Summary> eldest) { return size() > 512; }
    };
    private Theme theme;

    public Snapshot snapshot() { return snapshot; }
    public int cells() { return (gitActive ? 1 : 0) + (testingActive ? 2 : 0); }
    public void setTheme(Theme value) { theme = Objects.requireNonNull(value); }
    public void setBackground(Color value) { setTheme(new Theme("gutter", "Gutter", Map.of("editor.background", value), 16, true)); }
    public void clear() { invalidate(); gitActive = testingActive = false; }
    /** Keep the active geometry while a changed source is being reconciled by its controller. */
    public void invalidate() { snapshot = null; deletions = Map.of(); summaries.clear(); }
    public void install(Snapshot value) {
        snapshot = value; summaries.clear();
        if (value == null) { clear(); return; }
        gitActive = value.gitActive(); testingActive = value.testingActive();
        var index = new HashMap<Integer, List<Deletion>>();
        if (gitActive) for (Deletion deletion : value.deletions())
            index.computeIfAbsent(deletion.anchorLine(), ignored -> new ArrayList<>()).add(deletion);
        deletions = index;
    }

    public Rectangle bounds(Lane lane, int cellWidth, int y, int height) {
        int column = switch (lane) {
            case GIT -> gitActive ? 0 : -1;
            case TEST -> testingActive ? (gitActive ? 1 : 0) : -1;
            case COVERAGE -> testingActive ? (gitActive ? 2 : 1) : -1;
        };
        return column < 0 ? new Rectangle() : new Rectangle(column * cellWidth, y, cellWidth, height);
    }

    public Hit hit(Point point, int line, int rowY, int lineCount, int cellWidth, int cellHeight) {
        if (snapshot == null || line < 1 || line > lineCount || point.y < rowY || point.y >= rowY + cellHeight) return null;
        for (Lane lane : Lane.values()) {
            Rectangle bounds = bounds(lane, cellWidth, rowY, cellHeight);
            if (!bounds.contains(point)) continue;
            if (lane == Lane.GIT) {
                int edge = Math.max(3, cellHeight / 5);
                if (point.y < rowY + edge && deletions.containsKey(line - 1)) return new Hit(line, lane, line - 1);
                if (line == lineCount && point.y >= rowY + cellHeight - edge && deletions.containsKey(line))
                    return new Hit(line, lane, line);
            }
            return new Hit(line, lane, null);
        }
        return null;
    }

    public void paint(Graphics2D graphics, int line, int y, int lineCount, int cellWidth, int height) {
        if (snapshot == null || line < 1 || line > lineCount) return;
        Line value = snapshot.line(line);
        if (gitActive) {
            Rectangle lane = bounds(Lane.GIT, cellWidth, y, height);
            if (value.added()) addition(graphics, lane);
            if (deletions.containsKey(line - 1)) deletion(graphics, lane, true);
            if (line == lineCount && deletions.containsKey(line)) deletion(graphics, lane, false);
        }
        // Empty lines with no evidence stay visually quiet, but their neutral state is inspectable.
        if (!testingActive || !snapshot.lines().containsKey(line)) return;
        Summary summary = summaries.computeIfAbsent(line, ignored -> new Summary(value.testStatus(), value.testFreshness()));
        paintTest(graphics, bounds(Lane.TEST, cellWidth, y, height), summary);
        paintCoverage(graphics, bounds(Lane.COVERAGE, cellWidth, y, height), value.coverage());
    }

    private Color color(String group, String role) { return theme.color(group, role); }
    private void addition(Graphics2D g, Rectangle lane) {
        g.setColor(color("git", "added"));
        int radius = Math.max(1, (lane.width - 2) / 2);
        int x = lane.x + lane.width / 2, y = lane.y + lane.height / 2;
        g.drawLine(x - radius, y, x + radius, y);
        g.drawLine(x, y - radius, x, y + radius);
    }

    private void deletion(Graphics2D g, Rectangle lane, boolean before) {
        g.setColor(color("git", "deleted"));
        int thickness = Math.max(1, lane.width / 8), notch = Math.max(3, lane.height / 7);
        int y = before ? lane.y + 1 : lane.y + lane.height - thickness - 1;
        int width = Math.max(3, lane.width - 2);
        g.fillRect(lane.x + 1, y, width, thickness);
        // Boundary notch distinguishes a removed hunk from a line's own status.
        g.drawLine(lane.x + 1, y, lane.x + 1, y + (before ? notch : -notch));
    }

    private void glyph(Graphics2D g, Rectangle lane, char glyph, Color color) {
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(String.valueOf(glyph), lane.x + (lane.width - fm.charWidth(glyph)) / 2,
                lane.y + (lane.height - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintTest(Graphics2D g, Rectangle lane, Summary value) {
        if (value.freshness == Freshness.STALE && value.status != TestStatus.FAILED && value.status != TestStatus.PENDING) {
            glyph(g, lane, '~', color("status", "stale")); return;
        }
        Color ink = switch (value.status) {
            case PASSED -> color("test", "success");
            case FAILED -> color("test", "failure");
            case PENDING -> color("test", "pending");
            default -> color("test", "unknown");
        };
        int inset = Math.max(1, lane.width / 6);
        int x = lane.x + inset, y = lane.y + lane.height / 2, w = Math.max(3, lane.width - 2 * inset - 1);
        int radius = Math.max(2, Math.min(lane.height / 4, Math.max(3, lane.width / 2)));
        g.setColor(ink);
        switch (value.status) {
            case PASSED -> { g.drawLine(x, y, x + w / 3, y + radius / 2); g.drawLine(x + w / 3, y + radius / 2, x + w, y - radius); }
            case FAILED -> { g.drawLine(x, y - radius, x + w, y + radius); g.drawLine(x, y + radius, x + w, y - radius); }
            case PENDING -> glyph(g, lane, '\u2026', ink);
            case SKIPPED -> glyph(g, lane, '/', ink);
            case ABORTED -> glyph(g, lane, '!', ink);
            case UNKNOWN -> glyph(g, lane, '\u00b7', ink);
        }
        if (value.freshness == Freshness.STALE) {
            g.setColor(color("status", "stale"));
            g.drawLine(lane.x + 2, lane.y + lane.height - 3, lane.x + lane.width - 2, lane.y + lane.height - 3);
        }
    }

    private void paintCoverage(Graphics2D g, Rectangle lane, Coverage value) {
        if (value.freshness() == Freshness.STALE) { glyph(g, lane, '~', color("status", "stale")); return; }
        if (value.freshness() == Freshness.UNKNOWN && value.state() != CoverageState.NOT_APPLICABLE) {
            glyph(g, lane, '?', color("status", "unknown")); return;
        }
        int width = Math.max(4, lane.width - 4), height = Math.min(Math.max(6, lane.height / 2), width + 4);
        int x = lane.x + (lane.width - width) / 2, y = lane.y + (lane.height - height) / 2;
        switch (value.state()) {
            case COVERED -> { g.setColor(color("coverage", "covered")); g.fillRect(x, y, width, height); }
            case UNCOVERED -> { g.setColor(color("coverage", "uncovered")); g.drawRect(x, y, width - 1, height - 1); }
            case PARTIAL -> {
                g.setColor(color("coverage", "covered")); g.fillRect(x, y, width / 2, height);
                g.setColor(color("coverage", "uncovered")); g.drawRect(x + width / 2, y, width - width / 2 - 1, height - 1);
            }
            case UNKNOWN -> glyph(g, lane, '?', color("status", "unknown"));
            case NOT_APPLICABLE -> glyph(g, lane, '\u00b7', color("status", "unknown"));
        }
    }

    public String describe(int line, Lane lane, Integer deletionAnchor) {
        if (snapshot == null) return "Line information is being refreshed.";
        if (lane == null) return "Line " + line + ". " + describe(line, Lane.GIT, null) + " "
                + describe(line, Lane.TEST, null) + " " + describe(line, Lane.COVERAGE, null);
        Line value = snapshot.line(line);
        if (lane == Lane.GIT) {
            if (!gitActive) return "Git: unavailable.";
            if (deletionAnchor != null) {
                int count = deletions.getOrDefault(deletionAnchor, List.of()).stream().mapToInt(Deletion::removedLines).sum();
                return "Git: " + count + " deleted line(s) at boundary " + deletionAnchor + ". Baseline: " + shortText(snapshot.baseline());
            }
            int count = deletions.getOrDefault(line - 1, List.of()).stream().mapToInt(Deletion::removedLines).sum();
            int after = deletions.getOrDefault(line, List.of()).stream().mapToInt(Deletion::removedLines).sum();
            return "Git: " + (value.added() ? "added" : "unchanged") + (count > 0 ? "; " + count + " deleted line(s) before this line" : "")
                    + (after > 0 ? "; " + after + " deleted line(s) after this line" : "")
                    + ". Baseline: " + shortText(snapshot.baseline());
        }
        if (!testingActive) return lane == Lane.TEST ? "Tests: not displayed." : "Coverage: not displayed.";
        if (lane == Lane.TEST) {
            if (value.tests().isEmpty()) return "Tests: no verified line-specific attribution.";
            StringBuilder text = new StringBuilder("Tests: ").append(value.testStatus()).append(" (").append(value.testFreshness()).append("). ");
            int count = 0;
            for (TestResult test : value.tests()) {
                if (count++ == 5) { text.append("More results in line details."); break; }
                text.append(shortText(test.name())).append(": ").append(test.status()).append(" (").append(test.freshness()).append("); ");
            }
            return text.toString();
        }
        Coverage coverage = value.coverage();
        StringBuilder text = new StringBuilder("Coverage: ").append(coverage.state()).append(" (").append(coverage.freshness()).append("). ");
        if (coverage.coveredInstructions() >= 0 && coverage.missedInstructions() >= 0)
            text.append("Instructions ").append(coverage.coveredInstructions()).append(" covered / ").append(coverage.missedInstructions()).append(" missed. ");
        if (coverage.coveredBranches() >= 0 && coverage.missedBranches() >= 0)
            text.append("Branches ").append(coverage.coveredBranches()).append(" covered / ").append(coverage.missedBranches()).append(" missed. ");
        text.append(coverage.availability()).append(". ").append(shortText(coverage.reason()));
        return text.toString();
    }

    private static String shortText(String text) { return text.length() <= 180 ? text : text.substring(0, 177) + "..."; }
}
