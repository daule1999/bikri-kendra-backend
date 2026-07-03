package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight response for the GET /shifts/{id}/expected-cash endpoint. Contains the
 * live-calculated expected closing balances for an open shift, derived from SUM queries over sales
 * and returns (not a hot-row column).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExpectedCashResponse {
  private Long sessionId;
  private BigDecimal expectedClosingCash;
  private BigDecimal expectedClosingOnline;
  private BigDecimal openingCashBalance;
}
