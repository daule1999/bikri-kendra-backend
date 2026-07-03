package com.vy.sales.inventory.service;

import com.vy.sales.inventory.dto.report.InventoryIssuedRowDto;
import com.vy.sales.inventory.dto.report.InventoryReportDto;
import com.vy.sales.inventory.dto.report.InventoryStockRowDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryReportServiceImpl implements InventoryReportService {

  private final DatabaseClient databaseClient;

  @Override
  public Mono<InventoryReportDto> getInventoryReport(
      Long eventId, String shopId, Long categoryId, Long productId) {

    log.info(
        "getInventoryReport eventId={} shopId={} categoryId={} productId={}",
        eventId,
        shopId,
        categoryId,
        productId);

    // Build the WHERE clause additions and handle shopId parsing to avoid type decoding issues
    StringBuilder whereExtra = new StringBuilder();
    StringBuilder stockWhereExtra = new StringBuilder();
    Long shopIdLong = null;
    if (shopId != null && !shopId.isEmpty() && !shopId.equalsIgnoreCase("all")) {
      try {
        shopIdLong = Long.parseLong(shopId);
        whereExtra.append(" AND cs.shop_id = :shopId");
      } catch (NumberFormatException e) {
        log.warn("Invalid shopId format: {}", shopId);
      }
    }
    if (categoryId != null) {
      whereExtra.append(" AND p.category_id = :categoryId");
      stockWhereExtra.append(" AND p.category_id = :categoryId");
    }
    if (productId != null) {
      whereExtra.append(" AND p.id = :productId");
      stockWhereExtra.append(" AND p.id = :productId");
    }

    // ── Issued query ─────────────────────────────────────────────────────
    // Each counter_stocks row is one "issue" record (an allocation of
    // initialQuantity units to a shop).  We group by product+shop to get
    // the total issued (sum of initialQuantity) and live remaining qty.
    String issuedSql =
        """
        SELECT
            cs.id,
            cs.product_id,
            p.name            AS product_name,
            p.category_id,
            c.name            AS category_name,
            cs.shop_id,
            cs.seller_user,
            SUM(cs.initial_quantity) AS total_issued_qty,
            SUM(cs.live_quantity)    AS live_quantity,
            MIN(cs.sale_date)        AS sale_date,
            MIN(cs.created_at)       AS created_at
        FROM counter_stocks cs
        JOIN product p ON p.id = cs.product_id
        JOIN category c ON c.id = p.category_id
        WHERE cs.event_id = :eventId
        %s
        GROUP BY cs.product_id, cs.shop_id, p.name, p.category_id,
                 c.name, cs.seller_user, cs.id
        ORDER BY cs.id
        """
            .formatted(whereExtra);

    // ── Stock query ───────────────────────────────────────────────────────
    // Aggregate live vs initial per product to derive depleted qty.
    // We drive from product table so that products with warehouse stocks
    // appear even if they haven't been issued to any shop yet (issued qty = 0).
    String stockSql =
        """
        SELECT
            p.id              AS product_id,
            p.name            AS product_name,
            p.category_id,
            c.name            AS category_name,
            COALESCE(SUM(cs.initial_quantity), 0) AS initial_quantity,
            COALESCE(SUM(cs.live_quantity), 0)    AS live_quantity,
            COALESCE((SELECT SUM(s.quantity) FROM inventory_stocks s WHERE s.product_id = p.id AND s.event_id = :eventId), 0) AS inventory_stock
        FROM product p
        JOIN category c ON c.id = p.category_id
        LEFT JOIN counter_stocks cs ON cs.product_id = p.id AND cs.event_id = :eventId %s
        WHERE
            (
                EXISTS (SELECT 1 FROM inventory_stocks s WHERE s.product_id = p.id AND s.event_id = :eventId)
                OR cs.id IS NOT NULL
            )
        %s
        GROUP BY p.id, p.name, p.category_id, c.name
        ORDER BY p.name
        """
            .formatted(shopIdLong != null ? "AND cs.shop_id = :shopId" : "", stockWhereExtra);

    Mono<List<InventoryIssuedRowDto>> issuedMono =
        buildIssuedQuery(issuedSql, eventId, shopIdLong, categoryId, productId);
    Mono<List<InventoryStockRowDto>> stockMono =
        buildStockQuery(stockSql, eventId, shopIdLong, categoryId, productId);

    return Mono.zip(issuedMono, stockMono)
        .map(
            tuple ->
                InventoryReportDto.builder().issued(tuple.getT1()).stock(tuple.getT2()).build());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private Mono<List<InventoryIssuedRowDto>> buildIssuedQuery(
      String sql, Long eventId, Long shopIdLong, Long categoryId, Long productId) {

    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql).bind("eventId", eventId);
    if (shopIdLong != null) spec = spec.bind("shopId", shopIdLong);
    if (categoryId != null) spec = spec.bind("categoryId", categoryId);
    if (productId != null) spec = spec.bind("productId", productId);

    return spec.map(
            (row, meta) ->
                InventoryIssuedRowDto.builder()
                    .id(row.get("id", Long.class))
                    .productId(row.get("product_id", Long.class))
                    .productName(row.get("product_name", String.class))
                    .categoryId(row.get("category_id", Long.class))
                    .categoryName(row.get("category_name", String.class))
                    .shopId(safeString(row.get("shop_id")))
                    .sellerUser(row.get("seller_user", String.class))
                    .totalIssuedQty(safeInt(row.get("total_issued_qty", Number.class)))
                    .liveQuantity(safeInt(row.get("live_quantity", Number.class)))
                    .saleDate(row.get("sale_date", java.time.LocalDateTime.class))
                    .createdAt(row.get("created_at", java.time.LocalDateTime.class))
                    .build())
        .all()
        .collectList();
  }

  private Mono<List<InventoryStockRowDto>> buildStockQuery(
      String sql, Long eventId, Long shopIdLong, Long categoryId, Long productId) {

    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql).bind("eventId", eventId);
    if (shopIdLong != null) spec = spec.bind("shopId", shopIdLong);
    if (categoryId != null) spec = spec.bind("categoryId", categoryId);
    if (productId != null) spec = spec.bind("productId", productId);

    return spec.map(
            (row, meta) -> {
              int initial = safeInt(row.get("initial_quantity", Number.class));
              int live = safeInt(row.get("live_quantity", Number.class));
              int depleted = Math.max(0, initial - live);
              int invStock = safeInt(row.get("inventory_stock", Number.class));
              String status = computeStatus(live);

              return InventoryStockRowDto.builder()
                  .productId(row.get("product_id", Long.class))
                  .productName(row.get("product_name", String.class))
                  .categoryId(row.get("category_id", Long.class))
                  .categoryName(row.get("category_name", String.class))
                  .initialQuantity(initial)
                  .liveQuantity(live)
                  .depletedQuantity(depleted)
                  .inventoryStock(invStock)
                  .status(status)
                  .build();
            })
        .all()
        .collectList();
  }

  private static int safeInt(Number n) {
    return n != null ? n.intValue() : 0;
  }

  private static String safeString(Object val) {
    return val != null ? val.toString() : null;
  }

  private static String computeStatus(int liveQuantity) {
    if (liveQuantity <= 0) return "OUT_OF_STOCK";
    if (liveQuantity <= 10) return "LOW_STOCK";
    return "IN_STOCK";
  }
}
