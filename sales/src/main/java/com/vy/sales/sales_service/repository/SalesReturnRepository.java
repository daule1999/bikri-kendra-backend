package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SalesReturn;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SalesReturnRepository extends ReactiveCrudRepository<SalesReturn, Long> {

  Flux<SalesReturn> findBySalesOrderId(Long salesOrderId);
}
