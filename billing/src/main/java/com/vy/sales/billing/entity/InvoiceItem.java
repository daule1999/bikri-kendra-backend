package com.vy.sales.billing.entity;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("invoice_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItem {

  @Id private Long id;

  private Long invoiceId;

  private Long productId;
  private String productName;
  private String productSku;
  private String hsnCode;

  private Integer quantity;
  private BigDecimal unitPrice;
  private BigDecimal discount;

  private BigDecimal taxRate;
  private BigDecimal taxAmount;

  private BigDecimal totalPrice;
  private Integer returnedQuantity;
}
