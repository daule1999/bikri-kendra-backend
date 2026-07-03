package com.vy.sales.user.repository;

import com.vy.sales.user.entity.Permission;
import com.vy.sales.user.entity.RolePermission;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RolePermissionRepository extends ReactiveCrudRepository<RolePermission, Long> {

  Flux<RolePermission> findByRoleId(Long roleId);

  @Query("DELETE FROM role_permissions WHERE role_id = :roleId")
  Mono<Void> deleteByRoleId(Long roleId);

  @Query(
      """
        SELECT p.*
        FROM permissions p
        JOIN role_permissions rp ON rp.permission_id = p.id
        WHERE rp.role_id = :roleId
    """)
  Flux<Permission> findPermissionsByRoleId(Long roleId);

  Mono<Boolean> existsByRoleIdAndPermissionId(Long roleId, Long permissionId);
}
