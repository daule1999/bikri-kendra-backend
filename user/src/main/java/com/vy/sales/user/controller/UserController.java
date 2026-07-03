package com.vy.sales.user.controller;

import com.vy.sales.user.dto.*;
import com.vy.sales.user.service.UserAuthService;
import com.vy.sales.user.service.UserAuthorizationService;
import com.vy.sales.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users-svc")
@RequiredArgsConstructor
@Slf4j
public class UserController {

  private final UserAuthorizationService authorizationService;
  private final UserService userService;
  private final UserAuthService userAuthService;

  @GetMapping("/{username}/authorization")
  public Mono<UserAuthorizationInfo> getAuthorization(
      @PathVariable String username,
      @RequestHeader(value = "X-Username", required = false) String caller,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info(
        "USER_AUTHORIZATION_REQUEST caller={} roles={} targetUser={}", caller, roles, username);

    return authorizationService
        .getAuthorizationInfo(username)
        .doOnSuccess(
            auth ->
                log.info(
                    "USER_AUTHORIZATION_SUCCESS caller={} targetUser={} rolesCount={}",
                    caller,
                    username,
                    auth.getRoles() != null ? auth.getRoles().size() : 0))
        .doOnError(
            ex ->
                log.error(
                    "USER_AUTHORIZATION_FAILED caller={} targetUser={} reason={}",
                    caller,
                    username,
                    ex.getMessage(),
                    ex));
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<UserRegistrationResponse> register(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @RequestHeader(value = "X-Event-Id", required = false) String headerEventId,
      @Valid @RequestBody UserRegistrationRequest request) {

    log.info(
        "USER_REGISTER_REQUEST initiatedBy={} roles={} headerEventId={} username={} email={}",
        username,
        roles,
        headerEventId,
        request.getUsername(),
        request.getEmail());

    return userService
        .register(request, headerEventId)
        .doOnSuccess(
            response ->
                log.info(
                    "USER_REGISTER_SUCCESS initiatedBy={} userId={} username={}",
                    username,
                    response.getUserId(),
                    response.getUsername()))
        .doOnError(
            ex ->
                log.error(
                    "USER_REGISTER_FAILED initiatedBy={} reason={}",
                    username,
                    ex.getMessage(),
                    ex));
  }

  @PostMapping("/validate")
  public Mono<AuthValidationResponse> validateUser(
      @Valid @RequestBody AuthValidationRequest request) {
    log.info("AUTH_VALIDATION_REQUEST received for username={}", request.getUsername());

    return userAuthService
        .validate(request)
        .doOnSuccess(
            resp ->
                log.info(
                    "AUTH_VALIDATION_RESULT username={} valid={}",
                    request.getUsername(),
                    resp.isValid()))
        .doOnError(
            ex ->
                log.error(
                    "AUTH_VALIDATION_FAILED username={} reason={}",
                    request.getUsername(),
                    ex.getMessage(),
                    ex));
  }

  @GetMapping("/allUsers")
  public Flux<UserResponse> getAllUsers(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("USER_GET_ALL_REQUEST user={} roles={}", username, roles);

    return userService
        .getAllUsers()
        .doOnComplete(() -> log.info("USER_GET_ALL_SUCCESS user={}", username))
        .doOnError(
            ex ->
                log.error("USER_GET_ALL_FAILED user={} reason={}", username, ex.getMessage(), ex));
  }

  @GetMapping("/{username}")
  public Mono<UserResponse> getUser(
      @PathVariable String username,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("USER_GET_ALL_REQUEST user={} roles={}", username, roles);

    return userService
        .getUser(username)
        .doOnSuccess(user -> log.info("USER_FETCH_SUCCESS username={} user={}", username, user))
        .doOnError(
            ex ->
                log.error(
                    "USER_FETCH_FAILED username={} reason={}", username, ex.getMessage(), ex));
  }

  @GetMapping("/users/{userId}")
  public Mono<UserResponse> getUserById(
      @PathVariable String userId,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("USER_GET_ALL_REQUEST userId={} roles={}", userId, roles);

    return userService
        .getUserById(Long.parseLong(userId))
        .doOnSuccess(user -> log.info("USER_FETCH_SUCCESS userId={} user={}", userId, user))
        .doOnError(
            ex -> log.error("USER_FETCH_FAILED userId={} reason={}", userId, ex.getMessage(), ex));
  }

  @GetMapping("/users/{userId}/events/ids")
  public Flux<Long> getAccessibleEventIds(@PathVariable Long userId) {
    log.info("USER_GET_ACCESSIBLE_EVENT_IDS userId={}", userId);
    return userService
        .getUserById(userId)
        .flatMapMany(
            user -> {
              if (user.getAssignedEvents() == null || user.getAssignedEvents().isEmpty()) {
                return Flux.empty();
              }
              return Flux.fromIterable(user.getAssignedEvents());
            });
  }

  @DeleteMapping("/{username}")
  public Mono<ResponseEntity<UserDeleteResponse>> deleteUser(
      @PathVariable String username,
      @RequestHeader(value = "X-Username", required = false) String caller,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("USER_DELETE_REQUEST caller={} roles={} targetUser={}", caller, roles, username);

    return userService
        .deleteUser(username)
        .thenReturn(ResponseEntity.ok(new UserDeleteResponse(true, "User deleted successfully")))
        .onErrorResume(
            ResponseStatusException.class,
            ex ->
                Mono.just(
                    ResponseEntity.status(ex.getStatusCode())
                        .body(new UserDeleteResponse(false, ex.getReason()))));
  }

  @PutMapping("/{username}")
  public Mono<ResponseEntity<UserResponse>> updateUser(
      @PathVariable String username,
      @RequestHeader(value = "X-Username", required = false) String caller,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @RequestBody com.vy.sales.user.dto.UserUpdateRequest request) {

    log.info("USER_UPDATE_REQUEST caller={} targetUser={}", caller, username);
    return userService.updateUser(username, request).map(ResponseEntity::ok);
  }

  // ── Self-service: user changes their own password ─────────────────────────
  // Caller must be the same user (X-Username == {username}).
  // Requires old password to verify identity.

  @PostMapping("/{username}/change-password")
  public Mono<ResponseEntity<Void>> changePassword(
      @PathVariable String username,
      @RequestHeader(value = "X-Username", required = false) String caller,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody ChangePasswordRequest request) {

    log.info("CHANGE_PASSWORD_REQUEST caller={} targetUser={}", caller, username);

    // A user can only change their own password; admins use admin-reset-password.
    // Return early as Mono (not throw) so Java can infer Mono<ResponseEntity<Void>> consistently.
    if (!username.equals(caller)) {
      log.warn("CHANGE_PASSWORD_FORBIDDEN caller={} targetUser={}", caller, username);
      return Mono.just(ResponseEntity.<Void>status(HttpStatus.FORBIDDEN).build());
    }

    // Spring WebFlux maps ResponseStatusException to the correct HTTP status automatically.
    // No onErrorResume needed — letting it propagate is the idiomatic WebFlux pattern.
    return userService
        .changePassword(username, request)
        .thenReturn(ResponseEntity.<Void>ok().build());
  }

  // ── Admin-only: reset any user's password (no old password needed) ────────
  // Sets mustResetPassword=true so the user is prompted on next login.

  @PostMapping("/{username}/admin-reset-password")
  public Mono<ResponseEntity<Void>> adminResetPassword(
      @PathVariable String username,
      @RequestHeader(value = "X-Username", required = false) String caller,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody AdminResetPasswordRequest request) {

    log.info("ADMIN_RESET_PASSWORD_REQUEST caller={} targetUser={}", caller, username);

    // Authorization is based solely on the ADMIN role injected by Traefik post-auth.
    // Do NOT fall back on username matching — that would allow spoofing via X-Username.
    boolean isAdmin = roles != null && java.util.Arrays.asList(roles.split(",")).contains("ADMIN");
    if (!isAdmin) {
      log.warn("ADMIN_RESET_PASSWORD_FORBIDDEN caller={} roles={}", caller, roles);
      return Mono.just(ResponseEntity.<Void>status(HttpStatus.FORBIDDEN).build());
    }

    // Spring WebFlux maps ResponseStatusException to the correct HTTP status automatically.
    return userService
        .adminResetPassword(username, request)
        .thenReturn(ResponseEntity.<Void>ok().build());
  }
}
