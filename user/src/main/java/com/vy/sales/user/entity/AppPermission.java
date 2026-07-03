package com.vy.sales.user.entity;

public enum AppPermission {

  // =========================
  // USER & ROLE MANAGEMENT
  // =========================
  USER_CREATE,
  USER_UPDATE,
  USER_VIEW,
  USER_DISABLE,

  ROLE_CREATE,
  ROLE_UPDATE,
  ROLE_VIEW,
  ROLE_ASSIGN,

  // =========================
  // BILLING
  // =========================
  BILL_CREATE,
  BILL_VIEW,
  BILL_CANCEL,
  BILL_DISCOUNT_APPLY,
  PAYMENT_COLLECT,
  // =========================
  // SALES
  // =========================
  SALE_CREATE,
  SALE_VIEW,
  SALE_RETURN,
  // =========================
  // ITEMS & INVENTORY
  // =========================
  ITEM_CREATE,
  ITEM_UPDATE,
  ITEM_VIEW,
  ITEM_PRICE_OVERRIDE,
  STOCK_VIEW,
  STOCK_ADD,
  STOCK_ADJUST,
  STOCK_TRANSFER,

  // =========================
  // REPORTS & ACCOUNTS
  // =========================
  REPORT_DAILY_VIEW,
  REPORT_EVENT_VIEW,
  PROFIT_MARGIN_VIEW,

  // =========================
  // SYSTEM / ADMIN
  // =========================
  AUDIT_LOG_VIEW,
  SETTINGS_UPDATE
}
