package com.vy.sales.sales_service.dto;

import com.vy.sales.sales_service.client.InventoryClient;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the unified catalog response for a shop.
 *
 * <p>Returned by {@code GET /api/sales-svc/retail/shop/{shopId}/catalog}. Bundles all data the
 * Sales page needs on initial load or refresh into a single payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopCatalogDto {

  /** Preview of the next invoice number — counter is NOT advanced by fetching this. */
  private String nextInvoiceNumber;

  /** All inventory products for the event (used for the global type-ahead product search). */
  private List<InventoryClient.ProductDTO> products;

  /** Shop-specific counter stock entries (includes quantity info per product for this counter). */
  private List<ShopStockResponse> stocks;
}
