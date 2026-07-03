-- V5: Add receiving_print_enabled column to the shop table.
-- This per-shop toggle controls whether a receiving slip is printed after each sale.
-- BOTH this column AND the event-level receiptConfig.receivingPrint.enabled must be
-- true before a receiving slip fires.

ALTER TABLE shop
  ADD COLUMN receiving_print_enabled BOOLEAN NOT NULL DEFAULT FALSE;
