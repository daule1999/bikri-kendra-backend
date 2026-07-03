/* Refreshed for event-scoped architecture */
package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.dto.ProductSalesSummaryDTO;
import com.vy.sales.sales_service.dto.ProductShopSalesDTO;
import com.vy.sales.sales_service.dto.report.InventoryEventSummaryDTO;
import java.util.Collection;
import reactor.core.publisher.Flux;

public interface SalesAnalyticsRepository {
  Flux<ProductShopSalesDTO> getProductShopSalesSummary();

  Flux<ProductShopSalesDTO> getProductShopSalesSummaryByEventId(Long eventId);

  Flux<ProductSalesSummaryDTO> getProductSalesSummary(
      Long eventId, Long shopId, Long shiftSessionId);

  // ── MONOLITH: direct SQL over the merged bikri_db (former inventory_db / user_db tables) ──
  // These replace the WebClient hops to inventory-service / user-service in ReportServiceImpl.
  // Same data, one database round-trip, no silent-zero failure mode.

  /** counter_stocks for an event — was GET /api/inventory/analytics/event-summary. */
  Flux<InventoryEventSummaryDTO> getInventoryEventSummary(Long eventId);

  /** product catalog with category — was GET /api/inventory-svc/products. */
  Flux<InventoryClient.ProductDTO> getProductCatalog();

  /** all categories — was InventoryClient.getAllCategories(). */
  Flux<InventoryClient.CategoryDTO> getAllCategories();

  /** users by id — was N× GET /api/users-svc/users/{id}. */
  Flux<UserClient.UserDTO> getUsersByIds(Collection<Long> ids);
}
