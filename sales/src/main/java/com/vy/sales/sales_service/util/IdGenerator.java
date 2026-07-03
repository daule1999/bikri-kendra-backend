package com.vy.sales.sales_service.util;

import de.huxhorn.sulky.ulid.ULID;

public class IdGenerator {
  private static final ULID ulid = new ULID();

  public static final synchronized String generateId() {
    return ulid.nextULID();
  }
}
