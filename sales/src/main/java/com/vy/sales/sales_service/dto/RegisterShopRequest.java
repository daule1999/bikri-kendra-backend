package com.vy.sales.sales_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterShopRequest {

  @NotBlank(message = "Shop name is required")
  private String shopName;

  @NotNull(message = "Category ID is required")
  private Long categoryId;

  @NotNull(message = "Counter number is required")
  @Min(value = 1, message = "Counter number must be at least 1")
  private Integer counterNumber;

  private String categoryName;

  private Boolean isActive; // optional, default true
  private Long eventId; // ✅
}
