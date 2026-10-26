import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class Comparables {

    private Comparables() {
    }

    public static <T extends Comparable<? super T>> T max(Collection<? extends T> items) {
        Iterator<? extends T> iterator = items.iterator();
        if (!iterator.hasNext()) {
            throw new NoSuchElementException("empty collection");
        }
        T best = iterator.next();
        while (iterator.hasNext()) {
            T candidate = iterator.next();
            /*|*/
        }
        return best;
    }

    public static <T extends Comparable<? super T>> T clamp(T value, T min, T max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }
}
