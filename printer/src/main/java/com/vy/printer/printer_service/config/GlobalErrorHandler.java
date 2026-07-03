package com.vy.printer.printer_service.config;

import com.vy.printer.printer_service.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalErrorHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> badRequest(IllegalArgumentException ex) {
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiResponse<>(false, null, ex.getMessage())));
  }

  @ExceptionHandler(WebExchangeBindException.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> validation(WebExchangeBindException ex) {
    String msg =
        ex.getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("validation error");
    return Mono.just(
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>(false, null, msg)));
  }

  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ApiResponse<Void>>> generic(Exception ex) {
    log.error("Unhandled error", ex);
    return Mono.just(
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiResponse<>(false, null, "internal error")));
  }
}
