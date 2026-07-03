package com.vy.sales.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserAuthorizationInfo {

  private Long userId;
  private String username;
  private List<String> roles;
  private List<String> permissions;
}
