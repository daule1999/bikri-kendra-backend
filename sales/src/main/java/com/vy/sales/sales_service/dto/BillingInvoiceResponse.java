package com.vy.sales.sales_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillingInvoiceResponse {

  private String invoiceNo;
  private String salesOrderNumber;
  private BigDecimal taxAmount;
  private BigDecimal netAmount;
  private String status;
}
