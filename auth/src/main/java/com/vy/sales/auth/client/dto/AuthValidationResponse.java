package com.vy.sales.auth.client.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor // required for Jackson deserialization when new field is present
public class AuthValidationResponse {
  private boolean valid;
  private String username;
  private Long userId;
  private List<String> roles;
  private boolean mustResetPassword;
}
