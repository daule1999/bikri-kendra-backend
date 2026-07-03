package com.vy.sales.billing.repository;

import com.vy.sales.billing.entity.InvoiceAudit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface InvoiceAuditRepository extends ReactiveCrudRepository<InvoiceAudit, Long> {}
