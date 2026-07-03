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
@Table("sales_payment")
public class SalesPayment {

  @Id private Long id;

  private Long salesOrderId;

  private String paymentMode; // CASH, UPI, CARD
  private String paymentReference;

  private BigDecimal amount;
  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  // Bug A fix: record cash tendered and change returned for CASH mode invoices
  private BigDecimal cashReceived;
  private BigDecimal changeGiven;
  private String paymentStatus; // SUCCESS, FAILED, REFUNDED

  private Long eventId;

  private LocalDateTime paidAt;
}
