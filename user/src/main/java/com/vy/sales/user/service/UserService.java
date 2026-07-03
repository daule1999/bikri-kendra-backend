package com.vy.sales.user.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.user.client.SalesClient;
import com.vy.sales.user.dto.AdminResetPasswordRequest;
import com.vy.sales.user.dto.ChangePasswordRequest;
import com.vy.sales.user.dto.UserRegistrationRequest;
import com.vy.sales.user.dto.UserRegistrationResponse;
import com.vy.sales.user.dto.UserResponse;
import com.vy.sales.user.entity.AppRole;
import com.vy.sales.user.entity.User;
import com.vy.sales.user.entity.UserRole;
import com.vy.sales.user.entity.UserStatus;
import com.vy.sales.user.repository.RoleRepository;
import com.vy.sales.user.repository.UserRepository;
import com.vy.sales.user.repository.UserRoleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final Duration USER_TTL = Duration.ofMinutes(5);
  private static final String USERS_ALL_CACHE_KEY = "users:all";
  private static final Duration USERS_ALL_TTL = Duration.ofMinutes(5);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final PasswordEncoder passwordEncoder;
  private final SalesClient salesClient;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<UserRegistrationResponse> register(
      UserRegistrationRequest req, String headerEventId) {

    log.info(
        "USER_REGISTRATION_STARTED username={} email={} mobile={} headerEventId={}",
        req.getUsername(),
        req.getEmail(),
        req.getMobile(),
        headerEventId);

    return validateUniqueness(req)
        .then(createUser(req))
        .flatMap(
            user -> {
              if (req.getRole() == null) {
                log.info("SKIPPING_ROLE_ASSIGNMENT username={} role=null", user.getUsername());
                return Mono.just(user);
              }

              Long eventId = null;
              if (req.getEventId() != null) {
                eventId = req.getEventId();
              } else if (headerEventId != null && !headerEventId.trim().isEmpty()) {
                try {
                  eventId = Long.parseLong(headerEventId.trim());
                } catch (NumberFormatException e) {
                  log.warn(
                      "INVALID_EVENT_ID_HEADER value={} skipping role assignment", headerEventId);
                }
              }

              if (eventId == null) {
                log.info(
                    "SKIPPING_ROLE_ASSIGNMENT username={} role={} because no eventId was provided in request body or X-Event-Id header",
                    user.getUsername(),
                    req.getRole().name());
                return Mono.just(user);
              }

              return this.assignRole(user, req.getRole(), eventId);
            })
        .flatMap(
            user ->
                Mono.when(
                        redisTemplate.delete(USERS_ALL_CACHE_KEY),
                        redisTemplate.delete("user:username:" + user.getUsername()),
                        redisTemplate.delete("user:id:" + user.getId()))
                    .onErrorResume(
                        e -> {
                          log.warn("AllUsers cache delete failed on register", e);
                          return Mono.empty();
                        })
                    .thenReturn(user))
        .map(
            user -> {
              log.info(
                  "USER_REGISTRATION_COMPLETED userId={} username={}",
                  user.getId(),
                  user.getUsername());
              return new UserRegistrationResponse(
                  user.getId(), user.getUsername(), user.getStatus().name());
            })
        .doOnError(
            ex ->
                log.error(
                    "USER_REGISTRATION_FAILED username={} reason={}",
                    req.getUsername(),
                    ex.getMessage(),
                    ex));
  }

  private Mono<Void> validateUniqueness(UserRegistrationRequest req) {

    log.debug(
        "USER_UNIQUENESS_CHECK username={} email={} mobile={}",
        req.getUsername(),
        req.getEmail(),
        req.getMobile());

    return Mono.zip(
            userRepository.existsByUsername(req.getUsername()),
            userRepository.existsByEmail(req.getEmail()),
            userRepository.existsByMobile(req.getMobile()))
        .flatMap(
            tuple -> {
              if (tuple.getT1()) {
                log.warn("USERNAME_ALREADY_EXISTS username={}", req.getUsername());
                return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS"));
              }
              if (tuple.getT2()) {
                log.warn("EMAIL_ALREADY_EXISTS email={}", req.getEmail());
                return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS"));
              }
              // if (tuple.getT3()) {
              //   log.warn("MOBILE_ALREADY_EXISTS mobile={}", req.getMobile());
              //   return Mono.error(
              //       new ResponseStatusException(HttpStatus.CONFLICT, "MOBILE_ALREADY_EXISTS"));
              // }
              return Mono.empty();
            });
  }

  private Mono<User> createUser(UserRegistrationRequest req) {

    log.debug(
        "CREATING_USER_ENTITY username={} with role={}",
        req.getUsername(),
        req.getRole() != null ? req.getRole().name() : "NONE");

    return Mono.fromCallable(
            () -> {
              User user = new User();
              user.setUsername(req.getUsername());
              user.setEmail(req.getEmail());
              user.setMobile(req.getMobile());
              user.setFullName(req.getFullName());
              user.setStatus(UserStatus.ACTIVE);
              user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
              // New users must reset their password on first login (admin set a temp password)
              user.setMustResetPassword(true);
              return user;
            })
        .flatMap(userRepository::save)
        .doOnSuccess(
            user -> log.info("USER_SAVED userId={} username={}", user.getId(), user.getUsername()));
  }

  private Mono<User> assignRole(User user, AppRole appRole, Long eventId) {

    log.debug("ASSIGNING_ROLE userId={} role={} eventId={}", user.getId(), appRole.name(), eventId);

    return roleRepository
        .findByNameIgnoreCase(appRole.name())
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "ROLE_NOT_FOUND")))
        .flatMap(
            role -> {
              UserRole ur = new UserRole();
              ur.setUserId(user.getId());
              ur.setRoleId(role.getId());
              ur.setEventId(eventId);
              ur.setIsActive(true);
              ur.setAssignedAt(LocalDateTime.now());

              return userRoleRepository
                  .save(ur)
                  .doOnSuccess(
                      r ->
                          log.info(
                              "ROLE_ASSIGNED userId={} roleId={} eventId={}",
                              user.getId(),
                              role.getId(),
                              eventId))
                  .thenReturn(user);
            });
  }

  public Flux<UserResponse> getAllUsers() {

    log.debug("USER_SERVICE_GET_ALL_USERS");

    return redisTemplate
        .opsForValue()
        .get(USERS_ALL_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("AllUsers cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                java.util.List<UserResponse> cached =
                    CACHE_MAPPER.readValue(
                        json,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.List<UserResponse>>() {});
                log.debug("AllUsers cache HIT count={}", cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("AllUsers cache deserialize error, falling back to DB", e);
                return Flux.empty();
              }
            })
        .switchIfEmpty(
            userRepository
                .findAll()
                .flatMap(this::mapToResponse)
                .collectList()
                .flatMapMany(
                    users -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(users);
                        return redisTemplate
                            .opsForValue()
                            .set(USERS_ALL_CACHE_KEY, json, USERS_ALL_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("AllUsers cache SET error", e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(users));
                      } catch (Exception e) {
                        log.warn("AllUsers cache serialize error", e);
                        return Flux.fromIterable(users);
                      }
                    }))
        .doOnComplete(() -> log.info("USER_FETCH_ALL_COMPLETED"))
        .doOnError(ex -> log.error("USER_FETCH_ALL_FAILED reason={}", ex.getMessage(), ex));
  }

  public Mono<UserResponse> getUser(String username) {

    log.debug("USER_SERVICE_GET_ALL_USERS");

    String cacheKey = "user:username:" + username;
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("User cache GET error username={}, falling back to DB", username, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                UserResponse cached = CACHE_MAPPER.readValue(json, UserResponse.class);
                log.debug("User cache HIT username={}", username);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn(
                    "User cache deserialize error username={}, falling back to DB", username, e);
                return Mono.<UserResponse>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    userRepository
                        .findByUsername(username)
                        .flatMap(this::mapToResponse)
                        .flatMap(
                            response -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(response);
                                // Cache by both username and id for dual lookup
                                return Mono.when(
                                        redisTemplate.opsForValue().set(cacheKey, json, USER_TTL),
                                        redisTemplate
                                            .opsForValue()
                                            .set("user:id:" + response.getId(), json, USER_TTL))
                                    .onErrorResume(
                                        e -> {
                                          log.warn("User cache SET error username={}", username, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(response);
                              } catch (Exception e) {
                                log.warn("User cache serialize error username={}", username, e);
                                return Mono.just(response);
                              }
                            })))
        .doOnSuccess(user -> log.info("USER_FETCH_SUCCESS username={}", username))
        .doOnError(
            ex ->
                log.error(
                    "USER_FETCH_FAILED username={} reason={}", username, ex.getMessage(), ex));
  }

  private record RoleEventTuple(String roleName, Long eventId) {}

  private Mono<UserResponse> mapToResponse(User user) {
    return userRoleRepository
        .findByUserIdAndIsActive(user.getId(), true)
        .flatMap(
            userRole -> {
              Long eventId = userRole.getEventId();
              return roleRepository
                  .findById(userRole.getRoleId())
                  .map(role -> new RoleEventTuple(role.getName(), eventId));
            })
        .collectList()
        .map(
            list -> {
              java.util.Set<String> roles =
                  list.stream().map(RoleEventTuple::roleName).collect(Collectors.toSet());
              java.util.Set<Long> eventIds =
                  list.stream()
                      .map(RoleEventTuple::eventId)
                      .filter(java.util.Objects::nonNull)
                      .collect(Collectors.toSet());

              if ("admin".equals(user.getUsername())) {
                roles.add("ADMIN");
              }

              UserResponse dto = new UserResponse();
              dto.setId(user.getId());
              dto.setUsername(user.getUsername());
              dto.setEmail(user.getEmail());
              dto.setMobile(user.getMobile());
              dto.setFullName(user.getFullName());
              dto.setStatus(user.getStatus());
              dto.setRoles(roles);
              dto.setAssignedEvents(eventIds);
              return dto;
            });
  }

  public Mono<Void> deleteUser(String username) {

    return userRepository
        .findByUsername(username)
        .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
        .flatMap(
            user ->
                userRoleRepository
                    .deleteByUserId(user.getId())
                    .then(userRepository.delete(user))
                    .then(
                        Mono.when(
                                redisTemplate.delete("user:username:" + username),
                                redisTemplate.delete("user:id:" + user.getId()),
                                redisTemplate.delete(USERS_ALL_CACHE_KEY))
                            .onErrorResume(
                                e -> {
                                  log.warn(
                                      "User cache delete failed on deleteUser username={}",
                                      username,
                                      e);
                                  return Mono.empty();
                                })
                            .then()));
  }

  public Mono<UserResponse> updateUser(
      String username, com.vy.sales.user.dto.UserUpdateRequest req) {
    log.info("USER_UPDATE_STARTED username={}", username);
    return userRepository
        .findByUsername(username)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
        .flatMap(
            user -> {
              if (req.getEmail() != null && !req.getEmail().isBlank()) {
                user.setEmail(req.getEmail());
              }
              if (req.getMobile() != null && !req.getMobile().isBlank()) {
                user.setMobile(req.getMobile());
              }
              if (req.getFullName() != null && !req.getFullName().isBlank()) {
                user.setFullName(req.getFullName());
              }
              if (req.getPassword() != null && !req.getPassword().isBlank()) {
                user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
                // Admin set a new password → force the user to reset it on next login,
                // same behaviour as adminResetPassword.
                user.setMustResetPassword(true);
              }
              return userRepository.save(user);
            })
        .flatMap(this::mapToResponse)
        .flatMap(
            updatedResponse ->
                Mono.when(
                        redisTemplate.delete("user:username:" + username),
                        redisTemplate.delete("user:id:" + updatedResponse.getId()),
                        redisTemplate.delete(USERS_ALL_CACHE_KEY))
                    .onErrorResume(
                        e -> {
                          log.warn(
                              "User cache delete failed on updateUser username={}", username, e);
                          return Mono.empty();
                        })
                    .thenReturn(updatedResponse))
        .doOnSuccess(user -> log.info("USER_UPDATE_COMPLETED username={}", username));
  }

  public Mono<UserResponse> getUserById(Long userId) {
    log.debug("USER_SERVICE_GET_USERS_BY_ID");

    String cacheKey = "user:id:" + userId;
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("User cache GET error userId={}, falling back to DB", userId, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                UserResponse cached = CACHE_MAPPER.readValue(json, UserResponse.class);
                log.debug("User cache HIT userId={}", userId);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("User cache deserialize error userId={}, falling back to DB", userId, e);
                return Mono.<UserResponse>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    userRepository
                        .findById(userId)
                        .flatMap(this::mapToResponse)
                        .flatMap(
                            response -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(response);
                                // Cache by both id and username for dual lookup
                                return Mono.when(
                                        redisTemplate.opsForValue().set(cacheKey, json, USER_TTL),
                                        redisTemplate
                                            .opsForValue()
                                            .set(
                                                "user:username:" + response.getUsername(),
                                                json,
                                                USER_TTL))
                                    .onErrorResume(
                                        e -> {
                                          log.warn("User cache SET error userId={}", userId, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(response);
                              } catch (Exception e) {
                                log.warn("User cache serialize error userId={}", userId, e);
                                return Mono.just(response);
                              }
                            })))
        .doOnSuccess(user -> log.info("USER_FETCH_SUCCESS userId={}", userId))
        .doOnError(
            ex -> log.error("USER_FETCH_FAILED userId={} reason={}", userId, ex.getMessage(), ex));
  }

  // ── Self-service: change own password (requires old password) ────────────

  public Mono<Void> changePassword(String username, ChangePasswordRequest req) {
    log.info("CHANGE_PASSWORD_STARTED username={}", username);

    if (!req.getNewPassword().equals(req.getConfirmPassword())) {
      return Mono.error(
          new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "New password and confirm password do not match"));
    }

    return userRepository
        .findByUsername(username)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
        .flatMap(
            user -> {
              if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
                log.warn("CHANGE_PASSWORD_DENIED username={} reason=WRONG_OLD_PASSWORD", username);
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Old password is incorrect"));
              }
              if (passwordEncoder.matches(req.getNewPassword(), user.getPasswordHash())) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "New password must be different from the current password"));
              }
              user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
              user.setMustResetPassword(false);
              return userRepository.save(user);
            })
        .flatMap(
            user ->
                Mono.when(
                        redisTemplate.delete("user:username:" + username),
                        redisTemplate.delete("user:id:" + user.getId()),
                        redisTemplate.delete(USERS_ALL_CACHE_KEY))
                    .onErrorResume(
                        e -> {
                          log.warn(
                              "Cache delete failed on changePassword username={}", username, e);
                          return Mono.empty();
                        })
                    .then())
        .doOnSuccess(v -> log.info("CHANGE_PASSWORD_COMPLETED username={}", username))
        .doOnError(
            ex ->
                log.error(
                    "CHANGE_PASSWORD_FAILED username={} reason={}", username, ex.getMessage(), ex));
  }

  // ── Admin: reset a user's password without old password ──────────────────

  public Mono<Void> adminResetPassword(String username, AdminResetPasswordRequest req) {
    log.info("ADMIN_RESET_PASSWORD_STARTED targetUsername={}", username);

    return userRepository
        .findByUsername(username)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
        .flatMap(
            user -> {
              // Reject if the admin is setting the same password the user already has.
              // This ensures the forced-reset prompt actually results in a changed credential.
              if (passwordEncoder.matches(req.getNewPassword(), user.getPasswordHash())) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "New password must be different from the user's current password"));
              }
              user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
              // Force the user to reset on next login
              user.setMustResetPassword(true);
              return userRepository.save(user);
            })
        .flatMap(
            user ->
                Mono.when(
                        redisTemplate.delete("user:username:" + username),
                        redisTemplate.delete("user:id:" + user.getId()),
                        redisTemplate.delete(USERS_ALL_CACHE_KEY))
                    .onErrorResume(
                        e -> {
                          log.warn(
                              "Cache delete failed on adminResetPassword username={}", username, e);
                          return Mono.empty();
                        })
                    .then())
        .doOnSuccess(v -> log.info("ADMIN_RESET_PASSWORD_COMPLETED targetUsername={}", username))
        .doOnError(
            ex ->
                log.error(
                    "ADMIN_RESET_PASSWORD_FAILED username={} reason={}",
                    username,
                    ex.getMessage(),
                    ex));
  }
}
