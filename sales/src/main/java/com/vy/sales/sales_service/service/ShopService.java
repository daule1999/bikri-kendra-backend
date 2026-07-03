package com.vy.sales.sales_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.dto.RegisterShopRequest;
import com.vy.sales.sales_service.dto.ShopHistoryResponse;
import com.vy.sales.sales_service.dto.ShopHistoryResponse.HistoryEvent;
import com.vy.sales.sales_service.dto.ShopResponse;
import com.vy.sales.sales_service.dto.UpdateShopRequest;
import com.vy.sales.sales_service.enums.ShopHistoryEventType;
import com.vy.sales.sales_service.model.SalesReturn;
import com.vy.sales.sales_service.model.Shop;
import com.vy.sales.sales_service.model.ShopShiftSession;
import com.vy.sales.sales_service.model.ShopStaffAssignment;
import com.vy.sales.sales_service.repository.SalesOrderRepository;
import com.vy.sales.sales_service.repository.SalesReturnRepository;
import com.vy.sales.sales_service.repository.ShopRepository;
import com.vy.sales.sales_service.repository.ShopShiftSessionRepository;
import com.vy.sales.sales_service.repository.ShopStaffAssignmentRepository;
import com.vy.sales.sales_service.util.AppConstants.StockMovement;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String SHOPS_EVENT_KEY_PREFIX = "shops:event:";
  private static final String SHOP_KEY_PREFIX = "shop:";
  private static final String SHOP_NAME_KEY_PREFIX = "shop:name:";
  private static final String SHOP_HISTORY_KEY_PREFIX = "shop:history:";
  private static final Duration SHOPS_TTL = Duration.ofMinutes(30);
  private static final Duration SHOP_HISTORY_TTL = Duration.ofSeconds(30);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final ShopRepository shopRepository;
  private final ShopStaffAssignmentRepository shopStaffAssignmentRepository;
  private final ShopStaffAssignmentService shopStaffAssignmentService;
  private final SalesOrderRepository salesOrderRepository;
  private final SalesReturnRepository salesReturnRepository;
  private final InventoryClient inventoryClient;
  private final UserClient userClient;
  private final ReactiveRedisTemplate<String, String> redisTemplate;
  private final ShopShiftSessionRepository shiftSessionRepository;

  private Long validateAndResolveEventId(Long requestEventId, Long headerEventId) {
    if (requestEventId != null) {
      return requestEventId;
    }
    if (headerEventId != null) {
      return headerEventId;
    }
    throw new IllegalArgumentException(
        "Event ID is mandatory (must be provided either in the request body or via the X-Event-Id header)");
  }

  public Mono<ShopResponse> registerShop(RegisterShopRequest request, Long headerEventId) {
    log.info("Registering shop: {} with headerEventId: {}", request, headerEventId);

    Long resolvedEventId;
    try {
      resolvedEventId = validateAndResolveEventId(request.getEventId(), headerEventId);
      request.setEventId(resolvedEventId);
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }

    final Long eventId = resolvedEventId;
    Boolean active = request.getIsActive() != null ? request.getIsActive() : true;

    return shopRepository
        .existsByShopNameAndCounterNumber(request.getShopName(), request.getCounterNumber())
        .flatMap(
            exists -> {
              if (exists) {
                log.warn(
                    "Shop with name {} and counter {} already exists",
                    request.getShopName(),
                    request.getCounterNumber());
                return Mono.error(
                    new IllegalArgumentException("Shop with this counter already exists"));
              }
              Shop shop =
                  Shop.builder()
                      .shopName(request.getShopName())
                      .categoryId(request.getCategoryId())
                      .categoryName(request.getCategoryName())
                      .counterNumber(request.getCounterNumber())
                      .isActive(active)
                      .eventId(request.getEventId())
                      .createdAt(LocalDateTime.now())
                      .build();
              return shopRepository
                  .save(shop)
                  .flatMap(
                      saved ->
                          // Invalidate the event's shop list cache
                          redisTemplate
                              .delete(SHOPS_EVENT_KEY_PREFIX + eventId)
                              .onErrorResume(
                                  e -> {
                                    log.warn(
                                        "Shop list cache delete failed on register eventId={}",
                                        eventId,
                                        e);
                                    return Mono.empty();
                                  })
                              .thenReturn(mapToResponse(saved)));
            });
  }

  public Mono<ShopResponse> updateShop(Long id, UpdateShopRequest request, Long headerEventId) {
    log.info("Updating shop id={} with data {} and headerEventId={}", id, request, headerEventId);

    Long resolvedEventId;
    try {
      resolvedEventId = validateAndResolveEventId(request.getEventId(), headerEventId);
      request.setEventId(resolvedEventId);
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }

    final Long eventId = resolvedEventId;

    return shopRepository
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Shop not found with id: " + id)))
        .flatMap(
            existingShop -> {
              if (!existingShop.getEventId().equals(eventId)) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Access Denied: Cannot update shop belonging to another event"));
              }

              if (request.getCounterNumber() != null && request.getShopName() != null) {
                return shopRepository
                    .existsByShopNameAndCounterNumber(
                        request.getShopName(), request.getCounterNumber())
                    .flatMap(
                        exists -> {
                          if (exists
                              && (!existingShop.getShopName().equals(request.getShopName())
                                  || !existingShop
                                      .getCounterNumber()
                                      .equals(request.getCounterNumber()))) {
                            log.warn(
                                "Shop with name {} and counter {} already exists",
                                request.getShopName(),
                                request.getCounterNumber());
                            return Mono.error(
                                new IllegalArgumentException(
                                    "Shop with this counter already exists"));
                          }
                          return Mono.just(existingShop);
                        });
              }
              return Mono.just(existingShop);
            })
        .flatMap(
            existingShop -> {
              if (request.getShopName() != null) existingShop.setShopName(request.getShopName());
              if (request.getCategoryId() != null)
                existingShop.setCategoryId(request.getCategoryId());
              if (request.getCategoryName() != null)
                existingShop.setCategoryName(request.getCategoryName());
              if (request.getCounterNumber() != null)
                existingShop.setCounterNumber(request.getCounterNumber());
              if (request.getIsActive() != null) existingShop.setIsActive(request.getIsActive());
              if (request.getEventId() != null) existingShop.setEventId(request.getEventId());
              if (request.getReceivingPrintEnabled() != null)
                existingShop.setReceivingPrintEnabled(request.getReceivingPrintEnabled());

              final String oldShopName = existingShop.getShopName();
              final Long shopEventId = existingShop.getEventId();
              return shopRepository
                  .save(existingShop)
                  .flatMap(
                      saved ->
                          // Invalidate individual shop, event list, name, and history caches
                          Mono.when(
                                  redisTemplate
                                      .delete(SHOP_KEY_PREFIX + id)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Shop cache delete failed on update id={}", id, e);
                                            return Mono.empty();
                                          }),
                                  redisTemplate
                                      .delete(SHOPS_EVENT_KEY_PREFIX + eventId)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Shop list cache delete failed on update eventId={}",
                                                eventId,
                                                e);
                                            return Mono.empty();
                                          }),
                                  redisTemplate
                                      .delete(
                                          SHOP_NAME_KEY_PREFIX + oldShopName + ":" + shopEventId)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Shop name cache delete failed on update shopId={}",
                                                id,
                                                e);
                                            return Mono.empty();
                                          }),
                                  redisTemplate
                                      .delete(SHOP_HISTORY_KEY_PREFIX + id)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Shop history cache delete failed on update shopId={}",
                                                id,
                                                e);
                                            return Mono.empty();
                                          }))
                              .thenReturn(mapToResponse(saved)));
            });
  }

  public Flux<ShopResponse> getAllShops(Long eventId) {
    if (eventId == null) {
      return Flux.error(new IllegalArgumentException("X-Event-Id header is mandatory"));
    }
    log.info("Fetching all shops for eventId={}", eventId);

    String cacheKey = SHOPS_EVENT_KEY_PREFIX + eventId;

    // Step 1: get shop list from cache or DB (without shift status — shifts change too often to
    // cache)
    Mono<List<ShopResponse>> shopsMono =
        redisTemplate
            .opsForValue()
            .get(cacheKey)
            .onErrorResume(
                e -> {
                  log.warn("Shop list cache GET error eventId={}, falling back to DB", eventId, e);
                  return Mono.empty();
                })
            .flatMap(
                json -> {
                  try {
                    List<ShopResponse> cached =
                        CACHE_MAPPER.readValue(json, new TypeReference<List<ShopResponse>>() {});
                    log.debug("Shop list cache HIT eventId={} count={}", eventId, cached.size());
                    return Mono.just(cached);
                  } catch (Exception e) {
                    log.warn(
                        "Shop list cache deserialize error eventId={}, falling back to DB",
                        eventId,
                        e);
                    return Mono.empty();
                  }
                })
            .switchIfEmpty(
                shopRepository
                    .findByEventIdIn(List.of(eventId))
                    .map(this::mapToResponse)
                    .collectList()
                    .flatMap(
                        shops -> {
                          try {
                            String json = CACHE_MAPPER.writeValueAsString(shops);
                            return redisTemplate
                                .opsForValue()
                                .set(cacheKey, json, SHOPS_TTL)
                                .onErrorResume(
                                    e -> {
                                      log.warn("Shop list cache SET error eventId={}", eventId, e);
                                      return Mono.empty();
                                    })
                                .thenReturn(shops);
                          } catch (Exception e) {
                            log.warn("Shop list cache serialize error eventId={}", eventId, e);
                            return Mono.just(shops);
                          }
                        }));

    // Step 2: bulk-fetch open shifts and merge shiftOpen flag — always live, never cached
    return shopsMono
        .flatMap(
            shops -> {
              if (shops.isEmpty()) return Mono.just(shops);
              List<Long> shopIds =
                  shops.stream().map(ShopResponse::getId).collect(Collectors.toList());
              return shiftSessionRepository
                  .findActiveShiftsBulk(shopIds, eventId)
                  .map(s -> s.getShopId())
                  .collect(Collectors.toSet())
                  .map(
                      openShopIds -> {
                        shops.forEach(s -> s.setShiftOpen(openShopIds.contains(s.getId())));
                        return shops;
                      });
            })
        .flatMapMany(Flux::fromIterable)
        .doOnComplete(() -> log.info("Fetched all shops successfully for eventId={}", eventId))
        .doOnError(ex -> log.error("Failed to fetch shops for eventId={}", eventId, ex));
  }

  // GET SHOP BY ID
  public Mono<ShopResponse> getShopById(Long id, Long eventId) {
    if (eventId == null) {
      return Mono.error(new IllegalArgumentException("X-Event-Id header is mandatory"));
    }
    log.info("Fetching shop by id={} for eventId={}", id, eventId);

    String cacheKey = SHOP_KEY_PREFIX + id;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Shop cache GET error id={}, falling back to DB", id, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                ShopResponse cached = CACHE_MAPPER.readValue(json, ShopResponse.class);
                // Validate scoping on cached result too
                if (!cached.getEventId().equals(eventId)) {
                  return Mono.<ShopResponse>empty();
                }
                log.debug("Shop cache HIT id={}", id);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Shop cache deserialize error id={}, falling back to DB", id, e);
                return Mono.<ShopResponse>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    shopRepository
                        .findById(id)
                        .switchIfEmpty(
                            Mono.error(
                                new IllegalArgumentException("Shop not found with id: " + id)))
                        .flatMap(
                            shop -> {
                              if (!shop.getEventId().equals(eventId)) {
                                return Mono.error(
                                    new IllegalArgumentException(
                                        "Access Denied: Shop belongs to a different event"));
                              }
                              ShopResponse response = mapToResponse(shop);
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(response);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, SHOPS_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Shop cache SET error id={}", id, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(response);
                              } catch (Exception e) {
                                log.warn("Shop cache serialize error id={}", id, e);
                                return Mono.just(response);
                              }
                            })))
        // Always fetch shift status live — not cached
        .flatMap(
            response ->
                shiftSessionRepository
                    .findActiveSession(response.getId(), eventId)
                    .map(
                        s -> {
                          response.setShiftOpen(true);
                          return response;
                        })
                    .defaultIfEmpty(response)
                    .doOnNext(
                        r -> {
                          if (r.getShiftOpen() == null) r.setShiftOpen(false);
                        }))
        .doOnSuccess(shop -> log.info("Fetched shop successfully id={}", id))
        .doOnError(ex -> log.error("Failed to fetch shop id={}", id, ex));
  }

  public Flux<ShopResponse> getShopsByName(String shopName, Long eventId) {
    if (eventId == null) {
      return Flux.error(new IllegalArgumentException("X-Event-Id header is mandatory"));
    }
    log.info("Fetching shops with name={} for eventId={}", shopName, eventId);

    String cacheKey = SHOP_NAME_KEY_PREFIX + shopName + ":" + eventId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn(
                  "Shop name cache GET error name={} eventId={}, falling back to DB",
                  shopName,
                  eventId,
                  e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ShopResponse> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ShopResponse>>() {});
                log.debug(
                    "Shop name cache HIT name={} eventId={} count={}",
                    shopName,
                    eventId,
                    cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("Shop name cache deserialize error, falling back to DB", e);
                return Flux.<ShopResponse>empty();
              }
            })
        .switchIfEmpty(
            shopRepository
                .findByShopNameAndEventIdIn(shopName, List.of(eventId))
                .map(this::mapToResponse)
                .collectList()
                .flatMapMany(
                    shops -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(shops);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, SHOPS_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn(
                                      "Shop name cache SET error name={} eventId={}",
                                      shopName,
                                      eventId,
                                      e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(shops));
                      } catch (Exception e) {
                        log.warn("Shop name cache serialize error name={}", shopName, e);
                        return Flux.fromIterable(shops);
                      }
                    }))
        // Always fetch shift status live — not cached
        .collectList()
        .flatMapMany(
            shops -> {
              if (shops.isEmpty()) return Flux.fromIterable(shops);
              List<Long> shopIds =
                  shops.stream().map(ShopResponse::getId).collect(Collectors.toList());
              return shiftSessionRepository
                  .findActiveShiftsBulk(shopIds, eventId)
                  .map(s -> s.getShopId())
                  .collect(Collectors.toSet())
                  .flatMapMany(
                      openShopIds -> {
                        shops.forEach(s -> s.setShiftOpen(openShopIds.contains(s.getId())));
                        return Flux.fromIterable(shops);
                      });
            })
        .doOnComplete(
            () ->
                log.info(
                    "Fetched shops by name successfully name={} eventId={}", shopName, eventId))
        .doOnError(
            ex -> log.error("Failed to fetch shops by name={} eventId={}", shopName, eventId, ex));
  }

  private ShopResponse mapToResponse(Shop shop) {
    return ShopResponse.builder()
        .id(shop.getId())
        .shopName(shop.getShopName())
        .categoryId(shop.getCategoryId())
        .categoryName(shop.getCategoryName())
        .counterNumber(shop.getCounterNumber())
        .isActive(shop.getIsActive())
        .eventId(shop.getEventId())
        .receivingPrintEnabled(shop.getReceivingPrintEnabled())
        .createdAt(shop.getCreatedAt())
        .closedAt(shop.getClosedAt())
        .build();
  }

  @Transactional
  public Mono<Void> deleteShop(Long id, Long eventId, String authHeader) {
    log.info("Soft deleting shop id={} eventId={}", id, eventId);
    return shopRepository
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Shop not found with id: " + id)))
        .flatMap(
            existingShop -> {
              if (!existingShop.getEventId().equals(eventId)) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Access Denied: Cannot delete shop belonging to another event"));
              }
              // ── Fix 1: Reject if a shift is currently OPEN ──────────────────
              return shiftSessionRepository
                  .findActiveSession(id, eventId)
                  .flatMap(
                      openShift ->
                          Mono.<Void>error(
                              new IllegalStateException(
                                  "Cannot close shop: shift #"
                                      + openShift.getId()
                                      + " is still OPEN. Please close the shift first.")))
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            existingShop.setIsActive(false);
                            existingShop.setClosedAt(LocalDateTime.now());
                            return shopRepository
                                .save(existingShop)
                                .flatMap(
                                    savedShop ->
                                        // Unassign all staff
                                        shopStaffAssignmentService
                                            .unassignAllStaffFromShop(savedShop.getId())
                                            // ── Fix 3: Return remaining counter stocks ───
                                            .then(
                                                inventoryClient.returnShopCounterStocksToInventory(
                                                    savedShop.getId(),
                                                    savedShop.getEventId(),
                                                    authHeader))
                                            // Invalidate Redis caches
                                            .then(
                                                Mono.when(
                                                        redisTemplate
                                                            .delete(SHOP_KEY_PREFIX + id)
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "Shop cache delete failed on deleteShop id={}",
                                                                      id,
                                                                      e);
                                                                  return Mono.empty();
                                                                }),
                                                        redisTemplate
                                                            .delete(
                                                                SHOPS_EVENT_KEY_PREFIX + eventId)
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "Shop list cache delete failed on deleteShop eventId={}",
                                                                      eventId,
                                                                      e);
                                                                  return Mono.empty();
                                                                }),
                                                        redisTemplate
                                                            .delete(
                                                                SHOP_NAME_KEY_PREFIX
                                                                    + savedShop.getShopName()
                                                                    + ":"
                                                                    + eventId)
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "Shop name cache delete failed on deleteShop id={}",
                                                                      id,
                                                                      e);
                                                                  return Mono.empty();
                                                                }),
                                                        redisTemplate
                                                            .delete(SHOP_HISTORY_KEY_PREFIX + id)
                                                            .onErrorResume(
                                                                e -> {
                                                                  log.warn(
                                                                      "Shop history cache delete failed on deleteShop id={}",
                                                                      id,
                                                                      e);
                                                                  return Mono.empty();
                                                                }))
                                                    .then()));
                          }));
            });
  }

  /**
   * Feature 4 — Manual unissue: moves stock from a shop counter back to main inventory. Only ADMIN
   * or SHOP_SUPERVISOR can call this. The shop's shift must be CLOSED (no active shift).
   */
  public Mono<Void> unissueStocks(
      Long shopId,
      Long eventId,
      List<InventoryClient.UnissueItemRequest> items,
      String authHeader,
      String rolesHeader) {

    // Role guard
    boolean hasPermission =
        rolesHeader != null
            && (rolesHeader.contains("ADMIN") || rolesHeader.contains("SHOP_SUPERVISOR"));
    if (!hasPermission) {
      return Mono.error(
          new IllegalStateException(
              "Access Denied: Only ADMIN or SHOP_SUPERVISOR can unissue stock."));
    }

    return shopRepository
        .findById(shopId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Shop not found: " + shopId)))
        .flatMap(
            shop -> {
              if (!shop.getEventId().equals(eventId)) {
                return Mono.error(
                    new IllegalArgumentException("Shop does not belong to this event."));
              }
              if (!Boolean.TRUE.equals(shop.getIsActive())) {
                return Mono.error(
                    new IllegalStateException(
                        "Shop is closed. Unissue is not allowed on a closed shop."));
              }
              // Shift must be CLOSED (no open shift)
              return shiftSessionRepository
                  .findActiveSession(shopId, eventId)
                  .flatMap(
                      openShift ->
                          Mono.<Void>error(
                              new IllegalStateException(
                                  "Cannot unissue: shift #"
                                      + openShift.getId()
                                      + " is OPEN. Close the shift first.")))
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              inventoryClient
                                  .unissueStocksFromCounter(shopId, eventId, items, authHeader)
                                  .doFinally(
                                      signal ->
                                          redisTemplate
                                              .delete(SHOP_HISTORY_KEY_PREFIX + shopId)
                                              .doOnError(
                                                  e ->
                                                      log.warn(
                                                          "Failed to evict shop history cache after unissue shopId={}",
                                                          shopId,
                                                          e))
                                              .onErrorResume(e -> Mono.empty())
                                              .subscribe())));
            });
  }

  public Mono<ShopHistoryResponse> getShopHistory(Long shopId, String authHeader) {
    log.info("Aggregating history for shopId={}", shopId);

    String cacheKey = SHOP_HISTORY_KEY_PREFIX + shopId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn(
                  "Shop history cache GET error shopId={}, falling back to aggregation", shopId, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                ShopHistoryResponse cached =
                    CACHE_MAPPER.readValue(json, ShopHistoryResponse.class);
                log.debug("Shop history cache HIT shopId={}", shopId);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn(
                    "Shop history cache deserialize error shopId={}, re-aggregating", shopId, e);
                return Mono.<ShopHistoryResponse>empty();
              }
            })
        .switchIfEmpty(aggregateShopHistory(shopId, authHeader, cacheKey));
  }

  private Mono<ShopHistoryResponse> aggregateShopHistory(
      Long shopId, String authHeader, String cacheKey) {
    return shopRepository
        .findById(shopId)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Shop not found with id: " + shopId)))
        .flatMap(
            shop -> {
              ShopResponse shopResponse = mapToResponse(shop);

              // Enrich shopResponse with live shift status
              return shiftSessionRepository
                  .findActiveSession(shopId, shop.getEventId())
                  .doOnNext(s -> shopResponse.setShiftOpen(true))
                  .defaultIfEmpty(new ShopShiftSession()) // sentinel — shift closed
                  .doOnNext(
                      s -> {
                        if (shopResponse.getShiftOpen() == null) shopResponse.setShiftOpen(false);
                      })
                  .thenReturn(shopResponse)
                  .flatMap(
                      sr -> {
                        // 1. SHOP_OPENED
                        LocalDateTime creationTime =
                            shop.getCreatedAt() != null
                                ? shop.getCreatedAt()
                                : LocalDateTime.of(2026, 5, 20, 0, 0);
                        HistoryEvent shopOpenedEvent =
                            HistoryEvent.builder()
                                .type(ShopHistoryEventType.SHOP_OPENED.value())
                                .description(
                                    String.format(
                                        "Shop '%s' (Counter %d) was registered and opened.",
                                        shop.getShopName(), shop.getCounterNumber()))
                                .timestamp(creationTime)
                                .metadata(
                                    Map.of(
                                        "shopName", shop.getShopName(),
                                        "counterNumber", shop.getCounterNumber(),
                                        "eventId", shop.getEventId()))
                                .build();

                        // 2. SHOP_CLOSED — emit whenever isActive=false, use updatedAt as fallback
                        // if
                        // closedAt null
                        HistoryEvent shopClosedEvent = null;
                        if (!shop.getIsActive()) {
                          LocalDateTime closedTime =
                              shop.getClosedAt() != null
                                  ? shop.getClosedAt()
                                  : (shop.getCreatedAt() != null
                                      ? shop.getCreatedAt()
                                      : LocalDateTime.now());
                          shopClosedEvent =
                              HistoryEvent.builder()
                                  .type(ShopHistoryEventType.SHOP_CLOSED.value())
                                  .description(
                                      String.format(
                                          "Shop '%s' was permanently closed.", shop.getShopName()))
                                  .timestamp(closedTime)
                                  .metadata(Map.of("closedAt", closedTime))
                                  .build();
                        }

                        final HistoryEvent finalClosedEvent = shopClosedEvent;
                        Flux<HistoryEvent> staticEventsFlux =
                            finalClosedEvent != null
                                ? Flux.just(shopOpenedEvent, finalClosedEvent)
                                : Flux.just(shopOpenedEvent);

                        // 3. STAFF_ASSIGNED / STAFF_UNASSIGNED
                        // STAFF_UNASSIGNED fix: emit even when leftAt is null (use assignedAt as
                        // fallback)
                        Flux<HistoryEvent> staffEventsFlux =
                            shopStaffAssignmentRepository
                                .findByShopId(shopId)
                                .distinct(ShopStaffAssignment::getId)
                                .collectList()
                                .flatMapMany(
                                    assignments -> {
                                      if (assignments.isEmpty()) {
                                        return Flux.empty();
                                      }
                                      Set<Long> userIds =
                                          assignments.stream()
                                              .map(ShopStaffAssignment::getUserId)
                                              .filter(Objects::nonNull)
                                              .collect(Collectors.toSet());

                                      return Flux.fromIterable(userIds)
                                          .flatMap(
                                              userId ->
                                                  userClient
                                                      .getUserById(userId, authHeader)
                                                      .map(
                                                          user ->
                                                              new AbstractMap.SimpleEntry<>(
                                                                  userId, user.getUsername()))
                                                      .defaultIfEmpty(
                                                          new AbstractMap.SimpleEntry<>(
                                                              userId, "User ID " + userId)))
                                          .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                                          .flatMapMany(
                                              usernameMap -> {
                                                List<ShopHistoryResponse.HistoryEvent> list =
                                                    new ArrayList<>();
                                                for (ShopStaffAssignment assignment : assignments) {
                                                  String username =
                                                      usernameMap.getOrDefault(
                                                          assignment.getUserId(),
                                                          "User ID " + assignment.getUserId());
                                                  if (assignment.getAssignedAt() != null) {
                                                    list.add(
                                                        ShopHistoryResponse.HistoryEvent.builder()
                                                            .type(
                                                                ShopHistoryEventType.STAFF_ASSIGNED
                                                                    .value())
                                                            .description(
                                                                String.format(
                                                                    "Staff assigned: %s as %s.",
                                                                    username,
                                                                    assignment.getRoleCode()))
                                                            .timestamp(assignment.getAssignedAt())
                                                            .metadata(
                                                                Map.of(
                                                                    "userId",
                                                                        assignment.getUserId(),
                                                                    "username", username,
                                                                    "roleCode",
                                                                        assignment.getRoleCode()))
                                                            .build());
                                                  }
                                                  // Emit STAFF_UNASSIGNED for any inactive
                                                  // assignment
                                                  // Use leftAt if available, else fall back to
                                                  // assignedAt
                                                  if (!assignment.getIsActive()) {
                                                    LocalDateTime leftTime =
                                                        assignment.getLeftAt() != null
                                                            ? assignment.getLeftAt()
                                                            : assignment.getAssignedAt();
                                                    list.add(
                                                        ShopHistoryResponse.HistoryEvent.builder()
                                                            .type(
                                                                ShopHistoryEventType
                                                                    .STAFF_UNASSIGNED
                                                                    .value())
                                                            .description(
                                                                String.format(
                                                                    "Staff unassigned: %s (Role: %s) left the shop.",
                                                                    username,
                                                                    assignment.getRoleCode()))
                                                            .timestamp(leftTime)
                                                            .metadata(
                                                                Map.of(
                                                                    "userId",
                                                                    assignment.getUserId(),
                                                                    "username",
                                                                    username,
                                                                    "roleCode",
                                                                    assignment.getRoleCode(),
                                                                    "leftAt",
                                                                    leftTime))
                                                            .build());
                                                  }
                                                }
                                                return Flux.fromIterable(list);
                                              });
                                    });

                        // 4. SALE / SALE_CANCELLED / SALE_PARTIALLY_RETURNED
                        // Fix: SalesOrder.shopId is String — pass String.valueOf(shopId)
                        Flux<HistoryEvent> salesEventsFlux =
                            salesOrderRepository
                                .findByShopId(String.valueOf(shopId))
                                .flatMap(
                                    order -> {
                                      List<ShopHistoryResponse.HistoryEvent> orderEvents =
                                          new ArrayList<>();

                                      String status =
                                          order.getStatus() != null
                                              ? order.getStatus().toString()
                                              : "";

                                      orderEvents.add(
                                          HistoryEvent.builder()
                                              .type(ShopHistoryEventType.SALE.value())
                                              .description(
                                                  String.format(
                                                      "Bill No %s, Amount: ₹%s, Seller: %s.",
                                                      order.getBillingInvoiceNumber(),
                                                      order.getOrderSubtotal() != null
                                                          ? order.getOrderSubtotal().toString()
                                                          : "0",
                                                      order.getSellerName()))
                                              .timestamp(order.getCreatedAt())
                                              .metadata(
                                                  Map.of(
                                                      "orderNumber",
                                                      order.getOrderNumber(),
                                                      "amount",
                                                      order.getOrderSubtotal() != null
                                                          ? order.getOrderSubtotal()
                                                          : BigDecimal.ZERO,
                                                      "sellerName",
                                                      order.getSellerName() != null
                                                          ? order.getSellerName()
                                                          : "N/A",
                                                      "status",
                                                      status))
                                              .build());

                                      if ("CANCELLED".equals(status)) {
                                        LocalDateTime cancelTime =
                                            order.getUpdatedAt() != null
                                                ? order.getUpdatedAt()
                                                : order.getCreatedAt();
                                        orderEvents.add(
                                            ShopHistoryResponse.HistoryEvent.builder()
                                                .type(ShopHistoryEventType.SALE_CANCELLED.value())
                                                .description(
                                                    String.format(
                                                        "Sale cancelled: Order %s. Reason: %s.",
                                                        order.getOrderNumber(),
                                                        order.getCancellationReason() != null
                                                            ? order.getCancellationReason()
                                                            : "None"))
                                                .timestamp(cancelTime)
                                                .metadata(
                                                    Map.of(
                                                        "orderNumber",
                                                        order.getOrderNumber(),
                                                        "reason",
                                                        order.getCancellationReason() != null
                                                            ? order.getCancellationReason()
                                                            : "N/A"))
                                                .build());
                                      }

                                      // SALE_PARTIALLY_RETURNED: fetch returns from sales_return
                                      // table
                                      if ("PARTIALLY_RETURNED".equals(status)
                                          || "RETURNED".equals(status)) {
                                        return salesReturnRepository
                                            .findBySalesOrderId(order.getId())
                                            .collectList()
                                            .flatMapMany(
                                                returns -> {
                                                  for (SalesReturn ret : returns) {
                                                    orderEvents.add(
                                                        ShopHistoryResponse.HistoryEvent.builder()
                                                            .type(
                                                                ShopHistoryEventType
                                                                    .SALE_PARTIALLY_RETURNED
                                                                    .value())
                                                            .description(
                                                                String.format(
                                                                    "Partial return on Order %s: Qty %d returned, Refund ₹%s.",
                                                                    order.getOrderNumber(),
                                                                    ret.getQuantity(),
                                                                    ret.getRefundAmount() != null
                                                                        ? ret.getRefundAmount()
                                                                            .toString()
                                                                        : "0"))
                                                            .timestamp(
                                                                ret.getReturnedAt() != null
                                                                    ? ret.getReturnedAt()
                                                                    : order.getUpdatedAt())
                                                            .metadata(
                                                                Map.of(
                                                                    "orderNumber",
                                                                    order.getOrderNumber(),
                                                                    "productId",
                                                                    ret.getProductId(),
                                                                    "returnedQty",
                                                                    ret.getQuantity(),
                                                                    "refundAmount",
                                                                    ret.getRefundAmount() != null
                                                                        ? ret.getRefundAmount()
                                                                        : BigDecimal.ZERO,
                                                                    "reason",
                                                                    ret.getReason() != null
                                                                        ? ret.getReason()
                                                                        : "N/A",
                                                                    "processedBy",
                                                                    ret.getProcessedByName() != null
                                                                        ? ret.getProcessedByName()
                                                                        : "N/A"))
                                                            .build());
                                                  }
                                                  return Flux.fromIterable(orderEvents);
                                                });
                                      }

                                      return Flux.fromIterable(orderEvents);
                                    });

                        // 5. STOCK_ISSUE / STOCK_UNISSUE — split by locationFrom/locationTo
                        // Primary signal: locationFrom="MAIN" → STOCK_ISSUE; locationTo="MAIN" →
                        // STOCK_UNISSUE
                        // Fallback signal: reason contains "UNISSUE" → STOCK_UNISSUE;
                        // "COUNTER_ALLOCATION" →
                        // STOCK_ISSUE
                        Flux<HistoryEvent> movementEventsFlux =
                            inventoryClient
                                .getStockMovementsByShop(shopId, shop.getEventId(), authHeader)
                                .flatMap(
                                    mv -> {
                                      String locFrom =
                                          mv.getLocationFrom() != null ? mv.getLocationFrom() : "";
                                      String locTo =
                                          mv.getLocationTo() != null ? mv.getLocationTo() : "";
                                      String reason =
                                          mv.getReason() != null
                                              ? mv.getReason()
                                              : StockMovement.REASON_NA;

                                      // Skip sale-side deductions (COUNTER→CUSTOMER) — already
                                      // shown as SALE events
                                      if (StockMovement.LOC_CUSTOMER.equals(locTo)
                                          || StockMovement.REASON_SALE.equalsIgnoreCase(reason)) {
                                        return Flux.empty();
                                      }

                                      boolean isIssue =
                                          StockMovement.LOC_MAIN.equals(locFrom)
                                              || reason.contains(
                                                  StockMovement.REASON_COUNTER_ALLOC);
                                      boolean isUnissue =
                                          StockMovement.LOC_MAIN.equals(locTo)
                                              || reason
                                                  .toUpperCase()
                                                  .contains(StockMovement.REASON_UNISSUE);

                                      // Skip unclassifiable movements
                                      if (!isIssue && !isUnissue) {
                                        return Flux.empty();
                                      }

                                      String eventType =
                                          isUnissue
                                              ? ShopHistoryEventType.STOCK_UNISSUE.value()
                                              : ShopHistoryEventType.STOCK_ISSUE.value();

                                      String description =
                                          isUnissue
                                              ? String.format(
                                                  "Stock returned to main: Product ID %d, Qty: %d (%s).",
                                                  mv.getProductId(), mv.getQuantity(), reason)
                                              : String.format(
                                                  "Stock issued to counter: Product ID %d, Qty: %d (%s).",
                                                  mv.getProductId(), mv.getQuantity(), reason);

                                      Map<String, Object> meta = new LinkedHashMap<>();
                                      meta.put("productId", mv.getProductId());
                                      meta.put("quantity", mv.getQuantity());
                                      meta.put("reason", reason);
                                      if (!locFrom.isEmpty()) meta.put("locationFrom", locFrom);
                                      if (!locTo.isEmpty()) meta.put("locationTo", locTo);

                                      return Flux.just(
                                          ShopHistoryResponse.HistoryEvent.builder()
                                              .type(eventType)
                                              .description(description)
                                              .timestamp(
                                                  mv.getMovementDate() != null
                                                      ? mv.getMovementDate()
                                                      : LocalDateTime.now())
                                              .metadata(Collections.unmodifiableMap(meta))
                                              .build());
                                    });

                        return Flux.merge(
                                staticEventsFlux,
                                staffEventsFlux,
                                salesEventsFlux,
                                movementEventsFlux)
                            .collectList()
                            .map(
                                events -> {
                                  events.sort(
                                      (e1, e2) -> e1.getTimestamp().compareTo(e2.getTimestamp()));
                                  return ShopHistoryResponse.builder()
                                      .shop(sr)
                                      .events(events)
                                      .build();
                                })
                            .flatMap(
                                result -> {
                                  try {
                                    String json = CACHE_MAPPER.writeValueAsString(result);
                                    return redisTemplate
                                        .opsForValue()
                                        .set(cacheKey, json, SHOP_HISTORY_TTL)
                                        .onErrorResume(
                                            e -> {
                                              log.warn(
                                                  "Shop history cache SET error shopId={}",
                                                  shopId,
                                                  e);
                                              return Mono.empty();
                                            })
                                        .thenReturn(result);
                                  } catch (Exception e) {
                                    log.warn(
                                        "Shop history cache serialize error shopId={}", shopId, e);
                                    return Mono.just(result);
                                  }
                                });
                      }); // closes flatMap(sr ->
            });
  }
}
