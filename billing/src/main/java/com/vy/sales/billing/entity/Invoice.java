package com.vy.sales.billing.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("invoices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

  @Id private Long id;

  private String invoiceNo;
  private String salesOrderNumber;

  private Long eventId; // event-scope: which event this invoice belongs to

  private String shopId;

  private Long sellerId;
  private String sellerName;

  private Long billedBy;

  private String customerName;
  private String customerMobile;
  private String customerGstin;

  private BigDecimal subtotalAmount;
  private BigDecimal discountAmount;
  private BigDecimal taxAmount;
  private BigDecimal netAmount;

  private String status;

  private LocalDateTime billingDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
