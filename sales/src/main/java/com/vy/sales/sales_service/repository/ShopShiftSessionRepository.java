package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.ShopShiftSession;
import java.util.List;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ShopShiftSessionRepository extends ReactiveCrudRepository<ShopShiftSession, Long> {

  @Query(
      """
        SELECT * FROM shop_shift_session
        WHERE shop_id = :shopId
          AND event_id = :eventId
          AND status = 'OPEN'
        ORDER BY opened_at DESC
        LIMIT 1
    """)
  Mono<ShopShiftSession> findActiveSession(Long shopId, Long eventId);

  @Query(
      """
        SELECT * FROM shop_shift_session
        WHERE shop_id IN (:shopIds)
          AND event_id = :eventId
          AND status = 'OPEN'
    """)
  reactor.core.publisher.Flux<ShopShiftSession> findActiveShiftsBulk(
      @Param("shopIds") List<Long> shopIds, @Param("eventId") Long eventId);

  @Query("SELECT * FROM shop_shift_session WHERE event_id = :eventId")
  reactor.core.publisher.Flux<ShopShiftSession> findByEventId(Long eventId);

  @Query(
      """
        SELECT COALESCE(SUM(r.refund_amount), 0)
        FROM sales_return r
        INNER JOIN sales_order o ON r.sales_order_id = o.id
        WHERE o.shift_session_id = :shiftSessionId
    """)
  Mono<java.math.BigDecimal> sumCashRefundsBySession(Long shiftSessionId);

  @Query(
      """
        SELECT COALESCE(SUM(r.refund_amount), 0)
        FROM sales_return r
        INNER JOIN sales_order o ON r.sales_order_id = o.id
        WHERE o.shift_session_id = :shiftSessionId AND 1 = 0
    """)
  Mono<java.math.BigDecimal> sumOnlineRefundsBySession(Long shiftSessionId);

  @Query(
      """
        SELECT * FROM shop_shift_session
        WHERE shop_id = :shopId
          AND event_id = :eventId
        ORDER BY opened_at DESC
    """)
  reactor.core.publisher.Flux<ShopShiftSession> findSessionHistory(Long shopId, Long eventId);

  @Query(
      """
        SELECT * FROM shop_shift_session
        WHERE shop_id = :shopId
          AND event_id = :eventId
        ORDER BY opened_at DESC
        LIMIT :limit OFFSET :offset
    """)
  reactor.core.publisher.Flux<ShopShiftSession> findSessionHistoryPaged(
      Long shopId, Long eventId, int limit, int offset);

  @Query(
      """
        SELECT * FROM shop_shift_session
        WHERE shop_id IN (:shopIds)
          AND event_id = :eventId
        ORDER BY opened_at DESC
    """)
  reactor.core.publisher.Flux<ShopShiftSession> findSessionHistoryBulk(
      @Param("shopIds") List<Long> shopIds, @Param("eventId") Long eventId);
}
