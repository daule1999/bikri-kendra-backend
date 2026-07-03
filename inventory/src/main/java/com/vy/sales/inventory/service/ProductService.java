package com.vy.sales.inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.inventory.entity.Product;
import com.vy.sales.inventory.exceptions.BadRequestException;
import com.vy.sales.inventory.exceptions.ProductNotFoundException;
import com.vy.sales.inventory.repository.ProductRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String PRODUCTS_CACHE_KEY = "products:all";
  private static final Duration PRODUCTS_TTL = Duration.ofMinutes(30);
  private static final String PRODUCT_ID_KEY_PREFIX = "product:id:";
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final ProductRepository repository;
  private final DatabaseClient databaseClient;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<Product> create(Product product) {
    log.debug("Saving product name={}, sku={}", product.getName(), product.getSku());

    if (product.getMrp() == null || product.getMrp().compareTo(java.math.BigDecimal.ZERO) <= 0) {
      return Mono.error(new BadRequestException("MRP must be greater than 0"));
    }
    if (product.getSellingPrice() == null
        || product.getSellingPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
      return Mono.error(new BadRequestException("Selling price must be greater than 0"));
    }
    if (product.getDiscount() != null
        && product.getDiscount().compareTo(java.math.BigDecimal.ZERO) < 0) {
      return Mono.error(new BadRequestException("Discount cannot be negative"));
    }

    return repository
        .save(product)
        .flatMap(
            saved ->
                redisTemplate
                    .delete(PRODUCTS_CACHE_KEY)
                    .onErrorResume(
                        e -> {
                          log.warn("Product cache invalidate failed on create", e);
                          return Mono.empty();
                        })
                    .thenReturn(saved))
        .doOnSuccess(
            saved -> log.info("Product saved id={}, sku={}", saved.getId(), saved.getSku()))
        .onErrorMap(
            DataIntegrityViolationException.class,
            ex -> new BadRequestException("SKU already exists"))
        .onErrorMap(
            DataAccessException.class,
            ex ->
                new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create product", ex))
        .doOnError(ex -> log.error("Error saving product sku={}", product.getSku(), ex));
  }

  public Mono<Product> get(Long id) {
    log.debug("Finding product by id={}", id);

    String cacheKey = PRODUCT_ID_KEY_PREFIX + id;
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Product cache GET error id={}, falling back to DB", id, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                Product cached = CACHE_MAPPER.readValue(json, Product.class);
                log.debug("Product cache HIT id={}", id);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Product cache deserialize error id={}, falling back to DB", id, e);
                return Mono.<Product>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    repository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new ProductNotFoundException(id)))
                        .flatMap(
                            product -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(product);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, PRODUCTS_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Product cache SET error id={}", id, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(product);
                              } catch (Exception e) {
                                log.warn("Product cache serialize error id={}", id, e);
                                return Mono.just(product);
                              }
                            })));
  }

  public Flux<Product> getAll() {
    log.debug("Fetching all products");

    return redisTemplate
        .opsForValue()
        .get(PRODUCTS_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("Product cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<Product> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Product>>() {});
                log.debug("Products cache HIT count={}", list.size());
                return Flux.fromIterable(list);
              } catch (Exception e) {
                log.warn("Product cache deserialize error, falling back to DB", e);
                return Flux.<Product>empty();
              }
            })
        .switchIfEmpty(
            Flux.defer(
                () ->
                    repository
                        .findAll()
                        .collectList()
                        .flatMap(
                            list -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(list);
                                return redisTemplate
                                    .opsForValue()
                                    .set(PRODUCTS_CACHE_KEY, json, PRODUCTS_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Product cache SET error", e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(list);
                              } catch (Exception e) {
                                log.warn("Product cache serialize error", e);
                                return Mono.just(list);
                              }
                            })
                        .flatMapMany(Flux::fromIterable)))
        .doOnComplete(() -> log.info("All products fetched successfully"))
        .doOnError(ex -> log.error("Error fetching products", ex));
  }

  @Transactional
  public Mono<Product> update(Long id, Product product) {
    log.debug("Updating product id={}, sku={}", id, product.getSku());

    if (product.getMrp() != null && product.getMrp().compareTo(java.math.BigDecimal.ZERO) <= 0) {
      return Mono.error(new BadRequestException("MRP must be greater than 0"));
    }
    if (product.getSellingPrice() != null
        && product.getSellingPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
      return Mono.error(new BadRequestException("Selling price must be greater than 0"));
    }
    if (product.getDiscount() != null
        && product.getDiscount().compareTo(java.math.BigDecimal.ZERO) < 0) {
      return Mono.error(new BadRequestException("Discount cannot be negative"));
    }

    return repository
        .findById(id)
        .switchIfEmpty(Mono.error(new ProductNotFoundException(id)))
        .flatMap(
            existing -> {
              // Only update fields if they are non-null in request
              if (product.getName() != null && !product.getName().equals(existing.getName())) {
                existing.setName(product.getName());
              }
              if (product.getSku() != null && !product.getSku().equals(existing.getSku())) {
                existing.setSku(product.getSku());
              }
              if (product.getDescription() != null
                  && !product.getDescription().equals(existing.getDescription())) {
                existing.setDescription(product.getDescription());
              }
              // BigDecimal comparison optimization using compareTo instead of equals
              if (product.getMrp() != null
                  && (existing.getMrp() == null
                      || product.getMrp().compareTo(existing.getMrp()) != 0)) {
                existing.setMrp(product.getMrp());
              }
              if (product.getSellingPrice() != null
                  && (existing.getSellingPrice() == null
                      || product.getSellingPrice().compareTo(existing.getSellingPrice()) != 0)) {
                existing.setSellingPrice(product.getSellingPrice());
              }
              if (product.getDiscount() != null
                  && (existing.getDiscount() == null
                      || product.getDiscount().compareTo(existing.getDiscount()) != 0)) {
                existing.setDiscount(product.getDiscount());
              }
              // Functional bug fix: Map minThreshold which was previously ignored
              if (product.getMinThreshold() != null
                  && !product.getMinThreshold().equals(existing.getMinThreshold())) {
                existing.setMinThreshold(product.getMinThreshold());
              }
              if (product.getIsActive() != null
                  && !product.getIsActive().equals(existing.getIsActive())) {
                existing.setIsActive(product.getIsActive());
              }
              if (product.getCategoryId() != null
                  && !product.getCategoryId().equals(existing.getCategoryId())) {
                existing.setCategoryId(product.getCategoryId());
              }

              // Update the updatedAt timestamp
              existing.setUpdatedAt(LocalDateTime.now());

              return repository
                  .save(existing)
                  .flatMap(
                      savedProduct ->
                          Mono.when(
                                  redisTemplate.delete(PRODUCTS_CACHE_KEY),
                                  redisTemplate.delete(PRODUCT_ID_KEY_PREFIX + id))
                              .onErrorResume(
                                  e -> {
                                    log.warn(
                                        "Product cache invalidate failed on update id={}", id, e);
                                    return Mono.empty();
                                  })
                              .thenReturn(savedProduct));
            })
        .onErrorMap(
            DataIntegrityViolationException.class,
            ex -> new BadRequestException("SKU already exists"))
        .onErrorMap(
            DataAccessException.class,
            ex ->
                new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update product", ex))
        .doOnSuccess(
            updated -> log.info("Product updated id={}, sku={}", updated.getId(), updated.getSku()))
        .doOnError(ex -> log.error("Error updating product id={}", id, ex));
  }

  public Mono<Void> delete(Long id) {
    log.debug("Deleting product id={}", id);

    return repository
        .deleteById(id)
        .then(
            Mono.when(
                    redisTemplate.delete(PRODUCTS_CACHE_KEY),
                    redisTemplate.delete(PRODUCT_ID_KEY_PREFIX + id))
                .onErrorResume(
                    e -> {
                      log.warn("Product cache invalidate failed on delete id={}", id, e);
                      return Mono.empty();
                    })
                .then())
        .doOnSuccess(v -> log.info("Product deleted id={}", id))
        .doOnError(ex -> log.error("Error deleting product id={}", id, ex));
  }

  @Transactional
  public Flux<Product> createBulk(List<Product> products) {
    log.info("Saving bulk products in transaction. Count={}", products.size());

    for (Product product : products) {
      if (product.getMrp() == null || product.getMrp().compareTo(java.math.BigDecimal.ZERO) <= 0) {
        return Flux.error(new BadRequestException("MRP must be greater than 0"));
      }
      if (product.getSellingPrice() == null
          || product.getSellingPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
        return Flux.error(new BadRequestException("Selling price must be greater than 0"));
      }
      if (product.getDiscount() != null
          && product.getDiscount().compareTo(java.math.BigDecimal.ZERO) < 0) {
        return Flux.error(new BadRequestException("Discount cannot be negative"));
      }
    }

    return repository
        .saveAll(products)
        .onErrorMap(
            DataIntegrityViolationException.class,
            ex -> new BadRequestException("SKU already exists"))
        .onErrorMap(
            DataAccessException.class,
            ex ->
                new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create bulk products", ex))
        .doOnNext(
            saved ->
                log.info("Product saved successfully id={}, sku={}", saved.getId(), saved.getSku()))
        .collectList()
        .flatMap(
            savedList ->
                redisTemplate
                    .delete(PRODUCTS_CACHE_KEY)
                    .onErrorResume(
                        e -> {
                          log.warn("Product cache invalidate failed on bulk create", e);
                          return Mono.empty();
                        })
                    .thenReturn(savedList))
        .flatMapMany(Flux::fromIterable);
  }

  public Flux<Product> searchProducts(
      List<String> categories,
      String name,
      String sku,
      Boolean isActive,
      LocalDateTime createdBefore,
      LocalDateTime createdAfter) {

    // ── Cache-aside: if products:all is warm, filter in memory (no DB round-trip) ──
    List<Long> resolvedCategoryIds = new java.util.ArrayList<>();
    if (categories != null) {
      for (String cat : categories) {
        if (cat != null && !cat.trim().isEmpty()) {
          String[] parts = cat.split(",");
          for (String part : parts) {
            try {
              resolvedCategoryIds.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
          }
        }
      }
    }
    final List<Long> catIds = resolvedCategoryIds;
    final String trimmedName = (name != null && !name.trim().isEmpty()) ? name.trim() : null;
    final String trimmedSku = (sku != null && !sku.trim().isEmpty()) ? sku.trim() : null;

    return redisTemplate
        .opsForValue()
        .get(PRODUCTS_CACHE_KEY)
        .onErrorResume(e -> Mono.empty())
        .flatMapMany(
            json -> {
              try {
                List<Product> all =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Product>>() {});
                log.debug("Search using products:all cache count={}", all.size());
                return Flux.fromIterable(all)
                    .filter(
                        p ->
                            (catIds.isEmpty() || catIds.contains(p.getCategoryId()))
                                && (trimmedName == null
                                    || (p.getName() != null
                                        && p.getName()
                                            .toLowerCase()
                                            .contains(trimmedName.toLowerCase())))
                                && (trimmedSku == null
                                    || (p.getSku() != null
                                        && p.getSku().equalsIgnoreCase(trimmedSku)))
                                && (isActive == null || isActive.equals(p.getIsActive()))
                                && (createdBefore == null
                                    || (p.getCreatedAt() != null
                                        && !p.getCreatedAt().isAfter(createdBefore)))
                                && (createdAfter == null
                                    || (p.getCreatedAt() != null
                                        && !p.getCreatedAt().isBefore(createdAfter))));
              } catch (Exception e) {
                log.warn("Product search cache deserialize error, falling back to DB", e);
                return Flux.<Product>empty();
              }
            })
        .switchIfEmpty(
            Flux.defer(
                () ->
                    searchProductsFromDb(
                        catIds, trimmedName, trimmedSku, isActive, createdBefore, createdAfter)));
  }

  private Flux<Product> searchProductsFromDb(
      List<Long> categoryIds,
      String name,
      String sku,
      Boolean isActive,
      LocalDateTime createdBefore,
      LocalDateTime createdAfter) {

    StringBuilder sql = new StringBuilder("SELECT * FROM product WHERE 1=1");

    if (!categoryIds.isEmpty()) {
      sql.append(" AND category_id IN (:categoryIds)");
    }

    if (name != null) {
      sql.append(" AND name LIKE :name");
    }

    if (sku != null) {
      sql.append(" AND sku = :sku");
    }

    if (isActive != null) {
      sql.append(" AND is_active = :isActive");
    }

    if (createdBefore != null) {
      sql.append(" AND created_at <= :createdBefore");
    }

    if (createdAfter != null) {
      sql.append(" AND created_at >= :createdAfter");
    }

    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());

    if (!categoryIds.isEmpty()) {
      spec = spec.bind("categoryIds", categoryIds);
    }

    if (name != null) {
      spec = spec.bind("name", "%" + name + "%");
    }

    if (sku != null) {
      spec = spec.bind("sku", sku);
    }

    if (isActive != null) {
      spec = spec.bind("isActive", isActive);
    }

    if (createdBefore != null) {
      spec = spec.bind("createdBefore", createdBefore);
    }

    if (createdAfter != null) {
      spec = spec.bind("createdAfter", createdAfter);
    }

    return spec.map(
            (row, meta) ->
                Product.builder()
                    .id(row.get("id", Long.class))
                    .categoryId(row.get("category_id", Long.class))
                    .name(row.get("name", String.class))
                    .sku(row.get("sku", String.class))
                    .description(row.get("description", String.class))
                    .mrp(row.get("mrp", java.math.BigDecimal.class))
                    .sellingPrice(row.get("selling_price", java.math.BigDecimal.class))
                    .discount(row.get("discount", java.math.BigDecimal.class))
                    .minThreshold(
                        row.get("min_threshold", Integer.class)) // Bug Fix: Map minThreshold
                    .isActive(row.get("is_active", Boolean.class))
                    .createdAt(row.get("created_at", java.time.LocalDateTime.class))
                    .updatedAt(row.get("updated_at", java.time.LocalDateTime.class))
                    .build())
        .all();
  }
}
