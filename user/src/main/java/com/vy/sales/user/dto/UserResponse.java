package com.vy.sales.user.dto;

import com.vy.sales.user.entity.UserStatus;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.Data;

@Data
public class UserResponse {

  private Long id;
  private String username;
  private String email;
  private String mobile;
  private String fullName;
  private UserStatus status;
  private LocalDateTime createdAt;
  private Integer counterNumber;
  private Set<String> roles;
  private Set<Long> assignedEvents;
}
