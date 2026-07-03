package com.vy.sales.user.dto;

import com.vy.sales.user.entity.AppRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserRegistrationRequest {

  @NotBlank private String username;

  @Email private String email;

  @NotBlank private String mobile;

  @NotBlank
  @Size(min = 8)
  private String password;

  @NotBlank private String fullName;

  private AppRole role;

  private Integer counterNumber;

  private Long eventId;
}
