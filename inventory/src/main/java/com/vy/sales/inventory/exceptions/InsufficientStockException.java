package com.vy.sales.inventory.exceptions;

public class InsufficientStockException extends RuntimeException {

  private final Long productId;
  private final String shopId;
  private final int available;
  private final int requested;

  public InsufficientStockException(Long productId, String shopId, int available, int requested) {
    super(
        "Insufficient stock for product "
            + productId
            + " in shop "
            + shopId
            + ". Available: "
            + available
            + ", Requested: "
            + requested);
    this.productId = productId;
    this.shopId = shopId;
    this.available = available;
    this.requested = requested;
  }

  public Long getProductId() {
    return productId;
  }

  public String getShopId() {
    return shopId;
  }

  public int getAvailable() {
    return available;
  }

  public int getRequested() {
    return requested;
  }
}
