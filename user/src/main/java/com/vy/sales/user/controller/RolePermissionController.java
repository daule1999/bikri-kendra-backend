package com.vy.sales.user.controller;

import com.vy.sales.user.dto.AssignPermissionsToRoleRequest;
import com.vy.sales.user.service.RolePermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users-svc/roles-permission")
@RequiredArgsConstructor
@Slf4j
public class RolePermissionController {

  private final RolePermissionService rolePermissionService;

  @PutMapping("/permissions")
  public Mono<Void> assignPermissions(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String roles,
      @Valid @RequestBody AssignPermissionsToRoleRequest request) {

    log.info(
        "ROLE_PERMISSION_ASSIGN_REQUEST user={} roles={} request={}", username, roles, request);

    return rolePermissionService
        .assignPermissions(request)
        .doOnSuccess(
            v ->
                log.info(
                    "ROLE_PERMISSION_ASSIGN_SUCCESS user={} roleId={} permissionIds={}",
                    username,
                    request.getRoleId(),
                    request.getPermissionIds()))
        .doOnError(
            ex ->
                log.error(
                    "ROLE_PERMISSION_ASSIGN_FAILED user={} roleId={} reason={}",
                    username,
                    request.getRoleId(),
                    ex.getMessage(),
                    ex));
  }
}
