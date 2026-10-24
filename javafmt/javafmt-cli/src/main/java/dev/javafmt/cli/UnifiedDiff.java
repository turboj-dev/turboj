package dev.javafmt.cli;

import java.util.Arrays;
import java.util.List;

/** One bounded-memory unified hunk with three context lines; no quadratic diff matrix. */
final class UnifiedDiff {
    private record Lines(List<String> values, boolean terminated) {
        static Lines of(String text) {
            if (text.isEmpty()) return new Lines(List.of(), true);
            String[] split = text.split("\n", -1);
            return new Lines(
                    Arrays.asList(
                            text.endsWith("\n") ? Arrays.copyOf(split, split.length - 1) : split),
                    text.endsWith("\n"));
        }
    }

    private UnifiedDiff() {}

    static String create(String name, String original, String formatted) {
        if (original.equals(formatted)) return "";
        Lines a = Lines.of(original), b = Lines.of(formatted);
        int prefix = 0, suffix = 0;
        while (prefix < a.values.size()
                && prefix < b.values.size()
                && equalLine(a, prefix, b, prefix)) prefix++;
        while (suffix < a.values.size() - prefix
                && suffix < b.values.size() - prefix
                && equalLine(a, a.values.size() - suffix - 1, b, b.values.size() - suffix - 1))
            suffix++;
        int from = Math.max(0, prefix - 3), contextAfter = Math.min(3, suffix);
        int oldEnd = a.values.size() - suffix, newEnd = b.values.size() - suffix;
        int oldCount = oldEnd + contextAfter - from, newCount = newEnd + contextAfter - from;
        StringBuilder result =
                new StringBuilder("--- a/")
                        .append(name)
                        .append("\n+++ b/")
                        .append(name)
                        .append("\n@@ -")
                        .append(range(from, oldCount))
                        .append(" +")
                        .append(range(from, newCount))
                        .append(" @@\n");
        for (int i = from; i < prefix; i++) append(result, ' ', a, i);
        for (int i = prefix; i < oldEnd; i++) append(result, '-', a, i);
        for (int i = prefix; i < newEnd; i++) append(result, '+', b, i);
        for (int i = 0; i < contextAfter; i++) append(result, ' ', a, oldEnd + i);
        return result.toString();
    }

    private static boolean equalLine(Lines a, int ai, Lines b, int bi) {
        return a.values.get(ai).equals(b.values.get(bi))
                && (ai != a.values.size() - 1 || a.terminated)
                        == (bi != b.values.size() - 1 || b.terminated);
    }

    private static String range(int zeroStart, int count) {
        return (count == 0 ? zeroStart : zeroStart + 1) + "," + count;
    }

    private static void append(StringBuilder result, char prefix, Lines lines, int index) {
        result.append(prefix).append(lines.values.get(index)).append('\n');
        if (index == lines.values.size() - 1 && !lines.terminated)
            result.append("\\ No newline at end of file\n");
    }
}
