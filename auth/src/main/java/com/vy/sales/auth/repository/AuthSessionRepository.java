package com.vy.sales.auth.repository;

import com.vy.sales.auth.model.AuthSession;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AuthSessionRepository extends ReactiveCrudRepository<AuthSession, Long> {

  Mono<AuthSession> findByJti(String jti);

  @Query(
      """
        UPDATE auth_sessions
        SET revoked_at = :revokedAt
        WHERE user_id = :userId AND revoked_at IS NULL
      """)
  Mono<Integer> revokeAllForUser(Long userId, LocalDateTime revokedAt);

  @Query(
      """
        UPDATE auth_sessions
        SET revoked_at = :revokedAt
        WHERE jti = :jti AND revoked_at IS NULL
      """)
  Mono<Integer> revokeByJti(String jti, LocalDateTime revokedAt);

  /**
   * Cleanup job target: rows past their expiry (plus a grace window handled by the caller) are
   * safe to delete — they can no longer be validated against regardless of revoked_at.
   */
  @Query("DELETE FROM auth_sessions WHERE expires_at < :cutoff")
  Mono<Integer> deleteExpiredBefore(LocalDateTime cutoff);
}
