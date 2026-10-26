import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SalesReport {

    public record Sale(String region, String product, double amount) {
    }

    private final List<Sale> sales;

    public SalesReport(List<Sale> sales) {
        this.sales = List.copyOf(sales);
    }

    public Map<String, Double> totalByRegion() {
        return sales.stream()
                .collect(/*|*/);
    }

    public double grandTotal() {
        return sales.stream().mapToDouble(Sale::amount).sum();
    }
}
