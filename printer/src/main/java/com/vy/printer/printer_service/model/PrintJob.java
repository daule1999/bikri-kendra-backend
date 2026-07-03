package com.vy.printer.printer_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("print_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrintJob {

  @Id private Long id;

  private Long printerId;
  private Long invoiceId;
  private String invoiceNo;
  private String orderNo;
  private Long eventId;

  private String jobType; // INVOICE | RECEIVING | TEST | REPRINT
  private String status; // QUEUED|PROCESSING|DONE|FAILED|CANCELLED
  private Integer priority; // lower = sooner

  private String escposBase64; // hot path
  private String receiptSnapshot; // G11: JSON-as-String

  private String idempotencyKey;

  private Long submittedBy;
  private String submittedUsername;
  private String submittedAgent;
  private String claimedBy;
  private LocalDateTime claimedAt;

  private Integer attempts;
  private Integer maxAttempts;
  private String errorMessage;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
