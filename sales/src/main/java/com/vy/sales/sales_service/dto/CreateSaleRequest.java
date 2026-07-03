package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class CreateSaleRequest {

  private String shopId;
  private String customerName;
  private String customerMobile;

  private List<Item> items;

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
}
