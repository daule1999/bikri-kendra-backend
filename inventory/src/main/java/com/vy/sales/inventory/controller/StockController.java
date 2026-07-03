package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.entity.Stock;
import com.vy.sales.inventory.service.StockService;
import com.vy.sales.inventory.util.AppConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/inventory-svc/stocks")
@RequiredArgsConstructor
@Slf4j
public class StockController {

  private final StockService stockService;

  /* ================= WRITE ================= */

  @PostMapping
  public Mono<ResponseEntity<ApiResponse<Stock>>> create(
      @RequestBody Stock stock,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info(
        "STOCK_CONTROLLER_CREATE productId={} location={} eventId={}",
        stock.getProductId(),
        stock.getLocation(),
        eventId);
    stock.setEventId(eventId); // Guarantee isolation
    return stockService
        .create(stock)
        .map(
            saved ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(saved, "Stock created successfully")));
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Stock>>> update(
      @PathVariable Long id,
      @RequestBody Stock stock,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("STOCK_CONTROLLER_UPDATE id={} eventId={}", id, eventId);
    return stockService
        .update(id, eventId, stock)
        .map(
            updated ->
                ResponseEntity.ok(ApiResponse.success(updated, "Stock updated successfully")));
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Void>>> delete(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("STOCK_CONTROLLER_DELETE id={} eventId={}", id, eventId);
    return stockService
        .delete(id, eventId)
        .thenReturn(ResponseEntity.ok(ApiResponse.success(null, "Stock deleted successfully")));
  }

  /* ================= READ ================= */

  @GetMapping("/{id}")
  public Mono<ResponseEntity<ApiResponse<Stock>>> get(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.debug("STOCK_CONTROLLER_GET id={} eventId={}", id, eventId);
    return stockService
        .getById(id, eventId)
        .map(stock -> ResponseEntity.ok(ApiResponse.success(stock, "Stock fetched successfully")));
  }

  @GetMapping
  public Mono<ResponseEntity<ApiResponse<List<Stock>>>> getAll(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.debug("STOCK_CONTROLLER_GET_ALL eventId={}", eventId);
    return stockService
        .getAll(eventId)
        .collectList()
        .map(list -> ResponseEntity.ok(ApiResponse.success(list, "Stocks fetched successfully")));
  }
}
