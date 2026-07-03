package com.vy.sales.inventory.repository;

import com.vy.sales.inventory.entity.CounterStock;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CounterStockRepository extends ReactiveCrudRepository<CounterStock, Long> {
  Flux<CounterStock> findByEventIdIn(java.util.List<Long> eventIds);

  Flux<CounterStock> findByEventId(Long eventId);

  Mono<CounterStock> findByProductIdAndShopIdAndEventId(
      Long productId, String shopId, Long eventId);

  @Query(
      "SELECT * FROM counter_stocks WHERE product_id = :productId AND shop_id = CAST(:shopId AS UNSIGNED) AND event_id = :eventId FOR UPDATE")
  Mono<CounterStock> findByProductIdAndShopIdAndEventIdForUpdate(
      Long productId, String shopId, Long eventId);

  @Query(
      "SELECT * FROM counter_stocks WHERE shop_id = CAST(:shopId AS UNSIGNED) AND event_id = :eventId AND live_quantity > 0")
  Flux<CounterStock> findByShopIdAndEventIdWithPositiveStock(String shopId, Long eventId);
}
