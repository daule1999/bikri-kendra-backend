package com.vy.sales.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthValidationRequest {

  @NotBlank private String username;

  @NotBlank private String password;
}
