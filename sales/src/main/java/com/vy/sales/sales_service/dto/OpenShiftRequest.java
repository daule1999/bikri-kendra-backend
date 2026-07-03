package com.vy.sales.sales_service.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class OpenShiftRequest {
  private Long shopId;
  private BigDecimal openingCash;
  private List<DenominationInput> denominations;

  @Data
  public static class DenominationInput {
    private Integer currencyValue;
    private Integer noteCount;
  }
}
