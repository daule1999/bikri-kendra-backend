package com.vy.sales.sales_service.service;

import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.dto.EventResponse;
import com.vy.sales.sales_service.dto.ProductSalesSummaryDTO;
import com.vy.sales.sales_service.dto.report.AuditReportDto;
import com.vy.sales.sales_service.dto.report.AuditRowDto;
import com.vy.sales.sales_service.dto.report.FinancialReportDto;
import com.vy.sales.sales_service.dto.report.FinancialShiftDto;
import com.vy.sales.sales_service.dto.report.InventoryEventSummaryDTO;
import com.vy.sales.sales_service.dto.report.MasterSettlementDto;
import com.vy.sales.sales_service.dto.report.SalesReportDto;
import com.vy.sales.sales_service.dto.report.SalesReportRowDto;
import com.vy.sales.sales_service.dto.report.SalesReportSummaryDto;
import com.vy.sales.sales_service.dto.report.ThreeWayMatchDto;
import com.vy.sales.sales_service.model.Shop;
import com.vy.sales.sales_service.model.ShopShiftSession;
import com.vy.sales.sales_service.repository.SalesAnalyticsRepository;
import com.vy.sales.sales_service.repository.ShopRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

  private final EventService eventService;
  private final SalesService salesService;
  private final ShopShiftSessionService shopShiftSessionService;
  private final ShopRepository shopRepository;
  private final UserClient userClient;
  private final InventoryClient inventoryClient;
  private final WebClient.Builder webClientBuilder;

  // MONOLITH: inventory/product/category/user data now comes from the merged bikri_db via
  // direct SQL (single database round-trip) instead of HTTP calls to the former services.
  private final SalesAnalyticsRepository salesAnalyticsRepository;

  @Value("${services.inventory.base-url:http://inventory-service:8082}")
  private String inventoryServiceUrl;

  @Override
  public Mono<MasterSettlementDto> getMasterSettlement(Long eventId) {
    log.info("Fetching Master Settlement for eventId: {}", eventId);

    Mono<EventResponse> eventMono =
        eventService
            .getById(eventId)
            .onErrorReturn(EventResponse.builder().id(eventId).eventName("Unknown Event").build());

    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, null, null)
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Sales service unavailable",
                        e));

    Mono<List<InventoryEventSummaryDTO>> inventoryMono =
        salesAnalyticsRepository.getInventoryEventSummary(eventId).collectList();

    Mono<List<ShopShiftSession>> shiftMono =
        shopShiftSessionService
            .getSessionsByEvent(eventId)
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Shift service unavailable",
                        e));

    Mono<List<Shop>> shopsMono =
        shopRepository.findByEventId(eventId).collectList().onErrorReturn(List.of());

    return Mono.zip(eventMono, salesMono, inventoryMono, shiftMono, shopsMono)
        .map(
            tuple -> {
              var event = tuple.getT1();
              var salesList = tuple.getT2();
              var inventoryList = tuple.getT3();
              var shiftList = tuple.getT4();
              var shops = tuple.getT5();

              java.util.Map<Long, Shop> shopLookup =
                  shops.stream().collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));

              BigDecimal totalExpectedSales =
                  salesList.stream()
                      .map(ProductSalesSummaryDTO::getTotalCollected)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);

              BigDecimal totalActualCollected =
                  shiftList.stream()
                      .map(
                          s -> {
                            if ("OPEN".equals(s.getStatus())) {
                              BigDecimal expectedCashSales =
                                  (s.getExpectedClosingCash() != null
                                          ? s.getExpectedClosingCash()
                                          : BigDecimal.ZERO)
                                      .subtract(
                                          s.getOpeningCashBalance() != null
                                              ? s.getOpeningCashBalance()
                                              : BigDecimal.ZERO);
                              BigDecimal expectedOnline =
                                  s.getExpectedClosingOnline() != null
                                      ? s.getExpectedClosingOnline()
                                      : BigDecimal.ZERO;
                              return expectedCashSales.add(expectedOnline);
                            } else {
                              BigDecimal actualCash =
                                  s.getActualClosingCash() != null
                                      ? s.getActualClosingCash()
                                      : BigDecimal.ZERO;
                              BigDecimal opening =
                                  s.getOpeningCashBalance() != null
                                      ? s.getOpeningCashBalance()
                                      : BigDecimal.ZERO;
                              BigDecimal actualOnline =
                                  s.getActualClosingOnline() != null
                                      ? s.getActualClosingOnline()
                                      : BigDecimal.ZERO;
                              return actualCash.subtract(opening).add(actualOnline);
                            }
                          })
                      .reduce(BigDecimal.ZERO, BigDecimal::add);

              BigDecimal totalVariance = totalActualCollected.subtract(totalExpectedSales);

              Integer totalItemsSold =
                  salesList.stream()
                      .mapToInt(s -> s.getSoldQty() != null ? s.getSoldQty().intValue() : 0)
                      .sum();

              Integer totalItemsDepleted =
                  inventoryList.stream()
                      .mapToInt(i -> i.getDepletedQuantity() != null ? i.getDepletedQuantity() : 0)
                      .sum();

              // Group by Shop for shop settlements
              java.util.Map<Long, MasterSettlementDto.ShopSettlement> shopMap =
                  new java.util.HashMap<>();

              for (var sale : salesList) {
                Long shopId = sale.getShopId();
                var settlement =
                    shopMap.computeIfAbsent(
                        shopId,
                        id -> {
                          Shop shop = shopLookup.get(id);
                          return MasterSettlementDto.ShopSettlement.builder()
                              .shopId(id)
                              .shopName(shop != null ? shop.getShopName() : "Shop " + id)
                              .expectedAmount(BigDecimal.ZERO)
                              .collectedAmount(BigDecimal.ZERO)
                              .build();
                        });
                settlement.setExpectedAmount(
                    settlement
                        .getExpectedAmount()
                        .add(
                            sale.getTotalCollected() != null
                                ? sale.getTotalCollected()
                                : BigDecimal.ZERO));
              }

              for (var shift : shiftList) {
                Long shopId = shift.getShopId();
                var settlement =
                    shopMap.computeIfAbsent(
                        shopId,
                        id -> {
                          Shop shop = shopLookup.get(id);
                          return MasterSettlementDto.ShopSettlement.builder()
                              .shopId(id)
                              .shopName(shop != null ? shop.getShopName() : "Shop " + id)
                              .expectedAmount(BigDecimal.ZERO)
                              .collectedAmount(BigDecimal.ZERO)
                              .build();
                        });
                if ("OPEN".equals(shift.getStatus())) {
                  BigDecimal expectedCashSales =
                      (shift.getExpectedClosingCash() != null
                              ? shift.getExpectedClosingCash()
                              : BigDecimal.ZERO)
                          .subtract(
                              shift.getOpeningCashBalance() != null
                                  ? shift.getOpeningCashBalance()
                                  : BigDecimal.ZERO);
                  BigDecimal expectedOnline =
                      shift.getExpectedClosingOnline() != null
                          ? shift.getExpectedClosingOnline()
                          : BigDecimal.ZERO;
                  settlement.setCollectedAmount(
                      settlement.getCollectedAmount().add(expectedCashSales).add(expectedOnline));
                } else {
                  BigDecimal actualCash =
                      shift.getActualClosingCash() != null
                          ? shift.getActualClosingCash()
                          : BigDecimal.ZERO;
                  BigDecimal opening =
                      shift.getOpeningCashBalance() != null
                          ? shift.getOpeningCashBalance()
                          : BigDecimal.ZERO;
                  BigDecimal actualOnline =
                      shift.getActualClosingOnline() != null
                          ? shift.getActualClosingOnline()
                          : BigDecimal.ZERO;
                  settlement.setCollectedAmount(
                      settlement
                          .getCollectedAmount()
                          .add(actualCash.subtract(opening))
                          .add(actualOnline));
                }
              }

              List<MasterSettlementDto.ShopSettlement> shopSettlements =
                  shopMap.values().stream()
                      .peek(
                          s ->
                              s.setVariance(s.getCollectedAmount().subtract(s.getExpectedAmount())))
                      .toList();

              return MasterSettlementDto.builder()
                  .eventId(event.getId())
                  .eventName(event.getEventName())
                  .totalExpectedSales(totalExpectedSales)
                  .totalActualCollected(totalActualCollected)
                  .totalVariance(totalVariance)
                  .totalItemsSold(totalItemsSold)
                  .totalItemsDepleted(totalItemsDepleted)
                  .shopSettlements(shopSettlements)
                  .build();
            });
  }

  @Override
  public Mono<ThreeWayMatchDto> getThreeWayMatch(Long eventId, String authHeader) {
    log.info("Fetching 3-Way Match for eventId: {}", eventId);

    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, null, null)
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Sales service unavailable",
                        e));

    Mono<List<InventoryEventSummaryDTO>> inventoryMono =
        salesAnalyticsRepository.getInventoryEventSummary(eventId).collectList();

    Mono<List<InventoryClient.ProductDTO>> productsMono =
        salesAnalyticsRepository.getProductCatalog().collectList();

    return Mono.zip(salesMono, inventoryMono, productsMono)
        .map(
            tuple -> {
              var salesList = tuple.getT1();
              var inventoryList = tuple.getT2();
              var products = tuple.getT3();

              java.util.Map<Long, InventoryClient.ProductDTO> productMap =
                  products.stream()
                      .collect(
                          Collectors.toMap(InventoryClient.ProductDTO::getId, p -> p, (a, b) -> a));

              java.util.Map<Long, ThreeWayMatchDto.MatchItem> matchMap = new java.util.HashMap<>();

              for (var sale : salesList) {
                Long pId = sale.getProductId();
                var item =
                    matchMap.computeIfAbsent(
                        pId,
                        id -> {
                          InventoryClient.ProductDTO productRef = productMap.get(id);
                          return ThreeWayMatchDto.MatchItem.builder()
                              .productId(id)
                              .productName(
                                  productRef != null && productRef.getName() != null
                                      ? productRef.getName()
                                      : (sale.getProductName() != null
                                          ? sale.getProductName()
                                          : "Product " + id))
                              .expectedSalesQty(0)
                              .actualInventoryDepletedQty(0)
                              .expectedSalesAmount(BigDecimal.ZERO)
                              .actualFinancialsCollected(BigDecimal.ZERO)
                              .build();
                        });

                item.setExpectedSalesQty(
                    item.getExpectedSalesQty()
                        + (sale.getSoldQty() != null ? sale.getSoldQty().intValue() : 0));
                // Assuming expected sales amount is what the POS rang up, but we collected might be
                // the same here
                item.setExpectedSalesAmount(
                    item.getExpectedSalesAmount()
                        .add(
                            sale.getTotalCollected() != null
                                ? sale.getTotalCollected()
                                : BigDecimal.ZERO));
                item.setActualFinancialsCollected(
                    item.getActualFinancialsCollected()
                        .add(
                            sale.getTotalCollected() != null
                                ? sale.getTotalCollected()
                                : BigDecimal.ZERO));
              }

              for (var inv : inventoryList) {
                Long pId = inv.getProductId();
                var item =
                    matchMap.computeIfAbsent(
                        pId,
                        id -> {
                          InventoryClient.ProductDTO productRef = productMap.get(id);
                          return ThreeWayMatchDto.MatchItem.builder()
                              .productId(id)
                              .productName(
                                  productRef != null ? productRef.getName() : "Product " + id)
                              .expectedSalesQty(0)
                              .actualInventoryDepletedQty(0)
                              .expectedSalesAmount(BigDecimal.ZERO)
                              .actualFinancialsCollected(BigDecimal.ZERO)
                              .build();
                        });

                item.setActualInventoryDepletedQty(
                    item.getActualInventoryDepletedQty()
                        + (inv.getDepletedQuantity() != null ? inv.getDepletedQuantity() : 0));
              }

              BigDecimal totalVarianceAmount = BigDecimal.ZERO;
              for (ThreeWayMatchDto.MatchItem item : matchMap.values()) {
                item.setQtyVariance(
                    item.getExpectedSalesQty() - item.getActualInventoryDepletedQty());
                InventoryClient.ProductDTO productRef = productMap.get(item.getProductId());
                BigDecimal sellingPrice =
                    (productRef != null && productRef.getSellingPrice() != null)
                        ? productRef.getSellingPrice()
                        : BigDecimal.ZERO;
                BigDecimal calculatedVariance =
                    BigDecimal.valueOf(item.getQtyVariance()).multiply(sellingPrice);
                item.setFinancialVariance(calculatedVariance);
                totalVarianceAmount = totalVarianceAmount.add(calculatedVariance);
              }

              return ThreeWayMatchDto.builder()
                  .eventId(eventId)
                  .totalVarianceAmount(totalVarianceAmount)
                  .items(new java.util.ArrayList<>(matchMap.values()))
                  .build();
            });
  }

  @Override
  public Mono<Object> getLiveSnapshot(Long shopId, Long eventId) {
    log.info("Fetching Live Snapshot for shopId: {}, eventId: {}", shopId, eventId);

    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, shopId, null)
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Sales service unavailable",
                        e));

    Mono<List<InventoryEventSummaryDTO>> inventoryMono =
        salesAnalyticsRepository
            .getInventoryEventSummary(eventId)
            .filter(inv -> shopId.equals(inv.getShopId()))
            .collectList();

    return Mono.zip(salesMono, inventoryMono)
        .map(
            tuple -> {
              var salesList = tuple.getT1();
              var inventoryList = tuple.getT2();

              int salesRegistered =
                  salesList.stream()
                      .mapToInt(
                          sale -> sale.getSoldQty() != null ? sale.getSoldQty().intValue() : 0)
                      .sum();

              int inventoryDepleted =
                  inventoryList.stream()
                      .mapToInt(
                          inv -> inv.getDepletedQuantity() != null ? inv.getDepletedQuantity() : 0)
                      .sum();

              int currentVariance = salesRegistered - inventoryDepleted;

              return (Object)
                  java.util.Map.of(
                      "shopId", shopId,
                      "status", "ACTIVE",
                      "inventoryDepleted", inventoryDepleted,
                      "salesRegistered", salesRegistered,
                      "currentVariance", currentVariance);
            });
  }

  @Override
  public Mono<Object> getLiveTallyUp(Long shopId, Long eventId) {
    log.info("Fetching Live Tally-Up for shopId: {}, eventId: {}", shopId, eventId);

    Mono<List<ShopShiftSession>> shiftMono =
        shopShiftSessionService
            .getSessionsByEvent(eventId)
            .filter(shift -> shopId.equals(shift.getShopId()))
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Shift service unavailable",
                        e));

    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, shopId, null)
            .collectList()
            .onErrorMap(
                e ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Sales service unavailable",
                        e));

    return Mono.zip(shiftMono, salesMono)
        .map(
            tuple -> {
              var shifts = tuple.getT1();
              var salesList = tuple.getT2();

              BigDecimal expectedCashInTill =
                  shifts.stream()
                      .map(
                          shift ->
                              shift.getExpectedClosingCash() != null
                                  ? shift.getExpectedClosingCash()
                                  : BigDecimal.ZERO)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);

              BigDecimal actualCashDropped =
                  shifts.stream()
                      .map(
                          shift -> {
                            if ("OPEN".equals(shift.getStatus())) {
                              return shift.getExpectedClosingCash() != null
                                  ? shift.getExpectedClosingCash()
                                  : BigDecimal.ZERO;
                            } else {
                              return shift.getActualClosingCash() != null
                                  ? shift.getActualClosingCash()
                                  : BigDecimal.ZERO;
                            }
                          })
                      .reduce(BigDecimal.ZERO, BigDecimal::add);

              BigDecimal expectedTotalSales =
                  salesList.stream()
                      .map(
                          sale ->
                              sale.getTotalCollected() != null
                                  ? sale.getTotalCollected()
                                  : BigDecimal.ZERO)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);

              BigDecimal variance = actualCashDropped.subtract(expectedCashInTill);
              String varianceAlert = variance.compareTo(BigDecimal.ZERO) < 0 ? "SHORTAGE" : "OK";

              return (Object)
                  java.util.Map.of(
                      "shopId", shopId,
                      "expectedCashInTill", expectedCashInTill,
                      "actualCashDropped", actualCashDropped,
                      "inventoryValueDepleted", expectedTotalSales,
                      "variance", variance,
                      "varianceAlert", varianceAlert);
            });
  }

  @Override
  public Mono<Object> getOperatorTrustScore(Long userId, Long eventId) {
    log.info("Fetching Operator Trust Score for userId: {}, eventId: {}", userId, eventId);

    return shopShiftSessionService
        .getSessionsByEvent(eventId)
        .filter(
            shift ->
                userId.equals(shift.getClosedByUserId())
                    || userId.equals(shift.getOpenedByUserId()))
        .collectList()
        .map(
            shifts -> {
              long negativeShifts =
                  shifts.stream()
                      .filter(
                          shift -> {
                            if ("OPEN".equals(shift.getStatus())) {
                              return false;
                            }
                            BigDecimal expected =
                                shift.getExpectedClosingCash() != null
                                    ? shift.getExpectedClosingCash()
                                    : BigDecimal.ZERO;
                            BigDecimal actual =
                                shift.getActualClosingCash() != null
                                    ? shift.getActualClosingCash()
                                    : BigDecimal.ZERO;
                            return actual.subtract(expected).compareTo(BigDecimal.ZERO) < 0;
                          })
                      .count();

              long trustScore = 100 - (negativeShifts * 5);
              if (trustScore < 0) trustScore = 0;

              return (Object)
                  java.util.Map.of(
                      "userId", userId,
                      "trustScore", trustScore,
                      "historicalVariances", negativeShifts,
                      "status", trustScore < 70 ? "NEEDS_REVIEW" : "GOOD_STANDING");
            })
        .onErrorMap(
            e ->
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Shift service unavailable",
                    e));
  }

  // ── NEW: Sales Report ─────────────────────────────────────────────────────

  @Override
  public Mono<SalesReportDto> getSalesReport(
      Long eventId,
      Long shopId,
      Long categoryId,
      Long productId,
      Long shiftSessionId,
      String authHeader) {
    log.info(
        "getSalesReport eventId={} shopId={} categoryId={} productId={} shiftSessionId={}",
        eventId,
        shopId,
        categoryId,
        productId,
        shiftSessionId);

    // 1. Sales data from own DB
    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, shopId, shiftSessionId)
            .collectList()
            .onErrorReturn(List.of());

    // 2. Inventory issued data from inventory-service.
    // IMPORTANT: this used to be .onErrorReturn(List.of()) — an inventory-service hiccup
    // silently rendered the whole report with zero issued/remaining quantities, which users
    // saw as "conflicting report data". Fail loudly instead so the UI can show an error.
    Mono<List<InventoryEventSummaryDTO>> inventoryMono =
        salesAnalyticsRepository.getInventoryEventSummary(eventId).collectList();

    // 3. Shop metadata
    Mono<List<Shop>> shopsMono =
        shopRepository.findByEventId(eventId).collectList().onErrorReturn(List.of());

    // 4. Products list from inventory-service to resolve product names & category mapping
    Mono<List<InventoryClient.ProductDTO>> productsMono =
        salesAnalyticsRepository.getProductCatalog().collectList();

    return Mono.zip(salesMono, inventoryMono, shopsMono, productsMono)
        .map(
            tuple -> {
              List<ProductSalesSummaryDTO> salesList = tuple.getT1();
              List<InventoryEventSummaryDTO> inventoryList = tuple.getT2();
              List<Shop> shops = tuple.getT3();
              List<InventoryClient.ProductDTO> products = tuple.getT4();

              // Build product lookup map for names and category
              Map<Long, InventoryClient.ProductDTO> productMap =
                  products.stream()
                      .collect(
                          Collectors.toMap(InventoryClient.ProductDTO::getId, p -> p, (a, b) -> a));

              // Build inventory lookup: (productId, shopId) -> InventoryEventSummaryDTO
              Map<String, InventoryEventSummaryDTO> invMap = new HashMap<>();
              for (InventoryEventSummaryDTO inv : inventoryList) {
                if (inv.getProductId() != null && inv.getShopId() != null) {
                  invMap.put(inv.getProductId() + "-" + inv.getShopId(), inv);
                }
              }

              // Build sales lookup: (productId, shopId) -> ProductSalesSummaryDTO (aggregated
              // across shift sessions)
              Map<String, ProductSalesSummaryDTO> salesMap = new HashMap<>();
              for (ProductSalesSummaryDTO sale : salesList) {
                if (sale.getProductId() != null && sale.getShopId() != null) {
                  String key = sale.getProductId() + "-" + sale.getShopId();
                  if (salesMap.containsKey(key)) {
                    ProductSalesSummaryDTO existing = salesMap.get(key);
                    existing.setSoldQty(
                        existing.getSoldQty()
                            + (sale.getSoldQty() != null ? sale.getSoldQty() : 0L));
                    existing.setReturnedQty(
                        existing.getReturnedQty()
                            + (sale.getReturnedQty() != null ? sale.getReturnedQty() : 0L));
                    existing.setCashCollected(
                        existing
                            .getCashCollected()
                            .add(
                                sale.getCashCollected() != null
                                    ? sale.getCashCollected()
                                    : BigDecimal.ZERO));
                    existing.setOnlineCollected(
                        existing
                            .getOnlineCollected()
                            .add(
                                sale.getOnlineCollected() != null
                                    ? sale.getOnlineCollected()
                                    : BigDecimal.ZERO));
                    existing.setTotalCollected(
                        existing
                            .getTotalCollected()
                            .add(
                                sale.getTotalCollected() != null
                                    ? sale.getTotalCollected()
                                    : BigDecimal.ZERO));
                  } else {
                    ProductSalesSummaryDTO copy =
                        ProductSalesSummaryDTO.builder()
                            .productId(sale.getProductId())
                            .productName(sale.getProductName())
                            .shopId(sale.getShopId())
                            .shiftSessionId(shiftSessionId)
                            .soldQty(sale.getSoldQty() != null ? sale.getSoldQty() : 0L)
                            .returnedQty(sale.getReturnedQty() != null ? sale.getReturnedQty() : 0L)
                            .cashCollected(
                                sale.getCashCollected() != null
                                    ? sale.getCashCollected()
                                    : BigDecimal.ZERO)
                            .onlineCollected(
                                sale.getOnlineCollected() != null
                                    ? sale.getOnlineCollected()
                                    : BigDecimal.ZERO)
                            .totalCollected(
                                sale.getTotalCollected() != null
                                    ? sale.getTotalCollected()
                                    : BigDecimal.ZERO)
                            .build();
                    salesMap.put(key, copy);
                  }
                }
              }

              // Build shop lookup
              Map<Long, Shop> shopMap =
                  shops.stream().collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));

              // Outer join: union of all keys from inventory and sales
              Set<String> allKeys = new HashSet<>();
              allKeys.addAll(invMap.keySet());
              allKeys.addAll(salesMap.keySet());

              List<SalesReportRowDto> rows = new ArrayList<>();
              BigDecimal totalCash = BigDecimal.ZERO;
              BigDecimal totalOnline = BigDecimal.ZERO;
              BigDecimal totalCollected = BigDecimal.ZERO;
              long totalIssued = 0, totalSold = 0, totalReturned = 0;

              for (String key : allKeys) {
                String[] parts = key.split("-");
                Long pId = Long.parseLong(parts[0]);
                Long sId = Long.parseLong(parts[1]);

                // Apply optional filters (shopId, productId, categoryId, shiftSessionId)
                if (productId != null && !productId.equals(pId)) continue;
                if (shopId != null && !shopId.equals(sId)) continue;

                Shop shop = shopMap.get(sId);
                InventoryClient.ProductDTO productRef = productMap.get(pId);

                // Apply categoryId filter (if specified, filter by product category or shop
                // category)
                if (categoryId != null) {
                  Long pCatId = productRef != null ? productRef.getCategoryId() : null;
                  if (pCatId == null || !categoryId.equals(pCatId)) continue;
                }

                InventoryEventSummaryDTO inv = invMap.get(key);
                ProductSalesSummaryDTO sale = salesMap.get(key);

                // Apply shiftSessionId filter (if specified)
                Long shiftSessionIdVal = (sale != null) ? sale.getShiftSessionId() : null;
                if (shiftSessionId != null && !shiftSessionId.equals(shiftSessionIdVal)) {
                  continue;
                }

                int issued =
                    inv != null && inv.getInitialQuantity() != null ? inv.getInitialQuantity() : 0;
                int remaining =
                    inv != null && inv.getLiveQuantity() != null ? inv.getLiveQuantity() : 0;

                long sold = sale != null && sale.getSoldQty() != null ? sale.getSoldQty() : 0L;
                long returned =
                    sale != null && sale.getReturnedQty() != null ? sale.getReturnedQty() : 0L;
                long net = sold - returned;

                BigDecimal cash =
                    sale != null && sale.getCashCollected() != null
                        ? sale.getCashCollected()
                        : BigDecimal.ZERO;
                BigDecimal online =
                    sale != null && sale.getOnlineCollected() != null
                        ? sale.getOnlineCollected()
                        : BigDecimal.ZERO;
                BigDecimal total =
                    sale != null && sale.getTotalCollected() != null
                        ? sale.getTotalCollected()
                        : BigDecimal.ZERO;

                String pName =
                    (sale != null && sale.getProductName() != null)
                        ? sale.getProductName()
                        : (productRef != null ? productRef.getName() : "Product " + pId);

                rows.add(
                    SalesReportRowDto.builder()
                        .shopId(sId)
                        .shopName(shop != null ? shop.getShopName() : "Shop " + sId)
                        .counterNumber(shop != null ? shop.getCounterNumber() : null)
                        .productId(pId)
                        .productName(pName)
                        .shiftSessionId(shiftSessionIdVal)
                        .issuedQty(issued)
                        .remainingQty(Math.max(0, remaining))
                        .soldQty(sold)
                        .returnedQty(returned)
                        .netQty(net)
                        .cashCollected(cash)
                        .onlineCollected(online)
                        .totalCollected(total)
                        .build());

                totalIssued += issued;
                totalSold += sold;
                totalReturned += returned;
                totalCash = totalCash.add(cash);
                totalOnline = totalOnline.add(online);
                totalCollected = totalCollected.add(total);
              }

              SalesReportSummaryDto summary =
                  SalesReportSummaryDto.builder()
                      .totalIssued(totalIssued)
                      .totalSold(totalSold)
                      .totalReturned(totalReturned)
                      .totalNet(totalSold - totalReturned)
                      .totalCash(totalCash)
                      .totalOnline(totalOnline)
                      .totalCollected(totalCollected)
                      .build();

              return SalesReportDto.builder().rows(rows).summary(summary).build();
            });
  }

  // ── NEW: Financial Report ─────────────────────────────────────────────────

  @Override
  public Mono<FinancialReportDto> getFinancialReport(
      Long eventId, Long shopId, String status, int page, int size, String authHeader) {
    log.info(
        "getFinancialReport eventId={} shopId={} status={} page={} size={}",
        eventId,
        shopId,
        status,
        page,
        size);

    // Fetch all shifts for event (or specific shop)
    Mono<List<ShopShiftSession>> shiftsMono;
    if (shopId != null) {
      shiftsMono =
          shopShiftSessionService
              .getSessionHistory(shopId, eventId, page, size)
              .collectList()
              .onErrorReturn(List.of());
    } else {
      shiftsMono =
          shopShiftSessionService
              .getSessionsByEvent(eventId)
              .collectList()
              .onErrorReturn(List.of());
    }

    Mono<List<Shop>> shopsMono =
        shopRepository.findByEventId(eventId).collectList().onErrorReturn(List.of());

    return Mono.zip(shiftsMono, shopsMono)
        .flatMap(
            tuple -> {
              List<ShopShiftSession> allShifts = tuple.getT1();
              List<Shop> shops = tuple.getT2();

              // Apply status filter in Java
              List<ShopShiftSession> filtered =
                  (status != null && !"all".equalsIgnoreCase(status))
                      ? allShifts.stream()
                          .filter(s -> status.equalsIgnoreCase(s.getStatus()))
                          .collect(Collectors.toList())
                      : allShifts;

              Map<Long, Shop> shopMap =
                  shops.stream().collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));

              // Collect unique user IDs that need resolving
              List<Long> userIds =
                  filtered.stream()
                      .flatMap(
                          s -> {
                            List<Long> ids = new ArrayList<>();
                            if (s.getOpenedByUserId() != null) ids.add(s.getOpenedByUserId());
                            if (s.getClosedByUserId() != null) ids.add(s.getClosedByUserId());
                            if (s.getReconciledByUserId() != null)
                              ids.add(s.getReconciledByUserId());
                            return ids.stream();
                          })
                      .distinct()
                      .collect(Collectors.toList());

              // Usernames via ONE SQL IN-query on the merged users table
              // (was N sequential HTTP calls to user-service)
              Mono<Map<Long, String>> usernamesMono =
                  salesAnalyticsRepository
                      .getUsersByIds(userIds)
                      .collectMap(
                          UserClient.UserDTO::getId,
                          u -> u.getUsername() != null ? u.getUsername() : "ID:" + u.getId())
                      .defaultIfEmpty(new HashMap<>());

              return usernamesMono.map(
                  usernameMap -> {
                    List<FinancialShiftDto> shiftDtos =
                        filtered.stream()
                            .map(
                                s -> {
                                  Shop shop = shopMap.get(s.getShopId());
                                  BigDecimal actual =
                                      s.getActualClosingCash() != null
                                          ? s.getActualClosingCash()
                                          : BigDecimal.ZERO;
                                  BigDecimal expected =
                                      s.getExpectedClosingCash() != null
                                          ? s.getExpectedClosingCash()
                                          : BigDecimal.ZERO;
                                  BigDecimal variance =
                                      "OPEN".equals(s.getStatus())
                                          ? BigDecimal.ZERO
                                          : actual.subtract(expected);

                                  return FinancialShiftDto.builder()
                                      .id(s.getId())
                                      .shopId(s.getShopId())
                                      .shopName(
                                          shop != null
                                              ? shop.getShopName()
                                              : "Shop " + s.getShopId())
                                      .counterNumber(shop != null ? shop.getCounterNumber() : null)
                                      .status(s.getStatus())
                                      .openedAt(s.getOpenedAt())
                                      .openedByUserId(s.getOpenedByUserId())
                                      .openedByUsername(
                                          usernameMap.getOrDefault(
                                              s.getOpenedByUserId(),
                                              s.getOpenedByUserId() != null
                                                  ? "ID:" + s.getOpenedByUserId()
                                                  : null))
                                      .closedAt(s.getClosedAt())
                                      .closedByUserId(s.getClosedByUserId())
                                      .closedByUsername(
                                          usernameMap.getOrDefault(
                                              s.getClosedByUserId(),
                                              s.getClosedByUserId() != null
                                                  ? "ID:" + s.getClosedByUserId()
                                                  : null))
                                      .reconciledAt(s.getReconciledAt())
                                      .reconciledByUserId(s.getReconciledByUserId())
                                      .reconciledByUsername(
                                          usernameMap.getOrDefault(
                                              s.getReconciledByUserId(),
                                              s.getReconciledByUserId() != null
                                                  ? "ID:" + s.getReconciledByUserId()
                                                  : null))
                                      .reconciliationComment(s.getReconciliationComment())
                                      .openingCashBalance(s.getOpeningCashBalance())
                                      .expectedClosingCash(expected)
                                      .actualClosingCash(actual)
                                      .actualClosingOnline(s.getActualClosingOnline())
                                      .variance(variance)
                                      .build();
                                })
                            .sorted(
                                (a, b) ->
                                    b.getOpenedAt() != null && a.getOpenedAt() != null
                                        ? b.getOpenedAt().compareTo(a.getOpenedAt())
                                        : 0)
                            .collect(Collectors.toList());

                    boolean hasMore = shopId != null && shiftDtos.size() == size;
                    return FinancialReportDto.builder()
                        .shifts(shiftDtos)
                        .totalCount(shiftDtos.size())
                        .hasMore(hasMore)
                        .build();
                  });
            });
  }

  // ── NEW: Audit Report ─────────────────────────────────────────────────────

  @Override
  public Mono<AuditReportDto> getAuditReport(
      Long eventId,
      Long shopId,
      Long categoryId,
      Long shiftSessionId,
      Long productId,
      String authHeader) {
    log.info(
        "getAuditReport eventId={} shopId={} categoryId={} shiftSessionId={} productId={}",
        eventId,
        shopId,
        categoryId,
        shiftSessionId,
        productId);

    // 1. Sales analytics (shift-product-summary via existing analytics query)
    Mono<List<ProductSalesSummaryDTO>> salesMono =
        salesService
            .getProductSalesSummary(eventId, shopId, shiftSessionId)
            .collectList()
            .onErrorReturn(List.of());

    // 2. Inventory issued/live data from inventory-service.
    // Fail loudly (503) instead of silently returning zeros — see getSalesReport note.
    Mono<List<InventoryEventSummaryDTO>> inventoryMono =
        salesAnalyticsRepository.getInventoryEventSummary(eventId).collectList();

    // 3. Shop metadata
    Mono<List<Shop>> shopsMono =
        shopRepository.findByEventId(eventId).collectList().onErrorReturn(List.of());

    // 4. Shifts (to resolve supervisor usernames)
    Mono<List<ShopShiftSession>> shiftsMono =
        shopShiftSessionService.getSessionsByEvent(eventId).collectList().onErrorReturn(List.of());

    // 5. Products list from inventory-service to resolve product names & category mapping
    Mono<List<InventoryClient.ProductDTO>> productsMono =
        salesAnalyticsRepository.getProductCatalog().collectList();

    return Mono.zip(salesMono, inventoryMono, shopsMono, shiftsMono, productsMono)
        .flatMap(
            tuple -> {
              List<ProductSalesSummaryDTO> salesList = tuple.getT1();
              List<InventoryEventSummaryDTO> invList = tuple.getT2();
              List<Shop> shops = tuple.getT3();
              List<ShopShiftSession> allShifts = tuple.getT4();
              List<InventoryClient.ProductDTO> products = tuple.getT5();

              // Build product lookup map for names and category
              Map<Long, InventoryClient.ProductDTO> productMap =
                  products.stream()
                      .collect(
                          Collectors.toMap(InventoryClient.ProductDTO::getId, p -> p, (a, b) -> a));

              // Build lookups
              Map<String, InventoryEventSummaryDTO> invMap = new HashMap<>();
              for (InventoryEventSummaryDTO inv : invList) {
                if (inv.getProductId() != null && inv.getShopId() != null) {
                  invMap.put(inv.getProductId() + "-" + inv.getShopId(), inv);
                }
              }

              // Build sales lookup: (productId, shopId) -> ProductSalesSummaryDTO
              // Aggregate across ALL shifts so each product×shop pair has exactly one entry.
              Map<String, ProductSalesSummaryDTO> salesMap = new HashMap<>();
              for (ProductSalesSummaryDTO sale : salesList) {
                if (sale.getProductId() != null && sale.getShopId() != null) {
                  String key = sale.getProductId() + "-" + sale.getShopId();
                  if (!salesMap.containsKey(key)) {
                    salesMap.put(key, sale);
                  } else {
                    // Aggregate sales from multiple shifts into a single entry
                    ProductSalesSummaryDTO existing = salesMap.get(key);
                    existing.setSoldQty(
                        existing.getSoldQty()
                            + (sale.getSoldQty() != null ? sale.getSoldQty() : 0L));
                    existing.setReturnedQty(
                        existing.getReturnedQty()
                            + (sale.getReturnedQty() != null ? sale.getReturnedQty() : 0L));
                    existing.setCashCollected(
                        existing
                            .getCashCollected()
                            .add(
                                sale.getCashCollected() != null
                                    ? sale.getCashCollected()
                                    : BigDecimal.ZERO));
                    existing.setOnlineCollected(
                        existing
                            .getOnlineCollected()
                            .add(
                                sale.getOnlineCollected() != null
                                    ? sale.getOnlineCollected()
                                    : BigDecimal.ZERO));
                    existing.setTotalCollected(
                        existing
                            .getTotalCollected()
                            .add(
                                sale.getTotalCollected() != null
                                    ? sale.getTotalCollected()
                                    : BigDecimal.ZERO));
                  }
                }
              }

              Map<Long, Shop> shopMap =
                  shops.stream().collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));
              Map<Long, ShopShiftSession> shiftMap =
                  allShifts.stream()
                      .collect(Collectors.toMap(ShopShiftSession::getId, s -> s, (a, b) -> a));

              // For inventory-only rows (no sales → shiftSessionId=null), fall back to the shop's
              // most recent shift (by openedAt desc, prefer OPEN over CLOSED over RECONCILED).
              Map<Long, ShopShiftSession> latestShiftByShop =
                  allShifts.stream()
                      .filter(s -> s.getShopId() != null)
                      .collect(
                          Collectors.toMap(
                              ShopShiftSession::getShopId,
                              s -> s,
                              (a, b) -> {
                                // Prefer OPEN > CLOSED > RECONCILED, then most recent openedAt
                                int statusRank =
                                    Integer.compare(
                                        shiftStatusRank(b.getStatus()),
                                        shiftStatusRank(a.getStatus()));
                                if (statusRank != 0) return statusRank > 0 ? b : a;
                                if (a.getOpenedAt() == null) return b;
                                if (b.getOpenedAt() == null) return a;
                                return a.getOpenedAt().isAfter(b.getOpenedAt()) ? a : b;
                              }));

              // Build allKeys: salesMap now uses 2-part keys (productId-shopId).
              // Add inventory-only products (no sales at all) using the same 2-part format.
              Set<String> allKeys = new HashSet<>(salesMap.keySet());
              for (String invKey : invMap.keySet()) {
                if (!allKeys.contains(invKey)) {
                  allKeys.add(invKey);
                }
              }

              // Collect unique supervisor user IDs from all shifts
              List<Long> supervisorIds =
                  allShifts.stream()
                      .filter(s -> s.getOpenedByUserId() != null)
                      .map(ShopShiftSession::getOpenedByUserId)
                      .distinct()
                      .collect(Collectors.toList());

              // Supervisors + categories via direct SQL on the merged DB
              // (was N HTTP calls to user-service + 1 to inventory-service)
              Mono<List<UserClient.UserDTO>> supervisorUsersMono =
                  salesAnalyticsRepository
                      .getUsersByIds(supervisorIds)
                      .collectList()
                      .defaultIfEmpty(new ArrayList<>());

              Mono<List<InventoryClient.CategoryDTO>> categoriesMono =
                  salesAnalyticsRepository
                      .getAllCategories()
                      .collectList()
                      .defaultIfEmpty(new ArrayList<>());

              return supervisorUsersMono.zipWith(
                  categoriesMono,
                  (supervisorUsers, categoriesList) -> {
                    // Build lookup maps from fetched users
                    Map<Long, String> usernameMap = new HashMap<>();
                    Map<Long, String> fullNameMap = new HashMap<>();
                    for (UserClient.UserDTO u : supervisorUsers) {
                      if (u.getId() != null) {
                        usernameMap.put(
                            u.getId(),
                            u.getUsername() != null ? u.getUsername() : "ID:" + u.getId());
                        if (u.getFullName() != null) {
                          fullNameMap.put(u.getId(), u.getFullName());
                        }
                      }
                    }

                    // Build categoryId -> categoryName lookup
                    Map<Long, String> categoryNameMap =
                        categoriesList.stream()
                            .filter(c -> c.getId() != null && c.getName() != null)
                            .collect(
                                Collectors.toMap(
                                    InventoryClient.CategoryDTO::getId,
                                    InventoryClient.CategoryDTO::getName,
                                    (a, b) -> a));

                    List<AuditRowDto> auditRows = new ArrayList<>();

                    for (String key : allKeys) {
                      // Key format: "productId-shopId" (2-part, no shift dimension)
                      int dash = key.indexOf('-');
                      Long pId = Long.parseLong(key.substring(0, dash));
                      Long sId = Long.parseLong(key.substring(dash + 1));

                      // Resolve the shop's latest/active shift for supervisor + shiftSessionId
                      ShopShiftSession latestShift = latestShiftByShop.get(sId);
                      Long resolvedShiftId = latestShift != null ? latestShift.getId() : null;

                      // Apply optional filters
                      if (productId != null && !productId.equals(pId)) continue;
                      if (shopId != null && !shopId.equals(sId)) continue;
                      if (shiftSessionId != null && !shiftSessionId.equals(resolvedShiftId))
                        continue;

                      InventoryEventSummaryDTO inv = invMap.get(key);
                      ProductSalesSummaryDTO sale = salesMap.get(key);

                      int issued =
                          inv != null && inv.getInitialQuantity() != null
                              ? inv.getInitialQuantity()
                              : 0;
                      int remaining =
                          inv != null && inv.getLiveQuantity() != null ? inv.getLiveQuantity() : 0;

                      long sold =
                          sale != null && sale.getSoldQty() != null ? sale.getSoldQty() : 0L;
                      long returned =
                          sale != null && sale.getReturnedQty() != null
                              ? sale.getReturnedQty()
                              : 0L;

                      Shop shop = shopMap.get(sId);
                      InventoryClient.ProductDTO productRef = productMap.get(pId);
                      // Always use the shop's latest/active shift for supervisor info
                      ShopShiftSession shift = latestShift;

                      Long supervisorUserId = shift != null ? shift.getOpenedByUserId() : null;
                      String supervisorUsername =
                          supervisorUserId != null ? usernameMap.get(supervisorUserId) : null;
                      String supervisorFullName =
                          supervisorUserId != null ? fullNameMap.get(supervisorUserId) : null;

                      String pName =
                          (sale != null && sale.getProductName() != null)
                              ? sale.getProductName()
                              : (productRef != null ? productRef.getName() : "Product " + pId);

                      Long catId = productRef != null ? productRef.getCategoryId() : null;
                      String catName = catId != null ? categoryNameMap.get(catId) : null;

                      BigDecimal cashCollected =
                          sale != null && sale.getCashCollected() != null
                              ? sale.getCashCollected()
                              : BigDecimal.ZERO;
                      BigDecimal onlineCollected =
                          sale != null && sale.getOnlineCollected() != null
                              ? sale.getOnlineCollected()
                              : BigDecimal.ZERO;
                      BigDecimal totalCollected =
                          sale != null && sale.getTotalCollected() != null
                              ? sale.getTotalCollected()
                              : BigDecimal.ZERO;

                      BigDecimal sellingPrice =
                          (productRef != null && productRef.getSellingPrice() != null)
                              ? productRef.getSellingPrice()
                              : BigDecimal.ZERO;

                      auditRows.add(
                          AuditRowDto.builder()
                              .shiftSessionId(resolvedShiftId)
                              .shopId(sId)
                              .shopName(shop != null ? shop.getShopName() : "Shop " + sId)
                              .counterNumber(shop != null ? shop.getCounterNumber() : null)
                              .shopSupervisorUsername(supervisorUsername)
                              .shopSupervisorName(supervisorFullName)
                              .productId(pId)
                              .productName(pName)
                              .categoryId(catId)
                              .categoryName(catName)
                              .sellingPrice(sellingPrice)
                              .issuedQty(issued)
                              .remainingQty(Math.max(0, remaining))
                              .soldQty(sold)
                              .returnedQty(returned)
                              .netQty(sold - returned)
                              .cashCollected(cashCollected)
                              .onlineCollected(onlineCollected)
                              .totalCollected(totalCollected)
                              .build());
                    }

                    // Apply categoryId filter if specified
                    List<AuditRowDto> filteredRows = auditRows;
                    if (categoryId != null) {
                      final Long catId = categoryId;
                      filteredRows =
                          auditRows.stream()
                              .filter(
                                  r -> {
                                    boolean shopMatch =
                                        r.getShopId() != null
                                            && shopMap.containsKey(r.getShopId())
                                            && catId.equals(
                                                shopMap.get(r.getShopId()).getCategoryId());
                                    if (shopMatch) return true;

                                    InventoryClient.ProductDTO productRef =
                                        productMap.get(r.getProductId());
                                    return productRef != null
                                        && catId.equals(productRef.getCategoryId());
                                  })
                              .collect(Collectors.toList());
                    }

                    // 3-Way Match computation — all in Java
                    Map<Long, AuditReportDto.ThreeWayMatchItemDto> matchMap = new HashMap<>();
                    for (AuditRowDto row : filteredRows) {
                      Long pId = row.getProductId();
                      AuditReportDto.ThreeWayMatchItemDto item =
                          matchMap.computeIfAbsent(
                              pId,
                              id ->
                                  AuditReportDto.ThreeWayMatchItemDto.builder()
                                      .productId(id)
                                      .productName(
                                          row.getProductName() != null
                                              ? row.getProductName()
                                              : "Product " + id)
                                      .expectedSalesQty(0)
                                      .actualInventoryDepletedQty(0)
                                      .expectedSalesAmount(BigDecimal.ZERO)
                                      .actualFinancialsCollected(BigDecimal.ZERO)
                                      .build());

                      item.setExpectedSalesQty(
                          item.getExpectedSalesQty()
                              + (row.getNetQty() != null ? row.getNetQty().intValue() : 0));
                      int issued = row.getIssuedQty() != null ? row.getIssuedQty() : 0;
                      int remaining = row.getRemainingQty() != null ? row.getRemainingQty() : 0;
                      item.setActualInventoryDepletedQty(
                          item.getActualInventoryDepletedQty() + Math.max(0, issued - remaining));

                      BigDecimal collected =
                          row.getTotalCollected() != null
                              ? row.getTotalCollected()
                              : BigDecimal.ZERO;
                      item.setActualFinancialsCollected(
                          item.getActualFinancialsCollected().add(collected));
                      item.setExpectedSalesAmount(item.getExpectedSalesAmount().add(collected));
                    }

                    BigDecimal totalVariance = BigDecimal.ZERO;
                    List<AuditReportDto.ThreeWayMatchItemDto> matchItems =
                        new ArrayList<>(matchMap.values());
                    for (AuditReportDto.ThreeWayMatchItemDto item : matchItems) {
                      item.setQtyVariance(
                          item.getExpectedSalesQty() - item.getActualInventoryDepletedQty());
                      InventoryClient.ProductDTO productRef = productMap.get(item.getProductId());
                      BigDecimal sellingPrice =
                          (productRef != null && productRef.getSellingPrice() != null)
                              ? productRef.getSellingPrice()
                              : BigDecimal.ZERO;
                      BigDecimal calculatedVariance =
                          BigDecimal.valueOf(item.getQtyVariance()).multiply(sellingPrice);
                      item.setFinancialVariance(calculatedVariance);
                      totalVariance = totalVariance.add(calculatedVariance);
                    }

                    return AuditReportDto.builder()
                        .auditRows(filteredRows)
                        .threeWayMatch(
                            AuditReportDto.ThreeWayMatchResultDto.builder()
                                .totalVarianceAmount(totalVariance)
                                .items(matchItems)
                                .build())
                        .build();
                  });
            });
  }

  /** OPEN=2, CLOSED=1, RECONCILED=0 — higher wins in the fallback shift picker. */
  private static int shiftStatusRank(String status) {
    if ("OPEN".equalsIgnoreCase(status)) return 2;
    if ("CLOSED".equalsIgnoreCase(status)) return 1;
    return 0;
  }
}
