package com.vy.sales.sales_service.client;

import com.vy.sales.sales_service.config.AuthServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthClient {

  private final WebClient.Builder webClientBuilder;
  private final AuthServiceProperties properties;

  /**
   * Fire-and-forget force-logout call to auth-service.
   *
   * <p>Asks auth-service to: 1. Evict the user's token from Caffeine cache. 2. DELETE
   * session:{userId} from Redis. 3. SET force:logout:{userId} = "1" in Redis (TTL = access-token
   * expiry).
   *
   * <p>Any error is swallowed and only logged — caller is not impacted.
   *
   * @param userId target user to force-logout
   * @param authHeader caller's Bearer token (must be ADMIN — validated inside auth-service)
   * @return empty Mono (fire-and-forget)
   */
  public Mono<Void> forceLogout(Long userId, String authHeader) {
    log.info("AUTH_CLIENT_FORCE_LOGOUT_REQUEST userId={}", userId);
    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + "/api/auth-svc/force-logout/" + userId)
        .header("Authorization", authHeader)
        .retrieve()
        .toBodilessEntity()
        .doOnSuccess(
            res ->
                log.info(
                    "AUTH_CLIENT_FORCE_LOGOUT_SUCCESS userId={} status={}",
                    userId,
                    res.getStatusCode()))
        .onErrorResume(
            ex -> {
              log.warn(
                  "AUTH_CLIENT_FORCE_LOGOUT_FAILED userId={} reason={} — continuing",
                  userId,
                  ex.getMessage());
              return Mono.empty();
            })
        .then();
  }
}
