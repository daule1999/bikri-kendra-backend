package com.vy.sales.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryEventSummaryDTO {
  private Long productId;
  private String shopId;
  private Integer initialQuantity;
  private Integer liveQuantity;
  private Integer depletedQuantity;
}
