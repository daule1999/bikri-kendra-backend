package com.vy.sales.sales_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

  @Id private Long id;

  private String eventName;
  private String eventType;
  private String description;
  private String location;

  private LocalDateTime startDate;
  private LocalDateTime endDate;

  private Boolean isActive;

  private String receiptConfig;

  /** userId of the admin who created this event */
  private Long createdBy;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
