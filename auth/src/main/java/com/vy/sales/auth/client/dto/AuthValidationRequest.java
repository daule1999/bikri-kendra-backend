package com.vy.sales.auth.client.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AuthValidationRequest {

  @NotBlank private String username;

  @NotBlank private String password;
}
