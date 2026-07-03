package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row in the Sales Report — one (product × shop × shift) combination. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportRowDto {
  private Long shopId;
  private String shopName;
  private Integer counterNumber;
  private Long productId;
  private String productName;
  private Long categoryId;
  private String categoryName;
  private Long shiftSessionId;

  // Inventory-side fields (from counter_stocks via inventory-service)
  private Integer issuedQty;
  private Integer remainingQty;

  // Sales-side fields (from sales_order_item)
  private Long soldQty;
  private Long returnedQty;
  private Long netQty; // soldQty - returnedQty

  // Financial fields
  private BigDecimal sellingPrice;
  private BigDecimal cashCollected;
  private BigDecimal onlineCollected;
  private BigDecimal totalCollected;
}
