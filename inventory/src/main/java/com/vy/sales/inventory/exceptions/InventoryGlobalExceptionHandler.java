package com.vy.sales.inventory.exceptions;

import com.vy.sales.inventory.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Slf4j
public class InventoryGlobalExceptionHandler {

  /* ===================== 422 Insufficient Stock ===================== */
  @ExceptionHandler(InsufficientStockException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleInsufficientStock(
      InsufficientStockException ex) {
    log.warn(
        "INSUFFICIENT_STOCK productId={} shopId={} available={} requested={}",
        ex.getProductId(),
        ex.getShopId(),
        ex.getAvailable(),
        ex.getRequested());
    return Mono.just(
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiResponse.error(ex.getMessage(), "INSUFFICIENT_STOCK")));
  }

  /* ===================== 404 ===================== */
  @ExceptionHandler(ProductNotFoundException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleProductNotFound(
      ProductNotFoundException ex) {

    return Mono.just(
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiResponse<>(false, null, ex.getMessage())));
  }

  /* ===================== 400 ===================== */
  @ExceptionHandler({
    BadRequestException.class,
    MethodArgumentNotValidException.class,
    ServerWebInputException.class
  })
  public Mono<ResponseEntity<ApiResponse<Void>>> handleBadRequest(Exception ex) {

    return Mono.just(
        ResponseEntity.badRequest().body(new ApiResponse<>(false, null, ex.getMessage())));
  }

  /* ===================== 409 ===================== */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleConflict(
      DataIntegrityViolationException ex) {

    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiResponse<>(false, null, "Duplicate or invalid reference")));
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleResponseStatus(
      org.springframework.web.server.ResponseStatusException ex) {

    return Mono.just(
        ResponseEntity.status(ex.getStatusCode())
            .body(new ApiResponse<>(false, null, ex.getReason())));
  }

  /* ===================== 500 to 400 Fallback ===================== */
  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericError(Exception ex) {

    log.error("Unhandled exception trapped as 400 to prevent 500", ex);

    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiResponse<>(false, null, "Bad request or unhandled exception")));
  }

  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  public Mono<ResponseEntity<ApiResponse<Void>>> handleCommonRuntimeErrors(RuntimeException ex) {

    log.error("Runtime exception trapped as 400", ex);

    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiResponse<>(false, null, "Invalid input or state: " + ex.getMessage())));
  }
}
