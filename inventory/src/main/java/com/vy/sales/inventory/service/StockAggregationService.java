package com.vy.sales.inventory.service;

import com.vy.sales.inventory.entity.Stock;
import com.vy.sales.inventory.repository.StockMovementRepository;
import com.vy.sales.inventory.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAggregationService {

  private final StockMovementRepository movementRepository;
  private final StockRepository stockRepository;

  /** Recalculate stock for a product & location */
  @Transactional
  public Mono<Stock> recalculateStock(Long productId, Long eventId, String location) {
    log.info(
        "Recalculating stock for productId={}, eventId={}, location={}",
        productId,
        eventId,
        location);

    return movementRepository
        .calculateStock(productId, eventId)
        .defaultIfEmpty(0)
        .flatMap(totalQuantity -> updateOrCreateStock(productId, eventId, location, totalQuantity))
        .doOnError(
            ex ->
                log.error(
                    "Failed to recalculate stock for productId={}, location={}",
                    productId,
                    location,
                    ex));
  }

  private Mono<Stock> updateOrCreateStock(
      Long productId, Long eventId, String location, Integer quantity) {
    return stockRepository
        .findByProductIdAndEventIdAndLocation(productId, eventId, location)
        .defaultIfEmpty(new Stock())
        .flatMap(
            stock -> {
              if (stock.getId() == null) {
                stock.setProductId(productId);
                stock.setEventId(eventId);
                stock.setLocation(location);
              }
              stock.setQuantity(quantity);
              return stockRepository
                  .save(stock)
                  .doOnSuccess(
                      saved ->
                          log.info(
                              "Stock saved: productId={}, location={}, quantity={}",
                              productId,
                              location,
                              saved.getQuantity()));
            });
  }
}
