package com.vy.sales.inventory.dto.report;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryIssuedRowDto {
  private Long id;
  private Long productId;
  private String productName;
  private Long categoryId;
  private String categoryName;
  private String shopId;
  private String shopName;
  private String sellerUser;
  private Integer totalIssuedQty;
  private Integer liveQuantity;
  private LocalDateTime saleDate;
  private LocalDateTime createdAt;
}
