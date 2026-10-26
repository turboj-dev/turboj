public final class BinarySearch {

    private BinarySearch() {
    }

    public static int indexOf(int[] sorted, int target) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            /*|*/
        }
        return -1;
    }

    public static boolean contains(int[] sorted, int target) {
        return indexOf(sorted, target) >= 0;
    }
}
