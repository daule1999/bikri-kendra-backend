package com.vy.sales.sales_service.service;

import com.vy.sales.sales_service.client.InventoryClient;
import com.vy.sales.sales_service.client.UserClient;
import com.vy.sales.sales_service.util.PageInitKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class PageInitService {

  private final InventoryClient inventoryClient;
  private final UserClient userClient;
  private final ShopService shopService;
  private final SalesService salesService;
  private final EventService eventService;
  private final ShopStaffAssignmentService shopStaffAssignmentService;

  /**
   * Fetches only the requested resources in parallel and returns a combined map. Each resource is
   * cached individually in its own service/client — no aggregated cache here.
   *
   * @param include validated set of {@link PageInitKey} constants
   * @param eventId current event context
   * @param auth forwarded Authorization header
   */
  public Mono<Map<String, Object>> fetch(Set<String> include, Long eventId, String auth) {
    return Mono.defer(() -> fetchFresh(include, eventId, auth));
  }

  private Mono<Map<String, Object>> fetchFresh(Set<String> include, Long eventId, String auth) {

    // Build only the Monos the caller asked for — switch on each key
    Map<String, Mono<Object>> tasks = new LinkedHashMap<>();
    for (String key : include) {
      switch (key) {
        case PageInitKey.CATEGORIES ->
            tasks.put(
                PageInitKey.CATEGORIES, inventoryClient.getAllCategories(auth).cast(Object.class));

        case PageInitKey.PRODUCTS ->
            tasks.put(
                PageInitKey.PRODUCTS,
                inventoryClient
                    .getAllProducts(eventId, auth)
                    .map(r -> r.getData() != null ? r.getData() : List.of())
                    .cast(Object.class));

        case PageInitKey.USERS ->
            tasks.put(PageInitKey.USERS, userClient.getAllUsers(auth).cast(Object.class));

        case PageInitKey.STOCKS ->
            tasks.put(
                PageInitKey.STOCKS, inventoryClient.getAllStocks(eventId, auth).cast(Object.class));

        case PageInitKey.SALES ->
            tasks.put(
                PageInitKey.SALES,
                inventoryClient
                    .getAllIssues(eventId, auth)
                    .map(r -> r.getData() != null ? r.getData() : List.of())
                    .cast(Object.class));

        case PageInitKey.SHOPS ->
            tasks.put(
                PageInitKey.SHOPS,
                shopService.getAllShops(eventId).collectList().cast(Object.class));

        case PageInitKey.PRODUCT_SHOP_SALES ->
            tasks.put(
                PageInitKey.PRODUCT_SHOP_SALES,
                salesService.getProductShopSalesSummary(eventId).collectList().cast(Object.class));

        case PageInitKey.EVENTS ->
            tasks.put(PageInitKey.EVENTS, eventService.getAllAsList().cast(Object.class));

        case PageInitKey.ROLES ->
            tasks.put(PageInitKey.ROLES, userClient.getAllRoles(auth).cast(Object.class));

        case PageInitKey.SHOP_STAFF_ASSIGNMENTS ->
            tasks.put(
                PageInitKey.SHOP_STAFF_ASSIGNMENTS,
                shopStaffAssignmentService
                    .getAllActiveAssignments()
                    .collectList()
                    .cast(Object.class));

          // Unknown keys already filtered in controller — no default needed
      }
    }

    if (tasks.isEmpty()) {
      return Mono.just(Map.of());
    }

    List<String> orderedKeys = new ArrayList<>(tasks.keySet());
    List<Mono<Object>> orderedMonos = new ArrayList<>(tasks.values());

    return Mono.zip(
            orderedMonos,
            results -> {
              Map<String, Object> response = new LinkedHashMap<>();
              for (int i = 0; i < orderedKeys.size(); i++) {
                response.put(orderedKeys.get(i), results[i]);
              }
              return response;
            })
        .doOnSuccess(
            r ->
                log.info(
                    "PAGE_INIT_FETCH_SUCCESS eventId={} keys={}",
                    eventId,
                    String.join(",", orderedKeys)))
        .doOnError(
            ex ->
                log.error(
                    "PAGE_INIT_FETCH_FAILED eventId={} reason={}", eventId, ex.getMessage(), ex));
  }
}
