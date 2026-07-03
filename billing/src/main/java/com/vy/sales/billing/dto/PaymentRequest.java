package com.vy.sales.billing.dto;

import com.vy.sales.billing.entity.PaymentMode;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class PaymentRequest {

  @NotNull private PaymentMode paymentMode;

  private String paymentReference;

  @NotNull private BigDecimal amount;

  @NotNull private Long receivedBy;
}
