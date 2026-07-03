package com.vy.sales.inventory.repository;

import com.vy.sales.inventory.entity.StockMovement;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface StockMovementRepository extends ReactiveCrudRepository<StockMovement, Long> {

  Flux<StockMovement> findByShopId(Long shopId);

  @Query(
      """
        SELECT COALESCE(SUM(
            CASE
                WHEN movement_type = 'IN' AND (location_to = 'MAIN' OR location_to IS NULL) THEN quantity
                WHEN movement_type = 'OUT' AND (location_from = 'MAIN' OR location_from IS NULL) THEN -quantity
                WHEN movement_type = 'ADJUSTMENT' AND (location_to = 'MAIN' OR location_to IS NULL) THEN quantity
                WHEN movement_type = 'TRANSFER' AND location_from = 'MAIN' AND location_to != 'MAIN' THEN -quantity
                WHEN movement_type = 'TRANSFER' AND location_to = 'MAIN' AND location_from != 'MAIN' THEN quantity
                ELSE 0
            END
        ), 0)
        FROM stock_movement
        WHERE product_id = :productId AND event_id = :eventId
    """)
  Mono<Integer> calculateStock(Long productId, Long eventId);
}
