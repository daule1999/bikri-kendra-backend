package com.vy.printer.printer_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClaimRequest {
  @NotBlank private String agentId;
}
