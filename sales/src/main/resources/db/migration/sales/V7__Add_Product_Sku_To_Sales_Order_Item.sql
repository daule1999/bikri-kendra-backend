-- V7: Add product_sku to sales_order_item
-- Stores the product's SKU code alongside each line item for use on
-- receiving slips. Nullable because existing rows don't have this data.
ALTER TABLE sales_order_item
  ADD COLUMN product_sku VARCHAR(100) NULL AFTER product_name;
