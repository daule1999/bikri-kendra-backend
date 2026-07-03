package com.vy.sales.user.entity;

public enum AppRole {
  ADMIN("System Administrator"),
  INVENTORY_MANAGER("Inventory Manager"),
  INVENTORY_HELPER("Inventory Helper"),
  SHOP_SUPERVISOR("Shop Supervisor"),
  CASHIER("Cash Handler"),
  BILLING_OPERATOR("Billing Operator on System"),
  SHOP_HELPER("helps in showing the products"),
  ACCOUNTS("Accounts & Finance");

  private final String description;

  AppRole(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }
}
