package com.vy.sales.inventory.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing an audit log entry for physical stock movements.
 *
 * <h3>Standard Business "reason" mapping:</h3>
 *
 * <ul>
 *   <li><b>"PURCHASE"</b>: (MovementType.IN) Auto-triggered when goods are bought from suppliers to
 *       MAIN.
 *   <li><b>"COUNTER_ALLOCATION"</b>: (MovementType.TRANSFER) Explicit transfer from MAIN to
 *       COUNTER_shopId.
 *   <li><b>"Return From Counter"</b>: (MovementType.TRANSFER) Explicit transfer back from
 *       COUNTER_shopId to MAIN.
 *   <li><b>"From Store"</b>: (MovementType.IN) Manual addition sourced from central stores.
 *   <li><b>"Other"</b>: (MovementType.IN/ADJUSTMENT) General manual adjustments (damage, audit
 *       checks, etc.).
 * </ul>
 *
 * <h3>Location Format:</h3>
 *
 * <ul>
 *   <li><b>"MAIN"</b>: Central warehouse inventory
 *   <li><b>"COUNTER_X"</b>: Counter at shop X (e.g., COUNTER_1, COUNTER_101)
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("stock_movement")
public class StockMovement {

  @Id private Long id;
  private Long productId;
  private Long eventId;
  private String username;
  private MovementType movementType;
  private Integer quantity;
  private String reason;
  private String locationFrom; // e.g., "MAIN", "COUNTER_1"
  private String locationTo; // e.g., "COUNTER_1", "MAIN"
  private Long shopId; // Reference shop for counter movements
  private LocalDateTime movementDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public enum MovementType {
    IN,
    OUT,
    ADJUSTMENT,
    TRANSFER // New: explicit location-to-location transfer
  }
}
