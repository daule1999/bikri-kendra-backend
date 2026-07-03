package com.vy.sales.sales_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.dto.EventRequest;
import com.vy.sales.sales_service.dto.EventResponse;
import com.vy.sales.sales_service.model.Event;
import com.vy.sales.sales_service.repository.EventRepository;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String EVENTS_ALL_CACHE_KEY = "events:all";
  private static final Duration EVENTS_TTL = Duration.ofMinutes(30);
  private static final String EVENTS_USER_KEY_PREFIX = "events:user:";
  private static final Duration EVENTS_USER_TTL = Duration.ofMinutes(10);
  private static final String EVENT_ID_KEY_PREFIX = "event:id:";
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final EventRepository repository;
  private final UserClient userClient;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  // CREATE
  public Mono<EventResponse> create(EventRequest request) {
    log.info("Creating event {}", request.getEventName());

    if (request.getStartDate() != null && request.getEndDate() != null) {
      if (request.getEndDate().isBefore(request.getStartDate())) {
        return Mono.error(
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "End date cannot be earlier than start date"));
      }
    }

    return repository
        .save(mapToEntity(request))
        .flatMap(saved -> evictAllEventCaches("create").thenReturn(saved))
        .map(this::mapToResponse)
        .doOnSuccess(e -> log.info("Event created id={}", e.getId()));
  }

  // CHECK IF ANY EVENT EXISTS — cache-assist from events:all if warm
  public Mono<Boolean> hasAnyEvent() {
    return redisTemplate
        .opsForValue()
        .get(EVENTS_ALL_CACHE_KEY)
        .onErrorResume(e -> Mono.empty())
        .flatMap(
            json -> {
              try {
                List<EventResponse> all =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<EventResponse>>() {});
                return Mono.just(!all.isEmpty());
              } catch (Exception e) {
                return Mono.<Boolean>empty();
              }
            })
        .switchIfEmpty(repository.count().map(count -> count > 0));
  }

  /**
   * GET ALL — Role-Based Scoping: - ADMIN: cache-aside on events:all, returns all events. -
   * Non-ADMIN: personalized (no cache), calls user-service for assigned IDs.
   */
  public Flux<EventResponse> getAll(
      Long userId, List<String> roles, String username, String authHeader) {
    boolean isAdmin =
        (roles != null && roles.contains("ADMIN")) || "admin".equalsIgnoreCase(username);

    if (isAdmin) {
      log.info("EVENT_GET_ALL_ADMIN userId={} username={}", userId, username);
      return redisTemplate
          .opsForValue()
          .get(EVENTS_ALL_CACHE_KEY)
          .onErrorResume(
              e -> {
                log.warn("Events cache GET error, falling back to DB", e);
                return Mono.empty();
              })
          .flatMapMany(
              json -> {
                try {
                  List<EventResponse> cached =
                      CACHE_MAPPER.readValue(json, new TypeReference<List<EventResponse>>() {});
                  log.debug("Events cache HIT count={}", cached.size());
                  return Flux.fromIterable(cached);
                } catch (Exception e) {
                  log.warn("Events cache deserialize error, falling back to DB", e);
                  return Flux.<EventResponse>empty();
                }
              })
          .switchIfEmpty(
              repository
                  .findAll()
                  .map(this::mapToResponse)
                  .collectList()
                  .flatMapMany(
                      events -> {
                        try {
                          String json = CACHE_MAPPER.writeValueAsString(events);
                          return redisTemplate
                              .opsForValue()
                              .set(EVENTS_ALL_CACHE_KEY, json, EVENTS_TTL)
                              .onErrorResume(
                                  e -> {
                                    log.warn("Events cache SET error", e);
                                    return Mono.empty();
                                  })
                              .thenMany(Flux.fromIterable(events));
                        } catch (Exception e) {
                          log.warn("Events cache serialize error", e);
                          return Flux.fromIterable(events);
                        }
                      }));
    }

    log.info("EVENT_GET_ALL_RESTRICTED userId={}", userId);
    String userCacheKey = EVENTS_USER_KEY_PREFIX + userId;
    return redisTemplate
        .opsForValue()
        .get(userCacheKey)
        .onErrorResume(
            e -> {
              log.warn("Events user cache GET error userId={}, falling back to DB", userId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<EventResponse> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<EventResponse>>() {});
                log.debug("Events user cache HIT userId={} count={}", userId, cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn(
                    "Events user cache deserialize error userId={}, falling back to DB", userId, e);
                return Flux.<EventResponse>empty();
              }
            })
        .switchIfEmpty(
            userClient
                .getAccessibleEventIds(userId, authHeader)
                .collectList()
                .flatMapMany(
                    assignedIds -> {
                      if (assignedIds.isEmpty()) {
                        log.warn("EVENT_GET_ALL_EMPTY_ASSIGNMENTS userId={}", userId);
                        return Flux.<EventResponse>empty();
                      }
                      log.info(
                          "EVENT_GET_ALL_RESTRICTED_IDS userId={} eventIds={}",
                          userId,
                          assignedIds);
                      return repository
                          .findByIdIn(assignedIds)
                          .map(this::mapToResponse)
                          .collectList()
                          .flatMapMany(
                              events -> {
                                try {
                                  String json = CACHE_MAPPER.writeValueAsString(events);
                                  return redisTemplate
                                      .opsForValue()
                                      .set(userCacheKey, json, EVENTS_USER_TTL)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Events user cache SET error userId={}", userId, e);
                                            return Mono.empty();
                                          })
                                      .thenMany(Flux.fromIterable(events));
                                } catch (Exception e) {
                                  log.warn(
                                      "Events user cache serialize error userId={}", userId, e);
                                  return Flux.fromIterable(events);
                                }
                              });
                    }));
  }

  /**
   * Returns all events as a List — used by the page-init aggregator. Reuses the {@code events:all}
   * Redis cache (same key as the admin path of {@link #getAll}).
   */
  public Mono<List<EventResponse>> getAllAsList() {
    return redisTemplate
        .opsForValue()
        .get(EVENTS_ALL_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("Events getAllAsList cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                List<EventResponse> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<EventResponse>>() {});
                log.debug("Events getAllAsList cache HIT count={}", cached.size());
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Events getAllAsList cache deserialize error, falling back to DB", e);
                return Mono.<List<EventResponse>>empty();
              }
            })
        .switchIfEmpty(
            repository
                .findAll()
                .map(this::mapToResponse)
                .collectList()
                .flatMap(
                    events -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(events);
                        return redisTemplate
                            .opsForValue()
                            .set(EVENTS_ALL_CACHE_KEY, json, EVENTS_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("Events getAllAsList cache SET error", e);
                                  return Mono.empty();
                                })
                            .thenReturn(events);
                      } catch (Exception e) {
                        log.warn("Events getAllAsList cache serialize error", e);
                        return Mono.just(events);
                      }
                    }));
  }

  // GET BY ID — cache-assist: per-id key → events:all in-memory → DB + cache
  public Mono<EventResponse> getById(Long id) {
    log.info("Fetching event id={}", id);
    String idCacheKey = EVENT_ID_KEY_PREFIX + id;

    // 1) Try per-id cache
    return redisTemplate
        .opsForValue()
        .get(idCacheKey)
        .onErrorResume(
            e -> {
              log.warn("Event id cache GET error id={}", id, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                return Mono.just(CACHE_MAPPER.readValue(json, EventResponse.class));
              } catch (Exception e) {
                return Mono.<EventResponse>empty();
              }
            })
        // 2) Try events:all list in-memory
        .switchIfEmpty(
            redisTemplate
                .opsForValue()
                .get(EVENTS_ALL_CACHE_KEY)
                .onErrorResume(e -> Mono.empty())
                .flatMap(
                    json -> {
                      try {
                        List<EventResponse> all =
                            CACHE_MAPPER.readValue(
                                json, new TypeReference<List<EventResponse>>() {});
                        return all.stream()
                            .filter(e -> id.equals(e.getId()))
                            .findFirst()
                            .map(Mono::just)
                            .orElse(Mono.empty());
                      } catch (Exception e) {
                        return Mono.<EventResponse>empty();
                      }
                    }))
        // 3) DB fallback — populate per-id cache
        .switchIfEmpty(
            repository
                .findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Event not found")))
                .map(this::mapToResponse)
                .flatMap(
                    resp -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(resp);
                        return redisTemplate
                            .opsForValue()
                            .set(idCacheKey, json, EVENTS_TTL)
                            .onErrorResume(e -> Mono.empty())
                            .thenReturn(resp);
                      } catch (Exception e) {
                        return Mono.just(resp);
                      }
                    }));
  }

  // UPDATE
  public Mono<EventResponse> update(Long id, EventRequest request) {
    log.info("EVENT_SERVICE_UPDATE_REQUEST id={} name={}", id, request.getEventName());

    if (request.getStartDate() != null && request.getEndDate() != null) {
      if (request.getEndDate().isBefore(request.getStartDate())) {
        return Mono.error(
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "End date cannot be earlier than start date"));
      }
    }

    return repository
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Event not found")))
        .flatMap(
            e -> {
              e.setEventName(request.getEventName());
              e.setEventType(request.getEventType());
              e.setDescription(request.getDescription());
              e.setLocation(request.getLocation());
              e.setStartDate(request.getStartDate());
              e.setEndDate(request.getEndDate());
              e.setIsActive(request.getIsActive());
              if (request.getReceiptConfig() != null) {
                e.setReceiptConfig(serializeReceiptConfig(request.getReceiptConfig()));
              }
              return repository.save(e);
            })
        .flatMap(
            saved ->
                Mono.when(
                        evictAllEventCaches("update id=" + id),
                        redisTemplate
                            .delete(EVENT_ID_KEY_PREFIX + id)
                            .onErrorResume(e -> Mono.empty()))
                    .thenReturn(saved))
        .map(this::mapToResponse)
        .doOnSuccess(e -> log.info("EVENT_SERVICE_UPDATE_SUCCESS id={}", id))
        .doOnError(
            ex ->
                log.error("EVENT_SERVICE_UPDATE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  // DELETE
  public Mono<Void> delete(Long id) {
    log.info("EVENT_SERVICE_DELETE_REQUEST id={}", id);

    return repository
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Event not found")))
        .flatMap(repository::delete)
        .then(
            Mono.when(
                evictAllEventCaches("delete id=" + id),
                redisTemplate.delete(EVENT_ID_KEY_PREFIX + id).onErrorResume(e -> Mono.empty())))
        .doOnSuccess(v -> log.info("EVENT_SERVICE_DELETE_SUCCESS id={}", id))
        .doOnError(
            ex ->
                log.error("EVENT_SERVICE_DELETE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  /** Evicts events:all (admin cache) and all events:user:{userId} (per-user caches). */
  private Mono<Void> evictAllEventCaches(String context) {
    Mono<Void> evictAdmin =
        redisTemplate
            .delete(EVENTS_ALL_CACHE_KEY)
            .onErrorResume(
                e -> {
                  log.warn("Events:all cache delete failed on {}", context, e);
                  return Mono.empty();
                })
            .then();

    Mono<Void> evictUsers =
        redisTemplate
            .keys(EVENTS_USER_KEY_PREFIX + "*")
            .flatMap(
                key ->
                    redisTemplate
                        .delete(key)
                        .onErrorResume(
                            e -> {
                              log.warn(
                                  "Events user cache delete failed for key={} on {}",
                                  key,
                                  context,
                                  e);
                              return Mono.empty();
                            }))
            .then();

    return Mono.when(evictAdmin, evictUsers);
  }

  private String serializeReceiptConfig(Object receiptConfig) {
    if (receiptConfig == null) return null;
    try {
      return CACHE_MAPPER.writeValueAsString(receiptConfig);
    } catch (Exception ex) {
      log.warn("Failed to serialize receiptConfig, storing as null", ex);
      return null;
    }
  }

  private Object deserializeReceiptConfig(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return CACHE_MAPPER.readValue(json, Object.class);
    } catch (Exception ex) {
      log.warn("Failed to deserialize receiptConfig, returning null", ex);
      return null;
    }
  }

  private Event mapToEntity(EventRequest r) {
    return Event.builder()
        .eventName(r.getEventName())
        .eventType(r.getEventType())
        .description(r.getDescription())
        .location(r.getLocation())
        .startDate(r.getStartDate())
        .endDate(r.getEndDate())
        .isActive(r.getIsActive() != null ? r.getIsActive() : true)
        .receiptConfig(serializeReceiptConfig(r.getReceiptConfig()))
        .build();
  }

  private EventResponse mapToResponse(Event e) {
    return EventResponse.builder()
        .id(e.getId())
        .eventName(e.getEventName())
        .eventType(e.getEventType())
        .description(e.getDescription())
        .location(e.getLocation())
        .startDate(e.getStartDate())
        .endDate(e.getEndDate())
        .isActive(e.getIsActive())
        .receiptConfig(deserializeReceiptConfig(e.getReceiptConfig()))
        .build();
  }
}
