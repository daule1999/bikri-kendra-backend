package com.vy.sales.billing.controller;

import com.vy.sales.billing.dto.CreateInvoiceRequest;
import com.vy.sales.billing.dto.ReturnInvoiceRequest;
import com.vy.sales.billing.entity.Invoice;
import com.vy.sales.billing.entity.InvoiceItem;
import com.vy.sales.billing.service.InvoiceService;
import com.vy.sales.billing.util.AppConstants;
import com.vy.sales.platform.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/billing-svc/invoices")
@RequiredArgsConstructor
public class InvoiceController {

  private final InvoiceService invoiceService;
  private final JwtUtil jwtUtil;

  @PostMapping
  public Mono<Invoice> create(@RequestBody @Valid CreateInvoiceRequest request) {

    log.info(
        "Received create invoice request salesOrderNumber={}, counterNo={}, sellerId={}",
        request.getSalesOrderNumber(),
        request.getShopId(),
        request.getSellerId());

    return invoiceService
        .createInvoice(request)
        .doOnSubscribe(
            sub ->
                log.debug(
                    "Processing invoice creation for salesOrderNumber={}",
                    request.getSalesOrderNumber()))
        .doOnSuccess(
            invoice ->
                log.info(
                    "Invoice created successfully invoiceId={}, invoiceNo={}, status={}",
                    invoice.getId(),
                    invoice.getInvoiceNo(),
                    invoice.getStatus()))
        .doOnError(
            ex ->
                log.error(
                    "Invoice creation failed for salesOrderNumber={}",
                    request.getSalesOrderNumber(),
                    ex));
  }

  @GetMapping
  public Flux<Invoice> list(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    log.debug("Received request to list invoices for eventId={}", eventId);

    return invoiceService
        .listInvoices(eventId)
        .doOnSubscribe(sub -> log.debug("Fetching invoices from database"))
        .doOnComplete(() -> log.debug("Completed fetching invoices"))
        .doOnError(ex -> log.error("Failed to fetch invoices", ex));
  }

  @GetMapping("/search")
  public Flux<Invoice> search(
      @RequestParam(value = "eventIds", required = false) java.util.List<Long> eventIds,
      @RequestParam(value = "shopIds", required = false) java.util.List<String> shopIds,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "searchTerm", required = false) String searchTerm) {
    log.info(
        "Received request to search invoices: eventIds={}, shopIds={}, status={}, searchTerm={}",
        eventIds,
        shopIds,
        status,
        searchTerm);
    return invoiceService.searchInvoices(eventIds, shopIds, status, searchTerm);
  }

  @GetMapping("/order/{orderNumber}/items")
  public Flux<InvoiceItem> getItemsByOrderNumber(
      @PathVariable String orderNumber,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    log.info(
        "Received request to fetch items for orderNumber={}, eventId={}", orderNumber, eventId);

    return invoiceService
        .getItemsByOrderNumber(orderNumber, eventId)
        .doOnSubscribe(sub -> log.debug("Fetching items for orderNumber={}", orderNumber))
        .doOnComplete(() -> log.debug("Completed fetching items for orderNumber={}", orderNumber))
        .doOnError(ex -> log.error("Failed to fetch items for orderNumber={}", orderNumber, ex));
  }

  @PutMapping("/order/{orderNumber}/cancel")
  public Mono<Invoice> cancelInvoice(
      @PathVariable String orderNumber,
      @RequestParam String reason,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info(
        "Received request to cancel invoice for orderNumber={} eventId={}", orderNumber, eventId);
    return Mono.fromCallable(() -> jwtUtil.extractUserId(authHeader.substring(7)))
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(userId -> invoiceService.cancelInvoice(orderNumber, reason, userId, eventId));
  }

  /**
   * Saga compensation: reinstate a CANCELLED invoice back to PAID. Called by sales-service when the
   * final DB save in cancelSale fails after the invoice was already cancelled, to keep billing
   * consistent with the order's CONFIRMED status.
   */
  @PutMapping("/order/{orderNumber}/reinstate")
  public Mono<Invoice> reinstateInvoice(
      @PathVariable String orderNumber,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info(
        "Received request to reinstate invoice for orderNumber={} eventId={}",
        orderNumber,
        eventId);
    Long userId = jwtUtil.extractUserId(authHeader.substring(7));
    return invoiceService.reinstateInvoice(orderNumber, userId, eventId);
  }

  @PutMapping("/order/{orderNumber}/return")
  public Mono<Invoice> returnInvoice(
      @PathVariable String orderNumber,
      @RequestBody ReturnInvoiceRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {
    log.info(
        "Received request to process return for orderNumber={} eventId={}", orderNumber, eventId);
    return Mono.fromCallable(() -> jwtUtil.extractUserId(authHeader.substring(7)))
        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        .flatMap(userId -> invoiceService.processReturn(orderNumber, request, userId, eventId));
  }
}
