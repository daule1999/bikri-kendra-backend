package com.vy.sales.sales_service.dto.report;

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
  private Long shopId; // Changed to Long for consistency
  private Integer initialQuantity;
  private Integer liveQuantity;
  private Integer depletedQuantity;
}
