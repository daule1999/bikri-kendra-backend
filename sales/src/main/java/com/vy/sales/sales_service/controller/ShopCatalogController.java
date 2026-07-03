package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.dto.ShopCatalogDto;
import com.vy.sales.sales_service.dto.ShopStockResponse;
import com.vy.sales.sales_service.service.InvoiceSequenceService;
import com.vy.sales.sales_service.service.SalesService;
import com.vy.sales.sales_service.service.ShopShiftSessionService;
import com.vy.sales.sales_service.util.AppConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Unified catalog endpoint.
 *
 * <p>Returns products, shop stocks, and the next preview invoice number in a single call. Each
 * source runs concurrently via {@link Mono#zip}. Partial failures are isolated — a slow or failing
 * inventory-service response does NOT fail the whole request; the corresponding field will be an
 * empty list / empty string.
 *
 * <p>Path: {@code GET /api/sales-svc/retail/shop/{shopId}/catalog}
 */
@RestController
@RequestMapping("/api/sales-svc/retail/shop")
@RequiredArgsConstructor
@Slf4j
public class ShopCatalogController {

  private final SalesService salesService;
  private final InventoryClient inventoryClient;
  private final ShopShiftSessionService shopShiftSessionService;
  private final InvoiceSequenceService invoiceSequenceService;

  /**
   * Returns a {@link ShopCatalogDto} with:
   *
   * <ul>
   *   <li>{@code products} — all inventory products for the event (for type-ahead search)
   *   <li>{@code stocks} — shop-specific counter stocks (filtered to positive quantities by caller)
   *   <li>{@code nextInvoiceNumber} — preview of the next invoice number (read-only, no counter
   *       advance)
   * </ul>
   *
   * @param shopId the shop identifier
   * @param eventId the active event (from header)
   * @param authHeader the Bearer token, forwarded to downstream services
   * @return a {@link Mono} emitting the unified catalog DTO
   */
  @GetMapping("/{shopId}/catalog")
  public Mono<ShopCatalogDto> getShopCatalog(
      @PathVariable Long shopId,
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

    log.info("CATALOG_REQUEST shopId={} eventId={}", shopId, eventId);

    // ── 1. All inventory products (for the global type-ahead search) ──────────
    Mono<List<InventoryClient.ProductDTO>> productsMono =
        inventoryClient
            .getAllProducts(eventId, authHeader)
            .map(
                resp ->
                    resp.getData() != null ? resp.getData() : List.<InventoryClient.ProductDTO>of())
            .onErrorResume(
                ex -> {
                  log.warn(
                      "CATALOG_PRODUCTS_FETCH_FAILED shopId={} eventId={} reason={}",
                      shopId,
                      eventId,
                      ex.getMessage());
                  return Mono.just(List.of());
                });

    // ── 2. Shop-specific counter stocks ───────────────────────────────────────
    Mono<List<ShopStockResponse>> stocksMono =
        salesService
            .getShopStocks(shopId, eventId, authHeader)
            .collectList()
            .onErrorResume(
                ex -> {
                  log.warn(
                      "CATALOG_STOCKS_FETCH_FAILED shopId={} eventId={} reason={}",
                      shopId,
                      eventId,
                      ex.getMessage());
                  return Mono.just(List.of());
                });

    // ── 3. Next invoice number (read-only peek — counter not advanced) ─────────
    Mono<String> nextInvMono =
        shopShiftSessionService
            .getActiveSessionBasic(shopId, eventId)
            .flatMap(
                session ->
                    invoiceSequenceService.peekNextFormatted(shopId, eventId, session.getId()))
            .defaultIfEmpty("")
            .onErrorResume(
                ex -> {
                  log.warn(
                      "CATALOG_INVOICE_PEEK_FAILED shopId={} eventId={} reason={}",
                      shopId,
                      eventId,
                      ex.getMessage());
                  return Mono.just("");
                });

    // ── Zip and assemble ──────────────────────────────────────────────────────
    return Mono.zip(productsMono, stocksMono, nextInvMono)
        .map(
            tuple ->
                ShopCatalogDto.builder()
                    .products(tuple.getT1())
                    .stocks(tuple.getT2())
                    .nextInvoiceNumber(tuple.getT3())
                    .build())
        .doOnSuccess(
            dto ->
                log.info(
                    "CATALOG_SUCCESS shopId={} products={} stocks={} invoice={}",
                    shopId,
                    dto.getProducts().size(),
                    dto.getStocks().size(),
                    dto.getNextInvoiceNumber()));
  }
}
