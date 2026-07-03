package com.vy.sales.sales_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Stores idempotency keys for mutating HTTP requests.
 *
 * <p>The {@code IdempotencyFilter} inserts a row with status IN_FLIGHT before forwarding the
 * request downstream. On completion it updates the row to COMPLETED with the serialised response.
 * On error it marks FAILED. Rows are automatically expired after 24 h via the {@code expires_at}
 * column (see V6 migration).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("idempotency_keys")
public class IdempotencyKey {

  @Id private Long id;

  /** Client-supplied {@code X-Idempotency-Key} header value. */
  private String idempotencyKey;

  /** Request URI path (e.g. /api/sales-svc/retail/complete). */
  private String requestPath;

  /** HTTP status of the completed response (null while IN_FLIGHT). */
  private Integer responseStatus;

  /** Serialised JSON response body (null while IN_FLIGHT). */
  private String responseBody;

  /** IN_FLIGHT | COMPLETED | FAILED */
  private String status;

  private LocalDateTime createdAt;
  private LocalDateTime expiresAt;
}
