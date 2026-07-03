package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.dto.StockMovementDTO;
import com.vy.sales.inventory.entity.StockMovement;
import com.vy.sales.inventory.exceptions.BadRequestException;
import com.vy.sales.inventory.repository.ProductRepository;
import com.vy.sales.inventory.repository.StockMovementRepository;
import com.vy.sales.inventory.service.CounterStockService;
import com.vy.sales.inventory.service.StockAggregationService;
import com.vy.sales.inventory.util.AppConstants;
import com.vy.sales.platform.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/inventory-svc/stock-movements")
@RequiredArgsConstructor
@Slf4j
public class StockMovementController {

  private final StockMovementRepository movementRepository;
  private final ProductRepository productRepository;
  private final StockAggregationService aggregationService;
  private final CounterStockService counterStockService;
  private final JwtUtil jwtUtil;

  @GetMapping("/shop/{shopId}")
  public Flux<StockMovement> getMovementsByShop(@PathVariable Long shopId) {
    log.info("Fetching stock movements for shopId={}", shopId);
    return movementRepository.findByShopId(shopId);
  }

  @PostMapping
  public Mono<ResponseEntity<ApiResponse<StockMovement>>> createMovement(
      @RequestBody StockMovementDTO request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long headerEventId) {

    log.info(
        "Manual stock movement productId={}, type={}, qty={}, from={}, to={}",
        request.productId(),
        request.movementType(),
        request.quantity(),
        request.locationFrom() != null ? request.locationFrom() : "MAIN",
        request.locationTo() != null ? request.locationTo() : "COUNTER");

    return Mono.fromCallable(
            () -> {
              try {
                return extractUsername(authHeader);
              } catch (BadRequestException e) {
                throw new RuntimeException(e);
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            username ->
                productRepository
                    .existsById(request.productId())
                    .flatMap(
                        exists -> {
                          if (!exists) {
                            log.warn(
                                "STOCK_MOVEMENT_REJECTED productId={} not found",
                                request.productId());
                            return Mono.error(
                                new BadRequestException(
                                    "Product not found: " + request.productId()));
                          }
                          return doCreateMovement(request, username, headerEventId);
                        }))
        .map(
            saved ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse<>(true, saved, null)))
        .doOnError(ex -> log.error("STOCK_MOVEMENT_CREATE_FAILED reason={}", ex.getMessage(), ex));
  }

  private Mono<StockMovement> doCreateMovement(
      StockMovementDTO request, String username, Long headerEventId) {
    return Mono.defer(
        () -> {
          Long eventId = headerEventId != null ? headerEventId : request.eventId();

          // Determine location_from and location_to based on reason
          String locationFrom = request.locationFrom() != null ? request.locationFrom() : "MAIN";
          String locationTo = request.locationTo() != null ? request.locationTo() : "MAIN";

          // Handle "Return From Counter" - reverse the flow
          if (request.movementType().equals(StockMovement.MovementType.TRANSFER)
              && "Return From Counter".equals(request.reason())) {
            final String finalLocationFrom = "COUNTER_" + request.shopId();
            final String finalLocationTo = "MAIN";

            log.info(
                "RETURN_FROM_COUNTER productId={} shop={} quantity={}",
                request.productId(),
                request.shopId(),
                request.quantity());

            return counterStockService
                .returnProductsFromCounter(request, username)
                .flatMap(
                    cs -> {
                      StockMovement movement =
                          StockMovement.builder()
                              .productId(request.productId())
                              .eventId(eventId)
                              .username(username)
                              .movementType(request.movementType())
                              .quantity(request.quantity())
                              .reason(request.reason())
                              .locationFrom(finalLocationFrom)
                              .locationTo(finalLocationTo)
                              .shopId(
                                  request.shopId() != null
                                      ? Long.parseLong(request.shopId())
                                      : null)
                              .build();
                      return movementRepository.save(movement);
                    })
                .flatMap(
                    mv ->
                        aggregationService
                            .recalculateStock(mv.getProductId(), mv.getEventId(), finalLocationTo)
                            .thenReturn(mv));
          }

          StockMovement movement =
              StockMovement.builder()
                  .productId(request.productId())
                  .eventId(eventId)
                  .username(username)
                  .movementType(request.movementType())
                  .quantity(request.quantity())
                  .reason(request.reason())
                  .locationFrom(locationFrom)
                  .locationTo(locationTo)
                  .shopId(request.shopId() != null ? Long.parseLong(request.shopId()) : null)
                  .build();

          return movementRepository
              .save(movement)
              .flatMap(
                  mv ->
                      aggregationService
                          .recalculateStock(mv.getProductId(), mv.getEventId(), locationTo)
                          .thenReturn(mv));
        });
  }

  private String extractUsername(String authHeader) throws BadRequestException {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new BadRequestException("Missing or invalid Authorization header");
    }
    return jwtUtil.extractUsername(authHeader.substring(7));
  }
}
