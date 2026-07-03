package com.vy.sales.billing.dto;

import com.vy.sales.billing.entity.InvoiceStatus;
import com.vy.sales.billing.entity.PaymentMode;
import com.vy.sales.billing.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InvoiceResponse {

  /* ---- Invoice header ---- */
  private Long id;
  private String invoiceNo;
  private String salesOrderNumber;

  private String counterNo;

  private Long sellerId;
  private String sellerName;

  private Long billedBy;

  /* ---- Customer snapshot ---- */
  private String customerName;
  private String customerMobile;
  private String customerGstin;

  /* ---- Financials ---- */
  private BigDecimal subtotalAmount;
  private BigDecimal discountAmount;
  private BigDecimal taxAmount;
  private BigDecimal netAmount;

  private BigDecimal totalPaid;
  private BigDecimal balanceAmount;

  private InvoiceStatus status;

  private LocalDateTime billingDate;
  private LocalDateTime createdAt;

  /* ---- Details ---- */
  private List<Item> items;
  private List<Payment> payments;

  /* ================= INNER DTOs ================= */

  @Data
  @Builder
  public static class Item {
    private Long productId;
    private String productName;
    private String hsnCode;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal discount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;
  }

  @Data
  @Builder
  public static class Payment {
    private Long id;
    private PaymentMode paymentMode;
    private String paymentReference;
    private BigDecimal amount;
    private PaymentStatus paymentStatus;
    private LocalDateTime paidAt;
    private Long receivedBy;
  }
}
