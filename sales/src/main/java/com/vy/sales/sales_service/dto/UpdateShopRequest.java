package com.vy.sales.sales_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateShopRequest {

  @NotBlank(message = "Shop name cannot be blank")
  private String shopName;

  private Long categoryId;

  private String categoryName;

  @Min(value = 1, message = "Counter number must be at least 1")
  private Integer counterNumber;

  private Boolean isActive;

  private Long eventId;

  /** Per-shop receiving slip toggle — persisted to shop.receiving_print_enabled column */
  private Boolean receivingPrintEnabled;
}
