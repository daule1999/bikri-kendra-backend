package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row in the Audit Report (per product × shop × shift). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditRowDto {
  private Long shiftSessionId;
  private Long shopId;
  private String shopName;
  private Integer counterNumber;
  private String shopSupervisorUsername;
  private String shopSupervisorName;

  private Long productId;
  private String productName;
  private Long categoryId;
  private String categoryName;

  private BigDecimal sellingPrice;

  // Inventory fields (from inventory-service counter_stocks)
  private Integer issuedQty;
  private Integer remainingQty;

  // Sales fields
  private Long soldQty;
  private Long returnedQty;
  private Long netQty;

  // Financial fields
  private BigDecimal cashCollected;
  private BigDecimal onlineCollected;
  private BigDecimal totalCollected;
}
