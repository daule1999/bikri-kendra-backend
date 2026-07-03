package com.vy.sales.sales_service.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("shop_invoice_sequence")
public class ShopInvoiceSequence {

  @Id private Long id;

  @Column("shop_id")
  private Long shopId;

  @Column("event_id")
  private Long eventId;

  @Column("category_name")
  private String categoryName;

  @Column("next_seq")
  private Integer nextSeq;

  @Column("active_shift_id")
  private Long activeShiftId;

  @Column("next_invoice_no")
  private String nextInvoiceNo;

  @Column("updated_at")
  private LocalDateTime updatedAt;

  // Helper methods for compatibility with InvoiceSequenceService
  public long getIncrement() {
    return this.nextSeq != null ? this.nextSeq.longValue() : 0L;
  }

  public void setIncrement(long inc) {
    this.nextSeq = (int) inc;
  }

  public void setLastInvoiceNo(String invoiceNo) {
    this.nextInvoiceNo = invoiceNo;
  }

  // Additional constructor to match service usage
  public ShopInvoiceSequence(
      Long id, Long shopId, Long eventId, String categoryName, Long increment, Long activeShiftId) {
    this.id = id;
    this.shopId = shopId;
    this.eventId = eventId;
    this.categoryName = categoryName;
    this.nextSeq = increment != null ? increment.intValue() : 0;
    this.activeShiftId = activeShiftId;
    this.nextInvoiceNo = "";
    this.updatedAt = null;
  }
}
