package com.vy.sales.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PermissionRequest {

  @NotBlank private String name;

  private String description;
}
