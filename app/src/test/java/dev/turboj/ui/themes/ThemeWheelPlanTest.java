package dev.turboj.ui.themes;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ThemeWheelPlanTest {
    private static List<ThemeCatalog.Entry> entries(int count) {
        var entries = new ArrayList<ThemeCatalog.Entry>();
        for (int i = 0; i < count; i++)
            entries.add(
                    new ThemeCatalog.Entry(
                            "theme:" + String.format("%03d", i),
                            "id_" + i,
                            "Theme " + i,
                            null,
                            null));
        return entries;
    }

    @Test
    void fullDurationRangeHasFastInitialMotionAndProgressivelySlowerCrossings() {
        var entries = entries(103);
        for (int duration = 3000; duration <= 5000; duration++) {
            int fixed = duration;
            var random =
                    new Random(1) {
                        @Override
                        public int nextInt(int origin, int bound) {
                            assertEquals(3000, origin);
                            assertEquals(5001, bound);
                            return fixed;
                        }
                    };
            var plan = ThemeWheelPlan.create(entries, random);
            assertEquals(duration, plan.durationMillis());
            assertEquals(duration, plan.deadlines().getLast());
            assertEquals(Math.round(duration / 125.0) + 1, plan.frames().size());
            assertTrue(plan.interval(0) >= 40 && plan.interval(0) <= 45);
            assertEquals(0, plan.positionAt(0));
            assertEquals(plan.deadlines().size(), plan.positionAt(duration));
            long prior = 0;
            for (int i = 0; i < plan.deadlines().size(); i++) {
                long interval = plan.interval(i);
                assertTrue(interval > 0);
                assertTrue(
                        interval + 1 >= prior,
                        "Cubic intervals allow one millisecond quantization");
                prior = interval;
            }
        }
    }

    @Test
    void fullFilteredPoolIsEligibleAndMotionEndsOnThatRealEntry() {
        var entries = entries(103);
        var random =
                new Random(3) {
                    @Override
                    public int nextInt(int bound) {
                        assertEquals(103, bound);
                        return 102;
                    }
                };
        var plan = ThemeWheelPlan.create(entries, random);
        assertSame(entries.getLast(), plan.target().entry());
        for (var frame : plan.frames()) {
            assertTrue(entries.contains(frame.entry()));
            assertEquals(5, frame.names().size());
            assertEquals(frame.entry().name(), frame.names().get(2));
        }
    }

    @Test
    void smallPoolsNeverCycleAndOneThemeNeedsNoSpin() {
        var entries = entries(3);
        var plan = ThemeWheelPlan.create(entries, new Random(7));
        assertEquals(3, plan.frames().size());
        assertEquals(3, plan.frames().stream().map(f -> f.entry().key()).distinct().count());
        assertEquals(plan, ThemeWheelPlan.create(entries, new Random(7)));
        assertTrue(ThemeWheelPlan.create(entries(1), new Random(1)).deadlines().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> ThemeWheelPlan.create(List.of(), new Random()));
    }

    @Test
    void wheelOrderUsesLuminanceWithoutChangingCatalogMetadataOrder() {
        var entries =
                List.of(
                        new ThemeCatalog.Entry("b", "b", "White", null, null, "#ffffff"),
                        new ThemeCatalog.Entry("a", "a", "Black", null, null, "#000000"),
                        new ThemeCatalog.Entry("c", "c", "Gray", null, null, "#808080"));
        var random =
                new Random(3) {
                    @Override
                    public int nextInt(int bound) {
                        return 2;
                    }
                };
        var plan = ThemeWheelPlan.create(entries, random);
        assertEquals(
                List.of("Black", "Gray", "White"),
                plan.frames().stream().map(f -> f.entry().name()).toList());
        assertEquals("White", entries.getFirst().name());
    }
}
