package com.vy.sales.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingHistoryRow {

  // ── Invoice fields ──────────────────────────────────────────────────────────
  private Long invoiceId;
  private String invoiceNo;
  private String salesOrderNumber;   // join key
  private Long eventId;
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
  private String invoiceStatus;      // billing status: PAID, CANCELLED, RETURNED, …
  private LocalDateTime billingDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  // ── Sales order fields (from JOIN) ─────────────────────────────────────────
  private String orderStatus;        // order status: CONFIRMED, CANCELLED, …
  private String cancellationReason;
  private Boolean receivingPrinted;  // has receiving slip been printed?
}
