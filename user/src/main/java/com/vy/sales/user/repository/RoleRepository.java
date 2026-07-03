package com.vy.sales.user.repository;

import com.vy.sales.user.entity.Role;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoleRepository extends ReactiveCrudRepository<Role, Long> {

  // Case-insensitive role lookup to avoid mismatched casing issues
  @Query("SELECT * FROM roles WHERE LOWER(name) = LOWER(:name) LIMIT 1")
  Mono<Role> findByNameIgnoreCase(String name);

  // Retain original method for backward compatibility (may be used elsewhere)
  Mono<Role> findByName(String name);

  @Query(
      """
        SELECT r.name FROM roles r
        JOIN user_roles ur ON r.id = ur.role_id
        WHERE ur.user_id = :userId AND ur.is_active = TRUE
    """)
  Flux<String> findRoleNamesByUserId(Long userId);

  Mono<Boolean> existsByName(String name);
}
