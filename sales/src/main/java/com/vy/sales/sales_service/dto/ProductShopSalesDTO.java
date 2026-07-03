package com.vy.sales.sales_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductShopSalesDTO {
  private Long productId;
  private Long shopId;
  private Long totalSold;
}
