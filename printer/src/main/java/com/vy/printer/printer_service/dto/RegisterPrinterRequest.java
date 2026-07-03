package com.vy.printer.printer_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterPrinterRequest {
  @NotBlank private String name;
  @NotBlank private String qzPrinterName;
  private String location;

  @NotBlank private String ownerAgentId; // workstation registering this printer
  private String ownerLabel;
  private String hostname;

  private String connectionType; // qz (default) | agent | tcp
  private String driverInfo;
  private Boolean isDefault;
  private Long eventId; // optional (G3)
}
