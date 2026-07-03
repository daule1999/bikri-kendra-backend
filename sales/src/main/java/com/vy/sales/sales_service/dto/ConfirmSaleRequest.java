package com.vy.sales.sales_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ConfirmSaleRequest {

  @NotNull(message = "Payment mode is required")
  private String paymentMode; // CASH, UPI, CARD

  private String paymentReference;

  @NotNull(message = "Amount is required")
  @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
  private BigDecimal amount;

  @DecimalMin(value = "0.00", message = "Cash amount cannot be negative")
  private BigDecimal cashAmount;

  @DecimalMin(value = "0.00", message = "Online amount cannot be negative")
  private BigDecimal onlineAmount;

  // Bug A fix: added to record "received Rs.500 / change Rs.50" on invoice for CASH mode.
  @DecimalMin(value = "0.00", message = "Cash received cannot be negative")
  private BigDecimal cashReceived;

  @DecimalMin(value = "0.00", message = "Change given cannot be negative")
  private BigDecimal changeGiven;
}
