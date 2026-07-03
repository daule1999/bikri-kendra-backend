package com.vy.sales.sales_service.service;

import com.vy.sales.sales_service.dto.report.AuditReportDto;
import com.vy.sales.sales_service.dto.report.FinancialReportDto;
import com.vy.sales.sales_service.dto.report.MasterSettlementDto;
import com.vy.sales.sales_service.dto.report.SalesReportDto;
import com.vy.sales.sales_service.dto.report.ThreeWayMatchDto;
import reactor.core.publisher.Mono;

public interface ReportService {
  Mono<MasterSettlementDto> getMasterSettlement(Long eventId);

  Mono<ThreeWayMatchDto> getThreeWayMatch(Long eventId, String authHeader);

  Mono<Object> getLiveSnapshot(Long shopId, Long eventId);

  Mono<Object> getLiveTallyUp(Long shopId, Long eventId);

  Mono<Object> getOperatorTrustScore(Long userId, Long eventId);

  // ── New report-page APIs ──────────────────────────────────────────────

  /**
   * Sales report with issued vs sold data, pre-joined and totalled in Java. All params except
   * eventId are optional; null means "all".
   */
  Mono<SalesReportDto> getSalesReport(
      Long eventId,
      Long shopId,
      Long categoryId,
      Long productId,
      Long shiftSessionId,
      String authHeader);

  /**
   * Financial (shift ledger) report with resolved usernames and pre-computed variance. shopId and
   * status are optional.
   */
  Mono<FinancialReportDto> getFinancialReport(
      Long eventId, Long shopId, String status, int page, int size, String authHeader);

  /**
   * Audit report including 3-way-match — computed entirely on the backend. shopId, categoryId,
   * shiftSessionId, productId are optional.
   */
  Mono<AuditReportDto> getAuditReport(
      Long eventId,
      Long shopId,
      Long categoryId,
      Long shiftSessionId,
      Long productId,
      String authHeader);
}
