package com.vy.sales.inventory.service;

import com.vy.sales.inventory.dto.report.InventoryReportDto;
import reactor.core.publisher.Mono;

public interface InventoryReportService {
  /**
   * Returns a combined inventory report for a given event. Both "issued" and "stock" sections are
   * computed in the backend — the frontend does zero math.
   *
   * @param eventId required — event scope
   * @param shopId optional — null means all shops
   * @param categoryId optional — null means all categories
   * @param productId optional — null means all products
   */
  Mono<InventoryReportDto> getInventoryReport(
      Long eventId, String shopId, Long categoryId, Long productId);
}
