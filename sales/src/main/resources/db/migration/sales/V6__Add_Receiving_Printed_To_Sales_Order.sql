-- V6: Add receiving_printed column to sales_order table.
-- Tracks whether a receiving slip has been printed for a given order.
-- This prevents duplicate receiving prints on history reprints.
-- Once the frontend fires PATCH /{orderNumber}/receiving-printed, this is set to TRUE.

ALTER TABLE sales_order
  ADD COLUMN receiving_printed BOOLEAN NOT NULL DEFAULT FALSE;
