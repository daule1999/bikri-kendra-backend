-- Fix 6 (billing-service): Add a composite index on (status, event_id) to speed up
-- saga compensation queries that look up invoices by status (PAID → CANCELLED / RETURNED).
-- The existing invoices table already has the `status` VARCHAR(20) column from V1;
-- this migration only adds the query-performance index.

ALTER TABLE invoices
    ADD INDEX idx_invoice_status_event (status, event_id);
