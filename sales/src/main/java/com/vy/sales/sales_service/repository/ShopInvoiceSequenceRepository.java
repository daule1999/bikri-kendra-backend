package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.ShopInvoiceSequence;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ShopInvoiceSequenceRepository
    extends ReactiveCrudRepository<ShopInvoiceSequence, Long> {

  @Query(
      "SELECT * FROM shop_invoice_sequence WHERE shop_id = :shopId AND event_id = :eventId FOR UPDATE")
  Mono<ShopInvoiceSequence> findByShopIdAndEventIdForUpdate(Long shopId, Long eventId);

  Mono<ShopInvoiceSequence> findByShopIdAndEventId(Long shopId, Long eventId);
}
