package com.vy.sales.user.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.user.dto.UserAuthorizationInfo;
import com.vy.sales.user.entity.User;
import com.vy.sales.user.entity.UserStatus;
import com.vy.sales.user.repository.PermissionRepository;
import com.vy.sales.user.repository.RoleRepository;
import com.vy.sales.user.repository.UserRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserAuthorizationService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  // Called on every API request — highest-value cache in user-service.
  static final String AUTH_CACHE_PREFIX = "auth:info:";
  private static final Duration AUTH_TTL = Duration.ofMinutes(15);
  // Field-level visibility required: UserAuthorizationInfo has only @Getter (no setters).
  // Without this, Jackson creates the no-arg instance but cannot populate any fields.
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<UserAuthorizationInfo> getAuthorizationInfo(String username) {

    log.info("AUTHZ_LOOKUP_START username={}", username);

    String cacheKey = AUTH_CACHE_PREFIX + username;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Auth cache GET error username={}, falling back to DB", username, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                UserAuthorizationInfo cached =
                    CACHE_MAPPER.readValue(json, UserAuthorizationInfo.class);
                log.debug("Auth cache HIT username={}", username);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn(
                    "Auth cache deserialize error username={}, falling back to DB", username, e);
                return Mono.<UserAuthorizationInfo>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    userRepository
                        .findByUsername(username)
                        .switchIfEmpty(
                            Mono.defer(
                                () -> {
                                  log.warn("AUTHZ_USER_NOT_FOUND username={}", username);
                                  return Mono.error(
                                      new ResponseStatusException(
                                          HttpStatus.NOT_FOUND, "User not found"));
                                }))
                        .flatMap(this::validateUserStatus)
                        .flatMap(this::loadRolesAndPermissions)
                        .flatMap(
                            info -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(info);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, AUTH_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Auth cache SET error username={}", username, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(info);
                              } catch (Exception e) {
                                log.warn("Auth cache serialize error username={}", username, e);
                                return Mono.just(info);
                              }
                            })))
        .doOnSuccess(
            info ->
                log.info(
                    "AUTHZ_LOOKUP_SUCCESS userId={} username={} roles={} permissions={}",
                    info.getUserId(),
                    info.getUsername(),
                    info.getRoles().size(),
                    info.getPermissions().size()))
        .doOnError(
            ex ->
                log.error(
                    "AUTHZ_LOOKUP_FAILED username={} reason={}", username, ex.getMessage(), ex));
  }

  private Mono<User> validateUserStatus(User user) {
    if (user.getStatus() != UserStatus.ACTIVE) {
      log.warn(
          "AUTHZ_USER_INACTIVE userId={} username={} status={}",
          user.getId(),
          user.getUsername(),
          user.getStatus());

      return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is inactive"));
    }
    return Mono.just(user);
  }

  private Mono<UserAuthorizationInfo> loadRolesAndPermissions(User user) {
    if ("admin".equals(user.getUsername())) {
      return permissionRepository
          .findAll()
          .map(com.vy.sales.user.entity.Permission::getName)
          .collectList()
          .map(
              permissions ->
                  new UserAuthorizationInfo(
                      user.getId(), user.getUsername(), java.util.List.of("ADMIN"), permissions));
    }

    return Mono.zip(
            roleRepository.findRoleNamesByUserId(user.getId()).collectList(),
            permissionRepository.findPermissionsByUserId(user.getId()).collectList())
        .map(
            tuple ->
                new UserAuthorizationInfo(
                    user.getId(), user.getUsername(), tuple.getT1(), tuple.getT2()));
  }
}
