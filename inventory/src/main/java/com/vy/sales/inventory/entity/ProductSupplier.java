package com.vy.sales.inventory.entity;

import lombok.*;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("product_supplier")
public class ProductSupplier {

  private Long productId;
  private Long supplierId;
}
