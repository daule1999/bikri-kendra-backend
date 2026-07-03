package com.vy.sales.auth.controller;

import com.vy.sales.auth.client.dto.AuthValidationResponse;
import com.vy.sales.auth.dto.AuthRequest;
import com.vy.sales.auth.dto.AuthResponse;
import com.vy.sales.auth.service.AuthService;
import com.vy.sales.platform.security.JwtUtil;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController()
@RequestMapping("/api/auth-svc")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

  private static final String SESSION_KEY_PREFIX = "session:";
  private static final String FORCE_LOGOUT_KEY_PREFIX = "force:logout:";

  private final JwtUtil jwtUtil;
  private final AuthService authService;
  private final ReactiveStringRedisTemplate redisTemplate;

  @Value("${security.jwt.access-token-expiry-miliseconds}")
  private long accessTokenExpiryMs;

  private Mono<AuthValidationResponse> validateCredentials(AuthRequest authRequest) {
    return authService.validateUser(authRequest);
  }

  @GetMapping("/hello")
  public Mono<String> hello() {
    log.info("Hello world");
    return Mono.just("Hello world");
  }

  // ── LOGIN ──────────────────────────────────────────────────────────────────

  @PostMapping("/login")
  public Mono<ResponseEntity<?>> login(@RequestBody AuthRequest ar) {
    log.info("Login attempt for username={}", ar.getUsername());

    return validateCredentials(ar)
        .flatMap(
            authValidationResponse -> {
              if (!authValidationResponse.isValid()) {
                log.warn("Login failed for username={} : Invalid credentials", ar.getUsername());
                return Mono.just(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Credentials"));
              }

              Long userId = authValidationResponse.getUserId();
              log.info("Login successful for username={} userId={}", ar.getUsername(), userId);

              return Mono.fromCallable(
                      () -> {
                        String accessToken =
                            jwtUtil.generateAccessToken(
                                ar.getUsername(), userId, authValidationResponse.getRoles());
                        String refreshToken =
                            jwtUtil.generateRefreshToken(
                                ar.getUsername(), userId, authValidationResponse.getRoles());
                        return AuthResponse.builder()
                            .accessToken(accessToken)
                            .refreshToken(refreshToken)
                            .mustResetPassword(authValidationResponse.isMustResetPassword())
                            .build();
                      })
                  .subscribeOn(Schedulers.parallel())
                  .flatMap(
                      authResponse -> {
                        String sessionKey = SESSION_KEY_PREFIX + userId;
                        String forceLogoutKey = FORCE_LOGOUT_KEY_PREFIX + userId;
                        Duration ttl = Duration.ofMillis(accessTokenExpiryMs);

                        // Fetch old session token to invalidate from Caffeine
                        return redisTemplate
                            .opsForValue()
                            .get(sessionKey)
                            .doOnNext(
                                oldToken -> {
                                  jwtUtil.invalidate(oldToken);
                                  log.debug("LOGIN_OLD_SESSION_INVALIDATED userId={}", userId);
                                })
                            .then(
                                // Clear force-logout flag + store new session token
                                redisTemplate
                                    .delete(forceLogoutKey)
                                    .then(
                                        redisTemplate
                                            .opsForValue()
                                            .set(sessionKey, authResponse.getAccessToken(), ttl)))
                            .doOnSuccess(
                                ok ->
                                    log.info(
                                        "LOGIN_SESSION_STORED userId={} ttl={}ms",
                                        userId,
                                        accessTokenExpiryMs))
                            .onErrorResume(
                                e -> {
                                  log.warn(
                                      "LOGIN_SESSION_REDIS_ERROR userId={} reason={} — continuing",
                                      userId,
                                      e.getMessage());
                                  return Mono.empty();
                                })
                            .thenReturn(ResponseEntity.ok().body(authResponse));
                      });
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.warn(
                      "Login failed for username={} : User not found / empty validation",
                      ar.getUsername());
                  return Mono.just(
                      ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Credentials"));
                }))
        .doOnError(ex -> log.error("Login error for username={}", ar.getUsername(), ex));
  }

  // ── VERIFY ─────────────────────────────────────────────────────────────────

  @GetMapping("/verify")
  public Mono<ResponseEntity<Void>> verify(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
      @RequestHeader("X-Forwarded-Uri") String originalUri,
      @RequestHeader(value = "X-Forwarded-Method", required = false) String method,
      @RequestHeader(value = "X-Forwarded-Host", required = false) String host) {

    log.info("AUTH_VERIFY_REQUEST method={} uri={} host={}", method, originalUri, host);

    // 1️⃣ Missing token
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("AUTH_DENIED reason=NO_TOKEN uri={}", originalUri);
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    final String token = authHeader.substring(7);

    record AuthVerifyResult(
        boolean isValid, String username, List<String> roles, Long userId, String newToken) {}

    // 2️⃣ Parse + validate JWT (CPU-bound, runs on parallel scheduler)
    return Mono.fromCallable(
            () -> {
              if (!jwtUtil.validateToken(token)) {
                return new AuthVerifyResult(false, null, null, null, null);
              }
              String username = jwtUtil.extractUsername(token);
              List<String> roles = jwtUtil.extractRoles(token);
              Long userId = jwtUtil.extractUserId(token);
              String newToken =
                  !jwtUtil.isTokenExpired(token)
                      ? jwtUtil.generateAccessToken(username, userId, roles)
                      : null;
              return new AuthVerifyResult(true, username, roles, userId, newToken);
            })
        .subscribeOn(Schedulers.parallel())
        .flatMap(
            result -> {
              if (!result.isValid()) {
                log.warn("AUTH_DENIED reason=INVALID_OR_EXPIRED_TOKEN uri={}", originalUri);
                return Mono.<ResponseEntity<Void>>just(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
              }

              if (result.username() == null || result.roles() == null || result.userId() == null) {
                log.warn("AUTH_DENIED reason=INCOMPLETE_TOKEN_CLAIMS uri={}", originalUri);
                return Mono.<ResponseEntity<Void>>just(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
              }

              // 3️⃣ Force-logout check — gap-2 safe:
              //    Missing key = user predates this feature = allow through
              //    Present key = force-logout was issued = block with 401
              return redisTemplate
                  .hasKey(FORCE_LOGOUT_KEY_PREFIX + result.userId())
                  .flatMap(
                      forceLoggedOut -> {
                        if (Boolean.TRUE.equals(forceLoggedOut)) {
                          log.warn(
                              "AUTH_DENIED reason=FORCE_LOGGED_OUT userId={} uri={}",
                              result.userId(),
                              originalUri);
                          return Mono.<ResponseEntity<Void>>just(
                              ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
                        }

                        // 4️⃣ Role-based authorization
                        // AUTHZ-03: the real route is /api/users-svc/register (Traefik does NOT
                        // strip the prefix). The previous check used /api/users/register and never
                        // matched, leaving registration ungated at the gateway.
                        boolean isAdmin = result.roles().contains("ADMIN");
                        boolean adminOnly =
                            // user registration
                            originalUri.startsWith("/api/users-svc/register")
                                // admin password resets
                                || originalUri.contains("/admin-reset-password")
                                // destructive operations on users, roles, permissions
                                || ("DELETE".equalsIgnoreCase(method)
                                    && originalUri.startsWith("/api/users-svc/"))
                                // role / permission matrix changes
                                || (!"GET".equalsIgnoreCase(method)
                                    && (originalUri.startsWith("/api/users-svc/roles")
                                        || originalUri.startsWith("/api/users-svc/permissions")
                                        || originalUri.startsWith(
                                            "/api/users-svc/roles-permission")))
                                // event create/update/delete (tenant root)
                                || (!"GET".equalsIgnoreCase(method)
                                    && originalUri.startsWith("/api/sales-svc/events"));

                        if (adminOnly && !isAdmin) {
                          log.warn(
                              "AUTH_FORBIDDEN user={} roles={} method={} uri={}",
                              result.username(),
                              result.roles(),
                              method,
                              originalUri);
                          return Mono.<ResponseEntity<Void>>just(
                              ResponseEntity.status(HttpStatus.FORBIDDEN).build());
                        }

                        // 5️⃣ Success
                        log.info(
                            "AUTH_ALLOWED user={} roles={} uri={}",
                            result.username(),
                            result.roles(),
                            originalUri);

                        ResponseEntity.BodyBuilder response =
                            ResponseEntity.ok()
                                .header("X-User-Id", String.valueOf(result.userId()))
                                .header("X-Username", result.username())
                                .header("X-Roles", String.join(",", result.roles()));

                        if (result.newToken() != null) {
                          log.info(
                              "AUTH_TOKEN_REFRESHED user={} uri={}",
                              result.username(),
                              originalUri);
                          response.header("X-New-Access-Token", result.newToken());
                        }

                        return Mono.<ResponseEntity<Void>>just(response.build());
                      })
                  .onErrorResume(
                      e -> {
                        // Redis down — fail open (allow) to avoid auth outage
                        log.warn(
                            "AUTH_VERIFY_REDIS_ERROR userId={} uri={} reason={} — failing open",
                            result.userId(),
                            originalUri,
                            e.getMessage());
                        ResponseEntity.BodyBuilder response =
                            ResponseEntity.ok()
                                .header("X-User-Id", String.valueOf(result.userId()))
                                .header("X-Username", result.username())
                                .header("X-Roles", String.join(",", result.roles()));
                        return Mono.just(response.build());
                      });
            });
  }

  // ── REFRESH ────────────────────────────────────────────────────────────────

  /** Request body for POST /refresh. */
  public record RefreshRequest(String refreshToken) {}

  /**
   * Exchanges a valid refresh token for a new access token.
   *
   * <p>Previously this endpoint did not exist — the UI proxy (/api/auth/refresh) and the print
   * station both called it and always got 404, so every session hard-expired when the original
   * access token hit its TTL. This is the server half of the "logged out after inactivity" fix.
   *
   * <p>Response: {"accessToken": "...", "refreshToken": null, "mustResetPassword": false}. The
   * refresh token is NOT rotated — clients keep using the one issued at login (7d TTL).
   */
  @PostMapping("/refresh")
  public Mono<ResponseEntity<?>> refresh(@RequestBody RefreshRequest body) {
    if (body == null || body.refreshToken() == null || body.refreshToken().isBlank()) {
      log.warn("REFRESH_DENIED reason=NO_TOKEN");
      return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing refreshToken"));
    }

    final String refreshToken = body.refreshToken();

    return Mono.fromCallable(
            () -> {
              if (!jwtUtil.validateToken(refreshToken)) {
                return null; // expired / bad signature
              }
              String username = jwtUtil.extractUsername(refreshToken);
              Long userId = jwtUtil.extractUserId(refreshToken);
              List<String> roles = jwtUtil.extractRoles(refreshToken);
              if (username == null || userId == null) {
                return null;
              }
              String newAccessToken = jwtUtil.generateAccessToken(username, userId, roles);
              return AuthResponse.builder().accessToken(newAccessToken).build();
            })
        .subscribeOn(Schedulers.parallel())
        // fromCallable(() -> null) completes EMPTY — invalid/expired token lands here, not in
        // flatMap
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.warn("REFRESH_DENIED reason=INVALID_OR_EXPIRED_REFRESH_TOKEN");
                  return Mono.empty();
                }))
        .<ResponseEntity<?>>flatMap(
            authResponse -> {
              Long userId = jwtUtil.extractUserId(refreshToken);
              String sessionKey = SESSION_KEY_PREFIX + userId;
              String forceLogoutKey = FORCE_LOGOUT_KEY_PREFIX + userId;
              Duration ttl = Duration.ofMillis(accessTokenExpiryMs);

              // Respect force-logout: a force-logged-out user must re-authenticate.
              return redisTemplate
                  .hasKey(forceLogoutKey)
                  .flatMap(
                      forcedOut -> {
                        if (Boolean.TRUE.equals(forcedOut)) {
                          log.warn("REFRESH_DENIED reason=FORCE_LOGGED_OUT userId={}", userId);
                          return Mono.<ResponseEntity<?>>just(
                              ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
                        }
                        return redisTemplate
                            .opsForValue()
                            .set(sessionKey, authResponse.getAccessToken(), ttl)
                            .doOnSuccess(ok -> log.info("REFRESH_SESSION_STORED userId={}", userId))
                            .thenReturn((ResponseEntity<?>) ResponseEntity.ok(authResponse));
                      })
                  .onErrorResume(
                      e -> {
                        // Redis down — still hand out the token (same fail-open policy as /verify)
                        log.warn(
                            "REFRESH_REDIS_ERROR userId={} reason={} — failing open",
                            userId,
                            e.getMessage());
                        return Mono.just(ResponseEntity.ok(authResponse));
                      });
            })
        .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
        .doOnError(ex -> log.error("Refresh error", ex));
  }

  // ── FORCE LOGOUT ───────────────────────────────────────────────────────────

  @PostMapping("/force-logout/{userId}")
  public Mono<ResponseEntity<Void>> forceLogout(
      @PathVariable Long userId,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

    log.info("FORCE_LOGOUT_REQUEST userId={}", userId);

    // Gap-1 fix: manually validate caller is ADMIN (route is under permitAll in Traefik)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("FORCE_LOGOUT_DENIED reason=NO_TOKEN userId={}", userId);
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    String callerToken = authHeader.substring(7);
    if (!jwtUtil.validateToken(callerToken)) {
      log.warn("FORCE_LOGOUT_DENIED reason=INVALID_TOKEN userId={}", userId);
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    List<String> callerRoles = jwtUtil.extractRoles(callerToken);
    if (!callerRoles.contains("ADMIN")) {
      log.warn("FORCE_LOGOUT_FORBIDDEN caller roles={} userId={}", callerRoles, userId);
      return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    String sessionKey = SESSION_KEY_PREFIX + userId;
    String forceLogoutKey = FORCE_LOGOUT_KEY_PREFIX + userId;
    Duration ttl = Duration.ofMillis(accessTokenExpiryMs);

    Mono<ResponseEntity<Void>> result =
        redisTemplate
            .opsForValue()
            .get(sessionKey)
            .doOnNext(
                storedToken -> {
                  jwtUtil.invalidate(storedToken);
                  log.info("FORCE_LOGOUT_CAFFEINE_EVICTED userId={}", userId);
                })
            .then(redisTemplate.delete(sessionKey))
            .then(redisTemplate.opsForValue().set(forceLogoutKey, "1", ttl))
            .doOnSuccess(ok -> log.info("FORCE_LOGOUT_SUCCESS userId={}", userId))
            .thenReturn(ResponseEntity.<Void>ok().build());

    return result.onErrorResume(
        e -> {
          log.error("FORCE_LOGOUT_REDIS_ERROR userId={} reason={}", userId, e.getMessage(), e);
          return Mono.just(ResponseEntity.<Void>status(HttpStatus.INTERNAL_SERVER_ERROR).build());
        });
  }
}
