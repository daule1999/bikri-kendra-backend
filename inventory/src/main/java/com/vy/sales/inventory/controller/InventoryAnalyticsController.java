package com.vy.sales.inventory.controller;

import com.vy.sales.inventory.dto.InventoryEventSummaryDTO;
import com.vy.sales.inventory.repository.CounterStockRepository;
import com.vy.sales.inventory.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/inventory/analytics")
@RequiredArgsConstructor
@Slf4j
public class InventoryAnalyticsController {

  private final CounterStockRepository counterStockRepository;

  @GetMapping("/event-summary")
  public Flux<InventoryEventSummaryDTO> getEventSummary(
      @RequestHeader(value = AppConstants.X_EVENT_ID, required = true) Long eventId) {

    log.info("Fetching inventory event summary for eventId={}", eventId);

    return counterStockRepository
        .findByEventId(eventId)
        .map(
            stock ->
                InventoryEventSummaryDTO.builder()
                    .productId(stock.getProductId())
                    .shopId(stock.getShopId())
                    .initialQuantity(
                        stock.getInitialQuantity() != null ? stock.getInitialQuantity() : 0)
                    .liveQuantity(stock.getLiveQuantity() != null ? stock.getLiveQuantity() : 0)
                    .depletedQuantity(
                        (stock.getInitialQuantity() != null ? stock.getInitialQuantity() : 0)
                            - (stock.getLiveQuantity() != null ? stock.getLiveQuantity() : 0))
                    .build());
  }
}
