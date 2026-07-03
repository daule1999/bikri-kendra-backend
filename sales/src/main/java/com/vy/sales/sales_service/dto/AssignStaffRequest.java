package com.vy.sales.sales_service.dto;

import lombok.Data;

@Data
public class AssignStaffRequest {
  private Long shopId;
  private Long userId;
  private String roleCode;
}
