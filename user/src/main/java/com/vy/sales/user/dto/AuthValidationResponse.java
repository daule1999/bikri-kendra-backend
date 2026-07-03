package com.vy.sales.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthValidationResponse {

  private boolean valid;
  private String username;
  private Long userId;
  private List<String> roles;
  private boolean mustResetPassword;
}
