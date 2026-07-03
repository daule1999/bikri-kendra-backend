package com.vy.printer.printer_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePrintJobRequest {
  private Long printerId; // if null → resolve default for submittedAgent / global
  private Long invoiceId;
  private String invoiceNo;
  private String orderNo;
  private Long eventId; // optional (G3)

  private String jobType; // INVOICE (default) | RECEIVING | TEST

  @NotBlank private String escposBase64; // pre-rendered bytes (hot path)
  private String receiptSnapshot; // resolved data+config JSON (G8) for reprint/audit

  private String submittedAgent; // caller's agentId — drives own-billing priority (G6)
  private String idempotencyKey; // dedupe; reprint mints a fresh one (G4)
  private Integer priority; // optional manual override
}
