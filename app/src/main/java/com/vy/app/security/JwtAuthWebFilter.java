package com.vy.app.security;

import com.vy.sales.auth.service.SessionStoreService;
import com.vy.sales.platform.security.JwtUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * In-process replacement for the Traefik forwardAuth middleware.
 *
 * <p>In the microservice deployment, Traefik called auth-service /verify for every request and
 * forwarded X-User-Id / X-Username / X-Roles headers to the target service. In the monolith there
 * is no gateway, so this filter does the same work: validates the Bearer token, enforces the
 * admin-only rules, checks the Redis force-logout flag, and injects the same X-* headers that
 * controllers already read.
 */
@Slf4j
@Component
@Order(-100)
@RequiredArgsConstructor
public class JwtAuthWebFilter implements WebFilter {

  private static final String FORCE_LOGOUT_KEY_PREFIX = "force:logout:";

  private final JwtUtil jwtUtil;
  private final ReactiveStringRedisTemplate redisTemplate;
  private final SessionStoreService sessionStoreService;

  private boolean isPublic(ServerHttpRequest req) {
    String p = req.getPath().value();
    return p.startsWith("/api/auth-svc/")
        || p.startsWith("/actuator")
        || p.equals("/")
        || HttpMethod.OPTIONS.equals(req.getMethod());
  }

  /**
   * Former service-to-service endpoints that were called WITHOUT an Authorization header inside
   * the docker network (never exposed through Traefik). In the monolith these calls self-loop over
   * localhost, so they are allowed only when the connection originates from loopback:
   *
   * <ul>
   *   <li>/api/users-svc/validate — auth module validates credentials during login
   *   <li>/api/sales-svc/events/exists — user module checks event existence
   *   <li>/api/inventory/analytics/** — sales reports pull inventory summaries
   * </ul>
   */
  private boolean isInternalSelfLoop(ServerHttpRequest req) {
    String p = req.getPath().value();
    boolean internalPath =
        p.equals("/api/users-svc/validate")
            || p.equals("/api/sales-svc/events/exists")
            || p.startsWith("/api/inventory/analytics/");
    if (!internalPath) {
      return false;
    }
    var remote = req.getRemoteAddress();
    return remote != null
        && remote.getAddress() != null
        && remote.getAddress().isLoopbackAddress();
  }

  /** Same admin-only rules the gateway enforced in AuthController.verify(). */
  private boolean isAdminOnly(String method, String uri) {
    return uri.startsWith("/api/users-svc/register")
        || uri.contains("/admin-reset-password")
        || ("DELETE".equalsIgnoreCase(method) && uri.startsWith("/api/users-svc/"))
        || (!"GET".equalsIgnoreCase(method)
            && (uri.startsWith("/api/users-svc/roles")
                || uri.startsWith("/api/users-svc/permissions")
                || uri.startsWith("/api/users-svc/roles-permission")))
        || (!"GET".equalsIgnoreCase(method) && uri.startsWith("/api/sales-svc/events"));
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest req = exchange.getRequest();
    if (isPublic(req) || isInternalSelfLoop(req)) {
      return chain.filter(exchange);
    }

    String authHeader = req.getHeaders().getFirst("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return deny(exchange, HttpStatus.UNAUTHORIZED, "NO_TOKEN", req);
    }
    final String token = authHeader.substring(7);

    if (!jwtUtil.validateToken(token)) {
      return deny(exchange, HttpStatus.UNAUTHORIZED, "INVALID_OR_EXPIRED_TOKEN", req);
    }

    String username = jwtUtil.extractUsername(token);
    Long userId = jwtUtil.extractUserId(token);
    List<String> roles = jwtUtil.extractRoles(token);
    if (username == null || userId == null) {
      return deny(exchange, HttpStatus.UNAUTHORIZED, "INCOMPLETE_TOKEN_CLAIMS", req);
    }

    String method = req.getMethod() != null ? req.getMethod().name() : "GET";
    if (isAdminOnly(method, req.getPath().value()) && !roles.contains("ADMIN")) {
      return deny(exchange, HttpStatus.FORBIDDEN, "NOT_ADMIN user=" + username, req);
    }

    Mono<Void> proceed =
        Mono.defer(
            () -> {
              ServerHttpRequest mutated =
                  req.mutate()
                      .headers(
                          h -> {
                            h.set("X-User-Id", String.valueOf(userId));
                            h.set("X-Username", username);
                            h.set("X-Roles", String.join(",", roles));
                          })
                      .build();
              return chain.filter(exchange.mutate().request(mutated).build());
            });

    // Step 3 (shadow mode only): compare what the NEW session:{jti} check would have decided
    // against the legacy force:logout:{userId} flag that actually gates the request below.
    // This never denies a request by itself — it only logs disagreements so we can verify
    // parity before cutting over. See SessionStoreService / SessionStoreProperties.
    if (sessionStoreService.isEnabled()) {
      String jti = jwtUtil.extractJti(token);
      sessionStoreService
          .sessionExists(jti)
          .subscribe(
              exists -> {
                if (!exists) {
                  log.info(
                      "AUTH_SHADOW_SESSION_MISS jti={} userId={} uri={} — would deny under new"
                          + " check, legacy check still authoritative",
                      jti,
                      userId,
                      req.getPath());
                }
              },
              e -> log.warn("AUTH_SHADOW_CHECK_ERROR userId={} reason={}", userId, e.getMessage()));
    }

    // Force-logout check — fail open on Redis errors (same policy as the old /verify)
    return redisTemplate
        .hasKey(FORCE_LOGOUT_KEY_PREFIX + userId)
        .flatMap(
            forcedOut ->
                Boolean.TRUE.equals(forcedOut)
                    ? deny(exchange, HttpStatus.UNAUTHORIZED, "FORCE_LOGGED_OUT", req)
                    : proceed)
        .onErrorResume(
            e -> {
              log.warn("AUTH_FILTER_REDIS_ERROR reason={} — failing open", e.getMessage());
              return proceed;
            });
  }

  private Mono<Void> deny(
      ServerWebExchange exchange, HttpStatus status, String reason, ServerHttpRequest req) {
    log.warn("AUTH_DENIED reason={} method={} uri={}", reason, req.getMethod(), req.getPath());
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }
}
