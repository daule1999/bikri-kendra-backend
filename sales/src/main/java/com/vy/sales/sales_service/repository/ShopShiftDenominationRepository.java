package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.ShopShiftDenomination;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ShopShiftDenominationRepository
    extends ReactiveCrudRepository<ShopShiftDenomination, Long> {
  Flux<ShopShiftDenomination> findByShiftSessionId(Long shiftSessionId);
}
