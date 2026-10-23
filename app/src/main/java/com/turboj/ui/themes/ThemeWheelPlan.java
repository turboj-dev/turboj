package com.turboj.ui.themes;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Deterministic motion after one uniform pick; motion follows a cubic ease-out over real
 * luminance-ordered themes.
 */
public record ThemeWheelPlan(long durationMillis, List<Long> deadlines, List<Frame> frames) {
    public record Frame(ThemeCatalog.Entry entry, List<String> names) {
        public Frame {
            names = List.copyOf(names);
        }
    }

    public ThemeWheelPlan {
        deadlines = List.copyOf(deadlines);
        frames = List.copyOf(frames);
    }

    public static ThemeWheelPlan create(List<ThemeCatalog.Entry> matches, RandomGenerator random) {
        if (matches.isEmpty()) throw new IllegalArgumentException("No matching themes");
        List<ThemeCatalog.Entry> ordered =
                matches.stream()
                        .sorted(
                                Comparator.comparingDouble(
                                                (ThemeCatalog.Entry e) ->
                                                        luminance(e.previewBackground()))
                                        .thenComparing(ThemeCatalog.Entry::key))
                        .toList();
        int target = random.nextInt(ordered.size());
        int duration = random.nextInt(3_000, 5_001);
        int steps = Math.min(ordered.size() - 1, (int) Math.round(duration / 125.0));
        var deadlines = new ArrayList<Long>(steps);
        for (int crossing = 1; crossing <= steps; crossing++)
            deadlines.add(
                    crossing == steps
                            ? (long) duration
                            : Math.round(
                                    duration * (1 - Math.cbrt(1 - (double) crossing / steps))));
        var frames = new ArrayList<Frame>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            int center = Math.floorMod(target - steps + i, ordered.size());
            var names = new ArrayList<String>(5);
            for (int delta = -2; delta <= 2; delta++)
                names.add(ordered.get(Math.floorMod(center + delta, ordered.size())).name());
            frames.add(new Frame(ordered.get(center), names));
        }
        return new ThemeWheelPlan(duration, deadlines, frames);
    }

    private static double luminance(String color) {
        int rgb = Integer.parseInt(color.substring(1), 16);
        return .2126 * linear((rgb >> 16) & 255)
                + .7152 * linear((rgb >> 8) & 255)
                + .0722 * linear(rgb & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }

    public double positionAt(long elapsedMillis) {
        double t = Math.clamp((double) elapsedMillis / durationMillis, 0, 1);
        return deadlines.size() * (1 - Math.pow(1 - t, 3));
    }

    public Frame target() {
        return frames.getLast();
    }

    public long interval(int step) {
        return deadlines.get(step) - (step == 0 ? 0 : deadlines.get(step - 1));
    }
}
