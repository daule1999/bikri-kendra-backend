package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.dto.EventRequest;
import com.vy.sales.sales_service.dto.EventResponse;
import com.vy.sales.sales_service.service.EventService;
import com.vy.sales.platform.security.JwtUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/sales-svc/events")
@RequiredArgsConstructor
public class EventController {

  private final EventService service;
  private final JwtUtil jwtUtil;

  @PostMapping
  public Mono<EventResponse> create(@RequestBody EventRequest request) {
    log.info("EVENT_CREATE_REQUEST name={}", request.getEventName());
    return service
        .create(request)
        .doOnSuccess(
            e -> log.info("EVENT_CREATE_SUCCESS id={} name={}", e.getId(), e.getEventName()))
        .doOnError(
            ex ->
                log.error(
                    "EVENT_CREATE_FAILED name={} reason={}",
                    request.getEventName(),
                    ex.getMessage(),
                    ex));
  }

  @GetMapping("/exists")
  public Mono<Boolean> hasAnyEvent() {
    log.info("EVENT_EXISTS_CHECK_REQUEST");
    return service
        .hasAnyEvent()
        .doOnSuccess(exists -> log.info("EVENT_EXISTS_CHECK_SUCCESS exists={}", exists))
        .doOnError(ex -> log.error("EVENT_EXISTS_CHECK_FAILED reason={}", ex.getMessage(), ex));
  }

  @GetMapping
  public Flux<EventResponse> getAll(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    record JwtUserData(Long userId, List<String> roles, String username) {}

    return Mono.fromCallable(
            () -> {
              String token = authHeader.substring(7);
              return new JwtUserData(
                  jwtUtil.extractUserId(token),
                  jwtUtil.extractRoles(token),
                  jwtUtil.extractUsername(token));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMapMany(
            userData -> {
              log.debug(
                  "EVENT_GET_ALL_REQUEST userId={} roles={} username={}",
                  userData.userId(),
                  userData.roles(),
                  userData.username());

              return service
                  .getAll(userData.userId(), userData.roles(), userData.username(), authHeader)
                  .doOnComplete(
                      () -> log.debug("EVENT_GET_ALL_SUCCESS userId={}", userData.userId()))
                  .doOnError(
                      ex ->
                          log.error(
                              "EVENT_GET_ALL_FAILED userId={} reason={}",
                              userData.userId(),
                              ex.getMessage(),
                              ex));
            });
  }

  @GetMapping("/{id}")
  public Mono<EventResponse> getById(@PathVariable Long id) {
    log.debug("EVENT_GET_REQUEST id={}", id);
    return service
        .getById(id)
        .doOnSuccess(e -> log.debug("EVENT_GET_SUCCESS id={}", id))
        .doOnError(ex -> log.error("EVENT_GET_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  @PutMapping("/{id}")
  public Mono<EventResponse> update(@PathVariable Long id, @RequestBody EventRequest request) {
    log.info("EVENT_UPDATE_REQUEST id={} name={}", id, request.getEventName());
    return service
        .update(id, request)
        .doOnSuccess(e -> log.info("EVENT_UPDATE_SUCCESS id={}", id))
        .doOnError(ex -> log.error("EVENT_UPDATE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }

  @DeleteMapping("/{id}")
  public Mono<Void> delete(@PathVariable Long id) {
    log.info("EVENT_DELETE_REQUEST id={}", id);
    return service
        .delete(id)
        .doOnSuccess(v -> log.info("EVENT_DELETE_SUCCESS id={}", id))
        .doOnError(ex -> log.error("EVENT_DELETE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }
}
