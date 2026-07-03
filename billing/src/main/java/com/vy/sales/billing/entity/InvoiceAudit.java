package com.vy.sales.billing.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("invoice_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceAudit {

  @Id private Long id;

  private Long invoiceId;
  private AuditAction action;

  private Long actionBy;
  private String remarks;

  private LocalDateTime actionAt;
}
