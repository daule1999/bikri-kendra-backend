package com.vy.sales.user.repository;

import com.vy.sales.user.entity.Permission;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PermissionRepository extends ReactiveCrudRepository<Permission, Long> {

  Mono<Permission> findByName(String name);

  Mono<Boolean> existsByName(String name);

  @Query(
      """
        SELECT DISTINCT p.name FROM permissions p
        JOIN role_permissions rp ON p.id = rp.permission_id
        JOIN user_roles ur ON ur.role_id = rp.role_id
        WHERE ur.user_id = :userId AND ur.is_active = TRUE
    """)
  Flux<String> findPermissionsByUserId(Long userId);
}
