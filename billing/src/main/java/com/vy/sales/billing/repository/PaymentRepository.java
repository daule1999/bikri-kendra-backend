package com.vy.sales.billing.repository;

import com.vy.sales.billing.dto.InvoiceResponse;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface PaymentRepository extends ReactiveCrudRepository<InvoiceResponse.Payment, Long> {}
