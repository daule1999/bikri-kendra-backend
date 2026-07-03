package com.vy.sales.sales_service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.config.SalesUserServiceProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserClient {

  // Reads from cache keys written by user-service.
  // UserDTO is a subset of UserResponse — Jackson ignores extra fields.
  private static final String USERS_ALL_CACHE_KEY = "users:all";
  private static final String ROLES_ALL_CACHE_KEY = "roles:all";
  private static final java.time.Duration ROLES_TTL = java.time.Duration.ofMinutes(5);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final WebClient.Builder webClientBuilder;
  private final SalesUserServiceProperties properties;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  /**
   * Fetches all active event IDs assigned to the given user. Calls: GET
   * /api/users-svc/users/{userId}/events/ids
   */
  public Flux<Long> getAccessibleEventIds(Long userId, String authHeader) {
    log.info("USER_CLIENT_GET_EVENT_IDS userId={}", userId);
    return webClientBuilder
        .build()
        .get()
        .uri(properties.getBaseUrl() + "/api/users-svc/users/" + userId + "/events/ids")
        .header("Authorization", authHeader)
        .retrieve()
        .bodyToFlux(Long.class)
        .doOnComplete(() -> log.info("USER_CLIENT_GET_EVENT_IDS_SUCCESS userId={}", userId))
        .doOnError(
            ex ->
                log.error(
                    "USER_CLIENT_GET_EVENT_IDS_FAILED userId={} reason={}",
                    userId,
                    ex.getMessage(),
                    ex))
        .onErrorResume(
            ex -> {
              log.error(
                  "USER_CLIENT_GET_EVENT_IDS_ERROR userId={} reason={}", userId, ex.getMessage());
              return Flux.empty();
            });
  }

  /**
   * Fetches details of the user by ID. Checks Redis cache (user:id:{userId}) written by
   * user-service before making the HTTP call. Read-only consumer — does not write to Redis.
   */
  public Mono<UserDTO> getUserById(Long userId, String authHeader) {
    String cacheKey = "user:id:" + userId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("USER_CLIENT_CACHE_GET_ERROR userId={}, falling back to HTTP", userId, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                // UserResponse JSON → UserDTO (subset, extra fields ignored)
                UserDTO cached = CACHE_MAPPER.readValue(json, UserDTO.class);
                log.debug("USER_CLIENT_CACHE_HIT userId={}", userId);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn(
                    "USER_CLIENT_CACHE_DESERIALIZE_ERROR userId={}, falling back to HTTP",
                    userId,
                    e);
                return Mono.<UserDTO>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.info("USER_CLIENT_GET_USER_BY_ID userId={}", userId);
                  return webClientBuilder
                      .build()
                      .get()
                      .uri(properties.getBaseUrl() + "/api/users-svc/users/" + userId)
                      .header("Authorization", authHeader)
                      .retrieve()
                      .bodyToMono(UserDTO.class)
                      .doOnSuccess(
                          user -> log.info("USER_CLIENT_GET_USER_BY_ID_SUCCESS userId={}", userId))
                      .doOnError(
                          ex ->
                              log.error(
                                  "USER_CLIENT_GET_USER_BY_ID_FAILED userId={} reason={}",
                                  userId,
                                  ex.getMessage(),
                                  ex))
                      .onErrorResume(ex -> Mono.empty());
                }));
  }

  /**
   * Fetches all users. Reads Redis {@code users:all} (written by user-service UserService) before
   * making the HTTP call. Read-only — does not write to Redis.
   */
  public Mono<List<UserDTO>> getAllUsers(String authHeader) {
    return redisTemplate
        .opsForValue()
        .get(USERS_ALL_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("USER_CLIENT_ALL_USERS_CACHE_GET_ERROR, falling back to HTTP", e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                List<UserDTO> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<UserDTO>>() {});
                log.debug("USER_CLIENT_ALL_USERS_CACHE_HIT count={}", list.size());
                return Mono.just(list);
              } catch (Exception e) {
                log.warn("USER_CLIENT_ALL_USERS_CACHE_DESERIALIZE_ERROR, falling back to HTTP", e);
                return Mono.<List<UserDTO>>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.info("USER_CLIENT_ALL_USERS_HTTP_FETCH");
                  return webClientBuilder
                      .build()
                      .get()
                      .uri(properties.getBaseUrl() + "/api/users-svc/allUsers")
                      .header("Authorization", authHeader)
                      .retrieve()
                      .bodyToFlux(UserDTO.class)
                      .collectList()
                      .onErrorResume(
                          ex -> {
                            log.error(
                                "USER_CLIENT_ALL_USERS_HTTP_FAILED reason={}", ex.getMessage());
                            return Mono.just(List.of());
                          });
                }));
  }

  /**
   * Fetches all roles from users-service. Reads Redis {@code roles:all} before making the HTTP
   * call; writes back on a cache miss with a 5-minute TTL. Roles are nearly static so a longer TTL
   * is safe.
   */
  public Mono<List<RoleDTO>> getAllRoles(String authHeader) {
    return redisTemplate
        .opsForValue()
        .get(ROLES_ALL_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("USER_CLIENT_ROLES_CACHE_GET_ERROR, falling back to HTTP", e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                List<RoleDTO> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<RoleDTO>>() {});
                log.debug("USER_CLIENT_ROLES_CACHE_HIT count={}", list.size());
                return Mono.just(list);
              } catch (Exception e) {
                log.warn("USER_CLIENT_ROLES_CACHE_DESERIALIZE_ERROR, falling back to HTTP", e);
                return Mono.<List<RoleDTO>>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.info("USER_CLIENT_GET_ALL_ROLES");
                  return webClientBuilder
                      .build()
                      .get()
                      .uri(properties.getBaseUrl() + "/api/users-svc/roles")
                      .header("Authorization", authHeader)
                      .retrieve()
                      .bodyToFlux(RoleDTO.class)
                      .collectList()
                      .flatMap(
                          roles -> {
                            if (roles.isEmpty()) {
                              return Mono.just(roles);
                            }
                            try {
                              String json = CACHE_MAPPER.writeValueAsString(roles);
                              return redisTemplate
                                  .opsForValue()
                                  .set(ROLES_ALL_CACHE_KEY, json, ROLES_TTL)
                                  .onErrorResume(
                                      e -> {
                                        log.warn("USER_CLIENT_ROLES_CACHE_SET_ERROR", e);
                                        return Mono.empty();
                                      })
                                  .thenReturn(roles);
                            } catch (Exception e) {
                              log.warn("USER_CLIENT_ROLES_CACHE_SERIALIZE_ERROR", e);
                              return Mono.just(roles);
                            }
                          })
                      .onErrorResume(
                          ex -> {
                            log.error("USER_CLIENT_ROLES_HTTP_FAILED reason={}", ex.getMessage());
                            return Mono.just(List.of());
                          });
                }));
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class UserDTO {
    private Long id;
    private String username;
    private String email;
    private String mobile;
    private String fullName;
    private List<String> roles;
    private String status;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class RoleDTO {
    private Long id;
    private String name;
    private String description;
  }
}
