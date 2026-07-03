package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingInvoiceRequest {

  private String salesOrderNumber;
  private Long shopId;
  private Long eventId; // event scope propagated from SalesOrder

  private Seller seller;
  private Customer customer;

  private BigDecimal subtotalAmount;
  private BigDecimal discountAmount;

  private List<Item> items;
  private String preGeneratedInvoiceNo;

  @Data
  @Builder
  public static class Seller {
    private Long id;
    private String name;
  }

  @Data
  @Builder
  public static class Customer {
    private String name;
    private String mobile;
  }

  @Data
  @Builder
  public static class Item {
    private Long productId;
    private String productName;
    private String productSku;
    private String hsnCode;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal discount;
  }
}
