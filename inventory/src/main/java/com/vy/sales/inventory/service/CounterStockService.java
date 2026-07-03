package com.vy.sales.inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.inventory.dto.CounterStockDecrementRequest;
import com.vy.sales.inventory.dto.StockMovementDTO;
import com.vy.sales.inventory.dto.UnissueItemRequest;
import com.vy.sales.inventory.entity.CounterStock;
import com.vy.sales.inventory.entity.Stock;
import com.vy.sales.inventory.entity.StockMovement;
import com.vy.sales.inventory.exceptions.InsufficientStockException;
import com.vy.sales.inventory.repository.CounterStockRepository;
import com.vy.sales.inventory.repository.StockMovementRepository;
import com.vy.sales.inventory.repository.StockRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class CounterStockService {

  private static final String COUNTER_STOCKS_EVENT_KEY_PREFIX = "counter-stocks:event:";
  private static final Duration COUNTER_STOCKS_TTL = Duration.ofSeconds(30);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final CounterStockRepository counterStockRepository;
  private final StockMovementRepository movementRepository;
  private final StockAggregationService aggregationService;
  private final ProductService productService;
  private final StockRepository stockRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  @Transactional
  public Mono<CounterStock> create(CounterStock counterStock) {
    log.info(
        "COUNTER_STOCK_CREATE_REQUEST productId={} eventId={} quantity={} shopId={}",
        counterStock.getProductId(),
        counterStock.getEventId(),
        counterStock.getQuantity(),
        counterStock.getShopId());

    return stockRepository
        .findByProductIdAndEventIdAndLocationForUpdate(
            counterStock.getProductId(), counterStock.getEventId(), "MAIN")
        .defaultIfEmpty(new Stock())
        .flatMap(
            stockLock ->
                movementRepository
                    .calculateStock(counterStock.getProductId(), counterStock.getEventId())
                    .flatMap(
                        currentStock -> {
                          log.info(
                              "COUNTER_STOCK_CHECK productId={} available={} requested={}",
                              counterStock.getProductId(),
                              currentStock,
                              counterStock.getQuantity());
                          if (currentStock < counterStock.getQuantity()) {
                            log.warn(
                                "COUNTER_STOCK_INSUFFICIENT productId={} available={} requested={}",
                                counterStock.getProductId(),
                                currentStock,
                                counterStock.getQuantity());
                            return Mono.error(
                                new IllegalStateException(
                                    "Insufficient stock. Available: " + currentStock));
                          }
                          return proceedWithCounterStock(counterStock);
                        }))
        .doOnError(
            ex ->
                log.error(
                    "COUNTER_STOCK_CREATE_FAILED productId={} reason={}",
                    counterStock.getProductId(),
                    ex.getMessage(),
                    ex));
  }

  @Transactional
  public Flux<CounterStock> createBulk(List<CounterStock> counterStockList) {
    // Reuse the single create method sequentially for each item to avoid race conditions
    return Flux.fromIterable(counterStockList).concatMap(this::create);
  }

  private Mono<CounterStock> proceedWithCounterStock(CounterStock counterStock) {
    log.debug(
        "COUNTER_STOCK_PROCEED productId={} quantity={} shopId={}",
        counterStock.getProductId(),
        counterStock.getQuantity(),
        counterStock.getShopId());

    return productService
        .get(counterStock.getProductId())
        .switchIfEmpty(Mono.error(new RuntimeException("Product not found")))
        .flatMap(
            product -> {
              counterStock.setSaleDate(LocalDateTime.now());

              int incomingQty = counterStock.getQuantity() != null ? counterStock.getQuantity() : 0;

              return counterStockRepository
                  .findByProductIdAndShopIdAndEventIdForUpdate(
                      counterStock.getProductId(),
                      counterStock.getShopId(),
                      counterStock.getEventId())
                  .flatMap(
                      existingStock -> {
                        log.info(
                            "FOUND_EXISTING_COUNTER_STOCK id={} initialQuantity={} liveQuantity={}. Merging quantity={}",
                            existingStock.getId(),
                            existingStock.getInitialQuantity(),
                            existingStock.getLiveQuantity(),
                            incomingQty);
                        existingStock.setInitialQuantity(
                            (existingStock.getInitialQuantity() != null
                                    ? existingStock.getInitialQuantity()
                                    : 0)
                                + incomingQty);
                        existingStock.setLiveQuantity(
                            (existingStock.getLiveQuantity() != null
                                    ? existingStock.getLiveQuantity()
                                    : 0)
                                + incomingQty);
                        existingStock.setQuantity(existingStock.getLiveQuantity());
                        existingStock.setUpdatedAt(LocalDateTime.now());
                        return counterStockRepository.save(existingStock);
                      })
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            log.info(
                                "CREATING_NEW_COUNTER_STOCK productId={} shopId={} quantity={}",
                                counterStock.getProductId(),
                                counterStock.getShopId(),
                                incomingQty);
                            counterStock.setInitialQuantity(incomingQty);
                            counterStock.setLiveQuantity(incomingQty);
                            counterStock.setQuantity(incomingQty);
                            counterStock.setCreatedAt(LocalDateTime.now());
                            counterStock.setUpdatedAt(LocalDateTime.now());
                            return counterStockRepository.save(counterStock);
                          }))
                  .doOnSuccess(
                      saved ->
                          log.info(
                              "COUNTER_STOCK_SAVED id={} productId={} initialQuantity={} liveQuantity={} shopId={}",
                              saved.getId(),
                              saved.getProductId(),
                              saved.getInitialQuantity(),
                              saved.getLiveQuantity(),
                              saved.getShopId()))
                  .flatMap(
                      saved ->
                          movementRepository
                              .save(
                                  StockMovement.builder()
                                      .productId(saved.getProductId())
                                      .eventId(saved.getEventId())
                                      .username(saved.getSellerUser())
                                      .movementType(StockMovement.MovementType.TRANSFER)
                                      .quantity(incomingQty)
                                      .reason("COUNTER_ALLOCATION")
                                      .locationFrom("MAIN")
                                      .locationTo("COUNTER_" + saved.getShopId())
                                      .shopId(
                                          saved.getShopId() != null
                                              ? Long.parseLong(saved.getShopId())
                                              : null)
                                      .movementDate(LocalDateTime.now())
                                      .createdAt(LocalDateTime.now())
                                      .updatedAt(LocalDateTime.now())
                                      .build())
                              .doOnSuccess(
                                  mv ->
                                      log.info(
                                          "COUNTER_STOCK_MOVEMENT_SAVED productId={} quantity={} from=MAIN to=COUNTER_{}",
                                          mv.getProductId(),
                                          mv.getQuantity(),
                                          saved.getShopId()))
                              .then(
                                  aggregationService.recalculateStock(
                                      saved.getProductId(), saved.getEventId(), "MAIN"))
                              .then(evictCounterStockCache(saved.getEventId()))
                              .thenReturn(saved));
            });
  }

  public Flux<CounterStock> getAllCounterStocks(Long eventId) {
    log.info("SERVICE_FETCH_ALL_COUNTER_STOCKS_STARTED eventId={}", eventId);
    String cacheKey = COUNTER_STOCKS_EVENT_KEY_PREFIX + eventId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("COUNTER_STOCK_CACHE_GET_ERROR eventId={}, falling back to DB", eventId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<CounterStock> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<CounterStock>>() {});
                log.debug("COUNTER_STOCK_CACHE_HIT eventId={} count={}", eventId, list.size());
                return Flux.fromIterable(list);
              } catch (Exception e) {
                log.warn(
                    "COUNTER_STOCK_CACHE_DESERIALIZE_ERROR eventId={}, falling back to DB",
                    eventId,
                    e);
                return Flux.<CounterStock>empty();
              }
            })
        .switchIfEmpty(
            Flux.defer(
                () -> {
                  java.util.List<Long> eventIds = new java.util.ArrayList<>();
                  if (eventId != null) eventIds.add(eventId);

                  return counterStockRepository
                      .findByEventIdIn(eventIds)
                      .map(
                          item -> {
                            item.setQuantity(
                                item.getLiveQuantity() != null ? item.getLiveQuantity() : 0);
                            return item;
                          })
                      .collectList()
                      .flatMap(
                          list -> {
                            try {
                              String json = CACHE_MAPPER.writeValueAsString(list);
                              return redisTemplate
                                  .opsForValue()
                                  .set(cacheKey, json, COUNTER_STOCKS_TTL)
                                  .onErrorResume(
                                      e -> {
                                        log.warn(
                                            "COUNTER_STOCK_CACHE_SET_ERROR eventId={}", eventId, e);
                                        return Mono.empty();
                                      })
                                  .thenReturn(list);
                            } catch (Exception e) {
                              log.warn(
                                  "COUNTER_STOCK_CACHE_SERIALIZE_ERROR eventId={}", eventId, e);
                              return Mono.just(list);
                            }
                          })
                      .flatMapMany(Flux::fromIterable);
                }))
        .doOnNext(
            item ->
                log.debug(
                    "SERVICE_FETCH_ALL_COUNTER_STOCKS_RECORD id={} productId={}",
                    item.getId(),
                    item.getProductId()))
        .doOnComplete(
            () -> log.info("SERVICE_FETCH_ALL_COUNTER_STOCKS_COMPLETED eventId={}", eventId))
        .doOnError(
            ex ->
                log.error(
                    "SERVICE_FETCH_ALL_COUNTER_STOCKS_FAILED reason={}", ex.getMessage(), ex));
  }

  private Mono<Void> evictCounterStockCache(Long eventId) {
    return redisTemplate
        .delete(COUNTER_STOCKS_EVENT_KEY_PREFIX + eventId)
        .onErrorResume(
            e -> {
              log.warn("COUNTER_STOCK_CACHE_EVICT_ERROR eventId={}", eventId, e);
              return Mono.empty();
            })
        .then();
  }

  /**
   * Fix 3 — Called automatically on shop closure. Fetches all counter stocks with remaining
   * live_quantity, records a TRANSFER movement for each, zeroes live_quantity, and recalculates
   * inventory_stocks so the main warehouse balance is restored.
   */
  @Transactional
  public Mono<Void> returnShopStocksToInventory(Long shopId, Long eventId, String username) {
    String shopIdStr = String.valueOf(shopId);
    log.info("SHOP_CLOSURE_STOCK_RETURN_START shopId={} eventId={}", shopId, eventId);

    return counterStockRepository
        .findByShopIdAndEventIdWithPositiveStock(shopIdStr, eventId)
        .concatMap(
            cs -> {
              int qty = cs.getLiveQuantity();
              if (qty <= 0) return Mono.empty();

              StockMovement mv =
                  StockMovement.builder()
                      .productId(cs.getProductId())
                      .eventId(eventId)
                      .username(username)
                      .movementType(StockMovement.MovementType.TRANSFER)
                      .quantity(qty)
                      .reason("SHOP_CLOSURE_RETURN")
                      .locationFrom("COUNTER_" + shopId)
                      .locationTo("MAIN")
                      .shopId(shopId)
                      .movementDate(LocalDateTime.now())
                      .createdAt(LocalDateTime.now())
                      .updatedAt(LocalDateTime.now())
                      .build();

              cs.setLiveQuantity(0);
              cs.setQuantity(0);
              cs.setUpdatedAt(LocalDateTime.now());

              return movementRepository
                  .save(mv)
                  .then(counterStockRepository.save(cs))
                  .then(aggregationService.recalculateStock(cs.getProductId(), eventId, "MAIN"))
                  .doOnSuccess(
                      s ->
                          log.info(
                              "SHOP_CLOSURE_STOCK_RETURNED productId={} qty={} shopId={}",
                              cs.getProductId(),
                              qty,
                              shopId));
            })
        .then(evictCounterStockCache(eventId))
        .doOnSuccess(
            v -> log.info("SHOP_CLOSURE_STOCK_RETURN_DONE shopId={} eventId={}", shopId, eventId))
        .doOnError(
            ex ->
                log.error(
                    "SHOP_CLOSURE_STOCK_RETURN_FAILED shopId={} reason={}",
                    shopId,
                    ex.getMessage(),
                    ex));
  }

  /**
   * Feature 4 — Manual unissue. Validates each item's quantity against live_quantity, records
   * TRANSFER movements, decrements counter stock, and recalculates main inventory for each product.
   */
  @Transactional
  public Mono<Void> unissueStocks(
      Long shopId, Long eventId, List<UnissueItemRequest> items, String username) {
    String shopIdStr = String.valueOf(shopId);
    log.info(
        "UNISSUE_START shopId={} eventId={} items={} initiatedBy={}",
        shopId,
        eventId,
        items.size(),
        username);

    return Flux.fromIterable(items)
        .concatMap(
            item -> {
              if (item.getQuantity() == null || item.getQuantity() <= 0) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Quantity must be > 0 for productId=" + item.getProductId()));
              }
              return counterStockRepository
                  .findByProductIdAndShopIdAndEventIdForUpdate(
                      item.getProductId(), shopIdStr, eventId)
                  .switchIfEmpty(
                      Mono.error(
                          new IllegalStateException(
                              "No counter stock found for productId="
                                  + item.getProductId()
                                  + " in shopId="
                                  + shopId)))
                  .flatMap(
                      cs -> {
                        int available = cs.getLiveQuantity() != null ? cs.getLiveQuantity() : 0;
                        if (item.getQuantity() > available) {
                          return Mono.error(
                              new IllegalStateException(
                                  "Requested qty "
                                      + item.getQuantity()
                                      + " exceeds available "
                                      + available
                                      + " for productId="
                                      + item.getProductId()));
                        }

                        StockMovement mv =
                            StockMovement.builder()
                                .productId(item.getProductId())
                                .eventId(eventId)
                                .username(username)
                                .movementType(StockMovement.MovementType.TRANSFER)
                                .quantity(item.getQuantity())
                                .reason(
                                    item.getReason() != null && !item.getReason().isBlank()
                                        ? item.getReason()
                                        : "MANUAL_UNISSUE")
                                .locationFrom("COUNTER_" + shopId)
                                .locationTo("MAIN")
                                .shopId(shopId)
                                .movementDate(LocalDateTime.now())
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();

                        cs.setLiveQuantity(available - item.getQuantity());
                        cs.setInitialQuantity(cs.getInitialQuantity() - item.getQuantity());
                        cs.setQuantity(cs.getLiveQuantity());
                        cs.setUpdatedAt(LocalDateTime.now());

                        return movementRepository
                            .save(mv)
                            .then(counterStockRepository.save(cs))
                            .then(
                                aggregationService.recalculateStock(
                                    item.getProductId(), eventId, "MAIN"))
                            .doOnSuccess(
                                s ->
                                    log.info(
                                        "UNISSUE_ITEM_DONE productId={} qty={} shopId={}",
                                        item.getProductId(),
                                        item.getQuantity(),
                                        shopId));
                      });
            })
        .then(evictCounterStockCache(eventId))
        .doOnSuccess(v -> log.info("UNISSUE_DONE shopId={} eventId={}", shopId, eventId))
        .doOnError(
            ex -> log.error("UNISSUE_FAILED shopId={} reason={}", shopId, ex.getMessage(), ex));
  }

  @Transactional
  public Mono<CounterStock> returnProductsFromCounter(
      StockMovementDTO movement, String sellerUser) {
    log.info(
        "RETURN_PRODUCTS_FROM_COUNTER shopId={} productId={} quantity={}",
        movement.shopId(),
        movement.productId(),
        movement.quantity());

    return counterStockRepository
        .findByProductIdAndShopIdAndEventIdForUpdate(
            movement.productId(), movement.shopId(), movement.eventId())
        .flatMap(
            existingStock -> {
              int currentInitial =
                  existingStock.getInitialQuantity() != null
                      ? existingStock.getInitialQuantity()
                      : 0;
              int currentLive =
                  existingStock.getLiveQuantity() != null ? existingStock.getLiveQuantity() : 0;

              existingStock.setInitialQuantity(Math.max(0, currentInitial - movement.quantity()));
              existingStock.setLiveQuantity(Math.max(0, currentLive - movement.quantity()));
              existingStock.setQuantity(existingStock.getLiveQuantity());
              existingStock.setUpdatedAt(LocalDateTime.now());
              existingStock.setSellerUser(sellerUser);
              return counterStockRepository.save(existingStock);
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  // Fallback: create a new record if none existed
                  CounterStock newStock =
                      CounterStock.builder()
                          .shopId(movement.shopId())
                          .productId(movement.productId())
                          .eventId(movement.eventId())
                          .initialQuantity(-movement.quantity())
                          .liveQuantity(-movement.quantity())
                          .quantity(-movement.quantity())
                          .sellerUser(sellerUser)
                          .createdAt(LocalDateTime.now())
                          .updatedAt(LocalDateTime.now())
                          .saleDate(LocalDateTime.now())
                          .build();
                  return counterStockRepository.save(newStock);
                }));
  }

  @Transactional
  public Mono<Void> decrementStock(
      List<CounterStockDecrementRequest> requests, String username, Long eventId) {
    log.info(
        "DECREMENT_STOCK_REQUEST initiatedBy={} eventId={} totalItems={}",
        username,
        eventId,
        requests.size());

    // concatMap (not flatMap) ensures items are decremented sequentially within a single request,
    // preventing races where two items in the same sale fight over the same counter stock row.
    // Across separate HTTP requests, SELECT FOR UPDATE in the repository provides row-level
    // locking.
    return Flux.fromIterable(requests)
        .concatMap(
            req -> {
              log.debug(
                  "DECREMENT_STOCK_ITEM productId={} shopId={} quantity={}",
                  req.getProductId(),
                  req.getShopId(),
                  req.getQuantity());

              return counterStockRepository
                  .findByProductIdAndShopIdAndEventIdForUpdate(
                      req.getProductId(), req.getShopId(), eventId)
                  .switchIfEmpty(
                      Mono.error(
                          new IllegalStateException(
                              "Counter stock not found for product: "
                                  + req.getProductId()
                                  + " in shop: "
                                  + req.getShopId()
                                  + " and event: "
                                  + eventId)))
                  .flatMap(
                      existingStock -> {
                        int available =
                            existingStock.getLiveQuantity() != null
                                ? existingStock.getLiveQuantity()
                                : 0;
                        if (available < req.getQuantity()) {
                          return Mono.error(
                              new InsufficientStockException(
                                  req.getProductId(),
                                  req.getShopId(),
                                  available,
                                  req.getQuantity()));
                        }
                        existingStock.setLiveQuantity(available - req.getQuantity());
                        existingStock.setQuantity(existingStock.getLiveQuantity());
                        existingStock.setUpdatedAt(LocalDateTime.now());
                        existingStock.setSellerUser(username);

                        return counterStockRepository
                            .save(existingStock)
                            .doOnSuccess(
                                saved ->
                                    log.info(
                                        "DECREMENT_STOCK_SUCCESS id={} productId={} remaining={}",
                                        saved.getId(),
                                        saved.getProductId(),
                                        saved.getLiveQuantity()))
                            .flatMap(
                                saved -> {
                                  StockMovement movement =
                                      StockMovement.builder()
                                          .productId(saved.getProductId())
                                          .eventId(saved.getEventId())
                                          .username(username)
                                          .movementType(StockMovement.MovementType.OUT)
                                          .quantity(req.getQuantity())
                                          .reason("SALE")
                                          .locationFrom("COUNTER_" + saved.getShopId())
                                          .locationTo("CUSTOMER")
                                          .shopId(
                                              saved.getShopId() != null
                                                  ? Long.parseLong(saved.getShopId())
                                                  : null)
                                          .movementDate(LocalDateTime.now())
                                          .createdAt(LocalDateTime.now())
                                          .updatedAt(LocalDateTime.now())
                                          .build();
                                  return movementRepository.save(movement);
                                })
                            .then();
                      });
            })
        .then(evictCounterStockCache(eventId));
  }

  @Transactional
  public Mono<Void> incrementStock(
      List<CounterStockDecrementRequest> requests, String username, Long eventId) {
    log.info(
        "INCREMENT_STOCK_REQUEST initiatedBy={} eventId={} totalItems={}",
        username,
        eventId,
        requests.size());

    return Flux.fromIterable(requests)
        .flatMap(
            req -> {
              log.debug(
                  "INCREMENT_STOCK_ITEM productId={} shopId={} quantity={}",
                  req.getProductId(),
                  req.getShopId(),
                  req.getQuantity());

              return counterStockRepository
                  .findByProductIdAndShopIdAndEventIdForUpdate(
                      req.getProductId(), req.getShopId(), eventId)
                  .flatMap(
                      existingStock -> {
                        int available =
                            existingStock.getLiveQuantity() != null
                                ? existingStock.getLiveQuantity()
                                : 0;
                        existingStock.setLiveQuantity(available + req.getQuantity());
                        existingStock.setQuantity(existingStock.getLiveQuantity());
                        existingStock.setUpdatedAt(LocalDateTime.now());
                        existingStock.setSellerUser(username);

                        return counterStockRepository
                            .save(existingStock)
                            .doOnSuccess(
                                saved ->
                                    log.info(
                                        "INCREMENT_STOCK_SUCCESS id={} productId={} remaining={}",
                                        saved.getId(),
                                        saved.getProductId(),
                                        saved.getLiveQuantity()))
                            .flatMap(
                                saved -> {
                                  StockMovement movement =
                                      StockMovement.builder()
                                          .productId(saved.getProductId())
                                          .eventId(saved.getEventId())
                                          .username(username)
                                          .movementType(StockMovement.MovementType.IN)
                                          .quantity(req.getQuantity())
                                          .reason("SALE_CANCEL")
                                          .locationFrom("CUSTOMER")
                                          .locationTo("COUNTER_" + saved.getShopId())
                                          .shopId(
                                              saved.getShopId() != null
                                                  ? Long.parseLong(saved.getShopId())
                                                  : null)
                                          .movementDate(LocalDateTime.now())
                                          .createdAt(LocalDateTime.now())
                                          .updatedAt(LocalDateTime.now())
                                          .build();
                                  return movementRepository.save(movement);
                                })
                            .thenReturn(existingStock);
                      })
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            CounterStock newStock =
                                CounterStock.builder()
                                    .shopId(req.getShopId())
                                    .productId(req.getProductId())
                                    .eventId(eventId)
                                    .initialQuantity(req.getQuantity())
                                    .liveQuantity(req.getQuantity())
                                    .quantity(req.getQuantity())
                                    .sellerUser(username)
                                    .createdAt(LocalDateTime.now())
                                    .updatedAt(LocalDateTime.now())
                                    .saleDate(LocalDateTime.now())
                                    .build();
                            return counterStockRepository
                                .save(newStock)
                                .doOnSuccess(
                                    saved ->
                                        log.info(
                                            "INCREMENT_STOCK_CREATED id={} productId={} remaining={}",
                                            saved.getId(),
                                            saved.getProductId(),
                                            saved.getLiveQuantity()))
                                .flatMap(
                                    saved -> {
                                      StockMovement movement =
                                          StockMovement.builder()
                                              .productId(saved.getProductId())
                                              .eventId(saved.getEventId())
                                              .username(username)
                                              .movementType(StockMovement.MovementType.IN)
                                              .quantity(req.getQuantity())
                                              .reason("SALE_CANCEL")
                                              .locationFrom("CUSTOMER")
                                              .locationTo("COUNTER_" + saved.getShopId())
                                              .shopId(
                                                  saved.getShopId() != null
                                                      ? Long.parseLong(saved.getShopId())
                                                      : null)
                                              .movementDate(LocalDateTime.now())
                                              .createdAt(LocalDateTime.now())
                                              .updatedAt(LocalDateTime.now())
                                              .build();
                                      return movementRepository.save(movement);
                                    })
                                .thenReturn(newStock);
                          }));
            })
        .then(evictCounterStockCache(eventId));
  }
}
