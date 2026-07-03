package com.vy.sales.user.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("user_roles")
public class UserRole {

  private Long userId;
  private Long roleId;
  private Long eventId;
  private Boolean isActive;
  private LocalDateTime assignedAt;
}
