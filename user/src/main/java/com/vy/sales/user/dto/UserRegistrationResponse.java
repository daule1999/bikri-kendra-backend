package com.vy.sales.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserRegistrationResponse {

  private Long userId;
  private String username;
  private String status;
}
