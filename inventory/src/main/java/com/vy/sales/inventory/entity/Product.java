package com.vy.sales.inventory.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("product")
public class Product {

  @Id private Long id;

  @Column("category_id")
  private Long categoryId;

  private String name;
  private String sku;
  private String description;

  private BigDecimal mrp;
  private BigDecimal sellingPrice;
  private BigDecimal discount;

  @Column("min_threshold")
  private Integer minThreshold;

  private Boolean isActive;

  @Column("created_at")
  private LocalDateTime createdAt;

  @Column("updated_at")
  private LocalDateTime updatedAt;
}
