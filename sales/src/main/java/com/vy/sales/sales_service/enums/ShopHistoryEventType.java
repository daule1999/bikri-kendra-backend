package com.vy.sales.sales_service.enums;

/**
 * Canonical event types emitted by {@code ShopService#aggregateShopHistory()}.
 *
 * <p>Keep in sync with the frontend switch block in {@code shop.ts}.
 */
public enum ShopHistoryEventType {

  /** Shop was registered and opened for the first time. */
  SHOP_OPENED,

  /** A staff member was assigned to this shop. */
  STAFF_ASSIGNED,

  /** A staff member was removed / unassigned from this shop. */
  STAFF_UNASSIGNED,

  /** Stock was issued from main inventory to this shop's counter. */
  STOCK_ISSUE,

  /** Stock was returned from this shop's counter back to main inventory. */
  STOCK_UNISSUE,

  /** A sale was created at this shop. */
  SALE,

  /** A previously created sale was cancelled. */
  SALE_CANCELLED,

  /** One or more items from a sale were partially returned. */
  SALE_PARTIALLY_RETURNED,

  /** Shop was permanently closed (soft-deleted). */
  SHOP_CLOSED;

  /** Convenience method — returns the enum name as the string stored in history responses. */
  public String value() {
    return this.name();
  }
}
