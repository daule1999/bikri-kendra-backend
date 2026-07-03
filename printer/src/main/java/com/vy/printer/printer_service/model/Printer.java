package com.vy.printer.printer_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("printers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Printer {

  @Id private Long id;

  private String name;
  private String qzPrinterName;
  private String location;

  private String ownerAgentId; // workstation UUID (localStorage)
  private Long ownerUserId; // G6: stable identity that survives agentId churn
  private String ownerLabel;
  private String hostname;

  private String connectionType; // qz | agent | tcp
  private String driverInfo;

  private Boolean enabled;
  private Boolean isDefault;
  private Boolean deleted;

  private Long eventId;
  private Long createdBy;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
