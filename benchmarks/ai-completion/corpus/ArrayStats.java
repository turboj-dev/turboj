import java.util.Arrays;

public class ArrayStats {

    private final int[] values;

    public ArrayStats(int[] values) {
        this.values = Arrays.copyOf(values, values.length);
    }

    public int sum() {
        int total = 0;
        for (int i = 0; /*|*/) {
            total += values[i];
        }
        return total;
    }

    public double average() {
        if (values.length == 0) {
            return 0.0;
        }
        return (double) sum() / values.length;
    }

    public int max() {
        int max = Integer.MIN_VALUE;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }
}
