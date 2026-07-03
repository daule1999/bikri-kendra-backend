package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Aggregated totals for the Sales Report — computed in Java, rendered by the frontend as-is. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportSummaryDto {
  private long totalIssued;
  private long totalSold;
  private long totalReturned;
  private long totalNet;
  private BigDecimal totalCash;
  private BigDecimal totalOnline;
  private BigDecimal totalCollected;
}
