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
@Table("sales_order")
public class SalesOrder {

  @Id private Long id;

  private String orderNumber;
  private Long eventId; // event-scope: which event this sale belongs to
  private Long shiftSessionId;
  private String shopId;

  private Long sellerId;
  private String sellerName;

  private String customerName;
  private String customerMobile;

  private BigDecimal orderSubtotal;
  private BigDecimal discountAmount;

  private String billingInvoiceNumber;

  private String status; // CREATED, CONFIRMED, CANCELLED
  private String cancellationReason;

  /**
   * Set to true once the receiving slip has been printed for this order. Prevents duplicate
   * receiving prints on history reprints.
   */
  private Boolean receivingPrinted;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
