package com.vy.sales.sales_service.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.dto.ActiveShiftResponse;
import com.vy.sales.sales_service.dto.ExpectedCashResponse;
import com.vy.sales.sales_service.model.ShopShiftDenomination;
import com.vy.sales.sales_service.model.ShopShiftSession;
import com.vy.sales.sales_service.repository.SalesPaymentRepository;
import com.vy.sales.sales_service.repository.ShopRepository;
import com.vy.sales.sales_service.repository.ShopShiftDenominationRepository;
import com.vy.sales.sales_service.repository.ShopShiftSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
public class ShopShiftSessionService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final Duration SHIFT_TTL = Duration.ofMinutes(30);
  private static final Duration SHIFT_HISTORY_TTL = Duration.ofMinutes(5);
  private static final Duration SHIFT_EVENT_SUMMARY_TTL = Duration.ofMinutes(5);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final ShopShiftSessionRepository sessionRepo;
  private final ShopShiftDenominationRepository denominationRepo;
  private final SalesPaymentRepository paymentRepo;
  private final ShopRepository shopRepo;
  private final InvoiceSequenceService invoiceSequenceService;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  private String shiftCacheKey(Long shopId, Long eventId) {
    return "shift:active:" + shopId + ":" + eventId;
  }

  private String shiftHistoryCacheKey(Long shopId, Long eventId) {
    return "shift:history:" + shopId + ":" + eventId;
  }

  private String shiftEventSummaryCacheKey(Long eventId) {
    return "shift:event-summary:" + eventId;
  }

  /** Evicts shift history and event-summary caches for the given shopId and eventId. */
  private Mono<Void> evictShiftHistoryCaches(Long shopId, Long eventId) {
    return Mono.when(
            redisTemplate
                .delete(shiftHistoryCacheKey(shopId, eventId))
                .onErrorResume(
                    e -> {
                      log.warn(
                          "Shift history cache evict failed shopId={} eventId={}",
                          shopId,
                          eventId,
                          e);
                      return Mono.empty();
                    }),
            redisTemplate
                .delete(shiftEventSummaryCacheKey(eventId))
                .onErrorResume(
                    e -> {
                      log.warn("Shift event-summary cache evict failed eventId={}", eventId, e);
                      return Mono.empty();
                    }))
        .then();
  }

  @Transactional
  public Mono<ShopShiftSession> openSession(
      Long shopId,
      Long eventId,
      BigDecimal openingCash,
      List<ShopShiftDenomination> denominations,
      Long userId) {
    // Enforce lock on the shop to guarantee serial opening and prevent concurrency races
    return shopRepo
        .findAndLockById(shopId)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found")))
        .flatMap(lockedShopId -> sessionRepo.findActiveSession(shopId, eventId))
        .flatMap(
            active ->
                Mono.<ShopShiftSession>error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A shift session is already OPEN for this shop!")))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  BigDecimal scaledOpeningCash =
                      (openingCash != null ? openingCash : BigDecimal.ZERO)
                          .setScale(2, RoundingMode.HALF_UP);

                  ShopShiftSession session =
                      ShopShiftSession.builder()
                          .shopId(shopId)
                          .eventId(eventId)
                          .status("OPEN")
                          .openedAt(LocalDateTime.now())
                          .openingCashBalance(scaledOpeningCash)
                          .expectedClosingCash(scaledOpeningCash)
                          .actualClosingCash(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                          .expectedClosingOnline(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                          .actualClosingOnline(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                          .openedByUserId(userId)
                          .createdAt(LocalDateTime.now())
                          .updatedAt(LocalDateTime.now())
                          .build();

                  log.info("Opening new shift session for shopId={} eventId={}", shopId, eventId);
                  return sessionRepo
                      .save(session)
                      .flatMap(
                          savedSession -> {
                            Mono<Void> initSeqMono =
                                invoiceSequenceService.initializeOrUpdateSequence(shopId, eventId);
                            Mono<Void> saveDenomMono;
                            if (denominations == null || denominations.isEmpty()) {
                              saveDenomMono = Mono.empty();
                            } else {
                              LocalDateTime now = LocalDateTime.now();
                              List<ShopShiftDenomination> mapped =
                                  denominations.stream()
                                      .peek(d -> d.setShiftSessionId(savedSession.getId()))
                                      .peek(d -> d.setEntryType("OPENING"))
                                      .peek(d -> d.setCreatedAt(now))
                                      .toList();
                              saveDenomMono = denominationRepo.saveAll(mapped).then();
                            }
                            // Cache the new OPEN session + evict history/summary caches
                            return Mono.when(initSeqMono, saveDenomMono)
                                .thenReturn(savedSession)
                                .flatMap(
                                    s -> {
                                      String cacheKey = shiftCacheKey(shopId, eventId);
                                      Mono<Void> writeMono;
                                      try {
                                        String json = CACHE_MAPPER.writeValueAsString(s);
                                        writeMono =
                                            redisTemplate
                                                .opsForValue()
                                                .set(cacheKey, json, SHIFT_TTL)
                                                .onErrorResume(
                                                    e -> {
                                                      log.warn(
                                                          "Shift cache write failed on open key={}",
                                                          cacheKey,
                                                          e);
                                                      return Mono.empty();
                                                    })
                                                .then();
                                      } catch (Exception e) {
                                        log.warn(
                                            "Shift cache serialize error shopId={} eventId={}",
                                            shopId,
                                            eventId,
                                            e);
                                        writeMono = Mono.empty();
                                      }
                                      return Mono.when(
                                              writeMono, evictShiftHistoryCaches(shopId, eventId))
                                          .thenReturn(s);
                                    });
                          });
                }));
  }

  @Transactional
  public Mono<ShopShiftSession> closeSession(
      Long sessionId,
      BigDecimal actualClosingCash,
      BigDecimal actualClosingOnline,
      List<ShopShiftDenomination> denominations,
      Long userId) {
    return sessionRepo
        .findById(sessionId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift session not found!")))
        .flatMap(
            session -> {
              if (!"OPEN".equals(session.getStatus())) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Shift session is not OPEN!"));
              }

              // Calculate expected cash = opening cash + cash sales - cash refunds
              Mono<BigDecimal> cashSalesMono =
                  paymentRepo
                      .sumCashPaymentsBySession(sessionId)
                      .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
                      .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

              Mono<BigDecimal> cashRefundsMono =
                  sessionRepo
                      .sumCashRefundsBySession(sessionId)
                      .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
                      .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

              Mono<BigDecimal> onlineSalesMono =
                  paymentRepo
                      .sumOnlinePaymentsBySession(sessionId)
                      .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
                      .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

              Mono<BigDecimal> onlineRefundsMono =
                  sessionRepo
                      .sumOnlineRefundsBySession(sessionId)
                      .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
                      .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

              Mono<BigDecimal> cashAdditionsMono =
                  denominationRepo
                      .findByShiftSessionId(sessionId)
                      .filter(d -> "ADDITION".equals(d.getEntryType()))
                      .map(d -> BigDecimal.valueOf((long) d.getCurrencyValue() * d.getNoteCount()))
                      .reduce(BigDecimal.ZERO, BigDecimal::add)
                      .map(amount -> amount.setScale(2, RoundingMode.HALF_UP));

              return Mono.zip(
                      cashSalesMono,
                      cashRefundsMono,
                      onlineSalesMono,
                      onlineRefundsMono,
                      cashAdditionsMono)
                  .flatMap(
                      tuple -> {
                        BigDecimal cashSales = tuple.getT1();
                        BigDecimal cashRefunds = tuple.getT2();
                        BigDecimal onlineSales = tuple.getT3();
                        BigDecimal onlineRefunds = tuple.getT4();
                        BigDecimal cashAdditions = tuple.getT5();

                        BigDecimal expectedCash =
                            session
                                .getOpeningCashBalance()
                                .add(cashSales)
                                .subtract(cashRefunds)
                                .add(cashAdditions)
                                .setScale(2, RoundingMode.HALF_UP);

                        BigDecimal expectedOnline =
                            onlineSales.subtract(onlineRefunds).setScale(2, RoundingMode.HALF_UP);

                        BigDecimal scaledActualCash =
                            (actualClosingCash != null ? actualClosingCash : BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP);

                        BigDecimal scaledActualOnline =
                            (actualClosingOnline != null ? actualClosingOnline : BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP);

                        session.setExpectedClosingCash(expectedCash);
                        session.setActualClosingCash(scaledActualCash);
                        session.setExpectedClosingOnline(expectedOnline);
                        session.setActualClosingOnline(scaledActualOnline);
                        session.setStatus("CLOSED");
                        session.setClosedAt(LocalDateTime.now());
                        session.setClosedByUserId(userId);
                        session.setUpdatedAt(LocalDateTime.now());

                        log.info(
                            "Closing shift session id={} shopId={} expectedCash={} actualCash={} expectedOnline={} actualOnline={}",
                            sessionId,
                            session.getShopId(),
                            expectedCash,
                            scaledActualCash,
                            expectedOnline,
                            scaledActualOnline);

                        String cacheKey = shiftCacheKey(session.getShopId(), session.getEventId());

                        return sessionRepo
                            .save(session)
                            .flatMap(
                                savedSession -> {
                                  // Invalidate active shift, history, and event-summary caches
                                  Mono<Void> invalidate =
                                      Mono.when(
                                              redisTemplate
                                                  .delete(cacheKey)
                                                  .onErrorResume(
                                                      e -> {
                                                        log.warn(
                                                            "Shift cache delete failed on close key={}",
                                                            cacheKey,
                                                            e);
                                                        return Mono.empty();
                                                      }),
                                              evictShiftHistoryCaches(
                                                  savedSession.getShopId(),
                                                  savedSession.getEventId()))
                                          .then();

                                  if (denominations == null || denominations.isEmpty()) {
                                    return invalidate.thenReturn(savedSession);
                                  }
                                  // Map and save all closing denominations associated with this
                                  // session id
                                  LocalDateTime now = LocalDateTime.now();
                                  List<ShopShiftDenomination> mapped =
                                      denominations.stream()
                                          .peek(d -> d.setShiftSessionId(savedSession.getId()))
                                          .peek(d -> d.setEntryType("CLOSING"))
                                          .peek(d -> d.setCreatedAt(now))
                                          .toList();
                                  return denominationRepo
                                      .saveAll(mapped)
                                      .then(invalidate)
                                      .thenReturn(savedSession);
                                });
                      });
            });
  }

  @Transactional
  public Mono<ShopShiftSession> reconcileSession(Long sessionId, Long userId, String comment) {
    return sessionRepo
        .findById(sessionId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift session not found!")))
        .flatMap(
            session -> {
              if (!"CLOSED".equals(session.getStatus())
                  && !"RECONCILED".equals(session.getStatus())) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Shift session must be CLOSED to reconcile!"));
              }
              session.setStatus("RECONCILED");
              session.setReconciledByUserId(userId);
              session.setReconciledAt(LocalDateTime.now());
              session.setReconciliationComment(escapeHtml(comment));
              session.setUpdatedAt(LocalDateTime.now());
              log.info(
                  "Reconciling shift session id={} by userId={} with comment={}",
                  sessionId,
                  userId,
                  comment);
              // Clear active-shift, history, and event-summary caches
              String cacheKey = shiftCacheKey(session.getShopId(), session.getEventId());
              return sessionRepo
                  .save(session)
                  .flatMap(
                      savedSession ->
                          Mono.when(
                                  redisTemplate
                                      .delete(cacheKey)
                                      .onErrorResume(
                                          e -> {
                                            log.warn(
                                                "Shift cache delete failed on reconcile key={}",
                                                cacheKey,
                                                e);
                                            return Mono.empty();
                                          }),
                                  evictShiftHistoryCaches(
                                      savedSession.getShopId(), savedSession.getEventId()))
                              .thenReturn(savedSession));
            });
  }

  private String escapeHtml(String input) {
    if (input == null) return null;
    String escaped =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;");
    if (escaped.length() > 500) {
      escaped = escaped.substring(0, 500);
      // Smart truncation to avoid cutting in the middle of a structured HTML entity (e.g. "&lt")
      int lastAmp = escaped.lastIndexOf('&');
      if (lastAmp != -1 && lastAmp > 480) {
        int lastSemi = escaped.lastIndexOf(';');
        if (lastSemi < lastAmp) {
          escaped = escaped.substring(0, lastAmp);
        }
      }
    }
    return escaped;
  }

  public Mono<ShopShiftSession> getActiveSession(Long shopId, Long eventId) {
    String cacheKey = shiftCacheKey(shopId, eventId);
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Shift cache GET error key={}, falling back to DB", cacheKey, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                ShopShiftSession cached = CACHE_MAPPER.readValue(json, ShopShiftSession.class);
                log.debug("Shift cache HIT key={}", cacheKey);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Shift cache deserialize error key={}, falling back to DB", cacheKey, e);
                return Mono.<ShopShiftSession>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    sessionRepo
                        .findActiveSession(shopId, eventId)
                        .flatMap(
                            session -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(session);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, SHIFT_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Shift cache SET error key={}", cacheKey, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(session);
                              } catch (Exception e) {
                                log.warn(
                                    "Shift cache serialize error shopId={} eventId={}",
                                    shopId,
                                    eventId,
                                    e);
                                return Mono.just(session);
                              }
                            })))
        .flatMap(this::populateLiveExpectedCash);
  }

  /**
   * Lightweight variant that skips the live cash-balance calculation.
   *
   * <p>Use this in latency-sensitive paths (e.g. completeSale, getNextInvoiceNumber) where only
   * {@code session.getId()} is needed. {@link #getActiveSession} runs 5 parallel SUM queries via
   * {@link #populateLiveExpectedCash} — unnecessary when the caller only needs the session ID.
   */
  public Mono<ShopShiftSession> getActiveSessionBasic(Long shopId, Long eventId) {
    String cacheKey = shiftCacheKey(shopId, eventId);
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn("Shift cache GET error key={}, falling back to DB", cacheKey, e);
              return Mono.empty();
            })
        .flatMap(
            json -> {
              try {
                ShopShiftSession cached = CACHE_MAPPER.readValue(json, ShopShiftSession.class);
                log.debug("Shift cache HIT (basic) key={}", cacheKey);
                return Mono.just(cached);
              } catch (Exception e) {
                log.warn("Shift cache deserialize error key={}, falling back to DB", cacheKey, e);
                return Mono.<ShopShiftSession>empty();
              }
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    sessionRepo
                        .findActiveSession(shopId, eventId)
                        .flatMap(
                            session -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(session);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, SHIFT_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Shift cache SET error key={}", cacheKey, e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(session);
                              } catch (Exception e) {
                                log.warn(
                                    "Shift cache serialize error shopId={} eventId={}",
                                    shopId,
                                    eventId,
                                    e);
                                return Mono.just(session);
                              }
                            })));
  }

  /**
   * Returns a bulk list of {@link ActiveShiftResponse} indicating whether each requested shop has
   * an open shift. This method is used by the controller to keep the controller thin.
   */
  /**
   * Returns a bulk list of {@link ActiveShiftResponse} indicating whether each requested shop has
   * an open shift. The repository method returns only active sessions, so we map them by shopId and
   * construct the response list.
   *
   * <p>Note: It is assumed that at most one active session exists per shop. If duplicates are
   * present, the first one is used.
   */
  /**
   * Bulk active-shift lookup with per-shop cache-aside.
   *
   * <p>For each shopId: try the existing {@code shift:active:{shopId}:{eventId}} key → collect hits
   * in a map → batch-fetch misses from DB → cache DB results → build response list.
   */
  public Flux<ActiveShiftResponse> getActiveShiftDataByShopId(List<Long> shopIds, Long eventId) {
    if (shopIds == null || shopIds.isEmpty()) {
      return Flux.empty();
    }

    // Try each shopId from cache; emit singleton map on hit, empty map on miss
    return Flux.fromIterable(shopIds)
        .flatMap(
            shopId -> {
              String cacheKey = shiftCacheKey(shopId, eventId);
              return redisTemplate
                  .opsForValue()
                  .get(cacheKey)
                  .onErrorResume(e -> Mono.empty())
                  .flatMap(
                      json -> {
                        try {
                          ShopShiftSession session =
                              CACHE_MAPPER.readValue(json, ShopShiftSession.class);
                          return Mono.just(
                              (Map<Long, ShopShiftSession>)
                                  Collections.singletonMap(shopId, session));
                        } catch (Exception e) {
                          return Mono.<Map<Long, ShopShiftSession>>empty();
                        }
                      })
                  .defaultIfEmpty(Collections.emptyMap());
            })
        .collectList()
        .flatMapMany(
            partialMaps -> {
              // Merge cache hits
              Map<Long, ShopShiftSession> cacheHits = new HashMap<>();
              for (Map<Long, ShopShiftSession> m : partialMaps) {
                cacheHits.putAll(m);
              }

              // Identify cache misses
              List<Long> cacheMisses =
                  shopIds.stream()
                      .filter(id -> !cacheHits.containsKey(id))
                      .collect(Collectors.toList());

              if (cacheMisses.isEmpty()) {
                return buildBulkActiveShiftResponse(shopIds, cacheHits);
              }

              // Fetch misses from DB, cache them, then build full response
              return sessionRepo
                  .findActiveShiftsBulk(cacheMisses, eventId)
                  .collectList()
                  .flatMapMany(
                      dbSessions -> {
                        Map<Long, ShopShiftSession> dbMap =
                            dbSessions.stream()
                                .collect(
                                    Collectors.toMap(
                                        ShopShiftSession::getShopId,
                                        Function.identity(),
                                        (a, b) -> a));

                        // Cache each DB result under shift:active:{shopId}:{eventId}
                        Mono<Void> cacheOps =
                            Flux.fromIterable(dbMap.entrySet())
                                .flatMap(
                                    e -> {
                                      try {
                                        String json = CACHE_MAPPER.writeValueAsString(e.getValue());
                                        return redisTemplate
                                            .opsForValue()
                                            .set(
                                                shiftCacheKey(e.getKey(), eventId), json, SHIFT_TTL)
                                            .onErrorResume(err -> Mono.empty());
                                      } catch (Exception ex) {
                                        return Mono.empty();
                                      }
                                    })
                                .then();

                        Map<Long, ShopShiftSession> allHits = new HashMap<>(cacheHits);
                        allHits.putAll(dbMap);

                        return cacheOps.thenMany(buildBulkActiveShiftResponse(shopIds, allHits));
                      });
            })
        .onErrorResume(
            e -> {
              log.error("Error fetching bulk active shifts", e);
              return Flux.empty();
            });
  }

  private Flux<ActiveShiftResponse> buildBulkActiveShiftResponse(
      List<Long> shopIds, Map<Long, ShopShiftSession> activeMap) {
    List<ActiveShiftResponse> responses = new ArrayList<>();
    for (Long shopId : shopIds) {
      ShopShiftSession shift = activeMap.get(shopId);
      if (shift != null) {
        responses.add(new ActiveShiftResponse(shopId, true, shift));
      } else {
        responses.add(new ActiveShiftResponse(shopId, false));
      }
    }
    return Flux.fromIterable(responses);
  }

  public Flux<ShopShiftSession> getSessionHistory(Long shopId, Long eventId, int page, int size) {
    String cacheKey = shiftHistoryCacheKey(shopId, eventId);

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn(
                  "Shift history cache GET error shopId={} eventId={}, falling back to DB",
                  shopId,
                  eventId,
                  e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ShopShiftSession> cached =
                    CACHE_MAPPER.readValue(
                        json,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            List<ShopShiftSession>>() {});
                log.debug(
                    "Shift history cache HIT shopId={} eventId={} total={}",
                    shopId,
                    eventId,
                    cached.size());
                int offset = page * size;
                int end = Math.min(offset + size, cached.size());
                if (offset >= cached.size()) return Flux.empty();
                return Flux.fromIterable(cached.subList(offset, end));
              } catch (Exception e) {
                log.warn("Shift history cache deserialize error, falling back to DB", e);
                return Flux.<ShopShiftSession>empty();
              }
            })
        .switchIfEmpty(
            sessionRepo
                .findSessionHistory(shopId, eventId)
                .flatMap(this::populateLiveExpectedCash)
                .collectList()
                .flatMapMany(
                    all -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(all);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, SHIFT_HISTORY_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("Shift history cache SET error shopId={}", shopId, e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(all).skip((long) page * size).take(size));
                      } catch (Exception e) {
                        log.warn("Shift history cache serialize error shopId={}", shopId, e);
                        int offset = page * size;
                        int end = Math.min(offset + size, all.size());
                        return offset < all.size()
                            ? Flux.fromIterable(all.subList(offset, end))
                            : Flux.empty();
                      }
                    }));
  }

  /**
   * Fetches shift history for multiple shops in a single call.
   *
   * <p>For each shopId: tries the existing {@code shift:history:{shopId}:{eventId}} Redis key
   * first. Cache misses are batch-fetched from DB with a single {@code IN (:shopIds)} query, then
   * cached per shop. Returns a merged flat {@link Flux} of all sessions across all requested shops.
   */
  public Flux<ShopShiftSession> getSessionHistoryBulk(List<Long> shopIds, Long eventId) {
    if (shopIds == null || shopIds.isEmpty()) return Flux.empty();

    // Probe per-shop cache keys concurrently; collect hit map
    return Flux.fromIterable(shopIds)
        .flatMap(
            shopId -> {
              String key = shiftHistoryCacheKey(shopId, eventId);
              return redisTemplate
                  .opsForValue()
                  .get(key)
                  .onErrorResume(e -> Mono.empty())
                  .flatMap(
                      json -> {
                        try {
                          List<ShopShiftSession> list =
                              CACHE_MAPPER.readValue(
                                  json,
                                  new com.fasterxml.jackson.core.type.TypeReference<
                                      List<ShopShiftSession>>() {});
                          return Mono.just(
                              (Map<Long, List<ShopShiftSession>>)
                                  Collections.singletonMap(shopId, list));
                        } catch (Exception e) {
                          return Mono.<Map<Long, List<ShopShiftSession>>>empty();
                        }
                      })
                  .defaultIfEmpty(Collections.emptyMap());
            })
        .collectList()
        .flatMapMany(
            partialMaps -> {
              Map<Long, List<ShopShiftSession>> cacheHits = new HashMap<>();
              for (Map<Long, List<ShopShiftSession>> m : partialMaps) {
                cacheHits.putAll(m);
              }

              List<Long> misses =
                  shopIds.stream()
                      .filter(id -> !cacheHits.containsKey(id))
                      .collect(Collectors.toList());

              Flux<ShopShiftSession> cachedFlux =
                  Flux.fromIterable(cacheHits.values()).flatMap(Flux::fromIterable);

              if (misses.isEmpty()) return cachedFlux;

              Flux<ShopShiftSession> dbFlux =
                  sessionRepo
                      .findSessionHistoryBulk(misses, eventId)
                      .flatMap(this::populateLiveExpectedCash)
                      .collectList()
                      .flatMapMany(
                          all -> {
                            // Group by shopId so each shop's list can be cached individually
                            Map<Long, List<ShopShiftSession>> byShop =
                                new HashMap<>(
                                    all.stream()
                                        .collect(
                                            Collectors.groupingBy(ShopShiftSession::getShopId)));

                            // Write empty-list cache for miss shops with 0 sessions so they
                            // don't hit the DB on every subsequent bulk call
                            for (Long missedId : misses) {
                              byShop.putIfAbsent(missedId, Collections.emptyList());
                            }

                            Mono<Void> cacheOps =
                                Flux.fromIterable(byShop.entrySet())
                                    .flatMap(
                                        e -> {
                                          try {
                                            String json =
                                                CACHE_MAPPER.writeValueAsString(e.getValue());
                                            return redisTemplate
                                                .opsForValue()
                                                .set(
                                                    shiftHistoryCacheKey(e.getKey(), eventId),
                                                    json,
                                                    SHIFT_HISTORY_TTL)
                                                .onErrorResume(err -> Mono.empty());
                                          } catch (Exception ex) {
                                            return Mono.empty();
                                          }
                                        })
                                    .then();

                            return cacheOps.thenMany(Flux.fromIterable(all));
                          });

              return Flux.merge(cachedFlux, dbFlux);
            })
        .onErrorResume(
            e -> {
              log.error("getSessionHistoryBulk failed shopIds={} eventId={}", shopIds, eventId, e);
              return Flux.empty();
            });
  }

  public Flux<ShopShiftSession> getSessionsByEvent(Long eventId) {
    String cacheKey = shiftEventSummaryCacheKey(eventId);

    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn(
                  "Shift event-summary cache GET error eventId={}, falling back to DB", eventId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ShopShiftSession> cached =
                    CACHE_MAPPER.readValue(
                        json,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            List<ShopShiftSession>>() {});
                log.debug(
                    "Shift event-summary cache HIT eventId={} count={}", eventId, cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("Shift event-summary cache deserialize error, falling back to DB", e);
                return Flux.<ShopShiftSession>empty();
              }
            })
        .switchIfEmpty(
            sessionRepo
                .findByEventId(eventId)
                .flatMap(this::populateLiveExpectedCash)
                .collectList()
                .flatMapMany(
                    sessions -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(sessions);
                        return redisTemplate
                            .opsForValue()
                            .set(cacheKey, json, SHIFT_EVENT_SUMMARY_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn(
                                      "Shift event-summary cache SET error eventId={}", eventId, e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(sessions));
                      } catch (Exception e) {
                        log.warn(
                            "Shift event-summary cache serialize error eventId={}", eventId, e);
                        return Flux.fromIterable(sessions);
                      }
                    }));
  }

  /**
   * Returns the live expected-cash breakdown for an open shift. Delegates to {@link
   * #populateLiveExpectedCash} which uses SUM queries, not a hot-row column.
   */
  public Mono<ExpectedCashResponse> getExpectedCash(Long sessionId) {
    return sessionRepo
        .findById(sessionId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Shift session not found: " + sessionId)))
        .flatMap(this::populateLiveExpectedCash)
        .map(
            s ->
                new ExpectedCashResponse(
                    s.getId(),
                    s.getExpectedClosingCash(),
                    s.getExpectedClosingOnline(),
                    s.getOpeningCashBalance()));
  }

  private Mono<ShopShiftSession> populateLiveExpectedCash(ShopShiftSession session) {
    if (session == null) {
      return Mono.empty();
    }
    if (!"OPEN".equals(session.getStatus())) {
      return Mono.just(session);
    }
    Mono<BigDecimal> cashSalesMono =
        paymentRepo
            .sumCashPaymentsBySession(session.getId())
            .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
            .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    Mono<BigDecimal> cashRefundsMono =
        sessionRepo
            .sumCashRefundsBySession(session.getId())
            .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
            .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    Mono<BigDecimal> onlineSalesMono =
        paymentRepo
            .sumOnlinePaymentsBySession(session.getId())
            .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
            .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    Mono<BigDecimal> onlineRefundsMono =
        sessionRepo
            .sumOnlineRefundsBySession(session.getId())
            .map(amount -> amount.setScale(2, RoundingMode.HALF_UP))
            .defaultIfEmpty(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    Mono<BigDecimal> cashAdditionsMono =
        denominationRepo
            .findByShiftSessionId(session.getId())
            .filter(d -> "ADDITION".equals(d.getEntryType()))
            .map(d -> BigDecimal.valueOf((long) d.getCurrencyValue() * d.getNoteCount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .map(amount -> amount.setScale(2, RoundingMode.HALF_UP));

    return Mono.zip(
            cashSalesMono, cashRefundsMono, onlineSalesMono, onlineRefundsMono, cashAdditionsMono)
        .map(
            tuple -> {
              BigDecimal cashSales = tuple.getT1();
              BigDecimal cashRefunds = tuple.getT2();
              BigDecimal onlineSales = tuple.getT3();
              BigDecimal onlineRefunds = tuple.getT4();
              BigDecimal cashAdditions = tuple.getT5();

              BigDecimal expectedCash =
                  session
                      .getOpeningCashBalance()
                      .add(cashSales)
                      .subtract(cashRefunds)
                      .add(cashAdditions)
                      .setScale(2, RoundingMode.HALF_UP);

              BigDecimal expectedOnline =
                  onlineSales.subtract(onlineRefunds).setScale(2, RoundingMode.HALF_UP);

              session.setExpectedClosingCash(expectedCash);
              session.setExpectedClosingOnline(expectedOnline);
              return session;
            });
  }

  @Transactional
  public Mono<ShopShiftSession> addCash(
      Long sessionId, BigDecimal amount, List<ShopShiftDenomination> denominations, Long userId) {
    return sessionRepo
        .findById(sessionId)
        .switchIfEmpty(
            Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift session not found!")))
        .flatMap(
            session -> {
              if (!"OPEN".equals(session.getStatus())) {
                return Mono.error(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Shift session is not OPEN!"));
              }

              LocalDateTime now = LocalDateTime.now();
              List<ShopShiftDenomination> mapped =
                  denominations.stream()
                      .peek(d -> d.setShiftSessionId(sessionId))
                      .peek(d -> d.setEntryType("ADDITION"))
                      .peek(d -> d.setCreatedAt(now))
                      .toList();

              // addCash only inserts denominations — shop_shift_session row is unchanged.
              // populateLiveExpectedCash always re-runs the SUM queries, so cash figures
              // are always current without needing to invalidate the cached session.
              return denominationRepo.saveAll(mapped).then(populateLiveExpectedCash(session));
            });
  }

  public Flux<ShopShiftDenomination> getDenominations(Long sessionId) {
    return denominationRepo.findByShiftSessionId(sessionId);
  }
}
