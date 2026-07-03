package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.ShopStaffAssignment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ShopStaffAssignmentRepository
    extends ReactiveCrudRepository<ShopStaffAssignment, Long> {

  // Returns ALL active assignments for a given role in a shop (multiple CASHIERs are now allowed)
  Flux<ShopStaffAssignment> findByShopIdAndRoleCodeAndIsActiveTrue(Long shopId, String roleCode);

  Flux<ShopStaffAssignment> findByShopIdAndIsActiveTrue(Long shopId);

  Flux<ShopStaffAssignment> findByShopId(Long shopId);

  Mono<Boolean> existsByShopIdAndRoleCodeAndIsActiveTrue(Long shopId, String roleCode);

  Mono<Boolean> existsByShopIdAndUserIdAndIsActiveTrue(Long shopId, Long userId);

  // Used for upsert on re-assign: finds a previously-unassigned row for the same (shop, user) pair
  Mono<ShopStaffAssignment> findByShopIdAndUserIdAndIsActiveFalse(Long shopId, Long userId);

  Flux<ShopStaffAssignment> findByUserIdAndIsActiveTrue(Long userId);

  @Query(
      """
        SELECT *
        FROM shop_staff_assignment
        WHERE user_id = :userId
          AND is_active = TRUE
          AND event_id = :eventId
    """)
  Flux<ShopStaffAssignment> findByUserIdAndEventIdAndIsActiveTrue(Long userId, Long eventId);

  Flux<ShopStaffAssignment> findByIsActiveTrue();
}
