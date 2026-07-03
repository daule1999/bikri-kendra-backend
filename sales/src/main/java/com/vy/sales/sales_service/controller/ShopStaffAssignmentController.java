package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.dto.AssignStaffRequest;
import com.vy.sales.sales_service.dto.AssignStaffResponse;
import com.vy.sales.sales_service.model.ShopStaffAssignment;
import com.vy.sales.sales_service.service.ShopStaffAssignmentService;
import com.vy.sales.sales_service.util.AppConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/sales-svc/shops-staff")
@RequiredArgsConstructor
@Slf4j
public class ShopStaffAssignmentController {

  private final ShopStaffAssignmentService service;

  @PostMapping("/assign")
  public Mono<ResponseEntity<AssignStaffResponse>> assign(
      @RequestBody AssignStaffRequest req, @RequestHeader("Authorization") String authHeader) {

    log.info("SHOP_STAFF_ASSIGN_REQUEST shopId={} userId={}", req.getShopId(), req.getUserId());

    return service
        .assign(req, authHeader)
        .doOnSuccess(
            res ->
                log.info(
                    "SHOP_STAFF_ASSIGN_SUCCESS shopId={} userId={} role={}",
                    res.getShopId(),
                    res.getUserId(),
                    res.getRoleCode()))
        .map(ResponseEntity::ok)
        .onErrorResume(
            ex -> {
              log.warn(
                  "SHOP_STAFF_ASSIGN_FAILED shopId={} userId={} reason={}",
                  req.getShopId(),
                  req.getUserId(),
                  ex.getMessage());

              return Mono.just(
                  ResponseEntity.status(409)
                      .body(
                          AssignStaffResponse.builder()
                              .shopId(req.getShopId())
                              .userId(req.getUserId())
                              .message(ex.getMessage())
                              .build()));
            });
  }

  @GetMapping("/{shopId}")
  public Flux<ShopStaffAssignment> getStaff(@PathVariable Long shopId) {

    log.info("SHOP_STAFF_FETCH_REQUEST shopId={}", shopId);

    return service
        .getActiveStaff(shopId)
        .doOnComplete(() -> log.info("SHOP_STAFF_FETCH_SUCCESS shopId={}", shopId))
        .doOnError(
            ex ->
                log.error(
                    "SHOP_STAFF_FETCH_FAILED shopId={} reason={}", shopId, ex.getMessage(), ex));
  }

  @GetMapping("/user/{userId}")
  public Mono<ResponseEntity<List<ShopStaffAssignment>>> getShopDetails(
      @PathVariable Long userId,
      @RequestHeader(name = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    log.info("SHOP_STAFF_FETCH_REQUEST userId={} eventId={}", userId, eventId);

    Flux<ShopStaffAssignment> assignmentFlux =
        (eventId != null)
            ? service.getShopsByUserIdAndEventId(userId, eventId)
            : service.getShopsByUserId(userId);

    return assignmentFlux
        .map(
            a ->
                ShopStaffAssignment.builder()
                    .userId(a.getUserId())
                    .shopId(a.getShopId())
                    .eventId(a.getEventId())
                    .roleCode(a.getRoleCode())
                    .assignedAt(a.getAssignedAt())
                    .isActive(a.getIsActive())
                    .build())
        .collectList()
        .map(
            list -> {
              if (list.isEmpty()) {
                ResponseEntity<List<ShopStaffAssignment>> response =
                    ResponseEntity.notFound().build();
                return response;
              }
              ResponseEntity<List<ShopStaffAssignment>> response = ResponseEntity.ok(list);
              return response;
            })
        .doOnSuccess(
            r -> log.info("GET_ASSIGNMENT_BY_USER_SUCCESS userId={} eventId={}", userId, eventId))
        .onErrorResume(
            ex -> {
              ResponseEntity<List<ShopStaffAssignment>> response =
                  ResponseEntity.notFound().build();
              return Mono.just(response);
            });
  }

  @DeleteMapping("/user/{userId}")
  public Mono<ResponseEntity<Void>> unassignUser(@PathVariable Long userId) {
    log.info("SHOP_STAFF_UNASSIGN_ALL_REQUEST userId={}", userId);
    return service
        .unassignUserFromAllShops(userId)
        .doOnSuccess(v -> log.info("SHOP_STAFF_UNASSIGN_ALL_SUCCESS userId={}", userId))
        .doOnError(
            ex ->
                log.error(
                    "SHOP_STAFF_UNASSIGN_ALL_FAILED userId={} reason={}",
                    userId,
                    ex.getMessage(),
                    ex))
        .thenReturn(ResponseEntity.ok().build());
  }

  @DeleteMapping("/{shopId}/{role}")
  public Mono<ResponseEntity<Void>> remove(@PathVariable Long shopId, @PathVariable String role) {

    log.info("SHOP_STAFF_REMOVE_REQUEST shopId={} role={}", shopId, role);

    return service
        .remove(shopId, role)
        .doOnSuccess(v -> log.info("SHOP_STAFF_REMOVE_SUCCESS shopId={} role={}", shopId, role))
        .doOnError(
            ex ->
                log.error(
                    "SHOP_STAFF_REMOVE_FAILED shopId={} role={} reason={}",
                    shopId,
                    role,
                    ex.getMessage(),
                    ex))
        .thenReturn(ResponseEntity.ok().build());
  }

  @GetMapping("/allusers")
  public Flux<ShopStaffAssignment> getAllActiveAssignments() {
    log.info("SHOP_STAFF_FETCH_ALL_REQUEST");
    return service
        .getAllActiveAssignments()
        .doOnComplete(() -> log.info("SHOP_STAFF_FETCH_ALL_SUCCESS"))
        .doOnError(ex -> log.error("SHOP_STAFF_FETCH_ALL_FAILED", ex));
  }
}
