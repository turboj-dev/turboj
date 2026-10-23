/** Operations used to verify overload-aware source navigation. */
public class Calculator {
    /**
     * Adds two integer values.
     * @param left the first value
     * @param right the second value
     * @return the sum of the values
     */
    public int add(int left, int right) {
        return MathUtils.sum(left, right);
    }

    /**
     * Joins two strings without a separator.
     * @param left the first part
     * @param right the second part
     * @return the joined text
     */
    public String add(String left, String right) {
        return left + right;
    }
}
