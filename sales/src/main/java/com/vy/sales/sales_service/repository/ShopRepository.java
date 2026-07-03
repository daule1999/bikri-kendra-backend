package com.vy.sales.sales_service.repository;

import com.vy.sales.sales_service.model.Shop;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ShopRepository extends ReactiveCrudRepository<Shop, Long> {
  Mono<Boolean> existsByShopNameAndCounterNumber(String shopName, Integer counterNumber);

  @Query(
      """
        SELECT id
        FROM shop
        WHERE counter_number = :counterNumber
          AND is_active = TRUE
    """)
  Mono<Long> findShopIdByCounter(Integer counterNumber);

  Flux<Shop> findByEventIdIn(java.util.List<Long> eventIds);

  Flux<Shop> findByEventId(Long eventId);

  Flux<Shop> findByShopNameAndEventIdIn(String shopName, java.util.List<Long> eventIds);

  @Query("SELECT id FROM shop WHERE id = :shopId FOR UPDATE")
  Mono<Long> findAndLockById(Long shopId);
}
