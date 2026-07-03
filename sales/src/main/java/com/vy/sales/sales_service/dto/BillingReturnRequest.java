package com.vy.sales.sales_service.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingReturnRequest {
  private String reason;
  private List<ReturnItem> items;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ReturnItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double unitPrice;
  }
}
