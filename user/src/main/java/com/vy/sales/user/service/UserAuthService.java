package com.vy.sales.user.service;

import com.vy.sales.user.client.SalesClient;
import com.vy.sales.user.dto.AuthValidationRequest;
import com.vy.sales.user.dto.AuthValidationResponse;
import com.vy.sales.user.entity.UserStatus;
import com.vy.sales.user.repository.RoleRepository;
import com.vy.sales.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserAuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final RoleRepository roleRepository;
  private final SalesClient salesClient;

  public Mono<AuthValidationResponse> validate(AuthValidationRequest request) {

    log.info("AUTH_VALIDATION_REQUEST username={}", request.getUsername());

    return userRepository
        .findByUsername(request.getUsername())
        .flatMap(
            user -> {
              if (user.getStatus() != UserStatus.ACTIVE) {
                log.warn("AUTH_DENIED username={} reason=INACTIVE", user.getUsername());
                return Mono.empty();
              }
              if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                log.warn("AUTH_DENIED username={} reason=INVALID_PASSWORD", user.getUsername());
                return Mono.empty();
              }

              // Update last login time
              user.setLastLoginAt(LocalDateTime.now());
              return userRepository.save(user);
            })
        .flatMap(
            user ->
                roleRepository
                    .findRoleNamesByUserId(user.getId())
                    .collectList()
                    .map(
                        roles -> {
                          if ("admin".equals(user.getUsername()) && !roles.contains("ADMIN")) {
                            java.util.List<String> mutableRoles = new java.util.ArrayList<>(roles);
                            mutableRoles.add("ADMIN");
                            return mutableRoles;
                          }
                          return roles;
                        })
                    .flatMap(
                        roles -> {
                          boolean mustReset = Boolean.TRUE.equals(user.getMustResetPassword());
                          if (roles.contains("ADMIN")) {
                            log.info(
                                "User {} has ADMIN role, bypassing event presence check",
                                user.getUsername());
                            return Mono.just(
                                new AuthValidationResponse(
                                    true, user.getUsername(), user.getId(), roles, mustReset));
                          }
                          return salesClient
                              .hasAnyEvent()
                              .flatMap(
                                  hasEvents -> {
                                    if (hasEvents) {
                                      return Mono.just(
                                          new AuthValidationResponse(
                                              true,
                                              user.getUsername(),
                                              user.getId(),
                                              roles,
                                              mustReset));
                                    } else {
                                      log.warn(
                                          "AUTH_DENIED username={} reason=NO_EVENTS_PRESENT_AND_NOT_ADMIN",
                                          user.getUsername());
                                      return Mono.just(
                                          new AuthValidationResponse(
                                              false, null, null, List.of(), false));
                                    }
                                  });
                        }))
        .defaultIfEmpty(new AuthValidationResponse(false, null, null, List.of(), false))
        .doOnSuccess(
            resp ->
                log.info(
                    "AUTH_VALIDATION_RESULT username={} success={} mustResetPassword={}",
                    request.getUsername(),
                    resp.isValid(),
                    resp.isMustResetPassword()));
  }
}
