import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public interface Repository<K extends Comparable<? super K>, V extends Repository.Entity<K>> {
interface Entity<K> { K id(); }

Optional<V> findById(K id);

<R extends Collection<? super V>> R findAllMatching(Predicate<? super V> filter, Comparator<? super V> order, Supplier<R> collectionFactory, int limit);

static <K extends Comparable<? super K>, V extends Entity<K>> Map<String, List<V>> groupByIdPrefix(Collection<V> values, Function<? super K, String> prefix) {
return values.stream().collect(Collectors.groupingBy(value -> prefix.apply(value.id()), TreeMap::new, Collectors.toList()));
}
}
