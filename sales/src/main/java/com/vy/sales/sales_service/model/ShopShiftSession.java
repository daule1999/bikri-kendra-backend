package com.vy.sales.sales_service.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("shop_shift_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopShiftSession {

  @Id private Long id;

  private Long shopId;
  private Long eventId;
  private String status; // 'OPEN', 'CLOSED', 'RECONCILED'
  private LocalDateTime openedAt;
  private LocalDateTime closedAt;

  private BigDecimal openingCashBalance;
  private BigDecimal expectedClosingCash;
  private BigDecimal actualClosingCash;
  private BigDecimal expectedClosingOnline;
  private BigDecimal actualClosingOnline;

  private Long openedByUserId;
  private Long closedByUserId;

  private Long reconciledByUserId;
  private java.time.LocalDateTime reconciledAt;
  private String reconciliationComment;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
