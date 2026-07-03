package com.vy.printer.printer_service.dto;

import lombok.Data;

@Data
public class FailRequest {
  private String agentId;
  private String error;
}
