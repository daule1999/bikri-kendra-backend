package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SalesOrderItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SalesOrderItemRepository extends ReactiveCrudRepository<SalesOrderItem, Long> {

  Flux<SalesOrderItem> findBySalesOrderId(Long salesOrderId);
}
