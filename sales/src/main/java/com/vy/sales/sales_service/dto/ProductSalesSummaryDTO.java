package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSalesSummaryDTO {
  private Long productId;
  private String productName;
  private Long shopId;
  private Long shiftSessionId;
  private Long soldQty;
  private Long returnedQty;
  private BigDecimal cashCollected;
  private BigDecimal onlineCollected;
  private BigDecimal totalCollected;
}
