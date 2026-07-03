package com.vy.sales.inventory.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vy.sales.inventory.dto.ApiResponse;
import com.vy.sales.inventory.util.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(1) // Executed very early in the chain
@Slf4j
public class EventHeaderFilter implements WebFilter {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getURI().getPath();

    log.debug("EventHeaderFilter checking request path: {}", path);

    // MONOLITH: this filter used to run inside inventory-service only, so it enforced
    // X-Event-Id on everything it saw. In the single app it sees EVERY request (login,
    // users, auth…), so it must be scoped to inventory routes explicitly.
    if (!path.startsWith("/api/inventory")) {
      return chain.filter(exchange);
    }

    // Bypass check for products and category routes
    if (path.contains("/products") || path.contains("/categories")) {
      return chain.filter(exchange);
    }

    String eventId = request.getHeaders().getFirst(AppConstants.X_EVENT_ID);
    if (eventId == null || eventId.trim().isEmpty()) {
      log.warn("Missing X-Event-Id header for request path: {}", path);
      return writeErrorResponse(
          exchange.getResponse(),
          HttpStatus.BAD_REQUEST,
          "Missing or empty X-Event-Id request header");
    }

    try {
      Long.parseLong(eventId);
    } catch (NumberFormatException e) {
      log.warn("Invalid X-Event-Id header value: {} for request path: {}", eventId, path);
      return writeErrorResponse(
          exchange.getResponse(),
          HttpStatus.BAD_REQUEST,
          "Invalid X-Event-Id header value. Must be a numeric ID.");
    }

    return chain.filter(exchange);
  }

  private Mono<Void> writeErrorResponse(
      ServerHttpResponse response, HttpStatus status, String errorMessage) {
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    DataBufferFactory bufferFactory = response.bufferFactory();
    try {
      ApiResponse<Void> apiResponse = new ApiResponse<>(false, null, errorMessage);
      byte[] responseBytes = objectMapper.writeValueAsBytes(apiResponse);
      DataBuffer buffer = bufferFactory.wrap(responseBytes);
      return response.writeWith(Mono.just(buffer));
    } catch (Exception e) {
      log.error("Failed to write error response JSON", e);
      return Mono.error(e);
    }
  }
}
