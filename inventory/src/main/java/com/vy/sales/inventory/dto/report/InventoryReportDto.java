package com.vy.sales.inventory.dto.report;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReportDto {
  private List<InventoryIssuedRowDto> issued;
  private List<InventoryStockRowDto> stock;
}
