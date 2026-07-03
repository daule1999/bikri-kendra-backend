package com.vy.printer.printer_service.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row per (user × device × printer) that has sent jobs to a printer you own. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionDto {
  private String submittedUsername; // who (gateway username)
  private Long submittedBy; // userId
  private String submittedAgent; // which device (agentId)
  private Long printerId;
  private String printerName;
  private Long jobCount;
  private LocalDateTime lastJobAt;
  private String lastStatus;
}
