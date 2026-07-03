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
public class EventRequest {
  private String eventName;
  private String eventType;
  private String description;
  private String location;
  private LocalDateTime startDate;
  private LocalDateTime endDate;
  private Boolean isActive;
  private Object receiptConfig;
}
