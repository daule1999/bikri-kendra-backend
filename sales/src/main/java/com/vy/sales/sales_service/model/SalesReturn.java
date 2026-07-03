package com.vy.sales.sales_service.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("sales_return")
public class SalesReturn {

  @Id private Long id;

  private Long salesOrderId;
  private Long salesOrderItemId;
  private Long productId;

  private Long processedBy;
  private String processedByName;

  private Integer quantity;
  private BigDecimal refundAmount;
  private String reason;
  private String billingInvoiceNumber;

  private LocalDateTime returnedAt;
}
