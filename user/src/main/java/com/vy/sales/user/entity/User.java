package com.vy.sales.user.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class User {

  @Id private Long id;

  private String username;

  private String email;

  private String mobile;

  @Column("password_hash")
  private String passwordHash;

  @Column("full_name")
  private String fullName;

  private UserStatus status;

  @Column("last_login_at")
  private LocalDateTime lastLoginAt;

  @Column("must_reset_password")
  @Builder.Default
  private Boolean mustResetPassword = false;
}
