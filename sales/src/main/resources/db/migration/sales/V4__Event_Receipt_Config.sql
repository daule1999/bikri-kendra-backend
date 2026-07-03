-- V4: Add receipt_config column to event table
--
-- Stores the full receipt / print configuration as a JSON document.
-- NULL means "use client-side defaults" — no migration of existing rows needed.
-- The frontend sends this as a JSON object; the backend stores it as-is.

ALTER TABLE event
    ADD COLUMN receipt_config JSON NULL COMMENT 'Receipt layout and print config (JSON). NULL = client defaults.';
