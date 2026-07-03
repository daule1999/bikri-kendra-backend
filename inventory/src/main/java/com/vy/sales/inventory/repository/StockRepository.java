package com.vy.sales.inventory.repository;

import com.vy.sales.inventory.entity.Stock;
import java.util.List;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface StockRepository extends ReactiveCrudRepository<Stock, Long> {
  Mono<Stock> findByProductIdAndEventIdAndLocation(Long productId, Long eventId, String location);

  @org.springframework.data.r2dbc.repository.Query(
      "SELECT * FROM inventory_stocks WHERE product_id = :productId AND event_id = :eventId AND location = :location FOR UPDATE")
  Mono<Stock> findByProductIdAndEventIdAndLocationForUpdate(
      Long productId, Long eventId, String location);

  Flux<Stock> findByEventIdIn(List<Long> eventIds);

  Mono<Stock> findByIdAndEventId(Long id, Long eventId);

  Mono<Boolean> existsByIdAndEventId(Long id, Long eventId);

  Mono<Void> deleteByIdAndEventId(Long id, Long eventId);

  Flux<Stock> findByEventId(Long eventId);
}
