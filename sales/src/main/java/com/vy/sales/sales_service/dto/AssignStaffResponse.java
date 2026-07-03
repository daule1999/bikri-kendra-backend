package com.vy.sales.sales_service.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignStaffResponse {

  private Long shopId;
  private Long userId;
  private Long eventId;
  private String roleCode;

  private Boolean active;

  private LocalDateTime assignedAt;

  private String message;
}
