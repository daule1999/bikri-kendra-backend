/* Refreshed for event-scoped architecture */
package com.vy.sales.sales_service.repository.impl;

import com.vy.sales.sales_service.dto.ProductSalesSummaryDTO;
import com.vy.sales.sales_service.dto.ProductShopSalesDTO;
import com.vy.sales.sales_service.repository.SalesAnalyticsRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
@RequiredArgsConstructor
public class SalesAnalyticsRepositoryImpl implements SalesAnalyticsRepository {
  private final DatabaseClient databaseClient;

  @Override
  public Flux<ProductShopSalesDTO> getProductShopSalesSummary() {
    String query =
        """
            SELECT
                soi.product_id,
                so.shop_id,
                SUM(soi.quantity) AS total_sold
            FROM sales_order_item soi
            JOIN sales_order so
                ON soi.sales_order_id = so.id
            WHERE so.status IN ('CONFIRMED', 'PARTIALLY_RETURNED')
            GROUP BY soi.product_id, so.shop_id
        """;

    return databaseClient
        .sql(query)
        .map(
            (row, meta) ->
                new ProductShopSalesDTO(
                    row.get("product_id", Long.class),
                    row.get("shop_id", Long.class),
                    row.get("total_sold", Long.class)))
        .all();
  }

  @Override
  public Flux<ProductShopSalesDTO> getProductShopSalesSummaryByEventId(Long eventId) {
    String query =
        """
            SELECT
                soi.product_id,
                so.shop_id,
                SUM(soi.quantity) AS total_sold
            FROM sales_order_item soi
            JOIN sales_order so
                ON soi.sales_order_id = so.id
            WHERE so.status IN ('CONFIRMED', 'PARTIALLY_RETURNED') AND so.event_id = :eventId
            GROUP BY soi.product_id, so.shop_id
        """;

    return databaseClient
        .sql(query)
        .bind("eventId", eventId)
        .map(
            (row, meta) ->
                new ProductShopSalesDTO(
                    row.get("product_id", Long.class),
                    row.get("shop_id", Long.class),
                    row.get("total_sold", Long.class)))
        .all();
  }

  @Override
  public Flux<ProductSalesSummaryDTO> getProductSalesSummary(
      Long eventId, Long shopId, Long shiftSessionId) {
    String sFilters = "";
    String rFilters = "";
    if (shopId != null) {
      sFilters += " AND so.shop_id = :shopId";
      rFilters += " AND so.shop_id = :shopId";
    }
    if (shiftSessionId != null) {
      sFilters += " AND so.shift_session_id = :shiftSessionId";
      rFilters += " AND so.shift_session_id = :shiftSessionId";
    }

    // NOTE on the `pay` subquery: sales_payment can hold MULTIPLE rows per order.
    // The previous version joined sales_payment directly at item level, which fanned out
    // every item row once per payment row — multiplying sold_qty and collections and producing
    // the "conflicting report totals" seen in production. Payments are now pre-aggregated to
    // exactly one row per order before joining.
    String query =
        """
            SELECT
                COALESCE(s.product_id, r.product_id) AS product_id,
                COALESCE(s.product_name, 'Unknown') AS product_name,
                COALESCE(s.shop_id, r.shop_id) AS shop_id,
                COALESCE(s.shift_session_id, r.shift_session_id) AS shift_session_id,
                COALESCE(s.sold_qty, 0) AS sold_qty,
                COALESCE(r.returned_qty, 0) AS returned_qty,
                (COALESCE(s.cash_collected, 0) - COALESCE(r.refunded_cash, 0)) AS cash_collected,
                (COALESCE(s.online_collected, 0) - COALESCE(r.refunded_online, 0)) AS online_collected
            FROM (
                SELECT
                    soi.product_id,
                    soi.product_name,
                    so.shop_id,
                    so.shift_session_id,
                    SUM(soi.quantity + COALESCE(ret.returned_qty, 0)) AS sold_qty,
                    SUM(COALESCE((soi.line_total + COALESCE(ret.refunded_amount, 0)) * pay.cash_paid / NULLIF(so.order_subtotal + COALESCE(ord_ret.total_refund_amount, 0), 0), 0)) AS cash_collected,
                    SUM(COALESCE((soi.line_total + COALESCE(ret.refunded_amount, 0)) * pay.online_paid / NULLIF(so.order_subtotal + COALESCE(ord_ret.total_refund_amount, 0), 0), 0)) AS online_collected
                FROM sales_order_item soi
                JOIN sales_order so ON soi.sales_order_id = so.id
                LEFT JOIN (
                    SELECT
                        sales_order_id,
                        SUM(CASE WHEN payment_mode = 'CASH' THEN amount WHEN payment_mode = 'BOTH' THEN COALESCE(cash_amount, 0) ELSE 0 END) AS cash_paid,
                        SUM(CASE WHEN payment_mode = 'CASH' THEN 0 WHEN payment_mode = 'BOTH' THEN COALESCE(online_amount, 0) ELSE amount END) AS online_paid,
                        SUM(amount) AS total_paid
                    FROM sales_payment
                    GROUP BY sales_order_id
                ) pay ON pay.sales_order_id = so.id
                LEFT JOIN (
                    SELECT sales_order_item_id, SUM(quantity) AS returned_qty, SUM(refund_amount) AS refunded_amount
                    FROM sales_return
                    GROUP BY sales_order_item_id
                ) ret ON ret.sales_order_item_id = soi.id
                LEFT JOIN (
                    SELECT sales_order_id, SUM(refund_amount) AS total_refund_amount
                    FROM sales_return
                    GROUP BY sales_order_id
                ) ord_ret ON ord_ret.sales_order_id = so.id
                WHERE so.status IN ('CONFIRMED', 'RETURNED', 'PARTIALLY_RETURNED') AND so.event_id = :eventId
                %s
                GROUP BY soi.product_id, soi.product_name, so.shop_id, so.shift_session_id
            ) s
            LEFT JOIN (
                SELECT
                    ret.product_id,
                    so.shop_id,
                    so.shift_session_id,
                    SUM(ret.quantity) AS returned_qty,
                    SUM(ret.refund_amount) AS refunded_amount,
                    SUM(ret.refund_amount * COALESCE(pay.cash_paid / NULLIF(pay.total_paid, 0), 0)) AS refunded_cash,
                    SUM(ret.refund_amount * COALESCE(pay.online_paid / NULLIF(pay.total_paid, 0), 0)) AS refunded_online
                FROM sales_return ret
                JOIN sales_order so ON ret.sales_order_id = so.id
                LEFT JOIN (
                    SELECT
                        sales_order_id,
                        SUM(CASE WHEN payment_mode = 'CASH' THEN amount WHEN payment_mode = 'BOTH' THEN COALESCE(cash_amount, 0) ELSE 0 END) AS cash_paid,
                        SUM(CASE WHEN payment_mode = 'CASH' THEN 0 WHEN payment_mode = 'BOTH' THEN COALESCE(online_amount, 0) ELSE amount END) AS online_paid,
                        SUM(amount) AS total_paid
                    FROM sales_payment
                    GROUP BY sales_order_id
                ) pay ON pay.sales_order_id = so.id
                WHERE so.event_id = :eventId
                %s
                GROUP BY ret.product_id, so.shop_id, so.shift_session_id
            ) r ON s.product_id = r.product_id
               AND s.shop_id = r.shop_id
               AND s.shift_session_id = r.shift_session_id
        """
            .formatted(sFilters, rFilters);

    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(query).bind("eventId", eventId);

    if (shopId != null) {
      // Bind as Long — binding a String against the numeric shop_id column forced implicit
      // casts on every row.
      spec = spec.bind("shopId", shopId);
    }
    if (shiftSessionId != null) {
      spec = spec.bind("shiftSessionId", shiftSessionId);
    }

    return spec.map(
            (row, meta) -> {
              Number cashNum = row.get("cash_collected", Number.class);
              Number onlineNum = row.get("online_collected", Number.class);
              BigDecimal cash =
                  cashNum != null ? new BigDecimal(cashNum.toString()) : BigDecimal.ZERO;
              BigDecimal online =
                  onlineNum != null ? new BigDecimal(onlineNum.toString()) : BigDecimal.ZERO;
              BigDecimal total = cash.add(online);

              Long rowShopId = null;
              Object shopIdObj = row.get("shop_id");
              if (shopIdObj != null) {
                if (shopIdObj instanceof Number) {
                  rowShopId = ((Number) shopIdObj).longValue();
                } else {
                  try {
                    rowShopId = Long.parseLong(shopIdObj.toString());
                  } catch (Exception e) {
                  }
                }
              }

              Number soldQtyNum = row.get("sold_qty", Number.class);
              long soldQty = soldQtyNum != null ? soldQtyNum.longValue() : 0L;

              Number returnedQtyNum = row.get("returned_qty", Number.class);
              long returnedQty = returnedQtyNum != null ? returnedQtyNum.longValue() : 0L;

              return ProductSalesSummaryDTO.builder()
                  .productId(row.get("product_id", Long.class))
                  .productName(row.get("product_name", String.class))
                  .shopId(rowShopId)
                  .shiftSessionId(row.get("shift_session_id", Long.class))
                  .soldQty(soldQty)
                  .returnedQty(returnedQty)
                  .cashCollected(cash)
                  .onlineCollected(online)
                  .totalCollected(total)
                  .build();
            })
        .all();
  }

  // ── MONOLITH: direct SQL over merged bikri_db (former inventory_db / user_db tables) ──────

  @Override
  public Flux<com.vy.sales.sales_service.dto.report.InventoryEventSummaryDTO>
      getInventoryEventSummary(Long eventId) {
    String query =
        """
            SELECT product_id, shop_id, initial_quantity, live_quantity,
                   (initial_quantity - live_quantity) AS depleted_quantity
            FROM counter_stocks
            WHERE event_id = :eventId
        """;
    return databaseClient
        .sql(query)
        .bind("eventId", eventId)
        .map(
            (row, meta) -> {
              var dto = new com.vy.sales.sales_service.dto.report.InventoryEventSummaryDTO();
              dto.setProductId(row.get("product_id", Long.class));
              dto.setShopId(row.get("shop_id", Long.class));
              dto.setInitialQuantity(row.get("initial_quantity", Integer.class));
              dto.setLiveQuantity(row.get("live_quantity", Integer.class));
              dto.setDepletedQuantity(row.get("depleted_quantity", Integer.class));
              return dto;
            })
        .all();
  }

  @Override
  public Flux<com.vy.sales.sales_service.client.InventoryClient.ProductDTO> getProductCatalog() {
    String query =
        "SELECT id, name, sku, category_id, selling_price, mrp FROM product";
    return databaseClient
        .sql(query)
        .map(
            (row, meta) -> {
              var p = new com.vy.sales.sales_service.client.InventoryClient.ProductDTO();
              p.setId(row.get("id", Long.class));
              p.setName(row.get("name", String.class));
              p.setSku(row.get("sku", String.class));
              p.setCategoryId(row.get("category_id", Long.class));
              p.setSellingPrice(row.get("selling_price", BigDecimal.class));
              p.setMrp(row.get("mrp", BigDecimal.class));
              return p;
            })
        .all();
  }

  @Override
  public Flux<com.vy.sales.sales_service.client.InventoryClient.CategoryDTO> getAllCategories() {
    return databaseClient
        .sql("SELECT id, name FROM category")
        .map(
            (row, meta) -> {
              var c = new com.vy.sales.sales_service.client.InventoryClient.CategoryDTO();
              c.setId(row.get("id", Long.class));
              c.setName(row.get("name", String.class));
              return c;
            })
        .all();
  }

  @Override
  public Flux<com.vy.sales.sales_service.client.UserClient.UserDTO> getUsersByIds(
      java.util.Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Flux.empty();
    }
    // ids are internal Long PKs — safe to inline for the IN clause
    String in =
        ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    return databaseClient
        .sql("SELECT id, username, full_name FROM users WHERE id IN (" + in + ")")
        .map(
            (row, meta) -> {
              var u = new com.vy.sales.sales_service.client.UserClient.UserDTO();
              u.setId(row.get("id", Long.class));
              u.setUsername(row.get("username", String.class));
              u.setFullName(row.get("full_name", String.class));
              return u;
            })
        .all();
  }
}
