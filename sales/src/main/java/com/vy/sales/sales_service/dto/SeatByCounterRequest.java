package com.vy.sales.sales_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatByCounterRequest {

  private Long userId;
  private Integer counterNumber;
}
