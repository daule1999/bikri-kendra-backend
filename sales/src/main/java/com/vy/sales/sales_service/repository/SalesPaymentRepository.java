package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.SalesPayment;
import java.math.BigDecimal;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface SalesPaymentRepository extends ReactiveCrudRepository<SalesPayment, Long> {

  @Query(
      """
        SELECT COALESCE(SUM(p.cash_amount), 0)
        FROM sales_payment p
        INNER JOIN sales_order o ON p.sales_order_id = o.id
        WHERE o.shift_session_id = :shiftSessionId
          AND p.payment_status = 'SUCCESS'
    """)
  Mono<BigDecimal> sumCashPaymentsBySession(Long shiftSessionId);

  @Query(
      """
        SELECT COALESCE(SUM(p.online_amount), 0)
        FROM sales_payment p
        INNER JOIN sales_order o ON p.sales_order_id = o.id
        WHERE o.shift_session_id = :shiftSessionId
          AND p.payment_status = 'SUCCESS'
    """)
  Mono<BigDecimal> sumOnlinePaymentsBySession(Long shiftSessionId);

  reactor.core.publisher.Flux<SalesPayment> findBySalesOrderId(Long salesOrderId);
}
