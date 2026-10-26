import java.util.*;
import java.util.stream.*;

public class Reports {
record Sale(String region, String product, int quantity, double price) {}

static Map<String, Double> revenueByRegion(List<Sale> sales) {
return sales.stream().filter(sale -> sale.quantity() > 0).collect(Collectors.groupingBy(Sale::region, TreeMap::new, Collectors.summingDouble(sale -> sale.quantity() * sale.price())));
}

static List<String> topProducts(List<Sale> sales, int limit) {
return sales.stream().collect(Collectors.groupingBy(Sale::product, Collectors.summingInt(Sale::quantity))).entrySet().stream()
.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit).map(entry -> entry.getKey() + " (" + entry.getValue() + ")").toList();
}

static Runnable reporter(List<Sale> sales) {
return () -> revenueByRegion(sales).forEach((region, total) -> sales.stream().filter(sale -> sale.region().equals(region)).findFirst().ifPresent(first -> System.out.println(region + ": " + total + " (first: " + first.product() + ")")));
}
}
