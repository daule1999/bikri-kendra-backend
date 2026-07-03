package com.vy.sales.sales_service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.config.InventoryServiceProperties;
import com.vy.sales.sales_service.util.AppConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryClient {

  // Reads from the cache keys written by inventory-service.
  // Entity JSON → DTO via FAIL_ON_UNKNOWN_PROPERTIES=false.
  private static final String PRODUCTS_CACHE_KEY = "products:all";
  private static final String CATEGORIES_CACHE_KEY = "categories:all";
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final WebClient.Builder webClientBuilder;
  private final InventoryServiceProperties properties;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<Void> addStock(
      Long productId,
      Long eventId,
      Integer quantity,
      String reason,
      String shopId,
      String authHeader) {
    log.info(
        "Request to add stock for productId={} eventId={} quantity={} reason={}",
        productId,
        eventId,
        quantity,
        reason);

    StockMovementRequest request =
        StockMovementRequest.builder()
            .productId(productId)
            .movementType("IN")
            .quantity(quantity)
            .reason(reason)
            .shopId(shopId)
            .build();

    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/stock-movements")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(request)
        .retrieve()
        .bodyToMono(Void.class);
  }

  /**
   * Fetches all products. Checks Redis cache (products:all) written by inventory-service before
   * making the HTTP call. The cache stores List&lt;Product&gt; entity JSON; ProductDTO is a subset
   * so FAIL_ON_UNKNOWN_PROPERTIES=false handles extra fields. Read-only consumer — does not write
   * to Redis.
   */
  public Mono<InventoryProductsResponse> getAllProducts(Long eventId, String authHeader) {
    return redisTemplate
        .opsForValue()
        .get(PRODUCTS_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn(
                  "INVENTORY_CLIENT_CACHE_GET_ERROR eventId={}, falling back to HTTP", eventId, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                // Product entity JSON → List<ProductDTO> (extra fields ignored)
                java.util.List<ProductDTO> products =
                    CACHE_MAPPER.readValue(
                        json, new TypeReference<java.util.List<ProductDTO>>() {});
                log.debug(
                    "INVENTORY_CLIENT_CACHE_HIT eventId={} count={}", eventId, products.size());
                return Mono.just(new InventoryProductsResponse(true, products, ""));
              } catch (Exception e) {
                log.warn(
                    "INVENTORY_CLIENT_CACHE_DESERIALIZE_ERROR eventId={}, falling back to HTTP",
                    eventId,
                    e);
                return Mono.<InventoryProductsResponse>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.info("Fetching products from inventory service for eventId={}", eventId);
                  return webClientBuilder
                      .build()
                      .get()
                      .uri(properties.getBaseUrl() + "/api/inventory-svc/products")
                      .header("Authorization", authHeader)
                      .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
                      .retrieve()
                      .bodyToMono(InventoryProductsResponse.class);
                }));
  }

  public Mono<InventorySalesResponse> getAllIssues(Long eventId, String authHeader) {
    log.info("Fetching issued items from inventory service for eventId={}", eventId);
    return webClientBuilder
        .build()
        .get()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/sales")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .retrieve()
        .bodyToMono(InventorySalesResponse.class);
  }

  public Mono<Void> decrementCounterStock(
      java.util.List<CounterStockDecrementRequest> requests, Long eventId, String authHeader) {
    log.info("Request to decrement counter stock eventId={} items={}", eventId, requests.size());

    return webClientBuilder
        .build()
        .put()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/sales/decrement")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(requests)
        .retrieve()
        .bodyToMono(Void.class);
  }

  public Mono<Void> incrementCounterStock(
      java.util.List<CounterStockDecrementRequest> requests, Long eventId, String authHeader) {
    log.info("Request to increment counter stock eventId={} items={}", eventId, requests.size());

    return webClientBuilder
        .build()
        .put()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/sales/increment")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(requests)
        .retrieve()
        .bodyToMono(Void.class);
  }

  /**
   * Fetches all categories. Reads Redis {@code categories:all} (written by inventory-service
   * CategoryService) before making the HTTP call. Read-only — does not write to Redis.
   */
  public Mono<java.util.List<CategoryDTO>> getAllCategories(String authHeader) {
    return redisTemplate
        .opsForValue()
        .get(CATEGORIES_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("INVENTORY_CLIENT_CATEGORIES_CACHE_GET_ERROR, falling back to HTTP", e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                java.util.List<CategoryDTO> list =
                    CACHE_MAPPER.readValue(
                        json, new TypeReference<java.util.List<CategoryDTO>>() {});
                log.debug("INVENTORY_CLIENT_CATEGORIES_CACHE_HIT count={}", list.size());
                return Mono.just(list);
              } catch (Exception e) {
                log.warn(
                    "INVENTORY_CLIENT_CATEGORIES_CACHE_DESERIALIZE_ERROR, falling back to HTTP", e);
                return Mono.<java.util.List<CategoryDTO>>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  log.info("Fetching categories from inventory-service via HTTP");
                  return webClientBuilder
                      .build()
                      .get()
                      .uri(properties.getBaseUrl() + "/api/inventory-svc/categories")
                      .header("Authorization", authHeader)
                      .retrieve()
                      .bodyToMono(InventoryCategoriesResponse.class)
                      .map(
                          r -> r.getData() != null ? r.getData() : java.util.List.<CategoryDTO>of())
                      .onErrorResume(
                          ex -> {
                            log.error(
                                "INVENTORY_CLIENT_CATEGORIES_HTTP_FAILED reason={}",
                                ex.getMessage());
                            return Mono.just(java.util.List.<CategoryDTO>of());
                          });
                }));
  }

  /** Fetches all stocks for the given event. No cache — stocks change frequently. */
  public Mono<java.util.List<StockDTO>> getAllStocks(Long eventId, String authHeader) {
    return Mono.defer(
        () -> {
          log.info("Fetching stocks from inventory-service via HTTP eventId={}", eventId);
          return webClientBuilder
              .build()
              .get()
              .uri(properties.getBaseUrl() + "/api/inventory-svc/stocks")
              .header("Authorization", authHeader)
              .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
              .retrieve()
              .bodyToMono(InventoryStocksResponse.class)
              .map(r -> r.getData() != null ? r.getData() : java.util.List.<StockDTO>of())
              .onErrorResume(
                  ex -> {
                    log.error(
                        "INVENTORY_CLIENT_STOCKS_HTTP_FAILED eventId={} reason={}",
                        eventId,
                        ex.getMessage());
                    return Mono.just(java.util.List.<StockDTO>of());
                  });
        });
  }

  public Flux<StockMovementHistoryDTO> getStockMovementsByShop(
      Long shopId, Long eventId, String authHeader) {
    log.info(
        "Fetching stock movements from inventory service for shopId={} eventId={}",
        shopId,
        eventId);
    return webClientBuilder
        .build()
        .get()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/stock-movements/shop/" + shopId)
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .retrieve()
        .bodyToFlux(StockMovementHistoryDTO.class)
        .onErrorResume(
            ex -> {
              log.error(
                  "Failed to fetch stock movements for shopId={} from inventory-service: {}",
                  shopId,
                  ex.getMessage(),
                  ex);
              return Flux.empty();
            });
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class StockMovementHistoryDTO {
    private Long id;
    private Long productId;
    private Long eventId;
    private String username;
    private String movementType;
    private Integer quantity;
    private String reason;
    private String locationFrom;
    private String locationTo;
    private Long shopId;
    private java.time.LocalDateTime movementDate;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StockMovementRequest {
    private Long productId;
    private String movementType;
    private Integer quantity;
    private String reason;
    private String shopId;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CounterStockDecrementRequest {
    private Long productId;
    private String shopId;
    private Integer quantity;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class ProductDTO {
    private Long id;
    private String name;
    private String sku;
    private Long categoryId;
    private java.math.BigDecimal sellingPrice;
    private java.math.BigDecimal mrp;
    private String hsnCode;
    private Integer minThreshold;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class IssueDTO {
    private Long id;
    private Long productId;
    private Long eventId;
    private Integer initialQuantity;
    private Integer liveQuantity;
    private Integer quantity;
    private String shopId;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class InventoryProductsResponse {
    private boolean success;
    private java.util.List<ProductDTO> data;
    private String message;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class InventorySalesResponse {
    private boolean success;
    private java.util.List<IssueDTO> data;
    private String message;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class CategoryDTO {
    private Long id;
    private String name;
    private String description;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class StockDTO {
    private Long id;
    private Long productId;
    private Long eventId;
    private Integer quantity;
    private String location;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class InventoryCategoriesResponse {
    private boolean success;
    private java.util.List<CategoryDTO> data;
    private String message;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class InventoryStocksResponse {
    private boolean success;
    private java.util.List<StockDTO> data;
    private String message;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class UnissueItemRequest {
    private Long productId;
    private Integer quantity;
    private String reason;
  }

  /**
   * Fix 3 — Called by ShopService on shop closure to return all remaining counter stocks to main
   * inventory. Delegates to POST /api/inventory-svc/sales/shop/{shopId}/return.
   */
  public Mono<Void> returnShopCounterStocksToInventory(
      Long shopId, Long eventId, String authHeader) {
    log.info("INVENTORY_CLIENT_SHOP_CLOSURE_RETURN shopId={} eventId={}", shopId, eventId);
    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/sales/shop/" + shopId + "/return")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .retrieve()
        .bodyToMono(Void.class)
        .doOnSuccess(
            v -> log.info("INVENTORY_CLIENT_SHOP_CLOSURE_RETURN_SUCCESS shopId={}", shopId))
        .doOnError(
            ex ->
                log.error(
                    "INVENTORY_CLIENT_SHOP_CLOSURE_RETURN_FAILED shopId={} reason={}",
                    shopId,
                    ex.getMessage()));
  }

  /**
   * Feature 4 — Manual unissue. Delegates to POST /api/inventory-svc/sales/shop/{shopId}/unissue.
   */
  public Mono<Void> unissueStocksFromCounter(
      Long shopId, Long eventId, java.util.List<UnissueItemRequest> items, String authHeader) {
    log.info(
        "INVENTORY_CLIENT_UNISSUE shopId={} eventId={} items={}", shopId, eventId, items.size());
    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + "/api/inventory-svc/sales/shop/" + shopId + "/unissue")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(items)
        .retrieve()
        .bodyToMono(Void.class)
        .doOnSuccess(v -> log.info("INVENTORY_CLIENT_UNISSUE_SUCCESS shopId={}", shopId))
        .doOnError(
            ex ->
                log.error(
                    "INVENTORY_CLIENT_UNISSUE_FAILED shopId={} reason={}",
                    shopId,
                    ex.getMessage()));
  }
}
