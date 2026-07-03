package com.vy.sales.sales_service.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("shop")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shop {
  @Id private Long id;
  private String shopName;
  private Long categoryId;
  private Integer counterNumber;
  private Boolean isActive;
  private Long eventId; // ✅ LINK TO EVENT
  private String categoryName;

  /**
   * Per-shop receiving slip toggle. Both this AND event.receivingPrint.enabled must be true before
   * a receiving slip is printed after each sale.
   */
  private Boolean receivingPrintEnabled;

  private java.time.LocalDateTime createdAt;
  private java.time.LocalDateTime closedAt;
}
