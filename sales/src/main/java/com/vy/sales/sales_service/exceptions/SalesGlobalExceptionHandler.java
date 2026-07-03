package com.vy.sales.sales_service.exceptions;

import com.vy.sales.sales_service.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Slf4j
public class SalesGlobalExceptionHandler {

  /* ===================== 404 ===================== */
  @ExceptionHandler(ResourceNotFoundException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleNotFound(ResourceNotFoundException ex) {

    return Mono.just(
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiResponse<>(false, null, ex.getMessage())));
  }

  /* ===================== 400 ===================== */
  @ExceptionHandler({
    BadRequestException.class,
    MethodArgumentNotValidException.class,
    ServerWebInputException.class,
    IllegalArgumentException.class,
    IllegalStateException.class,
    NullPointerException.class
  })
  public Mono<ResponseEntity<ApiResponse<Void>>> handleBadRequest(Exception ex) {
    log.error("Bad Request or trapped runtime exception: {}", ex.getMessage(), ex);
    return Mono.just(
        ResponseEntity.badRequest()
            .body(new ApiResponse<>(false, null, "Invalid input or state: " + ex.getMessage())));
  }

  /* ===================== 409 ===================== */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleConflict(
      DataIntegrityViolationException ex) {

    return Mono.just(
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiResponse<>(false, null, "Duplicate or invalid reference")));
  }

  /* ===================== Upstream service error (inventory / billing 4xx/5xx) ===================== */
  /**
   * Propagates the HTTP status and response body from inventory-service or billing-service back to
   * the caller. Without this handler, WebClientResponseException falls through to the generic
   * Exception handler and the meaningful upstream message (e.g. "Insufficient stock for product X")
   * is replaced with an opaque "Bad request or unhandled exception".
   */
  @ExceptionHandler(WebClientResponseException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleUpstreamError(
      WebClientResponseException ex) {
    log.error(
        "Upstream service error: status={} body={}",
        ex.getStatusCode(),
        ex.getResponseBodyAsString());
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    return Mono.just(
        ResponseEntity.status(status != null ? status : HttpStatus.BAD_GATEWAY)
            .body(new ApiResponse<>(false, null, ex.getResponseBodyAsString())));
  }

  /* ===================== 500 to 400 Fallback ===================== */
  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericError(Exception ex) {

    log.error("Unhandled exception trapped as 400 to prevent 500", ex);

    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiResponse<>(false, null, "Bad request or unhandled exception")));
  }
}
