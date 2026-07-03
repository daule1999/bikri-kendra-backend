package com.vy.sales.inventory.exceptions;

public class BadRequestException extends Throwable {
  public BadRequestException(String skuAlreadyExists) {
    super(skuAlreadyExists);
  }
}
