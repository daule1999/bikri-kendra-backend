package com.vy.sales.inventory.repository;

import com.vy.sales.inventory.entity.Product;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
  Mono<Product> findBySku(String sku);
}
