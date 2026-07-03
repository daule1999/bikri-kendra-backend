package com.vy.sales.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnissueItemRequest {
  private Long productId;
  private Integer quantity;
  private String reason; // optional
}
