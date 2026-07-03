package com.vy.sales.sales_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.client.AuthClient;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.dto.AssignStaffRequest;
import com.vy.sales.sales_service.dto.AssignStaffResponse;
import com.vy.sales.sales_service.model.ShopStaffAssignment;
import com.vy.sales.sales_service.repository.ShopRepository;
import com.vy.sales.sales_service.repository.ShopStaffAssignmentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopStaffAssignmentService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String SHOP_STAFF_KEY_PREFIX = "shop:staff:";
  private static final String SHOP_HISTORY_KEY_PREFIX = "shop:history:";
  private static final String USER_SHOP_KEY_PREFIX = "user:shop:";
  private static final Duration STAFF_TTL = Duration.ofMinutes(15);
  private static final String USER_SHOPS_KEY_PREFIX = "user:shops:";
  private static final Duration USER_SHOPS_TTL = Duration.ofMinutes(15);
  private static final String ALL_STAFF_CACHE_KEY = "staff:all:active";
  private static final Duration ALL_STAFF_TTL = Duration.ofMinutes(5);
  private static final String PAGE_INIT_CACHE_PATTERN = "page-init:event:*";
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final ShopStaffAssignmentRepository repository;
  private final ShopRepository shopRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;
  private final UserClient userClient;
  private final AuthClient authClient;

  public Mono<AssignStaffResponse> assign(AssignStaffRequest req, String authHeader) {

    log.info("STAFF_ASSIGN_REQUEST shopId={} userId={}", req.getShopId(), req.getUserId());

    return shopRepository
        .findById(req.getShopId())
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Shop not found")))
        .flatMap(
            shop ->
                userClient
                    .getUserById(req.getUserId(), authHeader)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("User not found")))
                    .flatMap(
                        user -> {
                          String roleCode =
                              (user.getRoles() != null && !user.getRoles().isEmpty())
                                  ? user.getRoles().get(0)
                                  : "STAFF";

                          log.info(
                              "STAFF_ASSIGN_RESOLVED_ROLE shopId={} userId={} role={}",
                              req.getShopId(),
                              req.getUserId(),
                              roleCode);

                          return repository
                              .existsByShopIdAndUserIdAndIsActiveTrue(
                                  req.getShopId(), req.getUserId())
                              .flatMap(
                                  userAlreadyAssigned -> {
                                    if (userAlreadyAssigned) {
                                      log.warn(
                                          "STAFF_ASSIGN_USER_ALREADY_ASSIGNED shopId={} userId={}",
                                          req.getShopId(),
                                          req.getUserId());
                                      return Mono.error(
                                          new IllegalStateException(
                                              "This user is already assigned to this shop"));
                                    }
                                    // Only SHOP_SUPERVISOR is limited to one per shop.
                                    // CASHIER, BILLING_OPERATOR, etc. can have multiple.
                                    if (!"SHOP_SUPERVISOR".equals(roleCode)) {
                                      return Mono.just(false); // skip role-conflict check
                                    }
                                    return repository
                                        .existsByShopIdAndRoleCodeAndIsActiveTrue(
                                            req.getShopId(), roleCode)
                                        .flatMap(
                                            roleConflict -> {
                                              if (roleConflict) {
                                                log.warn(
                                                    "STAFF_ASSIGN_ROLE_CONFLICT shopId={} role={}",
                                                    req.getShopId(),
                                                    roleCode);
                                                return Mono.error(
                                                    new IllegalStateException(
                                                        "Shop already has an active "
                                                            + roleCode
                                                            + " assigned. Remove the existing "
                                                            + roleCode
                                                            + " before assigning a new one."));
                                              }

                                              return Mono.just(false); // proceed
                                            });
                                  })
                              .flatMap(
                                  ignored ->
                                      // Upsert: if a soft-deleted row exists for this (shop, user),
                                      // reactivate it to avoid UK constraint violation on
                                      // re-assign.
                                      repository
                                          .findByShopIdAndUserIdAndIsActiveFalse(
                                              req.getShopId(), req.getUserId())
                                          .map(
                                              existing -> {
                                                log.info(
                                                    "STAFF_ASSIGN_REACTIVATE shopId={} userId={}"
                                                        + " existingId={}",
                                                    req.getShopId(),
                                                    req.getUserId(),
                                                    existing.getId());
                                                existing.setIsActive(true);
                                                existing.setRoleCode(roleCode);
                                                existing.setAssignedAt(LocalDateTime.now());
                                                existing.setLeftAt(null);
                                                return existing;
                                              })
                                          .switchIfEmpty(
                                              Mono.fromSupplier(
                                                  () ->
                                                      ShopStaffAssignment.builder()
                                                          .shopId(req.getShopId())
                                                          .userId(req.getUserId())
                                                          .eventId(shop.getEventId())
                                                          .roleCode(roleCode)
                                                          .assignedAt(LocalDateTime.now())
                                                          .isActive(true)
                                                          .build())))
                              .flatMap(
                                  entity -> {
                                    return repository
                                        .save(entity)
                                        .flatMap(
                                            saved ->
                                                Mono.when(
                                                        redisTemplate
                                                            .delete(
                                                                SHOP_STAFF_KEY_PREFIX
                                                                    + saved.getShopId())
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "Staff cache delete failed"
                                                                          + " on assign shopId={}",
                                                                      saved.getShopId(),
                                                                      e);
                                                                  return Mono.empty();
                                                                }),
                                                        redisTemplate
                                                            .delete(
                                                                USER_SHOP_KEY_PREFIX
                                                                    + saved.getUserId())
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "User-shop cache delete"
                                                                          + " failed on assign"
                                                                          + " userId={}",
                                                                      saved.getUserId(),
                                                                      e);
                                                                  return Mono.empty();
                                                                }),
                                                        redisTemplate
                                                            .delete(
                                                                USER_SHOPS_KEY_PREFIX
                                                                    + saved.getUserId())
                                                            .onErrorResume(e -> Mono.empty()),
                                                        redisTemplate
                                                            .delete(
                                                                USER_SHOPS_KEY_PREFIX
                                                                    + saved.getUserId()
                                                                    + ":"
                                                                    + saved.getEventId())
                                                            .onErrorResume(e -> Mono.empty()),
                                                        redisTemplate
                                                            .delete(ALL_STAFF_CACHE_KEY)
                                                            .onErrorResume(e -> Mono.empty()),
                                                        invalidatePageInitCache())
                                                    .thenReturn(
                                                        AssignStaffResponse.builder()
                                                            .shopId(saved.getShopId())
                                                            .userId(saved.getUserId())
                                                            .eventId(saved.getEventId())
                                                            .roleCode(saved.getRoleCode())
                                                            .active(saved.getIsActive())
                                                            .assignedAt(saved.getAssignedAt())
                                                            .message("Staff assigned successfully")
                                                            .build()))
                                        .doOnSuccess(
                                            res ->
                                                log.info(
                                                    "STAFF_ASSIGN_SUCCESS shopId={} userId={}"
                                                        + " role={} eventId={}",
                                                    res.getShopId(),
                                                    res.getUserId(),
                                                    res.getRoleCode(),
                                                    res.getEventId()));
                                  });
                        }))
        .onErrorMap(
            ex -> {
              // Translate DB unique-constraint violations into human-readable messages
              // (catches any race conditions that slip past the pre-checks above)
              String msg = ex.getMessage() != null ? ex.getMessage() : "";
              if (msg.contains("uk_shop_user")
                  || msg.contains("uk_shop_role")
                  || msg.contains("Duplicate entry")) {
                return new IllegalStateException("This user is already assigned to this shop.");
              }
              return ex;
            })
        .doOnError(
            ex ->
                log.error(
                    "STAFF_ASSIGN_FAILED shopId={} userId={} reason={}",
                    req.getShopId(),
                    req.getUserId(),
                    ex.getMessage(),
                    ex));
  }

  public Flux<ShopStaffAssignment> getActiveStaff(Long shopId) {
    String cacheKey = SHOP_STAFF_KEY_PREFIX + shopId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Staff cache GET error shopId={}, falling back to DB", shopId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ShopStaffAssignment> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ShopStaffAssignment>>() {});
                log.debug("Staff cache HIT shopId={} count={}", shopId, cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("Staff cache deserialize error shopId={}, falling back to DB", shopId, e);
                return Flux.empty();
              }
            })
        .switchIfEmpty(
            repository
                .findByShopIdAndIsActiveTrue(shopId)
                .collectList()
                .flatMapMany(
                    staff -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(staff);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, STAFF_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("Staff cache SET error shopId={}", shopId, e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(staff));
                      } catch (Exception e) {
                        log.warn("Staff cache serialize error shopId={}", shopId, e);
                        return Flux.fromIterable(staff);
                      }
                    }));
  }

  public Mono<Void> remove(Long shopId, String roleCode) {
    log.info("STAFF_REMOVE_REQUEST shopId={} role={}", shopId, roleCode);
    // findByShopIdAndRoleCodeAndIsActiveTrue now returns Flux — handles multiple CASHIERs per shop
    return repository
        .findByShopIdAndRoleCodeAndIsActiveTrue(shopId, roleCode)
        .collectList()
        .flatMap(
            assignments -> {
              if (assignments.isEmpty()) {
                log.warn("STAFF_REMOVE_NOT_FOUND shopId={} role={}", shopId, roleCode);
                return Mono.empty();
              }
              List<Long> affectedUserIds =
                  assignments.stream()
                      .map(ShopStaffAssignment::getUserId)
                      .filter(java.util.Objects::nonNull)
                      .distinct()
                      .collect(java.util.stream.Collectors.toList());

              return Flux.fromIterable(assignments)
                  .flatMap(
                      existing -> {
                        existing.setIsActive(false);
                        existing.setLeftAt(LocalDateTime.now());
                        return repository.save(existing);
                      })
                  .then(
                      Mono.when(
                          redisTemplate
                              .delete(SHOP_STAFF_KEY_PREFIX + shopId)
                              .onErrorResume(
                                  e -> {
                                    log.warn(
                                        "Staff cache delete failed on remove shopId={}", shopId, e);
                                    return Mono.empty();
                                  }),
                          Flux.fromIterable(affectedUserIds)
                              .flatMap(
                                  uid ->
                                      Mono.when(
                                          redisTemplate
                                              .delete(USER_SHOP_KEY_PREFIX + uid)
                                              .onErrorResume(e -> Mono.empty()),
                                          redisTemplate
                                              .delete(USER_SHOPS_KEY_PREFIX + uid)
                                              .onErrorResume(e -> Mono.empty())))
                              .then(),
                          redisTemplate
                              .delete(ALL_STAFF_CACHE_KEY)
                              .onErrorResume(e -> Mono.empty()),
                          invalidatePageInitCache()));
            });
  }

  public Mono<ShopStaffAssignment> getShopByUserId(Long userId) {
    log.info("FETCH_SHOP_BY_USER_REQUEST userId={}", userId);

    String cacheKey = USER_SHOP_KEY_PREFIX + userId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("User-shop cache GET error userId={}, falling back to DB", userId, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                ShopStaffAssignment cached =
                    CACHE_MAPPER.readValue(json, ShopStaffAssignment.class);
                log.debug("User-shop cache HIT userId={}", userId);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn(
                    "User-shop cache deserialize error userId={}, falling back to DB", userId, e);
                return Mono.<ShopStaffAssignment>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    repository
                        .findByUserIdAndIsActiveTrue(userId)
                        .next()
                        .switchIfEmpty(
                            Mono.error(
                                new IllegalStateException("User not assigned to any active shop")))
                        .flatMap(
                            assignment -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(assignment);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, STAFF_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn(
                                              "User-shop cache SET error userId={}", userId, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(assignment);
                              } catch (Exception e) {
                                log.warn("User-shop cache serialize error userId={}", userId, e);
                                return Mono.just(assignment);
                              }
                            })))
        .doOnSuccess(
            r ->
                log.info(
                    "FETCH_SHOP_BY_USER_SUCCESS userId={} shopId={} role={}",
                    userId,
                    r.getShopId(),
                    r.getRoleCode()))
        .doOnError(
            ex ->
                log.warn("FETCH_SHOP_BY_USER_FAILED userId={} reason={}", userId, ex.getMessage()));
  }

  public Mono<ShopStaffAssignment> getShopByUserIdAndEventId(Long userId, Long eventId) {
    log.info("FETCH_SHOP_BY_USER_EVENT_REQUEST userId={} eventId={}", userId, eventId);

    return repository
        .findByUserIdAndEventIdAndIsActiveTrue(userId, eventId)
        .next()
        .switchIfEmpty(
            Mono.error(
                new IllegalStateException(
                    "User not assigned to any active shop in the specified event")))
        .doOnSuccess(
            r ->
                log.info(
                    "FETCH_SHOP_BY_USER_EVENT_SUCCESS userId={} eventId={} shopId={} role={}",
                    userId,
                    eventId,
                    r.getShopId(),
                    r.getRoleCode()))
        .doOnError(
            ex ->
                log.warn(
                    "FETCH_SHOP_BY_USER_EVENT_FAILED userId={} eventId={} reason={}",
                    userId,
                    eventId,
                    ex.getMessage()));
  }

  public Mono<Void> unassignUserFromAllShops(Long userId) {
    log.info("STAFF_UNASSIGN_ALL_REQUEST userId={}", userId);
    return repository
        .findByUserIdAndIsActiveTrue(userId)
        .collectList()
        .flatMap(
            assignments -> {
              // Collect affected shopIds so we can evict their shop:staff caches
              java.util.List<Long> affectedShopIds =
                  assignments.stream()
                      .map(ShopStaffAssignment::getShopId)
                      .filter(java.util.Objects::nonNull)
                      .distinct()
                      .collect(java.util.stream.Collectors.toList());

              return reactor.core.publisher.Flux.fromIterable(assignments)
                  .flatMap(
                      existing -> {
                        existing.setIsActive(false);
                        existing.setLeftAt(LocalDateTime.now());
                        return repository.save(existing);
                      })
                  .then(
                      // Invalidate user-shop, user-shops:*, shop:staff:*, and all-staff caches
                      Mono.when(
                          redisTemplate
                              .delete(USER_SHOP_KEY_PREFIX + userId)
                              .onErrorResume(
                                  e -> {
                                    log.warn(
                                        "User-shop cache delete failed on unassignUserFromAllShops"
                                            + " userId={}",
                                        userId,
                                        e);
                                    return Mono.empty();
                                  }),
                          redisTemplate
                              .keys(USER_SHOPS_KEY_PREFIX + userId + "*")
                              .flatMap(
                                  key -> redisTemplate.delete(key).onErrorResume(e -> Mono.empty()))
                              .then(),
                          // Evict shop:staff and shop:history caches for every affected shop
                          reactor.core.publisher.Flux.fromIterable(affectedShopIds)
                              .flatMap(
                                  shopId ->
                                      Mono.when(
                                          redisTemplate
                                              .delete(SHOP_STAFF_KEY_PREFIX + shopId)
                                              .onErrorResume(
                                                  e -> {
                                                    log.warn(
                                                        "Shop-staff cache delete failed on unassignUserFromAllShops shopId={}",
                                                        shopId,
                                                        e);
                                                    return Mono.empty();
                                                  }),
                                          redisTemplate
                                              .delete(SHOP_HISTORY_KEY_PREFIX + shopId)
                                              .onErrorResume(
                                                  e -> {
                                                    log.warn(
                                                        "Shop-history cache delete failed on unassignUserFromAllShops shopId={}",
                                                        shopId,
                                                        e);
                                                    return Mono.empty();
                                                  })))
                              .then(),
                          redisTemplate
                              .delete(ALL_STAFF_CACHE_KEY)
                              .onErrorResume(e -> Mono.empty()),
                          invalidatePageInitCache()));
            });
  }

  public Mono<Void> unassignAllStaffFromShop(Long shopId) {
    log.info("STAFF_UNASSIGN_ALL_FOR_SHOP_REQUEST shopId={}", shopId);
    return repository
        .findByShopIdAndIsActiveTrue(shopId)
        .collectList()
        .flatMap(
            assignments -> {
              if (assignments.isEmpty()) {
                return Mono.empty();
              }
              // Collect affected userIds for cache eviction
              java.util.List<Long> affectedUserIds =
                  assignments.stream()
                      .map(ShopStaffAssignment::getUserId)
                      .filter(java.util.Objects::nonNull)
                      .distinct()
                      .collect(java.util.stream.Collectors.toList());

              // Mark all as inactive
              return reactor.core.publisher.Flux.fromIterable(assignments)
                  .flatMap(
                      existing -> {
                        existing.setIsActive(false);
                        existing.setLeftAt(LocalDateTime.now());
                        return repository.save(existing);
                      })
                  .then(
                      Mono.when(
                              // Evict shop:staff and shop:history caches
                              redisTemplate
                                  .delete(SHOP_STAFF_KEY_PREFIX + shopId)
                                  .onErrorResume(
                                      e -> {
                                        log.warn(
                                            "Staff cache delete failed on unassignAllStaffFromShop shopId={}",
                                            shopId,
                                            e);
                                        return Mono.empty();
                                      }),
                              redisTemplate
                                  .delete(SHOP_HISTORY_KEY_PREFIX + shopId)
                                  .onErrorResume(
                                      e -> {
                                        log.warn(
                                            "Shop-history cache delete failed on unassignAllStaffFromShop shopId={}",
                                            shopId,
                                            e);
                                        return Mono.empty();
                                      }),
                              // Evict user:shop and user:shops:* caches for each affected user
                              reactor.core.publisher.Flux.fromIterable(affectedUserIds)
                                  .flatMap(
                                      userId ->
                                          Mono.when(
                                              redisTemplate
                                                  .delete(USER_SHOP_KEY_PREFIX + userId)
                                                  .onErrorResume(
                                                      e -> {
                                                        log.warn(
                                                            "User-shop cache delete failed on unassignAllStaffFromShop userId={}",
                                                            userId,
                                                            e);
                                                        return Mono.empty();
                                                      }),
                                              redisTemplate
                                                  .keys(USER_SHOPS_KEY_PREFIX + userId + "*")
                                                  .flatMap(
                                                      key ->
                                                          redisTemplate
                                                              .delete(key)
                                                              .onErrorResume(e -> Mono.empty()))
                                                  .then()))
                                  .then(),
                              // Evict staff:all:active cache
                              redisTemplate
                                  .delete(ALL_STAFF_CACHE_KEY)
                                  .onErrorResume(e -> Mono.empty()),
                              // Evict page-init aggregate cache
                              invalidatePageInitCache())
                          .then());
            });
  }

  public Flux<ShopStaffAssignment> getShopsByUserId(Long userId) {
    log.info("FETCH_SHOPS_BY_USER_REQUEST userId={}", userId);
    String cacheKey = USER_SHOPS_KEY_PREFIX + userId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("User shops cache GET error userId={}", userId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ShopStaffAssignment> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ShopStaffAssignment>>() {});
                log.debug("User shops cache HIT userId={} count={}", userId, cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                return Flux.<ShopStaffAssignment>empty();
              }
            })
        .switchIfEmpty(
            repository
                .findByUserIdAndIsActiveTrue(userId)
                .collectList()
                .flatMapMany(
                    list -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(list);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, USER_SHOPS_TTL)
                            .onErrorResume(e -> Mono.empty())
                            .thenMany(Flux.fromIterable(list));
                      } catch (Exception e) {
                        return Flux.fromIterable(list);
                      }
                    }));
  }

  public Flux<ShopStaffAssignment> getShopsByUserIdAndEventId(Long userId, Long eventId) {
    log.info("FETCH_SHOPS_BY_USER_EVENT_REQUEST userId={} eventId={}", userId, eventId);
    String cacheKey = USER_SHOPS_KEY_PREFIX + userId + ":" + eventId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(e -> Mono.empty())
        .flatMapMany(
            json -> {
              try {
                List<ShopStaffAssignment> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ShopStaffAssignment>>() {});
                log.debug(
                    "User shops event cache HIT userId={} eventId={} count={}",
                    userId,
                    eventId,
                    cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                return Flux.<ShopStaffAssignment>empty();
              }
            })
        .switchIfEmpty(
            repository
                .findByUserIdAndEventIdAndIsActiveTrue(userId, eventId)
                .collectList()
                .flatMapMany(
                    list -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(list);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, USER_SHOPS_TTL)
                            .onErrorResume(e -> Mono.empty())
                            .thenMany(Flux.fromIterable(list));
                      } catch (Exception e) {
                        return Flux.fromIterable(list);
                      }
                    }));
  }

  /**
   * Evicts all page-init aggregate cache entries whose key starts with "page-init:event:". Must be
   * called after any assignment mutation so the aggregated page-init response reflects the latest
   * data on the very next request (within the 30-second TTL window).
   */
  private Mono<Void> invalidatePageInitCache() {
    return redisTemplate
        .keys(PAGE_INIT_CACHE_PATTERN)
        .flatMap(key -> redisTemplate.delete(key).onErrorResume(e -> Mono.empty()))
        .onErrorResume(
            e -> {
              log.warn("PAGE_INIT_CACHE_CLEAR_ERROR pattern={}", PAGE_INIT_CACHE_PATTERN, e);
              return Flux.empty();
            })
        .then();
  }

  public Flux<ShopStaffAssignment> getAllActiveAssignments() {
    log.info("STAFF_FETCH_ALL_ACTIVE_REQUEST");

    return redisTemplate
        .opsForValue()
        .get(ALL_STAFF_CACHE_KEY)
        .onErrorResume(e -> Mono.empty())
        .flatMapMany(
            json -> {
              try {
                List<ShopStaffAssignment> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ShopStaffAssignment>>() {});
                log.debug("All staff cache HIT count={}", cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                return Flux.<ShopStaffAssignment>empty();
              }
            })
        .switchIfEmpty(
            repository
                .findByIsActiveTrue()
                .collectList()
                .flatMapMany(
                    list -> {
                      // Do not cache an empty result — avoids serving a stale empty
                      // list during the startup window before assignments are seeded.
                      if (list.isEmpty()) {
                        return Flux.fromIterable(list);
                      }
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(list);
                        return redisTemplate
                            .opsForValue()
                            .set(ALL_STAFF_CACHE_KEY, json, ALL_STAFF_TTL)
                            .onErrorResume(e -> Mono.empty())
                            .thenMany(Flux.fromIterable(list));
                      } catch (Exception e) {
                        return Flux.fromIterable(list);
                      }
                    }))
        .doOnComplete(() -> log.info("STAFF_FETCH_ALL_ACTIVE_SUCCESS"))
        .doOnError(ex -> log.error("STAFF_FETCH_ALL_ACTIVE_FAILED", ex));
  }
}
