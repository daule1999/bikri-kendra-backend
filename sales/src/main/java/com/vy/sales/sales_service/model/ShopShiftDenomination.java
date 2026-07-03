package com.vy.sales.sales_service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("shop_shift_denomination")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopShiftDenomination {

  @Id private Long id;

  private Long shiftSessionId;
  private String entryType; // 'OPENING', 'CLOSING', 'ADDITION'
  private Integer currencyValue;
  private Integer noteCount;
  private java.time.LocalDateTime createdAt;
}
