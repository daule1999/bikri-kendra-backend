package com.vy.sales.auth.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Durable record of an issued access token. Written at login/refresh, marked revoked on
 * force-logout. NOT read on the request-validation hot path — that path reads the Redis
 * {@code session:{jti}} cache instead. This table exists for audit, cleanup, and "list active
 * sessions" style features, and as the source of truth if the Redis cache is ever flushed.
 */
@Table("auth_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession {

  @Id private Long id;

  private String jti;
  private Long userId;
  private String username;
  private LocalDateTime issuedAt;
  private LocalDateTime expiresAt;
  private LocalDateTime revokedAt;
}
