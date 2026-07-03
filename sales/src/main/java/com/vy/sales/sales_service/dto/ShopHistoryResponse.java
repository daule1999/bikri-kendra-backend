package com.vy.sales.sales_service.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopHistoryResponse {
  private ShopResponse shop;
  private List<HistoryEvent> events;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HistoryEvent {
    private String type; // SHOP_OPENED, STAFF_ASSIGNED, STAFF_UNASSIGNED, STOCK_ISSUE,
    // STOCK_UNISSUE, SHOP_CLOSED, SALE, SALE_CANCELLED, SALE_PARTIALLY_RETURNED
    private String description;
    private LocalDateTime timestamp;
    private Map<String, Object> metadata;
  }
}
