import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrderIndex {

    public record Order(String id, String customer, double amount) {
    }

    private final Map<String, List<Order>> byCustomer = new HashMap<>();

    public void add(Order order) {
        byCustomer./*|*/
    }

    public List<Order> ordersFor(String customer) {
        return byCustomer.getOrDefault(customer, List.of());
    }

    public int customerCount() {
        return byCustomer.size();
    }
}
