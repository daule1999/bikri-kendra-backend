package com.vy.sales.user.controller;

import com.vy.sales.user.dto.RoleRequest;
import com.vy.sales.user.entity.Role;
import com.vy.sales.user.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users-svc/roles")
@RequiredArgsConstructor
@Slf4j
public class RoleController {

  private final RoleService roleService;

  // =======================
  // CREATE ROLE
  // =======================
  @PostMapping
  public Mono<Role> create(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody RoleRequest request) {

    log.info("ROLE_CREATE_REQUEST user={} roles={} request={}", username, roles, request);

    return roleService
        .create(request)
        .doOnSuccess(role -> log.info("ROLE_CREATED user={} roleId={}", username, role.getId()))
        .doOnError(
            ex -> log.error("ROLE_CREATE_FAILED user={} reason={}", username, ex.getMessage(), ex));
  }

  // =======================
  // GET ALL ROLES
  // =======================
  @GetMapping
  public Flux<Role> getAll(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("ROLE_GET_ALL_REQUEST user={} roles={}", username, roles);

    return roleService
        .getAll()
        .doOnComplete(() -> log.info("ROLE_GET_ALL_SUCCESS user={}", username))
        .doOnError(
            ex ->
                log.error("ROLE_GET_ALL_FAILED user={} reason={}", username, ex.getMessage(), ex));
  }

  // =======================
  // UPDATE ROLE
  // =======================
  @PutMapping("/{id}")
  public Mono<Role> update(
      @PathVariable Long id,
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody RoleRequest request) {

    log.info(
        "ROLE_UPDATE_REQUEST user={} roles={} roleId={} request={}", username, roles, id, request);

    return roleService
        .update(id, request)
        .doOnSuccess(role -> log.info("ROLE_UPDATED user={} roleId={}", username, role.getId()))
        .doOnError(
            ex ->
                log.error(
                    "ROLE_UPDATE_FAILED user={} roleId={} reason={}",
                    username,
                    id,
                    ex.getMessage(),
                    ex));
  }
}
