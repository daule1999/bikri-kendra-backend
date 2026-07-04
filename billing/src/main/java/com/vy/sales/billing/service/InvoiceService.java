package com.vy.sales.billing.service;

import com.vy.sales.billing.dto.CreateInvoiceRequest;
import com.vy.sales.billing.dto.ReturnInvoiceRequest;
import com.vy.sales.billing.entity.AuditAction;
import com.vy.sales.billing.entity.Invoice;
import com.vy.sales.billing.entity.InvoiceAudit;
import com.vy.sales.billing.entity.InvoiceItem;
import com.vy.sales.billing.entity.InvoiceStatus;
import com.vy.sales.billing.repository.InvoiceAuditRepository;
import com.vy.sales.billing.repository.InvoiceItemRepository;
import com.vy.sales.billing.repository.InvoiceRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

  private final InvoiceRepository invoiceRepo;
  private final InvoiceItemRepository itemRepo;
  private final InvoiceAuditRepository auditRepo;
  private final DatabaseClient databaseClient;

  @Transactional
  public Mono<Invoice> createInvoice(CreateInvoiceRequest request) {

    log.info(
        "Starting invoice creation for salesOrderNumber={}, sellerId={}, billedBy={}, customerName={}, customerMobile={}",
        request.getSalesOrderNumber(),
        request.getSellerId(),
        request.getBilledBy(),
        request.getCustomerName(),
        request.getCustomerMobile());

    Invoice invoice =
        Invoice.builder()
            .invoiceNo(
                request.getPreGeneratedInvoiceNo() != null
                        && !request.getPreGeneratedInvoiceNo().isEmpty()
                    ? request.getPreGeneratedInvoiceNo()
                    : UUID.randomUUID().toString())
            .salesOrderNumber(request.getSalesOrderNumber())
            .eventId(request.getEventId()) // propagate event scope
            .shopId(request.getShopId())
            .sellerId(request.getSellerId())
            .sellerName(request.getSellerName())
            .billedBy(request.getBilledBy())
            .customerName(request.getCustomerName()) // ✅ CASCADE customer data
            .customerMobile(request.getCustomerMobile()) // ✅ CASCADE customer data
            .subtotalAmount(request.getSubtotalAmount())
            .discountAmount(request.getDiscountAmount())
            .taxAmount(request.getTaxAmount())
            .netAmount(request.getNetAmount())
            .status(InvoiceStatus.PAID.name())
            .billingDate(LocalDateTime.now())
            .build();

    return invoiceRepo
        .save(invoice)
        .doOnSubscribe(
            sub ->
                log.debug(
                    "Persisting invoice [salesOrderNumber={}, invoiceNo={}, customer={}]",
                    invoice.getSalesOrderNumber(),
                    invoice.getInvoiceNo(),
                    invoice.getCustomerName()))
        .doOnSuccess(
            saved ->
                log.info(
                    "Invoice saved successfully invoiceId={}, invoiceNo={}, customerName={}, customerMobile={}",
                    saved.getId(),
                    saved.getInvoiceNo(),
                    saved.getCustomerName(),
                    saved.getCustomerMobile()))
        .flatMap(
            saved ->
                itemRepo
                    .saveAll(
                        request.getItems().stream().map(i -> i.toEntity(saved.getId())).toList())
                    .doOnSubscribe(
                        sub ->
                            log.debug(
                                "Saving {} invoice items for invoiceId={}",
                                request.getItems().size(),
                                saved.getId()))
                    .doOnComplete(
                        () -> log.info("All invoice items saved for invoiceId={}", saved.getId()))
                    .then(
                        auditRepo
                            .save(
                                InvoiceAudit.builder()
                                    .invoiceId(saved.getId())
                                    .action(AuditAction.CREATED)
                                    .actionBy(request.getBilledBy())
                                    .remarks("Invoice Created")
                                    .build())
                            .doOnSuccess(
                                audit ->
                                    log.info(
                                        "Invoice audit created action={}, invoiceId={}",
                                        audit.getAction(),
                                        audit.getInvoiceId())))
                    .thenReturn(saved))
        .onErrorResume(
            org.springframework.dao.DuplicateKeyException.class,
            ex -> {
              log.warn(
                  "Invoice already exists for salesOrderNumber={}, returning existing (idempotent)",
                  request.getSalesOrderNumber());
              return invoiceRepo
                  .findBySalesOrderNumberAndEventId(
                      request.getSalesOrderNumber(), request.getEventId())
                  .switchIfEmpty(
                      Mono.error(
                          new RuntimeException(
                              "Duplicate key but invoice not found for salesOrderNumber="
                                  + request.getSalesOrderNumber())));
            })
        .doOnError(
            ex ->
                log.error(
                    "Invoice creation FAILED for salesOrderNumber={}",
                    request.getSalesOrderNumber(),
                    ex))
        .doOnSuccess(
            inv ->
                log.info(
                    "Invoice creation COMPLETED invoiceId={}, status={}, customer={}",
                    inv.getId(),
                    inv.getStatus(),
                    inv.getCustomerName()));
  }

  public Flux<Invoice> listInvoices(Long eventId) {

    log.debug("Fetching all invoices for eventId={}", eventId);

    return invoiceRepo
        .findAllByEventId(eventId)
        .doOnSubscribe(sub -> log.debug("Invoice list query started"))
        .doOnComplete(() -> log.debug("Invoice list query completed"))
        .doOnError(ex -> log.error("Failed to fetch invoices", ex));
  }

  public Flux<InvoiceItem> getItemsByOrderNumber(String orderNumber, Long eventId) {

    log.info("Fetching items for salesOrderNumber={}, eventId={}", orderNumber, eventId);

    return invoiceRepo
        .findBySalesOrderNumberAndEventId(orderNumber, eventId)
        .doOnSubscribe(sub -> log.debug("Looking up invoice for salesOrderNumber={}", orderNumber))
        .switchIfEmpty(
            Mono.error(new RuntimeException("Invoice not found for orderNumber=" + orderNumber)))
        .doOnSuccess(
            inv ->
                log.debug(
                    "Invoice found invoiceId={} for salesOrderNumber={}", inv.getId(), orderNumber))
        .flatMapMany(
            inv ->
                itemRepo
                    .findByInvoiceId(inv.getId())
                    .doOnSubscribe(sub -> log.debug("Fetching items for invoiceId={}", inv.getId()))
                    .doOnComplete(
                        () ->
                            log.info(
                                "Items fetched successfully for salesOrderNumber={}", orderNumber)))
        .doOnError(
            ex -> log.error("Failed to fetch items for salesOrderNumber={}", orderNumber, ex));
  }

  @Transactional
  public Mono<Invoice> cancelInvoice(String orderNumber, String reason, Long userId, Long eventId) {
    log.info(
        "Cancelling invoice for orderNumber={} reason={} actionBy={} eventId={}",
        orderNumber,
        reason,
        userId,
        eventId);

    return invoiceRepo
        .findBySalesOrderNumberAndEventId(orderNumber, eventId)
        .switchIfEmpty(
            Mono.error(new RuntimeException("Invoice not found for order=" + orderNumber)))
        .flatMap(
            invoice -> {
              invoice.setStatus(InvoiceStatus.CANCELLED.name());
              invoice.setUpdatedAt(LocalDateTime.now());
              return invoiceRepo
                  .save(invoice)
                  .flatMap(
                      saved ->
                          auditRepo
                              .save(
                                  InvoiceAudit.builder()
                                      .invoiceId(saved.getId())
                                      .action(AuditAction.CANCELLED)
                                      .actionBy(userId)
                                      .remarks(reason)
                                      .actionAt(LocalDateTime.now())
                                      .build())
                              .thenReturn((Invoice) saved));
            })
        .doOnSuccess(
            inv ->
                log.info(
                    "Invoice CANCELLED successfully id={} order={}", inv.getId(), orderNumber));
  }

  /**
   * Reinstate a CANCELLED invoice back to PAID status.
   *
   * <p>Called as saga compensation when the sales-service DB save fails after {@link
   * #cancelInvoice} already succeeded. Reverting the invoice status ensures the billing record
   * stays consistent with the order's CONFIRMED status.
   */
  @Transactional
  public Mono<Invoice> reinstateInvoice(String orderNumber, Long userId, Long eventId) {
    log.info("Reinstating invoice for orderNumber={} actionBy={}", orderNumber, userId);
    return invoiceRepo
        .findBySalesOrderNumberAndEventId(orderNumber, eventId)
        .switchIfEmpty(
            Mono.error(new RuntimeException("Invoice not found for order=" + orderNumber)))
        .flatMap(
            invoice -> {
              if (!InvoiceStatus.CANCELLED.name().equals(invoice.getStatus())) {
                // Already reinstated or in another state — no-op is safe
                log.warn(
                    "Reinstate called on non-CANCELLED invoice id={} status={}",
                    invoice.getId(),
                    invoice.getStatus());
                return Mono.just(invoice);
              }
              invoice.setStatus(InvoiceStatus.PAID.name());
              invoice.setUpdatedAt(LocalDateTime.now());
              return invoiceRepo
                  .save(invoice)
                  .flatMap(
                      saved ->
                          auditRepo
                              .save(
                                  InvoiceAudit.builder()
                                      .invoiceId(saved.getId())
                                      .action(AuditAction.REINSTATED)
                                      .actionBy(userId)
                                      .remarks("Saga compensation: cancel DB save failed")
                                      .actionAt(LocalDateTime.now())
                                      .build())
                              .thenReturn((Invoice) saved));
            })
        .doOnSuccess(
            inv ->
                log.info(
                    "Invoice REINSTATED successfully id={} order={}", inv.getId(), orderNumber));
  }

  @Transactional
  public Mono<Invoice> processReturn(
      String orderNumber, ReturnInvoiceRequest request, Long userId, Long eventId) {
    log.info(
        "Processing return for orderNumber={} items={} actionBy={} eventId={}",
        orderNumber,
        request.getItems().size(),
        userId,
        eventId);

    return invoiceRepo
        .findBySalesOrderNumberAndEventId(orderNumber, eventId)
        .switchIfEmpty(
            Mono.error(new RuntimeException("Invoice not found for order=" + orderNumber)))
        .flatMap(
            invoice -> {
              // Update items returned quantity
              return itemRepo
                  .findByInvoiceId(invoice.getId())
                  .flatMap(
                      item -> {
                        return Flux.fromIterable(request.getItems())
                            .filter(ri -> ri.getProductId().equals(item.getProductId()))
                            .next()
                            .flatMap(
                                ri -> {
                                  int currentReturned =
                                      item.getReturnedQuantity() != null
                                          ? item.getReturnedQuantity()
                                          : 0;
                                  int newReturned = currentReturned + ri.getQuantity();
                                  if (newReturned > item.getQuantity()) {
                                    return Mono.error(
                                        new IllegalStateException(
                                            "Returned quantity exceeds original quantity for product: "
                                                + item.getProductName()));
                                  }
                                  item.setReturnedQuantity(newReturned);
                                  return itemRepo.save(item);
                                })
                            .defaultIfEmpty(item);
                      })
                  .then(
                      auditRepo.save(
                          InvoiceAudit.builder()
                              .invoiceId(invoice.getId())
                              .action(AuditAction.RETURNED)
                              .actionBy(userId)
                              .remarks(request.getReason())
                              .actionAt(LocalDateTime.now())
                              .build()))
                  .thenReturn((Invoice) invoice);
            })
        .doOnSuccess(
            inv ->
                log.info("Return processed for invoice id={} order={}", inv.getId(), orderNumber));
  }

  public Mono<java.util.Map<String, Object>> searchHistory(
      java.util.List<Long> eventIds,
      java.util.List<String> shopIds,
      String status,
      String searchTerm,
      int page,
      int size) {

    long offset = (long) page * size;

    StringBuilder where = new StringBuilder(" WHERE 1=1");

    if (eventIds != null && !eventIds.isEmpty()) {
      where.append(" AND i.event_id IN (");
      for (int i = 0; i < eventIds.size(); i++) {
        where.append(eventIds.get(i));
        if (i < eventIds.size() - 1) where.append(",");
      }
      where.append(")");
    }

    if (shopIds != null && !shopIds.isEmpty()) {
      where.append(" AND i.shop_id IN (");
      for (int i = 0; i < shopIds.size(); i++) {
        where.append(":shopId").append(i);
        if (i < shopIds.size() - 1) where.append(",");
      }
      where.append(")");
    }

    if (status != null && !status.trim().isEmpty()) {
      where.append(" AND i.status = :status");
    }

    if (searchTerm != null && !searchTerm.trim().isEmpty()) {
      where.append(
          " AND (i.customer_name LIKE :pattern OR i.customer_mobile LIKE :pattern"
              + " OR i.invoice_no LIKE :pattern OR i.sales_order_number LIKE :pattern)");
    }

    // JOIN invoices → sales_order on the natural key
    String baseFrom = " FROM invoices i"
        + " LEFT JOIN sales_order so ON so.order_number = i.sales_order_number";

    String dataSql  = "SELECT i.*, so.status AS order_status,"
        + " so.cancellation_reason, so.receiving_printed"
        + baseFrom + where
        + " ORDER BY i.billing_date DESC LIMIT :size OFFSET :offset";

    String countSql = "SELECT COUNT(*)" + baseFrom + where;

    java.util.function.Function<DatabaseClient.GenericExecuteSpec, DatabaseClient.GenericExecuteSpec> bindParams =
        spec -> {
          DatabaseClient.GenericExecuteSpec s = spec;
          if (shopIds != null) {
            for (int i = 0; i < shopIds.size(); i++) s = s.bind("shopId" + i, shopIds.get(i));
          }
          if (status != null && !status.trim().isEmpty())   s = s.bind("status", status.trim());
          if (searchTerm != null && !searchTerm.trim().isEmpty())
            s = s.bind("pattern", "%" + searchTerm.trim() + "%");
          return s;
        };

    Flux<com.vy.sales.billing.dto.BillingHistoryRow> data = bindParams
        .apply(databaseClient.sql(dataSql))
        .bind("size", size)
        .bind("offset", offset)
        .map((row, meta) -> com.vy.sales.billing.dto.BillingHistoryRow.builder()
            .invoiceId(row.get("id", Long.class))
            .invoiceNo(row.get("invoice_no", String.class))
            .salesOrderNumber(row.get("sales_order_number", String.class))
            .eventId(row.get("event_id", Long.class))
            .shopId(row.get("shop_id", Long.class) != null
                ? String.valueOf(row.get("shop_id", Long.class)) : null)
            .sellerId(row.get("seller_id", Long.class))
            .sellerName(row.get("seller_name", String.class))
            .billedBy(row.get("billed_by", Long.class))
            .customerName(row.get("customer_name", String.class))
            .customerMobile(row.get("customer_mobile", String.class))
            .customerGstin(row.get("customer_gstin", String.class))
            .subtotalAmount(row.get("subtotal_amount", java.math.BigDecimal.class))
            .discountAmount(row.get("discount_amount", java.math.BigDecimal.class))
            .taxAmount(row.get("tax_amount", java.math.BigDecimal.class))
            .netAmount(row.get("net_amount", java.math.BigDecimal.class))
            .invoiceStatus(row.get("status", String.class))
            .billingDate(row.get("billing_date", LocalDateTime.class))
            .createdAt(row.get("created_at", LocalDateTime.class))
            .updatedAt(row.get("updated_at", LocalDateTime.class))
            // sales_order JOIN columns
            .orderStatus(row.get("order_status", String.class))
            .cancellationReason(row.get("cancellation_reason", String.class))
            .receivingPrinted(row.get("receiving_printed", Boolean.class))
            .build())
        .all();

    Mono<Long> total = bindParams
        .apply(databaseClient.sql(countSql))
        .map((row, meta) -> row.get(0, Long.class))
        .one();

    return Mono.zip(data.collectList(), total)
        .map(tuple -> {
          java.util.Map<String, Object> result = new java.util.HashMap<>();
          result.put("content", tuple.getT1());
          result.put("page", page);
          result.put("size", size);
          result.put("totalElements", tuple.getT2());
          result.put("totalPages", (int) Math.ceil((double) tuple.getT2() / size));
          return result;
        });
  }

  public Mono<java.util.Map<String, Object>> searchInvoices(
      java.util.List<Long> eventIds,
      java.util.List<String> shopIds,
      String status,
      String searchTerm,
      int page,
      int size) {

    // Build the shared WHERE clause (no ORDER BY / LIMIT yet)
    StringBuilder where = new StringBuilder(" WHERE 1=1");

    // eventIds: safe — Long values only, no injection risk
    if (eventIds != null && !eventIds.isEmpty()) {
      where.append(" AND event_id IN (");
      for (int i = 0; i < eventIds.size(); i++) {
        where.append(eventIds.get(i));
        if (i < eventIds.size() - 1) where.append(",");
      }
      where.append(")");
    }

    // shopIds: bind as named params to prevent SQL injection
    if (shopIds != null && !shopIds.isEmpty()) {
      where.append(" AND shop_id IN (");
      for (int i = 0; i < shopIds.size(); i++) {
        where.append(":shopId").append(i);
        if (i < shopIds.size() - 1) where.append(",");
      }
      where.append(")");
    }

    if (status != null && !status.trim().isEmpty()) {
      where.append(" AND status = :status");
    }

    if (searchTerm != null && !searchTerm.trim().isEmpty()) {
      where.append(
          " AND (customer_name LIKE :pattern OR customer_mobile LIKE :pattern"
              + " OR invoice_no LIKE :pattern OR sales_order_number LIKE :pattern)");
    }

    long offset = (long) page * size;
    String dataSql  = "SELECT * FROM invoices" + where + " ORDER BY billing_date DESC LIMIT :size OFFSET :offset";
    String countSql = "SELECT COUNT(*) FROM invoices" + where;

    // Helper to bind all shared params onto a spec
    java.util.function.Function<DatabaseClient.GenericExecuteSpec, DatabaseClient.GenericExecuteSpec> bindParams =
        spec -> {
          DatabaseClient.GenericExecuteSpec s = spec;
          if (shopIds != null && !shopIds.isEmpty()) {
            for (int i = 0; i < shopIds.size(); i++) {
              s = s.bind("shopId" + i, shopIds.get(i));
            }
          }
          if (status != null && !status.trim().isEmpty()) {
            s = s.bind("status", status.trim());
          }
          if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            s = s.bind("pattern", "%" + searchTerm.trim() + "%");
          }
          return s;
        };

    Flux<Invoice> data = bindParams
        .apply(databaseClient.sql(dataSql))
        .bind("size", size)
        .bind("offset", offset)
        .map(
            (row, meta) ->
                Invoice.builder()
                    .id(row.get("id", Long.class))
                    .invoiceNo(row.get("invoice_no", String.class))
                    .salesOrderNumber(row.get("sales_order_number", String.class))
                    .eventId(row.get("event_id", Long.class))
                    .shopId(
                        row.get("shop_id", Long.class) != null
                            ? String.valueOf(row.get("shop_id", Long.class))
                            : null)
                    .sellerId(row.get("seller_id", Long.class))
                    .sellerName(row.get("seller_name", String.class))
                    .billedBy(row.get("billed_by", Long.class))
                    .customerName(row.get("customer_name", String.class))
                    .customerMobile(row.get("customer_mobile", String.class))
                    .customerGstin(row.get("customer_gstin", String.class))
                    .subtotalAmount(row.get("subtotal_amount", java.math.BigDecimal.class))
                    .discountAmount(row.get("discount_amount", java.math.BigDecimal.class))
                    .taxAmount(row.get("tax_amount", java.math.BigDecimal.class))
                    .netAmount(row.get("net_amount", java.math.BigDecimal.class))
                    .status(row.get("status", String.class))
                    .billingDate(row.get("billing_date", java.time.LocalDateTime.class))
                    .createdAt(row.get("created_at", java.time.LocalDateTime.class))
                    .updatedAt(row.get("updated_at", java.time.LocalDateTime.class))
                    .build())
        .all();

    Mono<Long> total = bindParams
        .apply(databaseClient.sql(countSql))
        .map((row, meta) -> row.get(0, Long.class))
        .one();

    return Mono.zip(data.collectList(), total)
        .map(tuple -> {
          java.util.Map<String, Object> result = new java.util.HashMap<>();
          result.put("content", tuple.getT1());
          result.put("page", page);
          result.put("size", size);
          result.put("totalElements", tuple.getT2());
          result.put("totalPages", (int) Math.ceil((double) tuple.getT2() / size));
          return result;
        });
  }
}
