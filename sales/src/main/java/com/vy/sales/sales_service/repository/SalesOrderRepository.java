package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SalesOrder;
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
}
