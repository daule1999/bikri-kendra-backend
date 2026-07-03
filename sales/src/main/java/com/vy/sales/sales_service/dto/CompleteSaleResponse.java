package com.vy.sales.sales_service.dto;

import com.vy.sales.sales_service.model.SalesOrder;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response envelope for the idempotent POST /retail/complete endpoint.
 *
 * <p>Bundles everything the frontend needs after a successful checkout into one response,
 * eliminating follow-up API calls (stocks refresh, products refresh, next invoice display).
 *
 * <p>Payment fields are echoed from the request because {@link SalesOrder} does not carry them
 * (payment lives in the separate {@code sales_payment} table). The frontend needs them immediately
 * at print time — before any second round-trip to fetch the payment record.
 */
@Data
@NoArgsConstructor
public class CompleteSaleResponse {

  /** The confirmed sales order. */
  private SalesOrder order;

  /**
   * Updated shop stock levels — mirrors GET /stocks/{shopId} but delivered inline so the frontend
   * can refresh its stock cache without a second round-trip.
   */
  private List<ShopStockResponse> stocks;

  /**
   * Pre-formatted next invoice number for display (read-only peek — the counter is NOT advanced by
   * returning this; calling {@code peekNextFormatted} is side-effect-free).
   */
  private String nextInvoiceNumber;

  // ── Payment fields (echoed from request for immediate receipt printing) ──────
  /** CASH, ONLINE, BOTH — required for the invoice payment mode line. */
  private String paymentMode;

  private String paymentReference;

  /** For BOTH mode: how much was paid in cash. */
  private BigDecimal cashAmount;

  /** For BOTH mode: how much was paid online. */
  private BigDecimal onlineAmount;

  /** For CASH mode: amount the customer handed over. */
  private BigDecimal cashReceived;

  /** For CASH mode: change returned to the customer. */
  private BigDecimal changeGiven;
}
