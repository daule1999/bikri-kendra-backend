package com.vy.sales.user.service;

import com.vy.sales.user.dto.AssignPermissionsToRoleRequest;
import com.vy.sales.user.entity.RolePermission;
import com.vy.sales.user.repository.PermissionRepository;
import com.vy.sales.user.repository.RolePermissionRepository;
import com.vy.sales.user.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class RolePermissionService {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final RolePermissionRepository rolePermissionRepository;

  public Mono<Void> assignPermissions(AssignPermissionsToRoleRequest request) {

    log.info(
        "ASSIGN_PERMISSIONS_REQUEST roleId={} permissionIds={}",
        request.getRoleId(),
        request.getPermissionIds());

    return roleRepository
        .findById(request.getRoleId())
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found")))
        .flatMap(
            role ->
                rolePermissionRepository
                    .deleteByRoleId(role.getId())
                    .thenMany(savePermissions(role.getId(), request))
                    .then())
        .doOnSuccess(v -> log.info("ASSIGN_PERMISSIONS_SUCCESS roleId={}", request.getRoleId()))
        .doOnError(
            ex ->
                log.error(
                    "ASSIGN_PERMISSIONS_FAILED roleId={} reason={}",
                    request.getRoleId(),
                    ex.getMessage(),
                    ex));
  }

  private Flux<RolePermission> savePermissions(
      Long roleId, AssignPermissionsToRoleRequest request) {
    return Flux.fromIterable(request.getPermissionIds())
        .flatMap(
            permissionId ->
                permissionRepository
                    .findById(permissionId)
                    .switchIfEmpty(
                        Mono.error(
                            new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Permission not found with id: " + permissionId)))
                    .flatMap(
                        permission -> {
                          RolePermission rp = new RolePermission();
                          rp.setRoleId(roleId);
                          rp.setPermissionId(permission.getId());
                          return rolePermissionRepository.save(rp);
                        }));
  }
}
