package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.IdempotencyKey;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface IdempotencyRepository extends ReactiveCrudRepository<IdempotencyKey, Long> {

  Mono<IdempotencyKey> findByIdempotencyKey(String idempotencyKey);

  Mono<Void> deleteByIdempotencyKey(String idempotencyKey);
}
