package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.dto.BillingReturnRequest;
import com.vy.sales.sales_service.dto.CompleteSaleRequest;
import com.vy.sales.sales_service.dto.CompleteSaleResponse;
import com.vy.sales.sales_service.dto.ConfirmSaleRequest;
import com.vy.sales.sales_service.dto.CreateSaleRequest;
import com.vy.sales.sales_service.dto.ProductSalesSummaryDTO;
import com.vy.sales.sales_service.dto.ProductShopSalesDTO;
import com.vy.sales.sales_service.dto.ShopStockResponse;
import com.vy.sales.sales_service.model.SalesOrder;
import com.vy.sales.sales_service.model.SalesPayment;
import com.vy.sales.sales_service.service.InvoiceSequenceService;
import com.vy.sales.sales_service.service.SalesService;
import com.vy.sales.sales_service.service.ShopShiftSessionService;
import com.vy.sales.sales_service.util.AppConstants;
import com.vy.sales.platform.security.JwtUtil;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/sales-svc/retail")
@RequiredArgsConstructor
@Slf4j
public class SalesController {

  private final SalesService salesService;
  private final InvoiceSequenceService invoiceSequenceService;
  private final ShopShiftSessionService shopShiftSessionService;
  private final JwtUtil jwtUtil;

  /** Create a new sale */
  @PostMapping
  public Mono<SalesOrder> createSale(
      @RequestBody CreateSaleRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    record JwtUserData(String username, Long sellerId, List<String> roles) {}

    return Mono.fromCallable(
            () -> {
              String token = authHeader.substring(7);
              return new JwtUserData(
                  jwtUtil.extractUsername(token),
                  jwtUtil.extractUserId(token),
                  jwtUtil.extractRoles(token));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(
            userData -> {
              log.info(
                  "SALE_CREATE_REQUEST seller={} sellerId={} eventId={} shopId={} items={}",
                  userData.username(),
                  userData.sellerId(),
                  eventId,
                  request.getShopId(),
                  request.getItems() != null ? request.getItems().size() : 0);

              return salesService
                  .createSale(
                      request, userData.username(), userData.sellerId(), eventId, userData.roles())
                  .doOnSuccess(
                      order ->
                          log.info(
                              "SALE_CREATE_SUCCESS orderNumber={} sellerId={} eventId={} subtotal={}",
                              order.getOrderNumber(),
                              userData.sellerId(),
                              eventId,
                              order.getOrderSubtotal()));
            })
        .doOnError(ex -> log.error("SALE_CREATE_FAILED reason={}", ex.getMessage(), ex));
  }

  /**
   * Idempotent single-step create + confirm endpoint.
   *
   * <p>Returns a {@link CompleteSaleResponse} that bundles the confirmed order, updated shop stock
   * levels, and the next invoice number preview — eliminating three separate follow-up calls the
   * frontend used to make after every checkout.
   *
   * <p>The client may optionally include a pre-generated {@code orderNumber} in the body (e.g.
   * recovered from {@code sessionStorage}) to enable safe retries without double-billing. If the
   * order already exists and is CONFIRMED it is returned as-is.
   */
  @PostMapping("/complete")
  public Mono<CompleteSaleResponse> completeSale(
      @Valid @RequestBody CompleteSaleRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    String token = authHeader.substring(7);
    String username = jwtUtil.extractUsername(token);
    Long sellerId = jwtUtil.extractUserId(token);
    List<String> roles = jwtUtil.extractRoles(token);
    log.info(
        "SALE_COMPLETE_REQUEST seller={} eventId={} shopId={} paymentMode={}",
        username,
        eventId,
        request.getShopId(),
        request.getPaymentMode());

    final Long shopIdLong;
    try {
      shopIdLong = Long.valueOf(request.getShopId());
    } catch (NumberFormatException e) {
      return Mono.error(
          new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid shop ID format"));
    }

    return salesService
        .completeSale(request, username, sellerId, eventId, roles, token)
        .flatMap(
            order -> {
              // Fetch updated stocks and peek next invoice in parallel — no extra round-trips.
              // Timeout after 500 ms: inventory-service can be slow (2 HTTP calls + 2 DB queries
              // inside getShopStocks). If it exceeds the budget the checkout still returns fast
              // and the frontend fallback calls handleRefreshStock() instead.
              Mono<List<ShopStockResponse>> stocksMono =
                  salesService
                      .getShopStocks(shopIdLong, eventId, authHeader)
                      .collectList()
                      .timeout(Duration.ofSeconds(3))
                      .onErrorReturn(List.of());

              // Use getActiveSessionBasic (no live cash SUM queries) — we only need
              // session.getId().
              Mono<String> nextInvMono =
                  shopShiftSessionService
                      .getActiveSessionBasic(shopIdLong, eventId)
                      .flatMap(
                          session ->
                              invoiceSequenceService.peekNextFormatted(
                                  shopIdLong, eventId, session.getId()))
                      .onErrorReturn("");

              return Mono.zip(stocksMono, nextInvMono)
                  .map(
                      tuple -> {
                        CompleteSaleResponse resp = new CompleteSaleResponse();
                        resp.setOrder(order);
                        resp.setStocks(tuple.getT1());
                        resp.setNextInvoiceNumber(tuple.getT2());
                        // Echo payment fields from request — SalesOrder has no payment columns
                        // (payment lives in sales_payment table). Frontend needs these immediately
                        // at print time without a second round-trip.
                        resp.setPaymentMode(request.getPaymentMode());
                        resp.setPaymentReference(request.getPaymentReference());
                        resp.setCashAmount(request.getCashAmount());
                        resp.setOnlineAmount(request.getOnlineAmount());
                        resp.setCashReceived(request.getCashReceived());
                        resp.setChangeGiven(request.getChangeGiven());
                        return resp;
                      });
            });
  }

  /** Confirm an existing sale */
  @PutMapping("/{orderNumber}/confirm")
  public Mono<SalesOrder> confirmSale(
      @PathVariable String orderNumber,
      @Valid @RequestBody ConfirmSaleRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    log.info("SALE_CONFIRM_REQUEST orderNumber={}", orderNumber);
    return salesService
        .confirmSale(orderNumber, request, authHeader.substring(7))
        .doOnSuccess(
            order ->
                log.info(
                    "SALE_CONFIRM_SUCCESS orderNumber={} status={}",
                    order.getOrderNumber(),
                    order.getStatus()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_CONFIRM_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  /** Get sale by order number */
  @GetMapping("/{orderNumber}")
  public Mono<SalesOrder> getSaleByOrderNumber(@PathVariable String orderNumber) {
    log.debug("SALE_GET_REQUEST orderNumber={}", orderNumber);
    return salesService
        .getByOrderNumber(orderNumber)
        .doOnSuccess(order -> log.debug("SALE_GET_SUCCESS orderNumber={}", orderNumber))
        .doOnError(
            ex ->
                log.error(
                    "SALE_GET_FAILED orderNumber={} reason={}", orderNumber, ex.getMessage(), ex));
  }

  /** List all sales for a seller */
  @GetMapping("/seller/{sellerId}")
  public Flux<SalesOrder> getSalesBySeller(@PathVariable Long sellerId) {
    log.debug("SALE_GET_BY_SELLER_REQUEST sellerId={}", sellerId);
    return salesService
        .getSalesBySeller(sellerId)
        .doOnComplete(() -> log.debug("SALE_GET_BY_SELLER_SUCCESS sellerId={}", sellerId))
        .doOnError(
            ex ->
                log.error(
                    "SALE_GET_BY_SELLER_FAILED sellerId={} reason={}",
                    sellerId,
                    ex.getMessage(),
                    ex));
  }

  @GetMapping("/all")
  public Mono<java.util.Map<String, Object>> getAllSales(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(value = "orderNumbers", required = false) java.util.List<String> orderNumbers) {
    log.info("SALE_GET_ALL_REQUEST eventId={} page={} size={} orderNumbers={}", eventId, page, size,
        orderNumbers != null ? orderNumbers.size() : 0);
    // When orderNumbers is provided, this is a targeted lookup (enrich-mode) — skip pagination.
    if (orderNumbers != null && !orderNumbers.isEmpty()) {
      return salesService.getByOrderNumbers(orderNumbers)
          .collectList()
          .map(list -> {
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("content", list);
            result.put("page", 0);
            result.put("size", list.size());
            result.put("totalElements", (long) list.size());
            result.put("totalPages", 1);
            return result;
          });
    }
    return salesService.getAllSalesPaged(eventId, page, size);
  }

  /** Cancel a sale (optional but useful) */
  @PutMapping("/{orderNumber}/cancel")
  public Mono<SalesOrder> cancelSale(
      @PathVariable String orderNumber,
      @RequestParam(required = false) String reason,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    log.info("SALE_CANCEL_REQUEST orderNumber={} reason={}", orderNumber, reason);
    return salesService
        .cancelSale(orderNumber, reason, authHeader)
        .doOnSuccess(
            order ->
                log.info(
                    "SALE_CANCEL_SUCCESS orderNumber={} status={}",
                    order.getOrderNumber(),
                    order.getStatus()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_CANCEL_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  /** Return a sale (partial or full) */
  @PutMapping("/{orderNumber}/return")
  public Mono<SalesOrder> returnSale(
      @PathVariable String orderNumber,
      @RequestBody BillingReturnRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    log.info("SALE_RETURN_REQUEST orderNumber={} items={}", orderNumber, request.getItems().size());
    return salesService
        .returnSale(orderNumber, request, authHeader)
        .doOnSuccess(
            order ->
                log.info(
                    "SALE_RETURN_SUCCESS orderNumber={} status={}",
                    order.getOrderNumber(),
                    order.getStatus()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_RETURN_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  @GetMapping("/analytics/product-shop-sales")
  public Flux<ProductShopSalesDTO> getProductShopSales(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.debug("SALE_ANALYTICS_PRODUCT_SHOP_REQUEST eventId={}", eventId);
    return salesService
        .getProductShopSalesSummary(eventId)
        .doOnComplete(() -> log.debug("SALE_ANALYTICS_PRODUCT_SHOP_SUCCESS"))
        .doOnError(
            ex -> log.error("SALE_ANALYTICS_PRODUCT_SHOP_FAILED reason={}", ex.getMessage(), ex));
  }

  @GetMapping("/analytics/shift-product-summary")
  public Flux<ProductSalesSummaryDTO> getShiftProductSummary(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestParam(required = false) Long shopId,
      @RequestParam(required = false) Long shiftSessionId) {
    log.info(
        "API_SHIFT_PRODUCT_SUMMARY_REQUEST eventId={} shopId={} shiftId={}",
        eventId,
        shopId,
        shiftSessionId);
    return salesService.getProductSalesSummary(eventId, shopId, shiftSessionId);
  }

  @GetMapping("/stocks/{shopId}")
  public Flux<ShopStockResponse> getShopStocks(
      @PathVariable Long shopId,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    log.info("API_SHOP_STOCKS_REQUEST shopId={} eventId={}", shopId, eventId);
    return salesService.getShopStocks(shopId, eventId, authHeader);
  }

  @GetMapping("/stocks/shops")
  public Flux<ShopStockResponse> getShopsStocks(
      @RequestParam(value = "shopIds", required = true) List<String> shopIds,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
    log.info("API_SHOPS_STOCKS_REQUEST shopIds={} eventId={}", shopIds, eventId);
    return salesService.getShopsStocks(shopIds, eventId, authHeader);
  }

  @GetMapping("/{orderNumber}/payment")
  public Mono<SalesPayment> getOrderPayment(@PathVariable String orderNumber) {
    log.info("Fetching payment details for orderNumber={}", orderNumber);
    return salesService.getOrderPayment(orderNumber);
  }

  /**
   * Marks the receiving slip as printed for a given order (idempotent).
   *
   * <p>Called by the frontend immediately after {@code printReceiving()} succeeds. Prevents
   * duplicate receiving prints on history reprints by persisting the flag to the DB.
   */
  @PatchMapping("/{orderNumber}/receiving-printed")
  public Mono<SalesOrder> markReceivingPrinted(@PathVariable String orderNumber) {
    log.info("RECEIVING_PRINTED_REQUEST orderNumber={}", orderNumber);
    return salesService
        .markReceivingPrinted(orderNumber)
        .doOnSuccess(
            order ->
                log.info(
                    "RECEIVING_PRINTED_SUCCESS orderNumber={} receivingPrinted={}",
                    order.getOrderNumber(),
                    order.getReceivingPrinted()))
        .doOnError(
            ex ->
                log.error(
                    "RECEIVING_PRINTED_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  /**
   * Returns the next invoice number that WOULD be issued — read-only, counter not advanced.
   *
   * <p>This endpoint is for display only (shown in the sales page header). Using {@code
   * peekNextFormatted} here rather than {@code claimAndAdvance} prevents wasting an invoice number
   * every time the page loads or refreshes.
   */
  @GetMapping("/next-invoice")
  public Mono<java.util.Map<String, String>> getNextInvoiceNumber(
      @RequestParam Long shopId,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    // Use getActiveSessionBasic — the invoice peek only needs session.getId(), not live cash sums.
    return shopShiftSessionService
        .getActiveSessionBasic(shopId, eventId)
        .flatMap(
            session -> invoiceSequenceService.peekNextFormatted(shopId, eventId, session.getId()))
        .map(invoice -> java.util.Map.of("nextInvoiceNumber", invoice))
        .doOnError(
            ex ->
                log.error(
                    "Failed to peek next invoice number for shopId={} eventId={}",
                    shopId,
                    eventId,
                    ex));
  }
}
