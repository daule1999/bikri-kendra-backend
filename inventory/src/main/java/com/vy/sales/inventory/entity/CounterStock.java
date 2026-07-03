package com.vy.sales.inventory.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("counter_stocks")
public class CounterStock {

  @Id private Long id;

  private Long productId;
  private Long eventId;
  private Integer initialQuantity;
  private Integer liveQuantity;

  @org.springframework.data.annotation.Transient private Integer quantity;

  // Who sold the product (employee / cashier)
  private String sellerUser;
  private String shopId;
  private LocalDateTime saleDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
