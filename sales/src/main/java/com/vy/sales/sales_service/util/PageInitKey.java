package com.vy.sales.sales_service.util;

import java.util.Set;

/**
 * String constants for the generic page-init aggregator API.
 *
 * <p>Frontend sends these as comma-separated values in the {@code include} query param: {@code GET
 * /api/sales-svc/page-init?include=categories,products,users}
 *
 * <p>Keep in sync with {@code lib/pageInitKeys.ts} on the frontend.
 */
public final class PageInitKey {

  public static final String CATEGORIES = "categories";
  public static final String PRODUCTS = "products";
  public static final String USERS = "users";
  public static final String STOCKS = "stocks";
  public static final String SALES = "sales";
  public static final String SHOPS = "shops";
  public static final String PRODUCT_SHOP_SALES = "productShopSales";
  public static final String EVENTS = "events";
  public static final String ROLES = "roles";
  public static final String SHOP_STAFF_ASSIGNMENTS = "shopStaffAssignments";

  /** All valid keys — unknown keys passed by the frontend are silently dropped. */
  public static final Set<String> VALID =
      Set.of(
          CATEGORIES,
          PRODUCTS,
          USERS,
          STOCKS,
          SALES,
          SHOPS,
          PRODUCT_SHOP_SALES,
          EVENTS,
          ROLES,
          SHOP_STAFF_ASSIGNMENTS);

  private PageInitKey() {}
}
