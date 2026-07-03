package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.dto.ActiveShiftResponse;
import com.vy.sales.sales_service.dto.AddCashRequest;
import com.vy.sales.sales_service.dto.CloseShiftRequest;
import com.vy.sales.sales_service.dto.ExpectedCashResponse;
import com.vy.sales.sales_service.dto.OpenShiftRequest;
import com.vy.sales.sales_service.dto.ReconcileRequest;
import com.vy.sales.sales_service.model.ShopShiftDenomination;
import com.vy.sales.sales_service.model.ShopShiftSession;
import com.vy.sales.sales_service.service.ShopShiftSessionService;
import com.vy.sales.sales_service.util.AppConstants;
import com.vy.sales.platform.security.JwtUtil;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/sales-svc/shifts")
@RequiredArgsConstructor
public class ShopShiftSessionController {

  private final ShopShiftSessionService shiftService;
  private final JwtUtil jwtUtil;

  @PostMapping("/open")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<ShopShiftSession> openShift(
      @RequestBody OpenShiftRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    return Mono.fromCallable(
            () -> {
              String token =
                  authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
              return jwtUtil.extractUserId(token);
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            userId -> {
              log.info(
                  "Request to open shift for shopId={} eventId={} userId={}",
                  request.getShopId(),
                  eventId,
                  userId);

              List<ShopShiftDenomination> mappedDenoms = new ArrayList<>();
              if (request.getDenominations() != null) {
                mappedDenoms =
                    request.getDenominations().stream()
                        .map(
                            d ->
                                ShopShiftDenomination.builder()
                                    .currencyValue(d.getCurrencyValue())
                                    .noteCount(d.getNoteCount())
                                    .build())
                        .toList();
              }

              return shiftService
                  .openSession(
                      request.getShopId(), eventId, request.getOpeningCash(), mappedDenoms, userId)
                  .doOnSuccess(
                      session -> log.info("Shift opened successfully: {}", session.getId()))
                  .doOnError(err -> log.error("Error opening shift: {}", err.getMessage()));
            });
  }

  @PostMapping("/{id}/close")
  @ResponseStatus(HttpStatus.OK)
  public Mono<ShopShiftSession> closeShift(
      @PathVariable Long id,
      @RequestBody CloseShiftRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

    return Mono.fromCallable(
            () -> {
              String token =
                  authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
              return jwtUtil.extractUserId(token);
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            userId -> {
              log.info("Request to close shift session id={} userId={}", id, userId);

              List<ShopShiftDenomination> mappedDenoms = new ArrayList<>();
              if (request.getDenominations() != null) {
                mappedDenoms =
                    request.getDenominations().stream()
                        .map(
                            d ->
                                ShopShiftDenomination.builder()
                                    .currencyValue(d.getCurrencyValue())
                                    .noteCount(d.getNoteCount())
                                    .build())
                        .toList();
              }

              return shiftService
                  .closeSession(
                      id,
                      request.getActualClosingCash(),
                      request.getActualClosingOnline(),
                      mappedDenoms,
                      userId)
                  .doOnSuccess(session -> log.info("Shift closed successfully: {}", id))
                  .doOnError(
                      err -> log.error("Error closing shift id={}: {}", id, err.getMessage()));
            });
  }

  @PostMapping("/{id}/add-cash")
  @ResponseStatus(HttpStatus.OK)
  public Mono<ShopShiftSession> addCash(
      @PathVariable Long id,
      @RequestBody AddCashRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

    return Mono.fromCallable(
            () -> {
              String token =
                  authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
              return jwtUtil.extractUserId(token);
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            userId -> {
              log.info(
                  "Request to add cash to shift session id={} userId={} amount={}",
                  id,
                  userId,
                  request.getAmount());

              List<ShopShiftDenomination> mappedDenoms = new ArrayList<>();
              if (request.getDenominations() != null) {
                mappedDenoms =
                    request.getDenominations().stream()
                        .map(
                            d ->
                                ShopShiftDenomination.builder()
                                    .currencyValue(d.getCurrencyValue())
                                    .noteCount(d.getNoteCount())
                                    .build())
                        .toList();
              }

              return shiftService
                  .addCash(id, request.getAmount(), mappedDenoms, userId)
                  .doOnSuccess(session -> log.info("Cash added successfully to shift id={}", id))
                  .doOnError(
                      err ->
                          log.error("Error adding cash to shift id={}: {}", id, err.getMessage()));
            });
  }

  /**
   * Returns live expected cash and online balances for an open shift. Computed from SUM queries on
   * sales/returns — not a stale hot-row column. Frontend can call this on demand (refresh button)
   * without any write-side contention.
   */
  @GetMapping("/{id}/expected-cash")
  public Mono<ExpectedCashResponse> getExpectedCash(@PathVariable Long id) {
    log.info("Fetching expected cash for shift session id={}", id);
    return shiftService.getExpectedCash(id);
  }

  @GetMapping("/active/{shopId}")
  public Mono<ShopShiftSession> getActiveShift(
      @PathVariable Long shopId,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Fetching active shift for shopId={} eventId={}", shopId, eventId);
    return shiftService.getActiveSession(shopId, eventId);
  }

  @GetMapping("/active/bulk")
  public reactor.core.publisher.Flux<ActiveShiftResponse> getActiveShiftDataByShopId(
      @RequestParam List<Long> shopIds,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Fetching active shifts in bulk for shopIds={} eventId={}", shopIds, eventId);
    return shiftService.getActiveShiftDataByShopId(shopIds, eventId);
  }

  @PostMapping("/{id}/reconcile")
  @ResponseStatus(HttpStatus.OK)
  public Mono<ShopShiftSession> reconcileShift(
      @PathVariable Long id,
      @RequestBody(required = false) ReconcileRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

    record TokenData(List<String> roles, Long userId) {}

    return Mono.fromCallable(
            () -> {
              String token =
                  authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
              return new TokenData(jwtUtil.extractRoles(token), jwtUtil.extractUserId(token));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            tokenData -> {
              List<String> roles = tokenData.roles();
              Long userId = tokenData.userId();

              boolean isCashierOrBilling =
                  roles.contains("CASHIER") || roles.contains("BILLING_OPERATOR");
              boolean hasAccess =
                  (roles.contains("ADMIN") || roles.contains("SHOP_SUPERVISOR"))
                      && !isCashierOrBilling;
              if (!hasAccess) {
                log.warn("Unauthorized request to reconcile shift id={} by roles={}", id, roles);
                return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only admins and shop supervisors can reconcile shifts!"));
              }

              String comment =
                  (request != null && request.getComment() != null) ? request.getComment() : "";
              log.info(
                  "Request to reconcile shift session id={} by userId={} with comment={}",
                  id,
                  userId,
                  comment);
              return shiftService
                  .reconcileSession(id, userId, comment)
                  .doOnSuccess(session -> log.info("Shift reconciled successfully: {}", id))
                  .doOnError(
                      err -> log.error("Error reconciling shift id={}: {}", id, err.getMessage()));
            });
  }

  @GetMapping("/{id}/denominations")
  public reactor.core.publisher.Flux<ShopShiftDenomination> getShiftDenominations(
      @PathVariable Long id) {
    log.info("Fetching denominations for shift session id={}", id);
    return shiftService.getDenominations(id);
  }

  /**
   * Bulk history — returns all shift sessions across the requested shops in one call.
   *
   * <p>Must be declared before {@code /history/{shopId}} so Spring's literal-path-wins rule routes
   * {@code /history/bulk} here and not to the per-shop endpoint.
   *
   * @param shopIds comma-separated list passed as repeated query params, e.g. {@code
   *     ?shopIds=1&shopIds=2} or {@code ?shopIds=1,2}
   */
  @GetMapping("/history/bulk")
  public reactor.core.publisher.Flux<ShopShiftSession> getShiftHistoryBulk(
      @RequestParam List<Long> shopIds,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Fetching bulk shift history shopIds={} eventId={}", shopIds, eventId);
    return shiftService.getSessionHistoryBulk(shopIds, eventId);
  }

  @GetMapping("/history/{shopId}")
  public reactor.core.publisher.Flux<ShopShiftSession> getShiftHistory(
      @PathVariable Long shopId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info(
        "Fetching shift history for shopId={} eventId={} page={} size={}",
        shopId,
        eventId,
        page,
        size);
    return shiftService.getSessionHistory(shopId, eventId, page, size);
  }

  @GetMapping("/event-summary")
  public reactor.core.publisher.Flux<ShopShiftSession> getEventShiftSummary(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info("Fetching all shift sessions for eventId={}", eventId);
    return shiftService.getSessionsByEvent(eventId);
  }
}
