package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SaleSagaLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SagaLogRepository extends ReactiveCrudRepository<SaleSagaLog, Long> {

  /** Find all saga log entries for a given order number (for ops tooling / dashboards). */
  Flux<SaleSagaLog> findByOrderNumber(String orderNumber);

  /** Find all unresolved (FAILED) entries for alerting or batch reconciliation. */
  Flux<SaleSagaLog> findByStatus(String status);
}
