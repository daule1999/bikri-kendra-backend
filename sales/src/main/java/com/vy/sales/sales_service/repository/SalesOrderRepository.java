package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SalesOrder;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SalesOrderRepository extends ReactiveCrudRepository<SalesOrder, Long> {

  /** Find order by order number */
  Mono<SalesOrder> findByOrderNumber(String orderNumber);

  /** Get all sales for a seller */
  Flux<SalesOrder> findBySellerId(Long sellerId);

  /** Optional: check existence by order number (useful for idempotency) */
  Mono<Boolean> existsByOrderNumber(String orderNumber);

  Flux<SalesOrder> findByEventId(Long eventId);

  Flux<SalesOrder> findByShopId(String shopId);

  /** Paginated fetch for a given eventId, ordered by created_at DESC */
  @Query("SELECT * FROM sales_orders WHERE event_id = :eventId ORDER BY created_at DESC LIMIT :size OFFSET :offset")
  Flux<SalesOrder> findByEventIdPaged(Long eventId, int size, long offset);

  /** Total count for pagination metadata */
  @Query("SELECT COUNT(*) FROM sales_orders WHERE event_id = :eventId")
  Mono<Long> countByEventId(Long eventId);
}
