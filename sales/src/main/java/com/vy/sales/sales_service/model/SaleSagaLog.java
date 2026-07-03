package com.vy.sales.sales_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Persists saga compensation failures for manual ops reconciliation.
 *
 * <p>When a compensation step (e.g. inventory restore after billing failure, billing reinstate
 * after DB failure on cancel) itself errors, a record is inserted here so the affected order can be
 * identified and corrected manually.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("sale_saga_log")
public class SaleSagaLog {

  @Id private Long id;

  /** Affected sales order number. */
  private String orderNumber;

  /** Saga step that triggered the compensation (e.g. CONFIRM_BILLING, CANCEL_BILLING). */
  private String sagaStep;

  /** Compensation action attempted (e.g. INVENTORY_RESTORE, BILLING_REINSTATE). */
  private String compensation;

  /** FAILED (default) or RESOLVED (set by ops after manual fix). */
  private String status;

  /** Root-cause exception message for the failed compensation. */
  private String errorMessage;

  private LocalDateTime createdAt;

  private LocalDateTime resolvedAt;

  /** Username of the operator who marked this entry as resolved. */
  private String resolvedBy;
}
