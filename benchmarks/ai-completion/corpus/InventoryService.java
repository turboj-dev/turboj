package com.example.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InventoryService {

    private final Map<String, Integer> stock = new HashMap<>();

    public void receive(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        stock.merge(sku, quantity, Integer::sum);
    }

    public int available(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    public boolean reserve(String sku, int quantity) {
        int current = available(sku);
        if (current < quantity) {
            return false;
        }
        /*|*/
        return true;
    }

    public Optional<String> mostStocked() {
        return stock.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }
}
