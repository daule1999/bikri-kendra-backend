package com.vy.sales.user.dto;

import java.time.LocalDateTime;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserRolesResponse {

  private Long userId;
  private String username;
  private Set<String> roles; // Role names (ADMIN, CASHIER, etc.)
  private String status;
  private LocalDateTime updatedAt;
}
