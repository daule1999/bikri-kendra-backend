package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopStockResponse {
  private Long id;
  private String name;
  private String sku;
  private Long categoryId;
  private BigDecimal sellingPrice;
  private BigDecimal mrp;
  private String hsnCode;
  private Integer shopStock;
  private Integer minThreshold;
}
