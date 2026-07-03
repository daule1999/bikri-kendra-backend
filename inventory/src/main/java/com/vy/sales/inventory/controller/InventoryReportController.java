package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.report.InventoryReportDto;
import com.vy.sales.inventory.service.InventoryReportService;
import com.vy.sales.inventory.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Inventory Report API — returns fully computed issued + stock data for a given event. The frontend
 * renders the response directly with zero JavaScript math.
 *
 * <p>Route (via Traefik): GET /api/inventory-svc/reports
 */
@RestController
@RequestMapping("/api/inventory-svc/reports")
@RequiredArgsConstructor
@Slf4j
public class InventoryReportController {

  private final InventoryReportService inventoryReportService;

  /**
   * Returns both "issued" and "stock" sections in a single response.
   *
   * <p>All filters are optional — omit a param to get data for all values.
   *
   * @param eventId Required — the active event scope (passed as X-Event-Id header)
   * @param shopId Optional — filter by shop counter ID
   * @param categoryId Optional — filter by product category ID
   * @param productId Optional — filter by specific product ID
   */
  @GetMapping
  public Mono<InventoryReportDto> getInventoryReport(
      @RequestHeader(value = AppConstants.X_EVENT_ID) Long eventId,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Long productId) {

    log.info(
        "GET /api/inventory-svc/reports eventId={} shopId={} categoryId={} productId={}",
        eventId,
        shopId,
        categoryId,
        productId);

    return inventoryReportService.getInventoryReport(eventId, shopId, categoryId, productId);
  }
}
