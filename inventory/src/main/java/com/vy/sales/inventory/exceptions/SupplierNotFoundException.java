package com.vy.sales.inventory.exceptions;

public class SupplierNotFoundException extends RuntimeException {
  public SupplierNotFoundException(Long id) {
    super("Supplier Id" + id + " not found");
  }
}
