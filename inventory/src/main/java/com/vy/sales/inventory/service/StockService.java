package com.vy.sales.inventory.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.inventory.entity.Stock;
import com.vy.sales.inventory.repository.StockRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class StockService {

  private static final String STOCKS_EVENT_KEY_PREFIX = "stocks:event:";
  private static final Duration STOCKS_TTL = Duration.ofSeconds(30);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final StockRepository repository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  @Transactional
  public Mono<Stock> create(Stock stock) {
    stock.setId(null);
    stock.setCreatedAt(LocalDateTime.now());
    stock.setUpdatedAt(LocalDateTime.now());
    log.debug(
        "STOCK_CREATE_REQUEST productId={} location={} quantity={} eventId={}",
        stock.getProductId(),
        stock.getLocation(),
        stock.getQuantity(),
        stock.getEventId());
    return repository
        .save(stock)
        .flatMap(saved -> evictCache(saved.getEventId()).thenReturn(saved))
        .doOnSuccess(
            saved ->
                log.info(
                    "STOCK_CREATE_SUCCESS id={} productId={} quantity={} eventId={}",
                    saved.getId(),
                    saved.getProductId(),
                    saved.getQuantity(),
                    saved.getEventId()))
        .doOnError(
            ex ->
                log.error(
                    "STOCK_CREATE_FAILED productId={} eventId={} reason={}",
                    stock.getProductId(),
                    stock.getEventId(),
                    ex.getMessage(),
                    ex));
  }

  public Mono<Stock> getById(Long id, Long eventId) {
    log.debug("STOCK_GET_REQUEST id={} eventId={}", id, eventId);
    return repository
        .findByIdAndEventId(id, eventId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stock not found with id: " + id + " for event: " + eventId)))
        .doOnSuccess(s -> log.debug("STOCK_GET_SUCCESS id={} productId={}", id, s.getProductId()))
        .doOnError(ex -> log.error("STOCK_GET_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  public Flux<Stock> getAll(Long eventId) {
    log.debug("STOCK_GET_ALL_REQUEST eventId={}", eventId);
    String cacheKey = STOCKS_EVENT_KEY_PREFIX + eventId;

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("STOCK_CACHE_GET_ERROR eventId={}, falling back to DB", eventId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<Stock> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Stock>>() {});
                log.debug("STOCK_CACHE_HIT eventId={} count={}", eventId, list.size());
                return Flux.fromIterable(list);
              } catch (Exception e) {
                log.warn(
                    "STOCK_CACHE_DESERIALIZE_ERROR eventId={}, falling back to DB", eventId, e);
                return Flux.<Stock>empty();
              }
            })
        .switchIfEmpty(
            Flux.defer(
                () ->
                    repository
                        .findByEventId(eventId)
                        .collectList()
                        .flatMap(
                            list -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(list);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, STOCKS_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("STOCK_CACHE_SET_ERROR eventId={}", eventId, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(list);
                              } catch (Exception e) {
                                log.warn("STOCK_CACHE_SERIALIZE_ERROR eventId={}", eventId, e);
                                return Mono.just(list);
                              }
                            })
                        .flatMapMany(Flux::fromIterable)))
        .doOnComplete(() -> log.debug("STOCK_GET_ALL_SUCCESS eventId={}", eventId))
        .doOnError(ex -> log.error("STOCK_GET_ALL_FAILED reason={}", ex.getMessage(), ex));
  }

  @Transactional
  public Mono<Stock> update(Long id, Long eventId, Stock request) {
    log.debug("STOCK_UPDATE_REQUEST id={} eventId={}", id, eventId);
    return repository
        .findByIdAndEventId(id, eventId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stock not found with id: " + id + " for event: " + eventId)))
        .flatMap(
            existing -> {
              if (request.getQuantity() != null) {
                existing.setQuantity(request.getQuantity());
              }
              if (request.getLocation() != null) {
                existing.setLocation(request.getLocation());
              }
              existing.setUpdatedAt(LocalDateTime.now());
              return repository.save(existing);
            })
        .flatMap(updated -> evictCache(eventId).thenReturn(updated))
        .doOnSuccess(
            updated ->
                log.info(
                    "STOCK_UPDATE_SUCCESS id={} quantity={} eventId={}",
                    updated.getId(),
                    updated.getQuantity(),
                    updated.getEventId()))
        .doOnError(ex -> log.error("STOCK_UPDATE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  @Transactional
  public Mono<Void> delete(Long id, Long eventId) {
    log.debug("STOCK_DELETE_REQUEST id={} eventId={}", id, eventId);
    return repository
        .existsByIdAndEventId(id, eventId)
        .flatMap(
            exists -> {
              if (!exists) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Stock not found with id: " + id + " for event: " + eventId));
              }
              return repository.deleteByIdAndEventId(id, eventId);
            })
        .then(evictCache(eventId))
        .doOnSuccess(v -> log.info("STOCK_DELETE_SUCCESS id={} eventId={}", id, eventId))
        .doOnError(ex -> log.error("STOCK_DELETE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  private Mono<Void> evictCache(Long eventId) {
    return redisTemplate
        .delete(STOCKS_EVENT_KEY_PREFIX + eventId)
        .onErrorResume(
            e -> {
              log.warn("STOCK_CACHE_EVICT_ERROR eventId={}", eventId, e);
              return Mono.empty();
            })
        .then();
  }
}
