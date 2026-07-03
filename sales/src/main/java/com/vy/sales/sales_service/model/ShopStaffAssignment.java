package com.vy.sales.sales_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("shop_staff_assignment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopStaffAssignment {

  @Id private Long id;

  private Long shopId;
  private Long userId;
  private Long eventId;

  private String roleCode;

  private LocalDateTime assignedAt;
  private LocalDateTime leftAt;

  private Boolean isActive;
}
