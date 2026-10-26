package com.example.shop;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping(value = "/api/orders", produces = "application/json")
public class OrderController {
private final OrderService orders;

public OrderController(OrderService orders) { this.orders = orders; }

@GetMapping("/{id}")
public ResponseEntity<OrderDto> find(@PathVariable("id") long id, @RequestHeader(value = "X-Tenant", required = false) String tenant) {
return orders.find(id, tenant).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
}

@PostMapping @ResponseStatus(HttpStatus.CREATED)
public OrderDto create(@Valid @RequestBody CreateOrderRequest request, @RequestParam(defaultValue = "false") boolean dryRun) { return orders.create(request, dryRun); }

@GetMapping
public List<OrderDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return orders.page(page, size); }
}
