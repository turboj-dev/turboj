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
    private CoverageRowPalette rowPalette;
    private boolean showBackgrounds = true;
    private java.util.function.IntPredicate coverageVisible = line -> true;
    private final Map<Integer, Boolean> coverageVisibility = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) { return size() > 512; }
    };

    /** Display policy only: raw counters and test/Git evidence are never discarded. */
    public void setCoverageVisibility(java.util.function.IntPredicate visible) {
        coverageVisible = Objects.requireNonNull(visible);
        coverageVisibility.clear();
    }
    private boolean showCoverage(int line) { return coverageVisibility.computeIfAbsent(line, coverageVisible::test); }

    public void setShowBackgrounds(boolean visible) { showBackgrounds = visible; }
    public boolean showBackgrounds() { return showBackgrounds; }

    public Snapshot snapshot() { return snapshot; }
    public int leftCells() { return (gitActive ? 1 : 0) + (testingActive ? 1 : 0); }
    public int rightCells() { return testingActive ? 1 : 0; }
    public int cells() { return leftCells() + rightCells(); }
    public void setTheme(Theme value) { theme = Objects.requireNonNull(value); rowPalette = CoverageRowPalette.resolve(value); }
    public void setBackground(Color value) { setTheme(new Theme("gutter", "Gutter", Map.of("editor.background", value), 16, true)); }
    public void clear() { invalidate(); gitActive = testingActive = false; }
    /** Keep the active geometry while a changed source is being reconciled by its controller. */
    public void invalidate() { snapshot = null; deletions = Map.of(); summaries.clear(); coverageVisibility.clear(); }
    public void install(Snapshot value) {
        snapshot = value; summaries.clear(); coverageVisibility.clear();
        if (value == null) { clear(); return; }
        gitActive = value.gitActive(); testingActive = value.testingActive();
        var index = new HashMap<Integer, List<Deletion>>();
        if (gitActive) for (Deletion deletion : value.deletions())
            index.computeIfAbsent(deletion.anchorLine(), ignored -> new ArrayList<>()).add(deletion);
        deletions = index;
    }

    public Rectangle bounds(Lane lane, int cellWidth, int y, int height, int editorWidth) {
        if (lane == Lane.COVERAGE)
            return testingActive ? new Rectangle(Math.max(0, editorWidth - cellWidth), y, cellWidth, height) : new Rectangle();
        int column = switch (lane) {
            case GIT -> gitActive ? 0 : -1;
            case TEST -> testingActive ? (gitActive ? 1 : 0) : -1;
            case COVERAGE -> -1;
        };
        return column < 0 ? new Rectangle() : new Rectangle(column * cellWidth, y, cellWidth, height);
    }

    public Hit hit(Point point, int line, int rowY, int lineCount, int cellWidth, int cellHeight, int editorWidth) {
        if (snapshot == null || line < 1 || line > lineCount || point.y < rowY || point.y >= rowY + cellHeight) return null;
        for (Lane lane : Lane.values()) {
            Rectangle bounds = bounds(lane, cellWidth, rowY, cellHeight, editorWidth);
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

    public void paint(Graphics2D graphics, int line, int y, int lineCount, int cellWidth, int height, int editorWidth) {
        if (snapshot == null || line < 1 || line > lineCount) return;
        Line value = snapshot.line(line);
        if (gitActive) {
            Rectangle lane = bounds(Lane.GIT, cellWidth, y, height, editorWidth);
            if (value.added()) addition(graphics, lane);
            if (deletions.containsKey(line - 1)) deletion(graphics, lane, true);
            if (line == lineCount && deletions.containsKey(line)) deletion(graphics, lane, false);
        }
        // Empty lines with no evidence stay visually quiet, but their neutral state is inspectable.
        if (!testingActive || !snapshot.lines().containsKey(line)) return;
        if (!value.tests().isEmpty()) {
            Summary summary = summaries.computeIfAbsent(line, ignored -> new Summary(value.testStatus(), value.testFreshness()));
            paintTest(graphics, bounds(Lane.TEST, cellWidth, y, height, editorWidth), summary);
        }
        if (showCoverage(line) && !unmeasuredTestDeclaration(line, value))
            paintCoverage(graphics, bounds(Lane.COVERAGE, cellWidth, y, height, editorWidth), value.coverage());
    }

    private boolean unmeasuredTestDeclaration(int line, Line value) {
        return value.coverage().state() == CoverageState.UNKNOWN
                && value.coverage().freshness() != Freshness.STALE
                && value.tests().stream().flatMap(test -> test.associations().stream())
                .anyMatch(association -> association.kind() == Attribution.TEST_DECLARATION
                        && association.line() == line && snapshot.source().file().equals(association.file()));
    }

    private Color color(String group, String role) { return theme.color(group, role); }

    public Color sourceInk(int line,Color original) {
        if (!showBackgrounds || snapshot == null || !testingActive || !showCoverage(line)) return original;
        Coverage coverage = snapshot.line(line).coverage();
        if (coverage.freshness() != Freshness.CURRENT || coverage.availability() != Availability.AVAILABLE) return original;
        return switch (coverage.state()) {
            case COVERED, PARTIAL -> rowPalette.ink(original,false);
            case UNCOVERED -> rowPalette.ink(original,true);
            default -> original;
        };
    }

    /** Source-area coordinates, before selection/text; never paints the independent gutter lanes. */
    public void paintSourceBackground(Graphics2D g,int line,int width,int height) {
        if (!showBackgrounds || snapshot == null || !testingActive || !snapshot.lines().containsKey(line) || !showCoverage(line)) return;
        Coverage coverage = snapshot.line(line).coverage();
        if (coverage.freshness() != Freshness.CURRENT || coverage.availability() != Availability.AVAILABLE) return;
        switch (coverage.state()) {
            case COVERED, PARTIAL -> g.setColor(rowPalette.covered());
            case UNCOVERED -> g.setColor(rowPalette.uncovered());
            default -> { return; }
        }
        g.fillRect(0,0,width,height);
    }

    private void addition(Graphics2D g, Rectangle lane) {
        glyph(g,lane,'+',color("git","added"));
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
        glyph(g,lane,glyph,glyph == '\u00b7' ? '.' : glyph == '\u2026' ? ':' : '?',color);
    }

    private void glyph(Graphics2D g, Rectangle lane, char preferred, char fallback, Color color) {
        FontMetrics fm = g.getFontMetrics();
        char printed = g.getFont().canDisplay(preferred) && fm.charWidth(preferred) <= lane.width ? preferred : fallback;
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clip(lane); clipped.setColor(color);
            clipped.drawString(String.valueOf(printed), lane.x + (lane.width - fm.charWidth(printed)) / 2,
                    lane.y + (lane.height - fm.getHeight()) / 2 + fm.getAscent());
        } finally { clipped.dispose(); }
    }

    private void paintTest(Graphics2D g, Rectangle lane, Summary value) {
        if (value.freshness == Freshness.STALE) {
            glyph(g, lane, '~', color("status", "stale")); return;
        }
        Color ink = switch (value.status) {
            case PASSED -> color("test", "success");
            case FAILED -> color("test", "failure");
            case PENDING -> color("test", "pending");
            default -> color("test", "unknown");
        };
        switch (value.status) {
            case PASSED -> glyph(g,lane,'\u2713','v',ink);
            case FAILED -> glyph(g,lane,'\u00d7','x',ink);
            case PENDING -> glyph(g, lane, '\u2026', ink);
            case SKIPPED -> glyph(g, lane, '/', ink);
            case ABORTED -> glyph(g, lane, '!', ink);
            case UNKNOWN -> glyph(g, lane, '\u00b7', ink);
        }
    }

    private void paintCoverage(Graphics2D g, Rectangle lane, Coverage value) {
        if (value.freshness() == Freshness.STALE) { glyph(g, lane, '~', color("status", "stale")); return; }
        if (value.freshness() == Freshness.UNKNOWN && value.state() != CoverageState.NOT_APPLICABLE) {
            glyph(g, lane, '?', color("status", "unknown")); return;
        }
        switch (value.state()) {
            case COVERED -> glyph(g,lane,'\u25a0','C',color("coverage","covered"));
            case UNCOVERED -> glyph(g,lane,'\u25a1','U',color("coverage","uncovered"));
            case PARTIAL -> {
                glyph(g,lane,'\u25e7','P',color("coverage","covered"));
                Graphics2D right = (Graphics2D) g.create();
                try {
                    right.clipRect(lane.x + lane.width / 2,lane.y,lane.width - lane.width / 2,lane.height);
                    glyph(right,lane,'\u25e7','P',color("coverage","uncovered"));
                } finally { right.dispose(); }
            }
            case UNKNOWN -> glyph(g, lane, '?', color("status", "unknown"));
            case NOT_APPLICABLE -> { /* No coverage claim and no visual placeholder. Details remain inspectable. */ }
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
        if (!showCoverage(line)) text.insert(0, "Brace-only source line: coverage decoration hidden. Raw instrumentation data follows. ");
        else if (unmeasuredTestDeclaration(line, value)) text.insert(0, "Test declaration: no measured coverage; placeholder hidden. Test execution status is shown separately. ");
        if (coverage.coveredInstructions() >= 0 && coverage.missedInstructions() >= 0)
            text.append("Instructions ").append(coverage.coveredInstructions()).append(" covered / ").append(coverage.missedInstructions()).append(" missed. ");
        if (coverage.coveredBranches() >= 0 && coverage.missedBranches() >= 0)
            text.append("Branches ").append(coverage.coveredBranches()).append(" covered / ").append(coverage.missedBranches()).append(" missed. ");
        text.append(coverage.availability()).append(". ").append(shortText(coverage.reason()));
        return text.toString();
    }

    private static String shortText(String text) { return text.length() <= 180 ? text : text.substring(0, 177) + "..."; }
}
