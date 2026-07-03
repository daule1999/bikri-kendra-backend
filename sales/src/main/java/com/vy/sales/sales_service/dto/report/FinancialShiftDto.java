package com.vy.sales.sales_service.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One shift row in the Financial Report — user IDs are resolved to usernames in the backend so the
 * frontend can render them directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialShiftDto {
  private Long id;
  private Long shopId;
  private String shopName;
  private Integer counterNumber;
  private String status;

  private LocalDateTime openedAt;
  private Long openedByUserId;
  private String openedByUsername;

  private LocalDateTime closedAt;
  private Long closedByUserId;
  private String closedByUsername;

  private LocalDateTime reconciledAt;
  private Long reconciledByUserId;
  private String reconciledByUsername;
  private String reconciliationComment;

  private BigDecimal openingCashBalance;
  private BigDecimal expectedClosingCash;
  private BigDecimal actualClosingCash;
  private BigDecimal actualClosingOnline;

  /** actualClosingCash - expectedClosingCash — computed in Java. */
  private BigDecimal variance;
}
