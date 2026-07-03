package com.vy.sales.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CounterStockDecrementRequest {
  private Long productId;
  private String shopId;
  private Integer quantity;
}
