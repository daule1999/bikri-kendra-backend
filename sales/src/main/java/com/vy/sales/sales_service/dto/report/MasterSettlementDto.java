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
public class MasterSettlementDto {
  private Long eventId;
  private String eventName;
  private BigDecimal totalExpectedSales;
  private BigDecimal totalActualCollected;
  private BigDecimal totalVariance;
  private Integer totalItemsSold;
  private Integer totalItemsDepleted;

  private List<ShopSettlement> shopSettlements;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ShopSettlement {
    private Long shopId;
    private String shopName;
    private BigDecimal expectedAmount;
    private BigDecimal collectedAmount;
    private BigDecimal variance;
  }
}
