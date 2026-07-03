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
@Table("sales_order_item")
public class SalesOrderItem {

  @Id private Long id;

  private Long salesOrderId;

  private Long productId;
  private String productName;
  private String productSku;
  private String hsnCode;

  private Integer quantity;
  private BigDecimal mrp;
  private BigDecimal sellingPrice;
  private BigDecimal discount;

  private BigDecimal lineTotal;

  private Integer returnedQuantity;

  private LocalDateTime createdAt;
}
