package com.vy.sales.sales_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.sales_service.client.BillingClient;
import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.dto.BillingInvoiceRequest;
import com.vy.sales.sales_service.dto.BillingReturnRequest;
import com.vy.sales.sales_service.dto.CompleteSaleRequest;
import com.vy.sales.sales_service.dto.ConfirmSaleRequest;
import com.vy.sales.sales_service.dto.CreateSaleRequest;
import com.vy.sales.sales_service.dto.ProductSalesSummaryDTO;
import com.vy.sales.sales_service.dto.ProductShopSalesDTO;
import com.vy.sales.sales_service.dto.ShopStockResponse;
import com.vy.sales.sales_service.model.SaleSagaLog;
import com.vy.sales.sales_service.model.SalesOrder;
import com.vy.sales.sales_service.model.SalesOrderItem;
import com.vy.sales.sales_service.model.SalesPayment;
import com.vy.sales.sales_service.model.Shop;
import com.vy.sales.sales_service.model.ShopShiftSession;
import com.vy.sales.sales_service.repository.SagaLogRepository;
import com.vy.sales.sales_service.repository.SalesAnalyticsRepository;
import com.vy.sales.sales_service.repository.SalesOrderItemRepository;
import com.vy.sales.sales_service.repository.SalesOrderRepository;
import com.vy.sales.sales_service.repository.SalesPaymentRepository;
import com.vy.sales.sales_service.repository.ShopRepository;
import com.vy.sales.sales_service.repository.ShopShiftSessionRepository;
import com.vy.sales.sales_service.repository.ShopStaffAssignmentRepository;
import com.vy.sales.sales_service.util.IdGenerator;
import com.vy.sales.platform.security.JwtUtil;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class SalesService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String SHOP_STOCKS_KEY_PREFIX = "shop-stocks:";
  private static final Duration SHOP_STOCKS_TTL = Duration.ofMinutes(2);
  private static final String PRODUCT_SHOP_SALES_KEY_PREFIX = "analytics:product-shop-sales:event:";
  private static final Duration PRODUCT_SHOP_SALES_TTL = Duration.ofSeconds(30);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final SalesOrderRepository orderRepo;
  private final SalesOrderItemRepository itemRepo;
  private final SalesPaymentRepository paymentRepository;
  private final SalesAnalyticsRepository analyticsRepository;
  private final ShopRepository shopRepo;
  private final ShopStaffAssignmentRepository shopStaffAssignmentRepo;
  private final ShopShiftSessionRepository sessionRepo;
  private final BillingClient billingClient;
  private final InvoiceSequenceService invoiceSequenceService;
  private final InventoryClient inventoryClient;
  private final JwtUtil jwtUtil;
  private final org.springframework.r2dbc.core.DatabaseClient databaseClient;
  private final SagaLogRepository sagaLogRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  /*
   * =========================
   * CREATE SALE
   * =========================
   */
  public Mono<SalesOrder> createSale(
      CreateSaleRequest request,
      String sellerName,
      Long sellerId,
      Long eventId,
      List<String> roles) {
    log.info(
        "SALE_CREATE_REQUEST seller={} sellerId={} eventId={} shopId={} items={}",
        sellerName,
        sellerId,
        eventId,
        request.getShopId(),
        request.getItems() != null ? request.getItems().size() : 0);

    Long shopIdLong;
    try {
      shopIdLong = Long.valueOf(request.getShopId());
    } catch (NumberFormatException e) {
      return Mono.error(
          new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid shop ID format"));
    }

    Mono<Boolean> hasAccessMono =
        roles.contains("ADMIN")
            ? Mono.just(true)
            : shopStaffAssignmentRepo
                .findByUserIdAndEventIdAndIsActiveTrue(sellerId, eventId)
                .hasElements();

    return hasAccessMono
        .flatMap(
            hasAccess -> {
              if (Boolean.FALSE.equals(hasAccess)) {
                return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Access Denied: You do not have access to this event!"));
              }

              return shopRepo
                  .findById(shopIdLong)
                  .switchIfEmpty(
                      Mono.error(
                          new org.springframework.web.server.ResponseStatusException(
                              org.springframework.http.HttpStatus.NOT_FOUND, "Shop not found")))
                  .flatMap(
                      shop -> {
                        if (!shop.getEventId().equals(eventId)) {
                          return Mono.error(
                              new org.springframework.web.server.ResponseStatusException(
                                  org.springframework.http.HttpStatus.FORBIDDEN,
                                  "Access Denied: Target shop counter belongs to a different event!"));
                        }

                        BigDecimal subtotal =
                            request.getItems().stream()
                                .map(
                                    i ->
                                        i.getSellingPrice()
                                            .multiply(BigDecimal.valueOf(i.getQuantity())))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        SalesOrder order =
                            SalesOrder.builder()
                                .orderNumber("SO-" + IdGenerator.generateId())
                                .eventId(eventId) // event scope
                                .shopId(request.getShopId())
                                .sellerId(sellerId)
                                .sellerName(sellerName)
                                .customerName(request.getCustomerName())
                                .customerMobile(request.getCustomerMobile())
                                .orderSubtotal(subtotal)
                                .discountAmount(BigDecimal.ZERO)
                                .status("CREATED")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();

                        return orderRepo
                            .save(order)
                            .doOnSuccess(
                                saved ->
                                    log.info(
                                        "SALE_ORDER_SAVED orderNumber={} sellerId={} eventId={} subtotal={}",
                                        saved.getOrderNumber(),
                                        sellerId,
                                        eventId,
                                        saved.getOrderSubtotal()))
                            .flatMap(
                                savedOrder ->
                                    itemRepo
                                        .saveAll(
                                            request.getItems().stream()
                                                .map(
                                                    i ->
                                                        SalesOrderItem.builder()
                                                            .salesOrderId(savedOrder.getId())
                                                            .productId(i.getProductId())
                                                            .productName(i.getProductName())
                                                            .productSku(i.getProductSku())
                                                            .hsnCode(i.getHsnCode())
                                                            .quantity(i.getQuantity())
                                                            .mrp(i.getMrp())
                                                            .sellingPrice(i.getSellingPrice())
                                                            .discount(i.getDiscount())
                                                            .lineTotal(
                                                                i.getSellingPrice()
                                                                    .multiply(
                                                                        BigDecimal.valueOf(
                                                                            i.getQuantity())))
                                                            .createdAt(LocalDateTime.now())
                                                            .build())
                                                .toList())
                                        .then(Mono.just(savedOrder)));
                      });
            })
        .doOnSuccess(
            saved -> log.info("SALE_CREATE_SUCCESS orderNumber={}", saved.getOrderNumber()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_CREATE_FAILED seller={} shopId={} reason={}",
                    sellerName,
                    request.getShopId(),
                    ex.getMessage(),
                    ex));
  }

  /*
   * =========================
   * COMPLETE SALE (idempotent single-step create + confirm)
   * =========================
   */

  /**
   * Creates and confirms a sale in a single call.
   *
   * <p>Idempotency: if the caller supplies a {@code request.orderNumber} (e.g. recovered from
   * browser {@code sessionStorage} after a crash), the backend will attempt an {@code INSERT
   * IGNORE} so that a duplicate {@code order_number} is silently skipped. The existing order is
   * then fetched and, if already CONFIRMED, returned immediately — making the endpoint safe to
   * retry without double-charging.
   */
  public Mono<SalesOrder> completeSale(
      CompleteSaleRequest request,
      String sellerName,
      Long sellerId,
      Long eventId,
      List<String> roles,
      String token) {

    Long shopIdLong;
    try {
      shopIdLong = Long.valueOf(request.getShopId());
    } catch (NumberFormatException e) {
      return Mono.error(
          new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid shop ID format"));
    }

    Mono<Boolean> hasAccessMono =
        roles.contains("ADMIN")
            ? Mono.just(true)
            : shopStaffAssignmentRepo
                .findByUserIdAndEventIdAndIsActiveTrue(sellerId, eventId)
                .hasElements();

    return hasAccessMono.flatMap(
        hasAccess -> {
          if (Boolean.FALSE.equals(hasAccess)) {
            return Mono.error(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Access Denied: You do not have access to this event!"));
          }
          return shopRepo
              .findById(shopIdLong)
              .switchIfEmpty(
                  Mono.error(
                      new org.springframework.web.server.ResponseStatusException(
                          org.springframework.http.HttpStatus.NOT_FOUND, "Shop not found")))
              .flatMap(
                  shop -> {
                    if (!shop.getEventId().equals(eventId)) {
                      return Mono.error(
                          new org.springframework.web.server.ResponseStatusException(
                              org.springframework.http.HttpStatus.FORBIDDEN,
                              "Target shop belongs to a different event!"));
                    }

                    String orderNum =
                        (request.getOrderNumber() != null && !request.getOrderNumber().isBlank())
                            ? request.getOrderNumber()
                            : "SO-" + IdGenerator.generateId();

                    BigDecimal subtotal =
                        request.getItems().stream()
                            .map(
                                i ->
                                    i.getSellingPrice()
                                        .multiply(BigDecimal.valueOf(i.getQuantity())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // INSERT IGNORE — if the order_number already exists (retry case) the
                    // insert is silently skipped and we fetch the existing row below.
                    return databaseClient
                        .sql(
                            "INSERT IGNORE INTO sales_order "
                                + "(order_number, event_id, shop_id, seller_id, seller_name, "
                                + " customer_name, customer_mobile, order_subtotal, "
                                + " discount_amount, status, receiving_printed, created_at, updated_at) "
                                + "VALUES (:orderNumber, :eventId, :shopId, :sellerId, :sellerName, "
                                + " :customerName, :customerMobile, :subtotal, 0, "
                                + " 'CREATED', FALSE, NOW(), NOW())")
                        .bind("orderNumber", orderNum)
                        .bind("eventId", eventId)
                        .bind("shopId", request.getShopId())
                        .bind("sellerId", sellerId)
                        .bind("sellerName", sellerName)
                        .bind(
                            "customerName",
                            request.getCustomerName() != null ? request.getCustomerName() : "")
                        .bind(
                            "customerMobile",
                            request.getCustomerMobile() != null ? request.getCustomerMobile() : "")
                        .bind("subtotal", subtotal)
                        .fetch()
                        .rowsUpdated()
                        .flatMap(
                            rowsInserted ->
                                orderRepo
                                    .findByOrderNumber(orderNum)
                                    .switchIfEmpty(
                                        Mono.error(
                                            new IllegalStateException(
                                                "Order not found after INSERT IGNORE: "
                                                    + orderNum)))
                                    .flatMap(
                                        order -> {
                                          // Already CONFIRMED — idempotent return
                                          if ("CONFIRMED".equals(order.getStatus())) {
                                            log.info(
                                                "COMPLETE_SALE_IDEMPOTENT_RETURN orderNumber={}",
                                                orderNum);
                                            return Mono.just(order);
                                          }

                                          if (!"CREATED".equals(order.getStatus())) {
                                            return Mono.error(
                                                new org.springframework.web.server
                                                    .ResponseStatusException(
                                                    org.springframework.http.HttpStatus.CONFLICT,
                                                    "Order "
                                                        + orderNum
                                                        + " is in status "
                                                        + order.getStatus()
                                                        + " and cannot be confirmed"));
                                          }

                                          // rowsInserted > 0 → fresh INSERT: save items now.
                                          // rowsInserted == 0 → INSERT IGNORE skipped (retry):
                                          // items already
                                          // exist, fetch them. Either branch avoids a redundant
                                          // COUNT query.
                                          Mono<List<SalesOrderItem>> itemsMono =
                                              rowsInserted > 0
                                                  ? itemRepo
                                                      .saveAll(
                                                          request.getItems().stream()
                                                              .map(
                                                                  i ->
                                                                      SalesOrderItem.builder()
                                                                          .salesOrderId(
                                                                              order.getId())
                                                                          .productId(
                                                                              i.getProductId())
                                                                          .productName(
                                                                              i.getProductName())
                                                                          .productSku(
                                                                              i.getProductSku())
                                                                          .hsnCode(i.getHsnCode())
                                                                          .quantity(i.getQuantity())
                                                                          .mrp(i.getMrp())
                                                                          .sellingPrice(
                                                                              i.getSellingPrice())
                                                                          .discount(i.getDiscount())
                                                                          .lineTotal(
                                                                              i.getSellingPrice()
                                                                                  .multiply(
                                                                                      BigDecimal
                                                                                          .valueOf(
                                                                                              i
                                                                                                  .getQuantity())))
                                                                          .createdAt(
                                                                              LocalDateTime.now())
                                                                          .build())
                                                              .toList())
                                                      .collectList()
                                                  : itemRepo
                                                      .findBySalesOrderId(order.getId())
                                                      .collectList();

                                          // Build ConfirmSaleRequest from the combined request
                                          // fields
                                          ConfirmSaleRequest confirmReq = new ConfirmSaleRequest();
                                          confirmReq.setPaymentMode(request.getPaymentMode());
                                          confirmReq.setPaymentReference(
                                              request.getPaymentReference());
                                          confirmReq.setAmount(request.getAmount());
                                          confirmReq.setCashAmount(request.getCashAmount());
                                          confirmReq.setOnlineAmount(request.getOnlineAmount());
                                          confirmReq.setCashReceived(request.getCashReceived());
                                          confirmReq.setChangeGiven(request.getChangeGiven());

                                          return itemsMono.flatMap(
                                              items ->
                                                  confirmSaleCore(order, items, confirmReq, token));
                                        }));
                  });
        });
  }

  /*
   * =========================
   * CONFIRM SALE (public API — 2-step flow)
   * =========================
   */
  @Transactional
  public Mono<SalesOrder> confirmSale(
      String orderNumber, ConfirmSaleRequest request, String token) {
    Long userId = jwtUtil.extractUserId(token);
    log.info(
        "SALE_CONFIRM_REQUEST orderNumber={} userId={} paymentMode={}",
        orderNumber,
        userId,
        request.getPaymentMode());

    return orderRepo
        .findByOrderNumber(orderNumber)
        .switchIfEmpty(Mono.error(new IllegalStateException("Order not found")))
        .flatMap(
            order -> {
              if (!"CREATED".equals(order.getStatus())) {
                return Mono.error(new IllegalStateException("Order cannot be confirmed"));
              }
              Long shopId = Long.valueOf(order.getShopId());

              // Fetch session and items in parallel — they are independent DB reads.
              Mono<ShopShiftSession> sessionMono =
                  sessionRepo
                      .findActiveSession(shopId, order.getEventId())
                      .switchIfEmpty(
                          Mono.error(
                              new org.springframework.web.server.ResponseStatusException(
                                  org.springframework.http.HttpStatus.BAD_REQUEST,
                                  "No active shift session is currently open for this shop"
                                      + " counter. A shift session must be opened before"
                                      + " checkout.")));

              Mono<List<SalesOrderItem>> itemsMono =
                  itemRepo.findBySalesOrderId(order.getId()).collectList();

              return Mono.zip(sessionMono, itemsMono)
                  .flatMap(
                      tuple -> {
                        order.setShiftSessionId(tuple.getT1().getId());
                        return confirmSaleCore(order, tuple.getT2(), request, token);
                      });
            });
  }

  /*
   * =========================
   * CONFIRM SALE CORE (private — shared by completeSale and confirmSale)
   * =========================
   *
   * Takes an already-fetched order and items list so callers don't repeat DB reads.
   *
   * Key optimisations vs. old confirmSale:
   *  - No shopRepo.findAndLockById — the shop-level row lock was held across all HTTP calls
   *    and is redundant because CounterStockService.decrementStock does SELECT FOR UPDATE on
   *    the counter_stock row, providing the necessary isolation.
   *  - No inventoryClient.getAllIssues pre-check — the inventory service's decrementStock
   *    validates stock atomically (SELECT FOR UPDATE + liveQuantity check), so a separate
   *    read-all-issues round-trip is wasted work. Insufficient stock surfaces as an HTTP 4xx
   *    from decrementCounterStock, which is caught by the caller.
   *  - Saga compensation is scoped ONLY to errors after a confirmed successful decrement.
   *    Previously the onErrorResume was applied to the whole chain including the decrement
   *    itself, so a decrement failure would (incorrectly) trigger an increment.
   */
  private Mono<SalesOrder> confirmSaleCore(
      SalesOrder order, List<SalesOrderItem> items, ConfirmSaleRequest request, String token) {

    Long userId = jwtUtil.extractUserId(token);
    Long shopId = Long.valueOf(order.getShopId());
    String orderNumber = order.getOrderNumber();

    // Session may already be set if called from the public confirmSale path (which zips it).
    // For the completeSale path, shiftSessionId is null and we fetch it here.
    Mono<SalesOrder> withSessionMono =
        order.getShiftSessionId() != null
            ? Mono.just(order)
            : sessionRepo
                .findActiveSession(shopId, order.getEventId())
                .switchIfEmpty(
                    Mono.error(
                        new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "No active shift session is currently open for this shop counter."
                                + " A shift session must be opened before checkout.")))
                .map(
                    session -> {
                      order.setShiftSessionId(session.getId());
                      return order;
                    });

    return withSessionMono.flatMap(
        o -> {
          List<InventoryClient.CounterStockDecrementRequest> decrementRequests =
              items.stream()
                  .map(
                      item ->
                          InventoryClient.CounterStockDecrementRequest.builder()
                              .productId(item.getProductId())
                              .shopId(o.getShopId())
                              .quantity(item.getQuantity())
                              .build())
                  .toList();

          // decrementCounterStock validates stock atomically inside the inventory service
          // (SELECT FOR UPDATE + liveQuantity check). If stock is insufficient it returns
          // an HTTP error which propagates here as a WebClientResponseException — no saga
          // compensation needed since nothing was written yet.
          //
          // IMPORTANT: decrementCounterStock returns Mono<Void> (empty — no element emitted).
          // .then() fires on upstream *completion*, whereas .flatMap() fires on *element*
          // emission and would silently skip on an empty Mono. Must use .then() here.
          // Errors from decrementCounterStock propagate through .then() without subscribing
          // to the inner Mono, so the onErrorResume below only catches post-decrement errors.
          return inventoryClient
              .decrementCounterStock(decrementRequests, o.getEventId(), "Bearer " + token)
              .then(
                  // Decrement succeeded — now persist payment, invoice, billing.
                  // Compensation only runs for errors that occur AFTER this point.
                  buildPaymentAndFinalize(o, items, request, token, userId)
                      .onErrorResume(
                          err -> {
                            log.error(
                                "SALE_CONFIRM_SAGA_COMPENSATING orderNumber={} cause={}",
                                orderNumber,
                                err.getMessage());
                            return inventoryClient
                                .incrementCounterStock(
                                    decrementRequests, o.getEventId(), "Bearer " + token)
                                .doOnSuccess(
                                    v ->
                                        log.info(
                                            "SALE_CONFIRM_SAGA_INVENTORY_RESTORED"
                                                + " orderNumber={}",
                                            orderNumber))
                                .doOnError(
                                    compErr ->
                                        log.error(
                                            "SALE_CONFIRM_SAGA_COMPENSATION_FAILED"
                                                + " orderNumber={}",
                                            orderNumber,
                                            compErr))
                                .onErrorResume(
                                    compErr ->
                                        sagaLogRepository
                                            .save(
                                                SaleSagaLog.builder()
                                                    .orderNumber(orderNumber)
                                                    .sagaStep("CONFIRM_BILLING")
                                                    .compensation("INVENTORY_RESTORE")
                                                    .status("FAILED")
                                                    .errorMessage(compErr.getMessage())
                                                    .createdAt(LocalDateTime.now())
                                                    .build())
                                            .then())
                                .then(Mono.error(err));
                          }));
        });
  }

  /** Saves payment, claims invoice sequence, creates billing invoice, marks order CONFIRMED. */
  private Mono<SalesOrder> buildPaymentAndFinalize(
      SalesOrder order,
      List<SalesOrderItem> items,
      ConfirmSaleRequest request,
      String token,
      Long userId) {

    // MONEY-S1: validate the client-supplied amount against the authoritative order total
    // (subtotal − discount) so a tampered/incorrect amount cannot be persisted.
    java.math.BigDecimal expectedTotal =
        (order.getOrderSubtotal() != null ? order.getOrderSubtotal() : java.math.BigDecimal.ZERO)
            .subtract(
                order.getDiscountAmount() != null
                    ? order.getDiscountAmount()
                    : java.math.BigDecimal.ZERO);
    if (request.getAmount() == null || request.getAmount().compareTo(expectedTotal) != 0) {
      return Mono.error(
          new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "Payment amount "
                  + request.getAmount()
                  + " does not match order total "
                  + expectedTotal));
    }

    // MONEY-S1: for split (BOTH) payments, the cash + online parts must sum to the total.
    if ("BOTH".equalsIgnoreCase(request.getPaymentMode())) {
      java.math.BigDecimal cash =
          request.getCashAmount() != null ? request.getCashAmount() : java.math.BigDecimal.ZERO;
      java.math.BigDecimal online =
          request.getOnlineAmount() != null ? request.getOnlineAmount() : java.math.BigDecimal.ZERO;
      if (cash.add(online).compareTo(expectedTotal) != 0) {
        return Mono.error(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Split payment cash+online ("
                    + cash.add(online)
                    + ") must equal total "
                    + expectedTotal));
      }
    }

    java.math.BigDecimal cashAmt = request.getCashAmount();
    java.math.BigDecimal onlineAmt = request.getOnlineAmount();
    String pMode = request.getPaymentMode();
    if (pMode != null) {
      if ("CASH".equalsIgnoreCase(pMode)) {
        cashAmt = request.getAmount();
        onlineAmt = java.math.BigDecimal.ZERO;
      } else if ("BOTH".equalsIgnoreCase(pMode)) {
        if (cashAmt == null) cashAmt = java.math.BigDecimal.ZERO;
        if (onlineAmt == null) onlineAmt = java.math.BigDecimal.ZERO;
      } else {
        cashAmt = java.math.BigDecimal.ZERO;
        onlineAmt = request.getAmount();
      }
    }

    // Persist cashReceived / changeGiven so invoices can show "Received ₹500 / Change ₹50"
    java.math.BigDecimal cashReceived = request.getCashReceived();
    java.math.BigDecimal changeGiven = request.getChangeGiven();
    if ("CASH".equalsIgnoreCase(request.getPaymentMode())) {
      if (cashReceived == null) cashReceived = request.getAmount();
      if (changeGiven == null) changeGiven = java.math.BigDecimal.ZERO;
    }

    final java.math.BigDecimal finalCashAmt = cashAmt;
    final java.math.BigDecimal finalOnlineAmt = onlineAmt;
    final java.math.BigDecimal finalCashReceived = cashReceived;
    final java.math.BigDecimal finalChangeGiven = changeGiven;

    SalesPayment payment =
        SalesPayment.builder()
            .salesOrderId(order.getId())
            .eventId(order.getEventId())
            .paymentMode(request.getPaymentMode())
            .paymentReference(request.getPaymentReference())
            .amount(request.getAmount())
            .cashAmount(finalCashAmt)
            .onlineAmount(finalOnlineAmt)
            .cashReceived(finalCashReceived)
            .changeGiven(finalChangeGiven)
            .paymentStatus("SUCCESS")
            .paidAt(LocalDateTime.now())
            .build();

    // SAGA-S1: persist the payment only AFTER billing succeeds, so a billing failure (which
    // triggers inventory compensation in confirmSaleCore) never leaves an orphaned SUCCESS
    // payment row behind.
    return invoiceSequenceService
        .claimAndAdvance(
            Long.valueOf(order.getShopId()), order.getEventId(), order.getShiftSessionId())
        .flatMap(
            invoiceNo ->
                billingClient
                    .createInvoice(
                        BillingInvoiceRequest.builder()
                            .salesOrderNumber(order.getOrderNumber())
                            .shopId(Long.valueOf(order.getShopId()))
                            .eventId(order.getEventId())
                            .preGeneratedInvoiceNo(invoiceNo)
                            .seller(
                                BillingInvoiceRequest.Seller.builder()
                                    .id(order.getSellerId())
                                    .name(order.getSellerName())
                                    .build())
                            .customer(
                                BillingInvoiceRequest.Customer.builder()
                                    .name(order.getCustomerName())
                                    .mobile(order.getCustomerMobile())
                                    .build())
                            .subtotalAmount(order.getOrderSubtotal())
                            .discountAmount(order.getDiscountAmount())
                            .items(
                                items.stream()
                                    .map(
                                        i ->
                                            BillingInvoiceRequest.Item.builder()
                                                .productId(i.getProductId())
                                                .productName(i.getProductName())
                                                .productSku(i.getProductSku())
                                                .hsnCode(i.getHsnCode())
                                                .quantity(i.getQuantity())
                                                .unitPrice(i.getSellingPrice())
                                                .discount(i.getDiscount())
                                                .build())
                                    .toList())
                            .build(),
                        userId,
                        order.getEventId())
                    .flatMap(
                        resp -> {
                          log.info(
                              "SALE_BILLING_INVOICE_CREATED orderNumber={} invoiceNo={}",
                              order.getOrderNumber(),
                              resp.getInvoiceNo());
                          order.setBillingInvoiceNumber(resp.getInvoiceNo());
                          order.setStatus("CONFIRMED");
                          order.setUpdatedAt(LocalDateTime.now());
                          return paymentRepository
                              .save(payment)
                              .doOnSuccess(
                                  p ->
                                      log.info(
                                          "SALE_PAYMENT_SAVED orderId={} amount={} mode={}",
                                          order.getId(),
                                          p.getAmount(),
                                          p.getPaymentMode()))
                              .then(orderRepo.save(order))
                              .doOnSuccess(
                                  saved -> {
                                    // Fire-and-forget: evict shop stocks + analytics caches after
                                    // confirmed sale
                                    String shopStockKey =
                                        SHOP_STOCKS_KEY_PREFIX
                                            + saved.getShopId()
                                            + ":"
                                            + saved.getEventId();
                                    redisTemplate
                                        .delete(shopStockKey)
                                        .onErrorResume(
                                            e -> {
                                              log.warn(
                                                  "Shop stocks cache evict failed shopId={} eventId={}",
                                                  saved.getShopId(),
                                                  saved.getEventId(),
                                                  e);
                                              return Mono.empty();
                                            })
                                        .subscribe();
                                    evictProductShopSalesCache(saved.getEventId()).subscribe();
                                  });
                        }));
  }

  /*
   * =========================
   * READ APIs
   * =========================
   */

  public Mono<SalesOrder> getByOrderNumber(String orderNumber) {
    log.debug("SALE_GET_REQUEST orderNumber={}", orderNumber);
    return orderRepo
        .findByOrderNumber(orderNumber)
        .switchIfEmpty(Mono.error(new IllegalStateException("Order not found")))
        .doOnSuccess(
            o -> log.debug("SALE_GET_SUCCESS orderNumber={} status={}", orderNumber, o.getStatus()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_GET_FAILED orderNumber={} reason={}", orderNumber, ex.getMessage(), ex));
  }

  public Flux<SalesOrder> getSalesBySeller(Long sellerId) {
    log.debug("SALE_GET_BY_SELLER_REQUEST sellerId={}", sellerId);
    return orderRepo
        .findBySellerId(sellerId)
        .doOnComplete(() -> log.debug("SALE_GET_BY_SELLER_SUCCESS sellerId={}", sellerId))
        .doOnError(
            ex ->
                log.error(
                    "SALE_GET_BY_SELLER_FAILED sellerId={} reason={}",
                    sellerId,
                    ex.getMessage(),
                    ex));
  }

  /*
   * =========================
   * CANCEL SALE
   * =========================
   */
  public Mono<SalesOrder> cancelSale(String orderNumber, String reason, String authHeader) {
    log.info("SALE_CANCEL_REQUEST orderNumber={} reason={}", orderNumber, reason);

    return orderRepo
        .findByOrderNumber(orderNumber)
        .switchIfEmpty(Mono.error(new IllegalStateException("Order not found")))
        .flatMap(
            order -> {
              // New Bug C / Issue 9 fix: orders that have been fully or partially
              // returned already had inventory restored through the return flow.
              // Cancelling them would leave billing and inventory inconsistent.
              if ("PARTIALLY_RETURNED".equals(order.getStatus())
                  || "RETURNED".equals(order.getStatus())) {
                return Mono.error(
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Cannot cancel a "
                            + order.getStatus()
                            + " order. Use the return flow instead."));
              }

              boolean wasConfirmed = "CONFIRMED".equals(order.getStatus());

              if (!wasConfirmed) {
                // Not yet confirmed — no inventory/billing side-effects needed
                order.setStatus("CANCELLED");
                order.setUpdatedAt(LocalDateTime.now());
                order.setCancellationReason(reason);
                return orderRepo.save(order);
              }

              // For a confirmed order: restore inventory and cancel billing BEFORE persisting
              // the CANCELLED status. If either external call fails the order stays CONFIRMED
              // and the cancellation can be safely retried without double-restoring inventory.
              return itemRepo
                  .findBySalesOrderId(order.getId())
                  .collectList()
                  .flatMap(
                      items -> {
                        List<InventoryClient.CounterStockDecrementRequest> incrementRequests =
                            items.stream()
                                .map(
                                    item ->
                                        InventoryClient.CounterStockDecrementRequest.builder()
                                            .productId(item.getProductId())
                                            .shopId(order.getShopId())
                                            .quantity(item.getQuantity())
                                            .build())
                                .collect(Collectors.toList());

                        // 1. Restore inventory → 2. Cancel billing → 3. Save CANCELLED
                        // If step 3 (DB save) fails, compensate by reinstating the billing invoice.
                        return inventoryClient
                            .incrementCounterStock(
                                incrementRequests, order.getEventId(), authHeader)
                            .then(
                                billingClient.cancelInvoice(
                                    orderNumber, reason, order.getEventId(), authHeader))
                            .then(
                                Mono.defer(
                                    () -> {
                                      order.setStatus("CANCELLED");
                                      order.setUpdatedAt(LocalDateTime.now());
                                      order.setCancellationReason(reason);
                                      return orderRepo
                                          .save(order)
                                          .onErrorResume(
                                              dbErr -> {
                                                // DB save failed after billing cancel —
                                                // reinstate the invoice to restore consistency.
                                                log.error(
                                                    "SALE_CANCEL_DB_SAVE_FAILED orderNumber={}"
                                                        + " — compensating with billing reinstate",
                                                    orderNumber,
                                                    dbErr);
                                                return billingClient
                                                    .reinstateInvoice(
                                                        orderNumber, order.getEventId(), authHeader)
                                                    .doOnSuccess(
                                                        v ->
                                                            log.info(
                                                                "SALE_CANCEL_BILLING_REINSTATED"
                                                                    + " orderNumber={}",
                                                                orderNumber))
                                                    .onErrorResume(
                                                        reinstateErr -> {
                                                          log.error(
                                                              "SALE_CANCEL_REINSTATE_FAILED"
                                                                  + " orderNumber={}",
                                                              orderNumber,
                                                              reinstateErr);
                                                          return sagaLogRepository
                                                              .save(
                                                                  SaleSagaLog.builder()
                                                                      .orderNumber(orderNumber)
                                                                      .sagaStep("CANCEL_BILLING")
                                                                      .compensation(
                                                                          "BILLING_REINSTATE")
                                                                      .status("FAILED")
                                                                      .errorMessage(
                                                                          reinstateErr.getMessage())
                                                                      .createdAt(
                                                                          LocalDateTime.now())
                                                                      .build())
                                                              .then();
                                                        })
                                                    .then(Mono.error(dbErr));
                                              });
                                    }));
                      });
            })
        .doOnSuccess(o -> log.info("SALE_CANCEL_SUCCESS orderNumber={}", o.getOrderNumber()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_CANCEL_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  public Mono<SalesOrder> returnSale(
      String orderNumber, BillingReturnRequest request, String authHeader) {
    log.info(
        "SALE_RETURN_REQUEST orderNumber={} itemsCount={}", orderNumber, request.getItems().size());

    String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
    Long userId = jwtUtil.extractUserId(token);
    String username = jwtUtil.extractUsername(token);

    return orderRepo
        .findByOrderNumber(orderNumber)
        .switchIfEmpty(Mono.error(new IllegalStateException("Order not found")))
        .flatMap(
            order -> {
              // New Bug D fix: fully RETURNED orders have all items at qty=0,
              // so further returns are impossible and should be rejected.
              if (!"CONFIRMED".equals(order.getStatus())
                  && !"PARTIALLY_RETURNED".equals(order.getStatus())) {
                return Mono.error(
                    new IllegalStateException(
                        "Only confirmed or partially returned orders can be returned. Current status: "
                            + order.getStatus()));
              }

              return itemRepo
                  .findBySalesOrderId(order.getId())
                  .collectList()
                  .flatMap(
                      existingItems -> {
                        List<Mono<Void>> returnInserts = new ArrayList<>();
                        // STOCK-S1: restore inventory ONLY for items that matched a line on this
                        // order. Building increments from request.getItems() directly let callers
                        // inflate counter stock by "returning" products that were never sold.
                        List<InventoryClient.CounterStockDecrementRequest> appliedIncrements =
                            new ArrayList<>();
                        java.util.Set<Long> matchedProductIds = new java.util.HashSet<>();

                        // Match returned items and update existing items
                        for (SalesOrderItem existingItem : existingItems) {
                          for (BillingReturnRequest.ReturnItem returnedItem : request.getItems()) {
                            if (returnedItem.getProductId().equals(existingItem.getProductId())) {
                              int returnedQty = returnedItem.getQuantity();
                              if (returnedQty > existingItem.getQuantity()) {
                                return Mono.error(
                                    new IllegalStateException(
                                        "Returned quantity "
                                            + returnedQty
                                            + " exceeds remaining quantity "
                                            + existingItem.getQuantity()
                                            + " for product: "
                                            + existingItem.getProductName()));
                              }
                              int newQty = existingItem.getQuantity() - returnedQty;
                              existingItem.setQuantity(newQty);
                              existingItem.setLineTotal(
                                  existingItem
                                      .getSellingPrice()
                                      .multiply(BigDecimal.valueOf(newQty)));

                              BigDecimal refundAmount =
                                  existingItem
                                      .getSellingPrice()
                                      .multiply(BigDecimal.valueOf(returnedQty));

                              Mono<Void> insertMono =
                                  databaseClient
                                      .sql(
                                          """
                                                                                                                            INSERT INTO sales_return (
                                                                                                                                sales_order_id, sales_order_item_id, product_id,
                                                                                                                                processed_by, processed_by_name, quantity,
                                                                                                                                refund_amount, reason, billing_invoice_number, returned_at
                                                                                                                            ) VALUES (
                                                                                                                                :orderId, :orderItemId, :productId,
                                                                                                                                :processedBy, :processedByName, :quantity,
                                                                                                                                :refundAmount, :reason, :invoiceNo, :returnedAt
                                                                                                                            )
                                                                                                                        """)
                                      .bind("orderId", order.getId())
                                      .bind("orderItemId", existingItem.getId())
                                      .bind("productId", existingItem.getProductId())
                                      .bind("processedBy", userId)
                                      .bind("processedByName", username)
                                      .bind("quantity", returnedQty)
                                      .bind("refundAmount", refundAmount)
                                      .bind(
                                          "reason",
                                          request.getReason() != null ? request.getReason() : "")
                                      .bind(
                                          "invoiceNo",
                                          order.getBillingInvoiceNumber() != null
                                              ? order.getBillingInvoiceNumber()
                                              : "")
                                      .bind("returnedAt", LocalDateTime.now())
                                      .then();

                              returnInserts.add(insertMono);
                              matchedProductIds.add(returnedItem.getProductId());
                              appliedIncrements.add(
                                  InventoryClient.CounterStockDecrementRequest.builder()
                                      .productId(returnedItem.getProductId())
                                      .shopId(order.getShopId())
                                      .quantity(returnedQty)
                                      .build());
                            }
                          }
                        }

                        // STOCK-S1: reject any requested return line that did not match an item on
                        // this order, instead of silently restoring stock for an unsold product.
                        for (BillingReturnRequest.ReturnItem ri : request.getItems()) {
                          if (!matchedProductIds.contains(ri.getProductId())) {
                            return Mono.error(
                                new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.BAD_REQUEST,
                                    "Returned product "
                                        + ri.getProductId()
                                        + " is not part of order "
                                        + orderNumber));
                          }
                        }

                        // Calculate new subtotal and remaining items count
                        BigDecimal newSubtotal = BigDecimal.ZERO;
                        int remainingQtySum = 0;
                        for (SalesOrderItem existingItem : existingItems) {
                          newSubtotal = newSubtotal.add(existingItem.getLineTotal());
                          remainingQtySum += existingItem.getQuantity();
                        }

                        // Set status based on remaining qty
                        if (remainingQtySum == 0) {
                          order.setStatus("RETURNED");
                        } else {
                          order.setStatus("PARTIALLY_RETURNED");
                        }

                        order.setOrderSubtotal(newSubtotal);
                        order.setUpdatedAt(LocalDateTime.now());
                        order.setCancellationReason(request.getReason());

                        return Flux.concat(returnInserts)
                            .then()
                            .then(itemRepo.saveAll(existingItems).collectList())
                            .then(orderRepo.save(order))
                            .flatMap(
                                savedOrder -> {
                                  // 1. Restore Inventory ONLY for matched returned items
                                  // (STOCK-S1) — see appliedIncrements built during matching.
                                  return inventoryClient
                                      .incrementCounterStock(
                                          appliedIncrements, order.getEventId(), authHeader)
                                      .onErrorResume(
                                          invErr -> {
                                            // DB already committed RETURNED/PARTIALLY_RETURNED —
                                            // inventory restore failed. Log for manual ops fix.
                                            log.error(
                                                "SALE_RETURN_SAGA_INVENTORY_RESTORE_FAILED"
                                                    + " orderNumber={} cause={}",
                                                orderNumber,
                                                invErr.getMessage(),
                                                invErr);
                                            return sagaLogRepository
                                                .save(
                                                    SaleSagaLog.builder()
                                                        .orderNumber(orderNumber)
                                                        .sagaStep("RETURN_DB_SAVE")
                                                        .compensation("INVENTORY_RESTORE")
                                                        .status("FAILED")
                                                        .errorMessage(invErr.getMessage())
                                                        .createdAt(LocalDateTime.now())
                                                        .build())
                                                .then(Mono.error(invErr));
                                          })
                                      .then(
                                          // 2. Update Billing
                                          billingClient
                                              .returnInvoice(
                                                  orderNumber,
                                                  request,
                                                  order.getEventId(),
                                                  authHeader)
                                              .onErrorResume(
                                                  billingErr -> {
                                                    // Inventory was restored but billing update
                                                    // failed — log for manual reconciliation.
                                                    log.error(
                                                        "SALE_RETURN_SAGA_BILLING_UPDATE_FAILED"
                                                            + " orderNumber={} cause={}",
                                                        orderNumber,
                                                        billingErr.getMessage(),
                                                        billingErr);
                                                    return sagaLogRepository
                                                        .save(
                                                            SaleSagaLog.builder()
                                                                .orderNumber(orderNumber)
                                                                .sagaStep(
                                                                    "RETURN_INVENTORY_RESTORED")
                                                                .compensation(
                                                                    "BILLING_RETURN_UPDATE")
                                                                .status("FAILED")
                                                                .errorMessage(
                                                                    billingErr.getMessage())
                                                                .createdAt(LocalDateTime.now())
                                                                .build())
                                                        .then(Mono.error(billingErr));
                                                  }))
                                      .thenReturn(savedOrder);
                                });
                      });
            })
        .doOnSuccess(o -> log.info("SALE_RETURN_SUCCESS orderNumber={}", o.getOrderNumber()))
        .doOnError(
            ex ->
                log.error(
                    "SALE_RETURN_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }

  public Mono<Map<String, Object>> getAllSalesPaged(Long eventId, int page, int size) {
    log.info("SALE_GET_ALL_REQUEST eventId={} page={} size={}", eventId, page, size);
    long offset = (long) page * size;

    Flux<SalesOrder> data = eventId != null
        ? orderRepo.findByEventIdPaged(eventId, size, offset)
        : orderRepo.findAll();

    Mono<Long> total = eventId != null
        ? orderRepo.countByEventId(eventId)
        : orderRepo.count();

    return Mono.zip(data.collectList(), total)
        .map(tuple -> {
          Map<String, Object> result = new HashMap<>();
          result.put("content", tuple.getT1());
          result.put("page", page);
          result.put("size", size);
          result.put("totalElements", tuple.getT2());
          result.put("totalPages", (int) Math.ceil((double) tuple.getT2() / size));
          return result;
        })
        .doOnSuccess(r -> log.info("SALE_GET_ALL_SUCCESS totalElements={}", r.get("totalElements")))
        .doOnError(ex -> log.error("SALE_GET_ALL_FAILED reason={}", ex.getMessage(), ex));
  }

  public Flux<ProductShopSalesDTO> getProductShopSalesSummary(Long eventId) {
    log.debug("SALE_ANALYTICS_PRODUCT_SHOP_REQUEST eventId={}", eventId);

    if (eventId == null) {
      return analyticsRepository.getProductShopSalesSummary();
    }

    String cacheKey = PRODUCT_SHOP_SALES_KEY_PREFIX + eventId;
    return redisTemplate
        .opsForValue()
        .get(cacheKey)
        .onErrorResume(
            e -> {
              log.warn(
                  "PRODUCT_SHOP_SALES_CACHE_GET_ERROR eventId={}, falling back to DB", eventId, e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<ProductShopSalesDTO> list =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<ProductShopSalesDTO>>() {});
                log.debug("PRODUCT_SHOP_SALES_CACHE_HIT eventId={} count={}", eventId, list.size());
                return Flux.fromIterable(list);
              } catch (Exception e) {
                log.warn(
                    "PRODUCT_SHOP_SALES_CACHE_DESERIALIZE_ERROR eventId={}, falling back to DB",
                    eventId,
                    e);
                return Flux.<ProductShopSalesDTO>empty();
              }
            })
        .switchIfEmpty(
            Flux.defer(
                () ->
                    analyticsRepository
                        .getProductShopSalesSummaryByEventId(eventId)
                        .collectList()
                        .flatMap(
                            list -> {
                              try {
                                String json = CACHE_MAPPER.writeValueAsString(list);
                                return redisTemplate
                                    .opsForValue()
                                    .set(cacheKey, json, PRODUCT_SHOP_SALES_TTL)
                                    .onErrorResume(
                                        e -> {
                                          log.warn(
                                              "PRODUCT_SHOP_SALES_CACHE_SET_ERROR eventId={}",
                                              eventId,
                                              e);
                                          return Mono.empty();
                                        })
                                    .thenReturn(list);
                              } catch (Exception e) {
                                log.warn(
                                    "PRODUCT_SHOP_SALES_CACHE_SERIALIZE_ERROR eventId={}",
                                    eventId,
                                    e);
                                return Mono.just(list);
                              }
                            })
                        .flatMapMany(Flux::fromIterable)))
        .doOnComplete(() -> log.debug("SALE_ANALYTICS_PRODUCT_SHOP_SUCCESS eventId={}", eventId))
        .doOnError(
            ex -> log.error("SALE_ANALYTICS_PRODUCT_SHOP_FAILED reason={}", ex.getMessage(), ex));
  }

  private Mono<Void> evictProductShopSalesCache(Long eventId) {
    if (eventId == null) return Mono.empty();
    return redisTemplate
        .delete(PRODUCT_SHOP_SALES_KEY_PREFIX + eventId)
        .onErrorResume(
            e -> {
              log.warn("PRODUCT_SHOP_SALES_CACHE_EVICT_ERROR eventId={}", eventId, e);
              return Mono.empty();
            })
        .then();
  }

  public Flux<ProductSalesSummaryDTO> getProductSalesSummary(
      Long eventId, Long shopId, Long shiftSessionId) {
    log.info(
        "SALE_ANALYTICS_SHIFT_SUMMARY_REQUEST eventId={} shopId={} shiftId={}",
        eventId,
        shopId,
        shiftSessionId);
    return analyticsRepository
        .getProductSalesSummary(eventId, shopId, shiftSessionId)
        .doOnComplete(() -> log.info("SALE_ANALYTICS_SHIFT_SUMMARY_SUCCESS"))
        .doOnError(
            ex -> log.error("SALE_ANALYTICS_SHIFT_SUMMARY_FAILED reason={}", ex.getMessage(), ex));
  }

  public Flux<ShopStockResponse> getShopStocks(Long shopId, Long eventId, String authHeader) {
    log.info("Fetching available stock for shopId={} eventId={}", shopId, eventId);

    String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
    Long userId = jwtUtil.extractUserId(token);
    List<String> roles = jwtUtil.extractRoles(token);

    Mono<Boolean> hasAccessMono =
        roles.contains("ADMIN")
            ? Mono.just(true)
            : shopStaffAssignmentRepo
                .findByUserIdAndEventIdAndIsActiveTrue(userId, eventId)
                .hasElements();

    Mono<Shop> shopMono =
        shopRepo
            .findById(shopId)
            .onErrorResume(
                ex -> {
                  log.error("Failed to fetch shop details for shopId={}", shopId, ex);
                  return Mono.empty();
                });

    Mono<List<InventoryClient.ProductDTO>> productsMono =
        inventoryClient
            .getAllProducts(eventId, authHeader)
            .map(
                resp ->
                    resp.isSuccess() && resp.getData() != null
                        ? resp.getData()
                        : new ArrayList<InventoryClient.ProductDTO>())
            .onErrorReturn(new ArrayList<>());

    Mono<List<InventoryClient.IssueDTO>> issuesMono =
        inventoryClient
            .getAllIssues(eventId, authHeader)
            .map(
                resp ->
                    resp.isSuccess() && resp.getData() != null
                        ? resp.getData()
                        : new ArrayList<InventoryClient.IssueDTO>())
            .onErrorReturn(new ArrayList<>());

    return Mono.zip(shopMono, productsMono, issuesMono, hasAccessMono)
        .flatMapMany(
            tuple -> {
              Shop shop = tuple.getT1();
              Boolean hasAccess = tuple.getT4();

              if (Boolean.FALSE.equals(hasAccess)) {
                return Flux.error(
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Access Denied: You do not have access to this event!"));
              }

              if (shop == null || !shop.getEventId().equals(eventId)) {
                return Flux.error(
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Access Denied: This shop counter is not associated with this event!"));
              }
              List<InventoryClient.ProductDTO> productsList = tuple.getT2();
              List<InventoryClient.IssueDTO> issuesList = tuple.getT3();

              Long shopCategoryId = shop.getCategoryId();

              // Filter global products to ONLY contain products from the Shop's CategoryId!
              List<InventoryClient.ProductDTO> filteredProducts =
                  productsList.stream()
                      .filter(
                          prod ->
                              prod.getCategoryId() != null
                                  && prod.getCategoryId().equals(shopCategoryId))
                      .collect(Collectors.toList());

              // Group issued liveQuantity by ProductId for THIS shop
              Map<Long, Integer> liveStockMap =
                  issuesList.stream()
                      .filter(
                          issue ->
                              issue.getShopId() != null
                                  && shopId.toString().equals(issue.getShopId()))
                      .collect(
                          Collectors.toMap(
                              InventoryClient.IssueDTO::getProductId,
                              issue ->
                                  issue.getLiveQuantity() != null ? issue.getLiveQuantity() : 0,
                              (existing, replacement) -> existing));

              return Flux.fromIterable(filteredProducts)
                  .map(
                      prod -> {
                        int availableStock = liveStockMap.getOrDefault(prod.getId(), 0);

                        return ShopStockResponse.builder()
                            .id(prod.getId())
                            .name(prod.getName())
                            .sku(prod.getSku())
                            .categoryId(prod.getCategoryId())
                            .sellingPrice(prod.getSellingPrice())
                            .mrp(prod.getMrp())
                            .hsnCode(prod.getHsnCode())
                            .shopStock(availableStock)
                            .minThreshold(
                                prod.getMinThreshold() != null ? prod.getMinThreshold() : 10)
                            .build();
                      });
            });
  }

  /**
   * Multi-shop stock view with per-shop cache-aside.
   *
   * <p>Phase 1: Try {@code shop-stocks:{shopId}:{eventId}} for each requested shopId. Phase 2: For
   * any misses, do a single combined inventory fetch (products + issues), compute per-shop results,
   * cache them, then merge with Phase 1 hits.
   *
   * <p>Eviction: {@link #buildPaymentAndFinalize} evicts {@code shop-stocks:{shopId}:{eventId}}
   * after each confirmed sale (fire-and-forget).
   */
  public Flux<ShopStockResponse> getShopsStocks(
      List<String> StringShopIds, Long eventId, String authHeader) {
    log.info("Fetching available stock for shopIds={} eventId={}", StringShopIds, eventId);

    List<Long> shopIds =
        StringShopIds.stream()
            .filter(id -> id != null && id.matches("\\d+"))
            .map(Long::valueOf)
            .toList();

    String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
    Long userId = jwtUtil.extractUserId(token);
    List<String> roles = jwtUtil.extractRoles(token);

    Mono<Boolean> hasAccessMono =
        roles.contains("ADMIN")
            ? Mono.just(true)
            : shopStaffAssignmentRepo
                .findByUserIdAndEventIdAndIsActiveTrue(userId, eventId)
                .hasElements();

    return hasAccessMono.flatMapMany(
        hasAccess -> {
          if (Boolean.FALSE.equals(hasAccess)) {
            return Flux.error(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Access Denied: You do not have access to this event!"));
          }
          if (shopIds.isEmpty()) {
            return Flux.empty();
          }

          // Phase 1: Try each shopId from cache.
          // Emit SimpleEntry(shopId, list) on hit, SimpleEntry(shopId, null) on miss.
          return Flux.fromIterable(shopIds)
              .flatMap(
                  shopId -> {
                    String cacheKey = SHOP_STOCKS_KEY_PREFIX + shopId + ":" + eventId;
                    return redisTemplate
                        .opsForValue()
                        .get(cacheKey)
                        .onErrorResume(e -> Mono.empty())
                        .flatMap(
                            json -> {
                              try {
                                List<ShopStockResponse> list =
                                    CACHE_MAPPER.readValue(
                                        json, new TypeReference<List<ShopStockResponse>>() {});
                                return Mono.just(new AbstractMap.SimpleEntry<>(shopId, list));
                              } catch (Exception e) {
                                return Mono
                                    .<AbstractMap.SimpleEntry<Long, List<ShopStockResponse>>>
                                        empty();
                              }
                            })
                        .defaultIfEmpty(
                            // null value = cache miss sentinel
                            new AbstractMap.SimpleEntry<>(shopId, (List<ShopStockResponse>) null));
                  })
              .collectList()
              .flatMapMany(
                  entries -> {
                    Map<Long, List<ShopStockResponse>> cacheHits = new HashMap<>();
                    List<Long> cacheMisses = new ArrayList<>();
                    for (AbstractMap.SimpleEntry<Long, List<ShopStockResponse>> e : entries) {
                      if (e.getValue() != null) {
                        cacheHits.put(e.getKey(), e.getValue());
                      } else {
                        cacheMisses.add(e.getKey());
                      }
                    }

                    Flux<ShopStockResponse> fromCache =
                        Flux.fromIterable(cacheHits.values()).flatMapIterable(list -> list);

                    if (cacheMisses.isEmpty()) {
                      return fromCache;
                    }

                    // Phase 2: One combined inventory call for all misses
                    Mono<List<Shop>> shopsMono =
                        shopRepo
                            .findAllById(cacheMisses)
                            .filter(
                                shop ->
                                    shop.getEventId() != null && shop.getEventId().equals(eventId))
                            .collectList()
                            .onErrorResume(
                                ex -> {
                                  log.error(
                                      "Failed to fetch shop details for shopIds={}",
                                      cacheMisses,
                                      ex);
                                  return Mono.just(new ArrayList<>());
                                });

                    Mono<List<InventoryClient.ProductDTO>> productsMono =
                        inventoryClient
                            .getAllProducts(eventId, authHeader)
                            .map(
                                resp ->
                                    resp.isSuccess() && resp.getData() != null
                                        ? resp.getData()
                                        : new ArrayList<InventoryClient.ProductDTO>())
                            .onErrorReturn(new ArrayList<>());

                    Mono<List<InventoryClient.IssueDTO>> issuesMono =
                        inventoryClient
                            .getAllIssues(eventId, authHeader)
                            .map(
                                resp ->
                                    resp.isSuccess() && resp.getData() != null
                                        ? resp.getData()
                                        : new ArrayList<InventoryClient.IssueDTO>())
                            .onErrorReturn(new ArrayList<>());

                    Flux<ShopStockResponse> fromDb =
                        Mono.zip(shopsMono, productsMono, issuesMono)
                            .flatMapMany(
                                tuple -> {
                                  List<Shop> shopsList = tuple.getT1();
                                  List<InventoryClient.ProductDTO> productsList = tuple.getT2();
                                  List<InventoryClient.IssueDTO> issuesList = tuple.getT3();

                                  return Flux.fromIterable(shopsList)
                                      .flatMap(
                                          shop -> {
                                            Long currentShopId = shop.getId();
                                            Long shopCategoryId = shop.getCategoryId();
                                            String currentShopIdStr = currentShopId.toString();

                                            List<InventoryClient.ProductDTO> filteredProducts =
                                                productsList.stream()
                                                    .filter(
                                                        prod ->
                                                            prod.getCategoryId() != null
                                                                && prod.getCategoryId()
                                                                    .equals(shopCategoryId))
                                                    .toList();

                                            Map<Long, Integer> liveStockMap =
                                                issuesList.stream()
                                                    .filter(
                                                        issue ->
                                                            issue.getShopId() != null
                                                                && currentShopIdStr.equals(
                                                                    issue.getShopId()))
                                                    .collect(
                                                        Collectors.toMap(
                                                            InventoryClient.IssueDTO::getProductId,
                                                            issue ->
                                                                issue.getLiveQuantity() != null
                                                                    ? issue.getLiveQuantity()
                                                                    : 0,
                                                            (existing, replacement) -> existing));

                                            List<ShopStockResponse> shopResults =
                                                filteredProducts.stream()
                                                    .map(
                                                        prod -> {
                                                          int availableStock =
                                                              liveStockMap.getOrDefault(
                                                                  prod.getId(), 0);
                                                          return ShopStockResponse.builder()
                                                              .id(prod.getId())
                                                              .name(prod.getName())
                                                              .sku(prod.getSku())
                                                              .categoryId(prod.getCategoryId())
                                                              .sellingPrice(prod.getSellingPrice())
                                                              .mrp(prod.getMrp())
                                                              .hsnCode(prod.getHsnCode())
                                                              .shopStock(availableStock)
                                                              .minThreshold(
                                                                  prod.getMinThreshold() != null
                                                                      ? prod.getMinThreshold()
                                                                      : 10)
                                                              .build();
                                                        })
                                                    .collect(Collectors.toList());

                                            // Cache this shop's result
                                            Mono<Void> cacheOp;
                                            try {
                                              String json =
                                                  CACHE_MAPPER.writeValueAsString(shopResults);
                                              cacheOp =
                                                  redisTemplate
                                                      .opsForValue()
                                                      .set(
                                                          SHOP_STOCKS_KEY_PREFIX
                                                              + currentShopId
                                                              + ":"
                                                              + eventId,
                                                          json,
                                                          SHOP_STOCKS_TTL)
                                                      .onErrorResume(e -> Mono.empty())
                                                      .then();
                                            } catch (Exception e) {
                                              cacheOp = Mono.empty();
                                            }

                                            return cacheOp.thenMany(Flux.fromIterable(shopResults));
                                          });
                                });

                    return fromCache.mergeWith(fromDb);
                  });
        });
  }

  public Mono<SalesPayment> getOrderPayment(String orderNumber) {
    return orderRepo
        .findByOrderNumber(orderNumber)
        .flatMap(order -> paymentRepository.findBySalesOrderId(order.getId()).next())
        .switchIfEmpty(
            Mono.error(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Payment details not found")));
  }

  /**
   * Marks a sales order's receiving slip as printed (idempotent).
   *
   * <p>Called by the frontend immediately after {@code printReceiving()} succeeds. Setting {@code
   * receivingPrinted = true} prevents a second receiving slip from being emitted on any subsequent
   * history reprint of the same order.
   */
  public Mono<SalesOrder> markReceivingPrinted(String orderNumber) {
    log.info("RECEIVING_PRINTED_REQUEST orderNumber={}", orderNumber);
    return orderRepo
        .findByOrderNumber(orderNumber)
        .switchIfEmpty(
            Mono.error(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Order not found: " + orderNumber)))
        .flatMap(
            order -> {
              if (Boolean.TRUE.equals(order.getReceivingPrinted())) {
                // Already marked — idempotent, just return existing record
                log.info("RECEIVING_PRINTED_ALREADY_SET orderNumber={}", orderNumber);
                return Mono.just(order);
              }
              order.setReceivingPrinted(true);
              order.setUpdatedAt(LocalDateTime.now());
              return orderRepo.save(order);
            })
        .doOnSuccess(o -> log.info("RECEIVING_PRINTED_SUCCESS orderNumber={}", o.getOrderNumber()))
        .doOnError(
            ex ->
                log.error(
                    "RECEIVING_PRINTED_FAILED orderNumber={} reason={}",
                    orderNumber,
                    ex.getMessage(),
                    ex));
  }
}
