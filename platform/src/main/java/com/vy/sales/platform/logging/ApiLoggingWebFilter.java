package com.vy.sales.platform.logging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Logs every API request and response — method, path, status, duration, and (truncated, redacted)
 * bodies — as one structured log event per exchange, tagged with the trace id via MDC and an
 * {@code X-Request-Id} correlation header (honored if the client sends one, generated otherwise,
 * always echoed back on the response).
 *
 * <p>Shipped to Loki via container stdout; search in Grafana with e.g.
 * {@code {container="bikri-backend"} | json | uri=~"/api/sales.*" | status >= 400}.
 *
 * <p>Bodies are captured only for text-like content types (JSON/text/form/XML), truncated at
 * {@code observability.api-logging.max-body-bytes}, and sensitive JSON fields are redacted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5) // wraps everything, including security failures
@EnableConfigurationProperties(ApiLoggingProperties.class)
public class ApiLoggingWebFilter implements WebFilter {

  private static final Logger log = LoggerFactory.getLogger("API");

  private final ApiLoggingProperties props;
  private final Pattern redactPattern;

  public ApiLoggingWebFilter(ApiLoggingProperties props) {
    this.props = props;
    this.redactPattern = buildRedactPattern(props.redactJsonFields());
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (!props.enabled() || isExcluded(path)) {
      return chain.filter(exchange);
    }

    long startNanos = System.nanoTime();
    String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    final String rid = requestId;
    exchange.getResponse().getHeaders().set("X-Request-Id", rid);

    ByteArrayOutputStream reqBody = new ByteArrayOutputStream();
    ByteArrayOutputStream resBody = new ByteArrayOutputStream();

    var request =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            if (!shouldCapture(getHeaders().getContentType())) {
              return super.getBody();
            }
            return super.getBody().doOnNext(buf -> capture(reqBody, buf));
          }
        };

    var response =
        new ServerHttpResponseDecorator(exchange.getResponse()) {
          @Override
          public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            if (!shouldCapture(getHeaders().getContentType())) {
              return super.writeWith(body);
            }
            return super.writeWith(Flux.from(body).doOnNext(buf -> capture(resBody, buf)));
          }

          @Override
          public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(
                Flux.from(body).map(inner -> Flux.from(inner).doOnNext(buf -> capture(resBody, buf))));
          }
        };

    ServerWebExchange mutated = exchange.mutate().request(request).response(response).build();

    return chain
        .filter(mutated)
        .doOnError(
            e ->
                logExchange(
                    mutated, rid, startNanos, reqBody, resBody, e.getClass().getSimpleName() + ": " + e.getMessage()))
        .doOnSuccess(v -> logExchange(mutated, rid, startNanos, reqBody, resBody, null));
  }

  private void logExchange(
      ServerWebExchange exchange,
      String requestId,
      long startNanos,
      ByteArrayOutputStream reqBody,
      ByteArrayOutputStream resBody,
      String error) {
    try {
      var req = exchange.getRequest();
      Integer status =
          exchange.getResponse().getStatusCode() != null
              ? exchange.getResponse().getStatusCode().value()
              : null;
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
      String query = req.getURI().getRawQuery();

      log.info(
          "{} {} -> {} ({} ms)",
          req.getMethod(),
          req.getPath().value(),
          status,
          durationMs,
          kv("type", "api"),
          kv("requestId", requestId),
          kv("method", String.valueOf(req.getMethod())),
          kv("uri", req.getPath().value()),
          kv("query", query == null ? "" : query),
          kv("status", status),
          kv("durationMs", durationMs),
          kv("clientIp", clientIp(exchange)),
          kv("requestBody", bodyText(reqBody)),
          kv("responseBody", bodyText(resBody)),
          kv("error", error == null ? "" : error));
    } catch (Exception e) {
      log.warn("API logging failed for {}: {}", requestId, e.toString());
    }
  }

  /** Copy readable bytes without consuming the buffer (read position is restored). */
  private void capture(ByteArrayOutputStream sink, DataBuffer buffer) {
    if (!props.logBodies()) return;
    int max = props.maxBodyBytes();
    if (sink.size() >= max) return;
    int count = Math.min(buffer.readableByteCount(), max - sink.size());
    if (count <= 0) return;
    byte[] bytes = new byte[count];
    int originalPos = buffer.readPosition();
    buffer.read(bytes, 0, count);
    buffer.readPosition(originalPos);
    sink.write(bytes, 0, count);
  }

  private String bodyText(ByteArrayOutputStream sink) {
    if (sink.size() == 0) return "";
    String text = sink.toString(StandardCharsets.UTF_8);
    text = redactPattern.matcher(text).replaceAll("$1\"***\"");
    return sink.size() >= props.maxBodyBytes() ? text + "…[truncated]" : text;
  }

  private boolean shouldCapture(MediaType contentType) {
    if (!props.logBodies()) return false;
    if (contentType == null) return true; // most error responses omit it; bodies stay tiny anyway
    return MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
        || MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)
        || MediaType.APPLICATION_XML.isCompatibleWith(contentType)
        || (contentType.getType() != null && contentType.getType().equalsIgnoreCase("text"));
  }

  private boolean isExcluded(String path) {
    for (String prefix : props.excludePathPrefixes()) {
      if (path.startsWith(prefix)) return true;
    }
    return false;
  }

  private String clientIp(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
    var addr = exchange.getRequest().getRemoteAddress();
    return addr != null ? addr.getAddress().getHostAddress() : "";
  }

  /** Matches {@code "field" : "value"} for each sensitive field name (case-insensitive). */
  private static Pattern buildRedactPattern(List<String> fields) {
    String names = String.join("|", fields.stream().map(Pattern::quote).toList());
    return Pattern.compile("(?i)(\"(?:" + names + ")\"\\s*:\\s*)\"[^\"]*\"");
  }
}
