package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.dto.CounterStockDecrementRequest;
import com.vy.sales.inventory.dto.UnissueItemRequest;
import com.vy.sales.inventory.entity.CounterStock;
import com.vy.sales.inventory.service.CounterStockService;
import com.vy.sales.inventory.util.AppConstants;
import com.vy.sales.platform.security.JwtUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/inventory-svc/sales")
@RequiredArgsConstructor
@Slf4j
public class CounterStockController {

  private final CounterStockService counterStockService;
  private final JwtUtil jwtUtil;

  private Mono<String> extractUsername(String authHeader) {
    return Mono.defer(
            () -> {
              if (authHeader == null
                  || !authHeader.startsWith("Bearer ")
                  || authHeader.length() <= 7) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Invalid Authorization token format. Must start with 'Bearer ' followed by token."));
              }
              try {
                String token = authHeader.substring(7);
                String username = jwtUtil.extractUsername(token);
                if (username == null || username.trim().isEmpty()) {
                  return Mono.error(
                      new IllegalArgumentException(
                          "Invalid token: username cannot be null or empty"));
                }
                return Mono.just(username);
              } catch (Exception e) {
                return Mono.error(
                    new IllegalArgumentException(
                        "Failed to extract username from token: " + e.getMessage()));
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel());
  }

  @PostMapping
  public Mono<ResponseEntity<ApiResponse<CounterStock>>> createSale(
      @RequestBody CounterStock counterStock,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            sellerUserId -> {
              counterStock.setSellerUser(sellerUserId);
              if (counterStock.getEventId() == null) {
                counterStock.setEventId(eventId);
              }
              return counterStockService.create(counterStock);
            })
        .map(saved -> ResponseEntity.ok(new ApiResponse<CounterStock>(true, saved, null)))
        .onErrorResume(
            ex -> {
              String errorMsg =
                  (ex instanceof DataIntegrityViolationException)
                      ? "Invalid reference: Product or user does not exist."
                      : ex.getMessage();
              log.error("Counter stock creation failed: {}", errorMsg, ex);
              return Mono.just(
                  ResponseEntity.badRequest()
                      .body(new ApiResponse<CounterStock>(false, null, errorMsg)));
            });
  }

  @PostMapping("/bulk")
  public Mono<ResponseEntity<ApiResponse<List<CounterStock>>>> createBulkCounterStocks(
      @RequestBody List<CounterStock> counterStockList,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            sellerUserId -> {
              log.info(
                  "BULK_COUNTER_STOCK_CREATE_REQUEST initiatedBy={} totalItems={} eventId={}",
                  sellerUserId,
                  counterStockList.size(),
                  eventId);

              // Assign sellerUser and eventId to each item
              counterStockList.forEach(
                  item -> {
                    item.setSellerUser(sellerUserId);
                    if (eventId != null) {
                      item.setEventId(eventId);
                    }
                  });

              return counterStockService
                  .createBulk(counterStockList)
                  .collectList()
                  .doOnNext(
                      saved ->
                          log.info(
                              "BULK_COUNTER_STOCK_CREATE_SUCCESS initiatedBy={} saved={}",
                              sellerUserId,
                              saved.size()))
                  .doOnError(
                      ex ->
                          log.error(
                              "BULK_COUNTER_STOCK_CREATE_FAILED initiatedBy={} reason={}",
                              sellerUserId,
                              ex.getMessage(),
                              ex));
            })
        .map(
            savedItems ->
                ResponseEntity.ok(new ApiResponse<List<CounterStock>>(true, savedItems, null)))
        .onErrorResume(
            ex ->
                Mono.just(
                    ResponseEntity.badRequest()
                        .body(new ApiResponse<List<CounterStock>>(false, null, ex.getMessage()))));
  }

  @GetMapping
  public Mono<ResponseEntity<ApiResponse<List<CounterStock>>>> getAllSales(
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            user -> {
              log.info("COUNTER_STOCK_FETCH_ALL_REQUEST initiatedBy={} eventId={}", user, eventId);
              return counterStockService
                  .getAllCounterStocks(eventId)
                  .collectList()
                  .map(
                      list -> {
                        log.info(
                            "SALES_FETCH_ALL_SUCCESS initiatedBy={} records={}", user, list.size());
                        return ResponseEntity.ok(
                            new ApiResponse<List<CounterStock>>(true, list, null));
                      })
                  .onErrorResume(
                      ex -> {
                        log.error(
                            "SALES_FETCH_ALL_FAILED initiatedBy={} reason={}",
                            user,
                            ex.getMessage(),
                            ex);
                        return Mono.just(
                            ResponseEntity.badRequest()
                                .body(
                                    new ApiResponse<List<CounterStock>>(
                                        false, null, ex.getMessage())));
                      });
            })
        .onErrorResume(
            ex ->
                Mono.just(
                    ResponseEntity.badRequest()
                        .body(new ApiResponse<List<CounterStock>>(false, null, ex.getMessage()))));
  }

  @PutMapping("/decrement")
  public Mono<ResponseEntity<ApiResponse<Void>>> decrementCounterStock(
      @RequestBody List<CounterStockDecrementRequest> requests,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            username -> {
              log.info(
                  "CONTROLLER_COUNTER_STOCK_DECREMENT_REQUEST initiatedBy={} eventId={} items={}",
                  username,
                  eventId,
                  requests.size());

              return counterStockService
                  .decrementStock(requests, username, eventId)
                  .then(Mono.just(ResponseEntity.ok(new ApiResponse<Void>(true, null, null))));
            })
        .onErrorResume(
            ex -> {
              log.error("Counter stock decrement failed: {}", ex.getMessage(), ex);
              return Mono.just(
                  ResponseEntity.badRequest()
                      .body(new ApiResponse<Void>(false, null, ex.getMessage())));
            });
  }

  @PutMapping("/increment")
  public Mono<ResponseEntity<ApiResponse<Void>>> incrementCounterStock(
      @RequestBody List<CounterStockDecrementRequest> requests,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            username -> {
              log.info(
                  "CONTROLLER_COUNTER_STOCK_INCREMENT_REQUEST initiatedBy={} eventId={} items={}",
                  username,
                  eventId,
                  requests.size());

              return counterStockService
                  .incrementStock(requests, username, eventId)
                  .then(Mono.just(ResponseEntity.ok(new ApiResponse<Void>(true, null, null))));
            })
        .onErrorResume(
            ex -> {
              log.error("Counter stock increment failed: {}", ex.getMessage(), ex);
              return Mono.just(
                  ResponseEntity.badRequest()
                      .body(new ApiResponse<Void>(false, null, ex.getMessage())));
            });
  }

  /** Fix 3 — Called by sales-service on shop closure to return all remaining counter stocks. */
  @PostMapping("/shop/{shopId}/return")
  public Mono<ResponseEntity<ApiResponse<Void>>> returnShopStocksToInventory(
      @PathVariable Long shopId,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            username -> {
              log.info(
                  "CONTROLLER_SHOP_CLOSURE_RETURN shopId={} eventId={} initiatedBy={}",
                  shopId,
                  eventId,
                  username);
              return counterStockService.returnShopStocksToInventory(shopId, eventId, username);
            })
        .then(
            Mono.just(
                ResponseEntity.ok(
                    new ApiResponse<Void>(true, null, "Stock returned to inventory"))))
        .onErrorResume(
            ex -> {
              log.error(
                  "CONTROLLER_SHOP_CLOSURE_RETURN_FAILED shopId={} reason={}",
                  shopId,
                  ex.getMessage(),
                  ex);
              return Mono.just(
                  ResponseEntity.badRequest()
                      .body(new ApiResponse<Void>(false, null, ex.getMessage())));
            });
  }

  /**
   * Feature 4 — Manual unissue: Admin/Supervisor returns specific product quantities to main
   * inventory.
   */
  @PostMapping("/shop/{shopId}/unissue")
  public Mono<ResponseEntity<ApiResponse<Void>>> unissueStocks(
      @PathVariable Long shopId,
      @RequestBody List<UnissueItemRequest> items,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return extractUsername(authHeader)
        .flatMap(
            username -> {
              log.info(
                  "CONTROLLER_UNISSUE shopId={} eventId={} items={} initiatedBy={}",
                  shopId,
                  eventId,
                  items.size(),
                  username);
              return counterStockService.unissueStocks(shopId, eventId, items, username);
            })
        .then(
            Mono.just(
                ResponseEntity.ok(
                    new ApiResponse<Void>(true, null, "Stock unissued successfully"))))
        .onErrorResume(
            ex -> {
              log.error(
                  "CONTROLLER_UNISSUE_FAILED shopId={} reason={}", shopId, ex.getMessage(), ex);
              return Mono.just(
                  ResponseEntity.badRequest()
                      .body(new ApiResponse<Void>(false, null, ex.getMessage())));
            });
  }
}
