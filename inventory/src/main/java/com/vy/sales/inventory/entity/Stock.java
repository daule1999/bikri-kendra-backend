package com.vy.sales.inventory.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("inventory_stocks")
public class Stock {

  @Id private Long id;

  private Long productId;
  private Long eventId;
  private Integer quantity;
  private String location;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
