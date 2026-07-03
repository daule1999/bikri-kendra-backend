-- Bug A Fix: Add cash_received and change_given columns to sales_payment.
-- These allow invoices to display "Received: Rs.500 / Change: Rs.50" for CASH mode sales.
-- NULL means not applicable (ONLINE / BOTH payment modes).

ALTER TABLE sales_payment
    ADD COLUMN cash_received DECIMAL(12, 2) DEFAULT NULL AFTER online_amount,
    ADD COLUMN change_given  DECIMAL(12, 2) DEFAULT NULL AFTER cash_received;

-- Fix 6: Invoice sequence audit column.
-- The race condition in invoice number generation was fixed at the application
-- layer via explicit R2DBC connection pinning (Mono.usingWhen) with LAST_INSERT_ID.
-- This migration adds a last_issued_at audit column so operations teams can
-- quickly see when each sequence was last used, and a lock_version optimistic
-- counter for future use.

ALTER TABLE shop_invoice_sequence
    ADD COLUMN last_issued_at TIMESTAMP NULL DEFAULT NULL
        COMMENT 'Timestamp of the last successful invoice number claim for this sequence'
        AFTER next_invoice_no,
    ADD COLUMN lock_version  INT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock counter incremented on every claim'
        AFTER last_issued_at;


-- Fix 3: Add cash tracking columns to shop_shift_session.
--
-- cash_additions_total   : Sum of all manual cash additions recorded during the shift.
--                          Used by the expected-cash calculator so additions are reflected
--                          in the closing balance without touching a hot row per-sale.
-- refunded_cash_amount   : Total cash component refunded across all returns in this shift.
-- refunded_online_amount : Total online component refunded across all returns in this shift.
--
-- All columns default to 0.00 so existing rows (open shifts at migration time) are unaffected.
-- The application recalculates expected_closing_cash on-demand via a SUM query; these columns
-- are additive accumulators updated only on manual cash-addition and return events (low frequency).

SET FOREIGN_KEY_CHECKS = 0;

-- Pre-migration guard: warn if any shifts are currently OPEN.
-- We use a stored procedure so Flyway can run this as a single statement block.
DROP PROCEDURE IF EXISTS guard_open_shifts_v4;
DELIMITER $$
CREATE PROCEDURE guard_open_shifts_v4()
BEGIN
  DECLARE open_count INT DEFAULT 0;
  SELECT COUNT(*) INTO open_count FROM shop_shift_session WHERE status = 'OPEN';
  IF open_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V4 migration blocked: close all open shifts before migrating';
  END IF;
END$$
DELIMITER ;

-- Comment out the CALL if you intentionally run this with open shifts (e.g. a dev environment).
CALL guard_open_shifts_v4();

DROP PROCEDURE IF EXISTS guard_open_shifts_v4;

ALTER TABLE shop_shift_session
    ADD COLUMN cash_additions_total   DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT 'Cumulative manual cash additions recorded during this shift'
        AFTER expected_closing_online,
    ADD COLUMN refunded_cash_amount   DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT 'Total cash component returned to customers across all returns in this shift'
        AFTER cash_additions_total,
    ADD COLUMN refunded_online_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT 'Total online component returned to customers across all returns in this shift'
        AFTER refunded_cash_amount;

SET FOREIGN_KEY_CHECKS = 1;


-- Sec Bug 3: Saga compensation failure log table.
-- When a saga compensation step fails (e.g. inventory re-increment after billing
-- failure, or billing reinstate after DB failure on cancel), the failure is
-- persisted here so ops teams can manually reconcile affected orders.

CREATE TABLE sale_saga_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number    VARCHAR(80)   NOT NULL COMMENT 'Affected sales order number',
    saga_step       VARCHAR(60)   NOT NULL COMMENT 'Step that triggered compensation (e.g. CONFIRM_BILLING, CANCEL_BILLING)',
    compensation    VARCHAR(60)   NOT NULL COMMENT 'Compensation action attempted (e.g. INVENTORY_RESTORE, BILLING_REINSTATE)',
    status          ENUM('FAILED','RESOLVED') NOT NULL DEFAULT 'FAILED',
    error_message   TEXT          NULL COMMENT 'Root-cause exception message',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at     TIMESTAMP     NULL DEFAULT NULL,
    resolved_by     VARCHAR(100)  NULL COMMENT 'Username that marked this as resolved',
    INDEX idx_saga_log_order  (order_number),
    INDEX idx_saga_log_status (status),
    INDEX idx_saga_log_created(created_at)
);


-- Fix 1 / Sec Bug 1: HTTP-layer idempotency key store.
-- The sales-service IdempotencyFilter checks this table before processing any
-- mutating request (POST / PUT). If a matching key is found and the prior
-- request is still IN_FLIGHT the new request is rejected with 409.
-- If the prior request is COMPLETED the cached response body is returned.
-- Rows expire after 24 h (cleaned up by a scheduled task or DB event).

CREATE TABLE idempotency_keys (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128)  NOT NULL UNIQUE COMMENT 'Client-supplied X-Idempotency-Key value',
    request_path    VARCHAR(255)  NOT NULL COMMENT 'Request URI path',
    response_status INT           NULL     COMMENT 'HTTP status code of the completed response',
    response_body   MEDIUMTEXT    NULL     COMMENT 'Serialised JSON response body (max ~16 MB)',
    status          ENUM('IN_FLIGHT','COMPLETED','FAILED') NOT NULL DEFAULT 'IN_FLIGHT',
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP     NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL 1 DAY),
    INDEX idx_idem_key    (idempotency_key),
    INDEX idx_idem_expiry (expires_at)
);

