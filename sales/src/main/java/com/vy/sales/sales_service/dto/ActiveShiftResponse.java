package com.vy.sales.sales_service.dto;

import com.vy.sales.sales_service.model.ShopShiftSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveShiftResponse {
  private Long shopId;
  private boolean open;
  private ShopShiftSession shift; // present only when open == true

  public ActiveShiftResponse(Long shopId, boolean open) {
    this(shopId, open, null);
  }
}
