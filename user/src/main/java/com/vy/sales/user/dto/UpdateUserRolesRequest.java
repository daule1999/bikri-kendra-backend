package com.vy.sales.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserRolesRequest {

  @NotNull private Long userId;

  // Optional: scope role assignments to a specific event
  private Long eventId;

  @NotEmpty private List<Long> roleIds; // Use role IDs from the database

  private Boolean isActive;
}
