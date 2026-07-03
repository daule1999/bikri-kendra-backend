package com.vy.sales.inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.inventory.entity.Category;
import com.vy.sales.inventory.exceptions.BadRequestException;
import com.vy.sales.inventory.exceptions.CategoryNotFoundException;
import com.vy.sales.inventory.repository.CategoryRepository;
import java.rmi.ServerException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String CATEGORIES_CACHE_KEY = "categories:all";
  private static final Duration CATEGORIES_TTL = Duration.ofHours(1);
  private static final String CATEGORY_ID_KEY_PREFIX = "category:id:";
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final CategoryRepository repository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<Category> create(Category category) {
    log.debug("Saving category name={}", category.getName());

    return repository
        .save(category)
        .flatMap(
            saved ->
                redisTemplate
                    .delete(CATEGORIES_CACHE_KEY)
                    .onErrorResume(
                        e -> {
                          log.warn("Category cache invalidate failed on create", e);
                          return Mono.empty();
                        })
                    .thenReturn(saved))
        .doOnSuccess(
            saved -> log.info("Category saved id={}, name={}", saved.getId(), saved.getName()))
        .doOnError(ex -> log.error("Error saving category name={}", category.getName(), ex));
  }

  public Mono<Category> getById(Long id) {
    log.debug("Finding category by id={}", id);

    String cacheKey = CATEGORY_ID_KEY_PREFIX + id;
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Category cache GET error id={}, falling back to DB", id, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                Category cached = CACHE_MAPPER.readValue(json, Category.class);
                log.debug("Category cache HIT id={}", id);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Category cache deserialize error id={}, falling back to DB", id, e);
                return Mono.<Category>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    repository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new CategoryNotFoundException(id)))
                        .flatMap(
                            category -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(category);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, CATEGORIES_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Category cache SET error id={}", id, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(category);
                              } catch (Exception e) {
                                log.warn("Category cache serialize error id={}", id, e);
                                return Mono.just(category);
                              }
                            })
                        .onErrorMap(
                            DataAccessException.class,
                            ex -> new ServerException("Database failure", ex))));
  }

  public Flux<Category> getAll() {
    log.debug("Fetching all categories");

    return redisTemplate
        .opsForValue()
        .get(CATEGORIES_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("Category cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<Category> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Category>>() {});
                log.debug("Categories cache HIT count={}", list.size());
                return Flux.fromIterable(list);
              } catch (Exception e) {
                log.warn("Category cache deserialize error, falling back to DB", e);
                return Flux.<Category>empty();
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
                                    .set(CATEGORIES_CACHE_KEY, json, CATEGORIES_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Category cache SET error", e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(list);
                              } catch (Exception e) {
                                log.warn("Category cache serialize error", e);
                                return Mono.just(list);
                              }
                            })
                        .flatMapMany(Flux::fromIterable)))
        .doOnComplete(() -> log.info("All categories fetched successfully"))
        .doOnError(ex -> log.error("Error fetching categories", ex));
  }

  @Transactional
  public Mono<Category> update(Long id, Category category) {
    log.debug("Updating category id={}", id);

    return repository
        .findById(id)
        .switchIfEmpty(Mono.error(new CategoryNotFoundException(id)))
        .flatMap(
            existing -> {
              boolean changed = false;

              if (category.getName() != null && !category.getName().equals(existing.getName())) {
                existing.setName(category.getName());
                changed = true;
              }

              if (category.getDescription() != null
                  && !category.getDescription().equals(existing.getDescription())) {
                existing.setDescription(category.getDescription());
                changed = true;
              }

              // Do NOT set createdAt from request to avoid null overwrite
              // Only update updatedAt if there is a change
              if (changed) {
                existing.setUpdatedAt(LocalDateTime.now());
                return repository
                    .save(existing)
                    .flatMap(
                        savedCat ->
                            Mono.when(
                                    redisTemplate.delete(CATEGORIES_CACHE_KEY),
                                    redisTemplate.delete(CATEGORY_ID_KEY_PREFIX + id))
                                .onErrorResume(
                                    e -> {
                                      log.warn(
                                          "Category cache invalidate failed on update id={}",
                                          id,
                                          e);
                                      return Mono.empty();
                                    })
                                .thenReturn(savedCat));
              } else {
                return Mono.just(existing);
              }
            })
        .onErrorMap(
            DataIntegrityViolationException.class,
            ex -> new BadRequestException("Category name already exists"))
        .onErrorMap(
            DataAccessException.class,
            ex ->
                new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update category", ex))
        .doOnSuccess(
            updated ->
                log.info("Category updated id={}, name={}", updated.getId(), updated.getName()))
        .doOnError(ex -> log.error("Error updating category id={}", id, ex));
  }

  public Mono<Void> delete(Long id) {
    log.debug("Deleting category id={}", id);

    return repository
        .deleteById(id)
        .then(
            Mono.when(
                    redisTemplate.delete(CATEGORIES_CACHE_KEY),
                    redisTemplate.delete(CATEGORY_ID_KEY_PREFIX + id))
                .onErrorResume(
                    e -> {
                      log.warn("Category cache invalidate failed on delete id={}", id, e);
                      return Mono.empty();
                    })
                .then())
        .doOnSuccess(v -> log.info("Category deleted id={}", id))
        .doOnError(ex -> log.error("Error deleting category id={}", id, ex));
  }
}
