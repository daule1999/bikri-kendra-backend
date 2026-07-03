package com.vy.sales.sales_service.dto.report;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Top-level response for GET /api/reports/financial. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialReportDto {
  private List<FinancialShiftDto> shifts;
  private long totalCount;
  private boolean hasMore;
}
