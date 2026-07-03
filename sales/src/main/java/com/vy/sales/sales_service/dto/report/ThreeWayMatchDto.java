package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreeWayMatchDto {
  private Long eventId;
  private List<MatchItem> items;
  private BigDecimal totalVarianceAmount;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class MatchItem {
    private Long productId;
    private String productName;
    private Integer expectedSalesQty;
    private Integer actualInventoryDepletedQty;
    private BigDecimal expectedSalesAmount;
    private BigDecimal actualFinancialsCollected;
    private Integer qtyVariance; // Expected Qty - Actual Depleted Qty
    private BigDecimal financialVariance; // Expected Amount - Collected Amount
  }
}
