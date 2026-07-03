package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Top-level response for GET /api/reports/audit. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditReportDto {
  private List<AuditRowDto> auditRows;
  private ThreeWayMatchResultDto threeWayMatch;

  /** Embedded 3-way-match computation result — fully computed in Java. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ThreeWayMatchResultDto {
    private BigDecimal totalVarianceAmount;
    private List<ThreeWayMatchItemDto> items;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ThreeWayMatchItemDto {
    private Long productId;
    private String productName;
    private long expectedSalesQty;
    private long actualInventoryDepletedQty;
    private long qtyVariance;
    private BigDecimal expectedSalesAmount;
    private BigDecimal actualFinancialsCollected;
    private BigDecimal financialVariance;
  }
}
