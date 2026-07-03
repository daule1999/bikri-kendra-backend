package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.dto.report.AuditReportDto;
import com.vy.sales.sales_service.dto.report.FinancialReportDto;
import com.vy.sales.sales_service.dto.report.SalesReportDto;
import com.vy.sales.sales_service.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/sales-svc/reports")
@RequiredArgsConstructor
public class ReportController {

  private final ReportService reportService;

  // ── Existing internal reports ──────────────────────────────────────────

  @GetMapping("/master-settlement/{eventId}")
  public Mono<?> getMasterSettlement(@PathVariable Long eventId) {
    return reportService.getMasterSettlement(eventId);
  }

  @GetMapping("/3-way-match/{eventId}")
  public Mono<?> getThreeWayMatch(
      @PathVariable Long eventId,
      @RequestHeader(value = "Authorization", required = false) String authHeader) {
    return reportService.getThreeWayMatch(eventId, authHeader);
  }

  @GetMapping("/live-snapshot/{shopId}")
  public Mono<?> getLiveSnapshot(
      @PathVariable Long shopId,
      @RequestHeader(value = "X-Event-Id", required = true) Long eventId) {
    return reportService.getLiveSnapshot(shopId, eventId);
  }

  @GetMapping("/live-tally/{shopId}")
  public Mono<?> getLiveTallyUp(
      @PathVariable Long shopId,
      @RequestHeader(value = "X-Event-Id", required = true) Long eventId) {
    return reportService.getLiveTallyUp(shopId, eventId);
  }

  @GetMapping("/trust-score/{userId}")
  public Mono<?> getOperatorTrustScore(
      @PathVariable Long userId,
      @RequestHeader(value = "X-Event-Id", required = true) Long eventId) {
    return reportService.getOperatorTrustScore(userId, eventId);
  }

  // ── New report-page APIs ───────────────────────────────────────────────

  /**
   * Sales Report — returns issued vs sold data, fully computed in Java.
   *
   * <p>Route (via Traefik): GET /api/reports/sales
   *
   * <p>All query params are optional; omit to get all data.
   */
  @GetMapping("/sales")
  public Mono<SalesReportDto> getSalesReport(
      @RequestHeader(value = "X-Event-Id") Long eventId,
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @RequestParam(required = false) Long shopId,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long shiftSessionId) {
    log.info("GET /api/sales-svc/reports/sales eventId={}", eventId);
    return reportService.getSalesReport(
        eventId, shopId, categoryId, productId, shiftSessionId, authHeader);
  }

  /**
   * Financial Report — shift ledger with resolved usernames and pre-computed variance.
   *
   * <p>Route (via Traefik): GET /api/reports/financial
   */
  @GetMapping("/financial")
  public Mono<FinancialReportDto> getFinancialReport(
      @RequestHeader(value = "X-Event-Id") Long eventId,
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @RequestParam(required = false) Long shopId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    log.info(
        "GET /api/sales-svc/reports/financial eventId={} shopId={} status={}",
        eventId,
        shopId,
        status);
    return reportService.getFinancialReport(eventId, shopId, status, page, size, authHeader);
  }

  /**
   * Audit Report — POS data joined with inventory, 3-way-match computed in Java.
   *
   * <p>Route (via Traefik): GET /api/reports/audit
   */
  @GetMapping("/audit")
  public Mono<AuditReportDto> getAuditReport(
      @RequestHeader(value = "X-Event-Id") Long eventId,
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @RequestParam(required = false) Long shopId,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Long shiftSessionId,
      @RequestParam(required = false) Long productId) {
    log.info("GET /api/sales-svc/reports/audit eventId={}", eventId);
    return reportService.getAuditReport(
        eventId, shopId, categoryId, shiftSessionId, productId, authHeader);
  }
}
