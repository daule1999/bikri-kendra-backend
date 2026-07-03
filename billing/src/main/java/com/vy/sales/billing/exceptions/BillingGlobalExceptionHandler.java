package com.vy.sales.billing.exceptions;

import com.vy.sales.billing.dto.ApiResponse;
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
public class BillingGlobalExceptionHandler {

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

  /* ===================== 500 to 400 Fallback ===================== */
  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericError(Exception ex) {

    log.error("Unhandled exception trapped as 400 to prevent 500", ex);

    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiResponse<>(false, null, "Bad request or unhandled exception")));
  }
}
