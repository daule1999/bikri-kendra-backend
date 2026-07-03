package com.vy.sales.inventory.dto;

import com.vy.sales.inventory.entity.StockMovement;

public record StockMovementDTO(
    Long productId,
    Long eventId,
    Integer quantity,
    String reason,
    String shopId,
    String locationFrom,
    String locationTo,
    StockMovement.MovementType movementType) {}
