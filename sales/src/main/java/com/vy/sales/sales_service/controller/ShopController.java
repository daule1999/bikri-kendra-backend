package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.dto.RegisterShopRequest;
import com.vy.sales.sales_service.dto.ShopHistoryResponse;
import com.vy.sales.sales_service.dto.ShopResponse;
import com.vy.sales.sales_service.dto.UpdateShopRequest;
import com.vy.sales.sales_service.service.ShopService;
import com.vy.sales.sales_service.util.AppConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/sales-svc/shops")
@RequiredArgsConstructor
public class ShopController {

  private final ShopService shopService;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<ShopResponse> registerShop(
      @Validated @RequestBody RegisterShopRequest request,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    return shopService
        .registerShop(request, eventId)
        .doOnSuccess(shop -> log.info("Shop registered successfully: {}", shop))
        .doOnError(error -> log.error("Error registering shop: {}", error.getMessage()));
  }

  @PutMapping("/{id}")
  @ResponseStatus(HttpStatus.OK)
  public Mono<ShopResponse> updateShop(
      @PathVariable Long id,
      @Validated @RequestBody UpdateShopRequest request,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Received request to update shop id={} eventId={}", id, eventId);
    return shopService
        .updateShop(id, request, eventId)
        .doOnSuccess(shop -> log.info("Shop updated successfully: {}", shop))
        .doOnError(error -> log.error("Error updating shop id={} : {}", id, error.getMessage()));
  }

  @GetMapping
  public Flux<ShopResponse> getAllShops(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Received request to fetch all shops for eventId={}", eventId);
    return shopService.getAllShops(eventId);
  }

  // GET SHOP BY ID
  @GetMapping("/{id}")
  public Mono<ShopResponse> getShopById(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Received request to fetch shop id={} eventId={}", id, eventId);
    return shopService
        .getShopById(id, eventId)
        .doOnError(err -> log.error("Error fetching shop id={} : {}", id, err.getMessage()));
  }

  // GET SHOPS BY NAME
  @GetMapping("/name/{shopName}")
  public Flux<ShopResponse> getShopsByName(
      @PathVariable String shopName,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Received request to fetch shops by name={} eventId={}", shopName, eventId);
    return shopService
        .getShopsByName(shopName, eventId)
        .doOnError(
            err ->
                log.error(
                    "Error fetching shops by name={} eventId={} : {}",
                    shopName,
                    eventId,
                    err.getMessage()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteShop(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestHeader("Authorization") String authHeader) {
    log.info("Received request to soft delete shop id={} eventId={}", id, eventId);
    return shopService
        .deleteShop(id, eventId, authHeader)
        .doOnSuccess(v -> log.info("Shop soft deleted successfully id={}", id))
        .doOnError(err -> log.error("Error soft deleting shop id={} : {}", id, err.getMessage()));
  }

  @PostMapping("/{shopId}/unissue")
  @ResponseStatus(HttpStatus.OK)
  public Mono<Void> unissueStocks(
      @PathVariable Long shopId,
      @RequestBody List<InventoryClient.UnissueItemRequest> items,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestHeader("Authorization") String authHeader,
      @RequestHeader(value = "X-Roles", required = false) String rolesHeader) {
    log.info(
        "Received unissue request shopId={} eventId={} items={}", shopId, eventId, items.size());
    return shopService.unissueStocks(shopId, eventId, items, authHeader, rolesHeader);
  }

  @GetMapping("/{id}/history")
  public Mono<ShopHistoryResponse> getShopHistory(
      @PathVariable Long id,
      @RequestHeader(value = "Authorization", required = true) String authHeader) {
    log.info("Received request to fetch history for shop id={}", id);
    return shopService
        .getShopHistory(id, authHeader)
        .doOnError(
            err -> log.error("Error fetching history for shop id={} : {}", id, err.getMessage()));
  }
}
