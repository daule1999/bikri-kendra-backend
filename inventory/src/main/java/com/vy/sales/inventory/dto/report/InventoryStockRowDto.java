package com.vy.sales.inventory.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryStockRowDto {
  private Long productId;
  private String productName;
  private Long categoryId;
  private String categoryName;
  private String shopId;
  private String shopName;
  private Integer initialQuantity;
  private Integer liveQuantity;
  private Integer depletedQuantity;
  private Integer inventoryStock;
  private String status; // IN_STOCK, LOW_STOCK, OUT_OF_STOCK
}
