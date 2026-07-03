package com.vy.sales.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserUpdateRequest {
  private String email;
  private String mobile;
  private String password;
  private String fullName;
}
