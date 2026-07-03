package com.vy.sales.sales_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopResponse {
  private Long id;
  private String shopName;
  private Long categoryId;
  private String categoryName;
  private Integer counterNumber;
  private Boolean isActive;
  private Long eventId; // ✅

  /** Per-shop receiving slip toggle — returned so the frontend can read it on load */
  private Boolean receivingPrintEnabled;

  private java.time.LocalDateTime createdAt;
  private java.time.LocalDateTime closedAt;
  private Boolean shiftOpen; // true = shift currently OPEN, false = closed/no shift
}
