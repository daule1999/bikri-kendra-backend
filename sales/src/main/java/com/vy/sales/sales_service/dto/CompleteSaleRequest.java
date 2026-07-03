package com.vy.sales.sales_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * Combined create-and-confirm request for the idempotent {@code POST /retail/complete} endpoint.
 *
 * <p>An optional {@code orderNumber} may be supplied by the client (e.g. recovered from {@code
 * sessionStorage}) to re-drive an in-flight or previously attempted checkout. The backend will
 * execute {@code INSERT IGNORE} so that a duplicate order_number is silently dropped and the
 * existing order is returned instead — enabling safe client-side retries without double-charging.
 */
@Data
public class CompleteSaleRequest {

  // ── Order creation fields ─────────────────────────────────────────────────

  private String shopId;
  private String customerName;
  private String customerMobile;

  private List<Item> items;

  /**
   * Optional client-supplied order number for idempotent retries. When provided the server uses
   * {@code INSERT IGNORE} — if the order already exists it is confirmed (or returned as-is if
   * already CONFIRMED). When null a fresh ULID is generated server-side.
   */
  private String orderNumber;

  @Data
  public static class Item {
    private Long productId;
    private String productName;
    private String productSku;
    private String hsnCode;
    private Integer quantity;
    private BigDecimal mrp;
    private BigDecimal sellingPrice;
    private BigDecimal discount;
  }

  // ── Payment / confirmation fields ─────────────────────────────────────────

  @NotNull(message = "Payment mode is required")
  private String paymentMode; // CASH, UPI, CARD, BOTH

  private String paymentReference;

  @NotNull(message = "Amount is required")
  @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
  private BigDecimal amount;

  @DecimalMin(value = "0.00", message = "Cash amount cannot be negative")
  private BigDecimal cashAmount;

  @DecimalMin(value = "0.00", message = "Online amount cannot be negative")
  private BigDecimal onlineAmount;

  /** For CASH mode: how much the customer handed over (to print on invoice). */
  @DecimalMin(value = "0.00", message = "Cash received cannot be negative")
  private BigDecimal cashReceived;

  /** For CASH mode: change returned to the customer (to print on invoice). */
  @DecimalMin(value = "0.00", message = "Change given cannot be negative")
  private BigDecimal changeGiven;
}
