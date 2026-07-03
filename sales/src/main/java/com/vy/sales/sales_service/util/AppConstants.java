package com.vy.sales.sales_service.util;

public class AppConstants {

  public static final String X_EVENT_ID = "X-Event-Id";

  /** String literals used when classifying inventory stock movements. */
  public static class StockMovement {
    private StockMovement() {}

    public static final String LOC_MAIN = "MAIN";
    public static final String LOC_CUSTOMER = "CUSTOMER";
    public static final String REASON_COUNTER_ALLOC = "COUNTER_ALLOCATION";
    public static final String REASON_UNISSUE = "UNISSUE";
    public static final String REASON_SALE = "SALE";
    public static final String REASON_NA = "N/A";
  }
}
