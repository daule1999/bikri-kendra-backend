package com.vy.sales.billing.repository;

import com.vy.sales.billing.entity.InvoiceItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface InvoiceItemRepository extends ReactiveCrudRepository<InvoiceItem, Long> {
  Flux<InvoiceItem> findByInvoiceId(Long invoiceId);
}
