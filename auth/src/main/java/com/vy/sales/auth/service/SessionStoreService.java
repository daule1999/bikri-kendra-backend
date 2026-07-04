package com.vy.sales.auth.service;

import com.vy.sales.auth.config.SessionStoreProperties;
import com.vy.sales.auth.model.AuthSession;
import com.vy.sales.auth.repository.AuthSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Step 1/2 of the DB+Redis session-store migration (see {@link SessionStoreProperties}).
 *
 * <p>Writes are additive and best-effort: nothing here is on a path that can fail a login/refresh
 * request, and nothing here is read on the request-validation hot path yet (that's Step 3, and it
 * runs in shadow/log-only mode until proven safe). All writes are gated behind {@code
 * security.session-store.enabled} so this can be deployed dark.
 *
 * <p>Redis key scheme:
 *
 * <ul>
 *   <li>{@code session:{jti}} → "{userId}|{username}" — TTL matches remaining token life. This is
 *       what Step 3's revocation check will read once cut over.
 *   <li>{@code user:{userId}:active-jti} → the most recently issued jti for that user — lets
 *       force-logout find and revoke/delete the current session without needing the token itself.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStoreService {

  private static final String SESSION_KEY_PREFIX = "session:jti:";
  private static final String ACTIVE_JTI_KEY_PREFIX = "user:active-jti:";

  private final SessionStoreProperties properties;
  private final AuthSessionRepository authSessionRepository;
  private final ReactiveStringRedisTemplate redisTemplate;

  /** Call on login/refresh once a new token has been issued. Never blocks the response on error. */
  public Mono<Void> recordNewSession(
      String jti, Long userId, String username, Duration ttl, LocalDateTime expiresAt) {
    if (!properties.isEnabled() || jti == null) {
      return Mono.empty();
    }

    AuthSession row =
        AuthSession.builder()
            .jti(jti)
            .userId(userId)
            .username(username)
            .issuedAt(LocalDateTime.now())
            .expiresAt(expiresAt)
            .build();

    Mono<Void> dbWrite =
        authSessionRepository
            .save(row)
            .doOnSuccess(saved -> log.debug("SESSION_STORE_DB_WRITE jti={} userId={}", jti, userId))
            .onErrorResume(
                e -> {
                  log.warn(
                      "SESSION_STORE_DB_WRITE_FAILED jti={} userId={} reason={} — continuing",
                      jti,
                      userId,
                      e.getMessage());
                  return Mono.empty();
                })
            .then();

    Mono<Void> redisWrite =
        redisTemplate
            .opsForValue()
            .set(SESSION_KEY_PREFIX + jti, userId + "|" + username, ttl)
            .then(
                redisTemplate
                    .opsForValue()
                    .set(ACTIVE_JTI_KEY_PREFIX + userId, jti, ttl))
            .doOnSuccess(ok -> log.debug("SESSION_STORE_REDIS_WRITE jti={} userId={}", jti, userId))
            .onErrorResume(
                e -> {
                  log.warn(
                      "SESSION_STORE_REDIS_WRITE_FAILED jti={} userId={} reason={} — continuing",
                      jti,
                      userId,
                      e.getMessage());
                  return Mono.empty();
                })
            .then();

    return Mono.when(dbWrite, redisWrite);
  }

  /**
   * Call on force-logout / explicit logout. Revokes the DB record(s) and deletes the active
   * Redis session key so Step 3's (eventual, non-shadow) check denies the token immediately.
   */
  public Mono<Void> revokeAllSessionsForUser(Long userId) {
    if (!properties.isEnabled() || userId == null) {
      return Mono.empty();
    }

    Mono<Void> dbRevoke =
        authSessionRepository
            .revokeAllForUser(userId, LocalDateTime.now())
            .doOnSuccess(count -> log.info("SESSION_STORE_DB_REVOKED userId={} rows={}", userId, count))
            .onErrorResume(
                e -> {
                  log.warn(
                      "SESSION_STORE_DB_REVOKE_FAILED userId={} reason={} — continuing",
                      userId,
                      e.getMessage());
                  return Mono.empty();
                })
            .then();

    Mono<Void> redisRevoke =
        redisTemplate
            .opsForValue()
            .get(ACTIVE_JTI_KEY_PREFIX + userId)
            .flatMap(jti -> redisTemplate.delete(SESSION_KEY_PREFIX + jti))
            .then(redisTemplate.delete(ACTIVE_JTI_KEY_PREFIX + userId))
            .doOnSuccess(ok -> log.info("SESSION_STORE_REDIS_REVOKED userId={}", userId))
            .onErrorResume(
                e -> {
                  log.warn(
                      "SESSION_STORE_REDIS_REVOKE_FAILED userId={} reason={} — continuing",
                      userId,
                      e.getMessage());
                  return Mono.empty();
                })
            .then();

    return Mono.when(dbRevoke, redisRevoke);
  }

  /** Shadow-mode read for Step 3: does the session:{jti} key exist right now? */
  public Mono<Boolean> sessionExists(String jti) {
    if (jti == null) {
      return Mono.just(false);
    }
    return redisTemplate.hasKey(SESSION_KEY_PREFIX + jti).defaultIfEmpty(false);
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }
}
