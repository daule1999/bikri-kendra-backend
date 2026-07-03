package com.vy.sales.sales_service.filter;

import com.vy.sales.sales_service.model.IdempotencyKey;
import com.vy.sales.sales_service.repository.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive WebFilter that provides HTTP-layer idempotency for mutating requests.
 *
 * <p>When a request includes an {@code X-Idempotency-Key} header:
 *
 * <ol>
 *   <li>If no record exists: insert IN_FLIGHT, forward the request, update to COMPLETED/FAILED.
 *   <li>If IN_FLIGHT: return HTTP 409 Conflict (another identical request is already processing).
 *   <li>If COMPLETED: replay the cached response (status + body) without hitting the handler.
 *   <li>If FAILED: delete the stale record and allow a fresh attempt.
 * </ol>
 *
 * <p>Only applies to {@code POST} and {@code PUT} requests to {@code /api/sales-svc/retail/**}.
 */
@Component
@Order(-10)
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter implements WebFilter {

  private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

  private final IdempotencyRepository idempotencyRepository;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    HttpMethod method = exchange.getRequest().getMethod();
    String path = exchange.getRequest().getPath().value();

    // Only guard mutating requests on the retail sales path
    if ((method != HttpMethod.POST && method != HttpMethod.PUT) || !path.contains("/retail")) {
      return chain.filter(exchange);
    }

    String idempKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
    if (idempKey == null || idempKey.isBlank()) {
      return chain.filter(exchange);
    }

    return idempotencyRepository
        .findByIdempotencyKey(idempKey)
        .flatMap(
            existing -> {
              switch (existing.getStatus()) {
                case "IN_FLIGHT":
                  log.warn("Idempotency conflict key={} path={}", idempKey, path);
                  exchange.getResponse().setStatusCode(HttpStatus.CONFLICT);
                  exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                  byte[] conflictBody =
                      ("{\"message\":\"A request with the same idempotency key is already"
                              + " in flight. Please wait and retry.\",\"errorCode\":\"IDEMPOTENCY_CONFLICT\"}")
                          .getBytes(StandardCharsets.UTF_8);
                  DataBuffer conflictBuf =
                      exchange.getResponse().bufferFactory().wrap(conflictBody);
                  return exchange.getResponse().writeWith(Mono.just(conflictBuf));

                case "COMPLETED":
                  log.info(
                      "Idempotency replay key={} status={}",
                      idempKey,
                      existing.getResponseStatus());
                  exchange
                      .getResponse()
                      .setStatusCode(
                          HttpStatus.resolve(
                              existing.getResponseStatus() != null
                                  ? existing.getResponseStatus()
                                  : 200));
                  exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                  byte[] cachedBody =
                      (existing.getResponseBody() != null ? existing.getResponseBody() : "{}")
                          .getBytes(StandardCharsets.UTF_8);
                  DataBuffer cachedBuf = exchange.getResponse().bufferFactory().wrap(cachedBody);
                  return exchange.getResponse().writeWith(Mono.just(cachedBuf));

                case "FAILED":
                default:
                  // Delete stale entry and allow retry
                  return idempotencyRepository
                      .deleteByIdempotencyKey(idempKey)
                      .then(proceedWithCapture(exchange, chain, idempKey, path));
              }
            })
        .switchIfEmpty(
            // No existing record — first attempt
            idempotencyRepository
                .save(
                    IdempotencyKey.builder()
                        .idempotencyKey(idempKey)
                        .requestPath(path)
                        .status("IN_FLIGHT")
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusDays(1))
                        .build())
                .then(Mono.defer(() -> proceedWithCapture(exchange, chain, idempKey, path))));
  }

  private Mono<Void> proceedWithCapture(
      ServerWebExchange exchange, WebFilterChain chain, String idempKey, String path) {

    StringBuilder capturedBody = new StringBuilder();
    int[] capturedStatus = {200};

    ServerHttpResponseDecorator decoratedResponse =
        new ServerHttpResponseDecorator(exchange.getResponse()) {
          @Override
          public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
            capturedStatus[0] =
                getDelegate().getStatusCode() != null ? getDelegate().getStatusCode().value() : 200;
            return DataBufferUtils.join(Flux.from(body))
                .flatMap(
                    joined -> {
                      byte[] bytes = new byte[joined.readableByteCount()];
                      joined.read(bytes);
                      DataBufferUtils.release(joined);
                      capturedBody.append(new String(bytes, StandardCharsets.UTF_8));
                      DataBuffer newBuf = getDelegate().bufferFactory().wrap(bytes);
                      return getDelegate().writeWith(Mono.just(newBuf));
                    });
          }
        };

    return chain
        .filter(exchange.mutate().response(decoratedResponse).build())
        .then(
            Mono.defer(
                () ->
                    idempotencyRepository
                        .findByIdempotencyKey(idempKey)
                        .flatMap(
                            rec -> {
                              rec.setResponseStatus(capturedStatus[0]);
                              rec.setResponseBody(capturedBody.toString());
                              // Only cache 2xx as COMPLETED. 4xx errors (e.g. INSUFFICIENT_STOCK,
                              // validation failures) must be marked FAILED so the client can
                              // retry with the same key after fixing the request. Marking a 4xx
                              // as COMPLETED would permanently replay the error on every retry.
                              rec.setStatus(
                                  (capturedStatus[0] >= 200 && capturedStatus[0] < 300)
                                      ? "COMPLETED"
                                      : "FAILED");
                              return idempotencyRepository.save(rec);
                            })
                        .then()))
        .onErrorResume(
            err ->
                idempotencyRepository
                    .findByIdempotencyKey(idempKey)
                    .flatMap(
                        rec -> {
                          rec.setStatus("FAILED");
                          rec.setResponseBody(err.getMessage());
                          return idempotencyRepository.save(rec);
                        })
                    .then(Mono.error(err)));
  }
}
