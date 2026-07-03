package com.vy.sales.user.controller;

import com.vy.sales.user.dto.PermissionRequest;
import com.vy.sales.user.entity.Permission;
import com.vy.sales.user.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users-svc/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {

  private final PermissionService permissionService;

  @PostMapping
  public Mono<Permission> create(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody PermissionRequest request) {

    log.info("PERMISSION_CREATE_REQUEST user={} roles={} request={}", username, roles, request);

    return permissionService
        .create(request)
        .doOnSuccess(
            permission ->
                log.info(
                    "PERMISSION_CREATED user={} permissionId={}", username, permission.getId()))
        .doOnError(
            ex ->
                log.error(
                    "PERMISSION_CREATE_FAILED user={} reason={}", username, ex.getMessage(), ex));
  }

  @GetMapping
  public Flux<Permission> getAll(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles) {

    log.info("PERMISSION_GET_ALL_REQUEST user={} roles={}", username, roles);

    return permissionService
        .getAll()
        .doOnComplete(() -> log.info("PERMISSION_GET_ALL_SUCCESS user={}", username))
        .doOnError(
            ex ->
                log.error(
                    "PERMISSION_GET_ALL_FAILED user={} reason={}", username, ex.getMessage(), ex));
  }

  @PutMapping("/{id}")
  public Mono<Permission> update(
      @PathVariable Long id,
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody PermissionRequest request) {

    log.info(
        "PERMISSION_UPDATE_REQUEST user={} roles={} permissionId={} request={}",
        username,
        roles,
        id,
        request);

    return permissionService
        .update(id, request)
        .doOnSuccess(
            permission ->
                log.info(
                    "PERMISSION_UPDATED user={} permissionId={}", username, permission.getId()))
        .doOnError(
            ex ->
                log.error(
                    "PERMISSION_UPDATE_FAILED user={} permissionId={} reason={}",
                    username,
                    id,
                    ex.getMessage(),
                    ex));
  }
}
