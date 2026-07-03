-- V3: Add product_sku to invoice_items
-- Stores the product's SKU code on each invoice line item so it can be
-- returned via the /items API and printed on receiving slips.
-- Nullable because existing invoice rows don't have this data.
ALTER TABLE invoice_items
  ADD COLUMN product_sku VARCHAR(100) NULL AFTER product_name;
