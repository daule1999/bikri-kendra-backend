package com.vy.sales.billing.dto;

import com.vy.sales.billing.entity.InvoiceItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class CreateInvoiceRequest {

  /* ---- Sales linkage ---- */
  @NotBlank private String salesOrderNumber;

  /* ---- Event scope ---- */
  @NotNull private Long eventId;

  /* ---- Counter & seller snapshot ---- */
  @NotBlank private String shopId;

  @NotNull private Long sellerId;

  @NotBlank private String sellerName;

  /* ---- Billing user ---- */
  @NotNull private Long billedBy;

  /* ---- Customer snapshot (optional) ---- */
  private String customerName;
  private String customerMobile;
  private String customerGstin;

  /* ---- Financials ---- */
  @NotNull private BigDecimal subtotalAmount;

  private BigDecimal discountAmount = BigDecimal.ZERO;
  private BigDecimal taxAmount = BigDecimal.ZERO;

  @NotNull private BigDecimal netAmount;

  /* ---- Line items ---- */
  @NotEmpty private List<InvoiceItemRequest> items;
  private String preGeneratedInvoiceNo;

  /* ================= INNER DTO ================= */

  @Data
  public static class InvoiceItemRequest {

    @NotNull private Long productId;

    @NotBlank private String productName;

    private String productSku;

    private String hsnCode;

    @NotNull private Integer quantity;

    @NotNull private BigDecimal unitPrice;

    private BigDecimal discount = BigDecimal.ZERO;

    private BigDecimal taxRate = BigDecimal.ZERO;
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @NotNull private BigDecimal lineTotal;

    public InvoiceItem toEntity(Long invoiceId) {
      return InvoiceItem.builder()
          .invoiceId(invoiceId)
          .productId(productId)
          .productName(productName)
          .productSku(productSku)
          .hsnCode(hsnCode)
          .quantity(quantity)
          .unitPrice(unitPrice)
          .discount(discount)
          .taxRate(taxRate)
          .taxAmount(taxAmount)
          .totalPrice(lineTotal)
          .build();
    }
  }
}
