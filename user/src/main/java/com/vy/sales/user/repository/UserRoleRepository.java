package com.vy.sales.user.repository;

import com.vy.sales.user.entity.UserRole;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRoleRepository extends ReactiveCrudRepository<UserRole, Long> {

  Flux<UserRole> findByUserId(Long userId);

  Flux<UserRole> findByUserIdAndEventId(Long userId, Long eventId);

  Flux<UserRole> findByRoleId(Long roleId);

  Flux<UserRole> findByUserIdAndIsActive(Long userId, Boolean isActive);

  Flux<UserRole> findByUserIdAndEventIdAndIsActive(Long userId, Long eventId, Boolean isActive);

  Flux<UserRole> findByEventIdAndIsActive(Long eventId, Boolean isActive);

  Mono<UserRole> findByUserIdAndRoleIdAndEventId(Long userId, Long roleId, Long eventId);

  @Query("DELETE FROM user_roles WHERE user_id = :userId")
  Mono<Void> deleteByUserId(Long userId);

  @Query(
      "DELETE FROM user_roles WHERE user_id = :userId AND (event_id = :eventId OR (:eventId IS NULL AND event_id IS NULL))")
  Mono<Void> deleteByUserIdAndEventId(Long userId, Long eventId);

  Mono<Boolean> existsByUserIdAndRoleId(Long userId, Long roleId);

  Mono<Boolean> existsByUserIdAndRoleIdAndEventId(Long userId, Long roleId, Long eventId);
}
