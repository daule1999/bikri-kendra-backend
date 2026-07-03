package com.vy.sales.billing.repository;

import com.vy.sales.billing.entity.Invoice;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface InvoiceRepository extends ReactiveCrudRepository<Invoice, Long> {
  Mono<Invoice> findByInvoiceNoAndEventId(String invoiceNo, Long eventId);

  Mono<Invoice> findBySalesOrderNumberAndEventId(String salesOrderNumber, Long eventId);

  reactor.core.publisher.Flux<Invoice> findAllByEventId(Long eventId);
}
