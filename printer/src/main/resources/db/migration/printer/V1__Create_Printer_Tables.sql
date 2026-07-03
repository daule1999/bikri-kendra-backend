-- ============================================================================
-- Central Printer Registry & Cross-Workstation Print Queue  (printer_db)
-- Fixes baked in: G2 (ms precision + id tiebreak), G6 (owner_user_id),
--                 G11 (LONGTEXT snapshot, not JSON), priority (own-billing-first)
-- ============================================================================

-- printers: one row per physical printer, owned by the workstation attached to it
CREATE TABLE printers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,             -- friendly name, e.g. "Counter-1 Epson"
    qz_printer_name VARCHAR(255) NOT NULL,             -- exact OS/QZ printer name to print to
    location        VARCHAR(255),
    owner_agent_id  VARCHAR(64)  NOT NULL,             -- workstation that physically owns it (UUID, localStorage)
    owner_user_id   BIGINT,                            -- G6: userId that registered it; survives agentId churn
    owner_label     VARCHAR(255),                      -- human label of that workstation
    hostname        VARCHAR(255),                      -- display only (best-effort)
    connection_type VARCHAR(32)  NOT NULL DEFAULT 'qz',-- qz | agent | tcp  (G10 executor branch)
    driver_info     VARCHAR(512),
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,-- default target when printerId omitted
    event_id        BIGINT,                            -- optional scoping (G3: NOT required)
    deleted         TIMESTAMP(3) NULL DEFAULT NULL,
    created_by      BIGINT,                            -- userId from gateway header
    created_at      TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_printer_owner_name (owner_agent_id, qz_printer_name),
    KEY idx_owner (owner_agent_id),
    KEY idx_enabled (enabled)
);

-- print_jobs: durable priority-FIFO queue + audit log
CREATE TABLE print_jobs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    printer_id       BIGINT      NOT NULL,
    invoice_id       BIGINT,
    invoice_no       VARCHAR(64),
    order_no         VARCHAR(64),
    event_id         BIGINT,
    job_type         VARCHAR(32) NOT NULL DEFAULT 'INVOICE', -- INVOICE | RECEIVING | TEST | REPRINT
    status           VARCHAR(32) NOT NULL DEFAULT 'QUEUED',  -- QUEUED|PROCESSING|DONE|FAILED|CANCELLED
    priority         INT         NOT NULL DEFAULT 100,        -- LOWER = sooner. own-billing=10, normal=100
    escpos_base64    LONGTEXT    NULL,                        -- pre-rendered bytes (hot path)
    receipt_snapshot LONGTEXT,                                -- G11: JSON-as-TEXT (resolved data+config) for reprint/audit
    idempotency_key  VARCHAR(96),                             -- dedupe accidental double-submit (G4: reprint mints new key)
    submitted_by     BIGINT,                                  -- userId from gateway header
    submitted_username VARCHAR(255),
    submitted_agent  VARCHAR(64),                             -- agentId of submitter (B)
    claimed_by       VARCHAR(64),                             -- agentId of executor (A)
    claimed_at       TIMESTAMP(3) NULL,
    attempts         INT         NOT NULL DEFAULT 0,
    max_attempts     INT         NOT NULL DEFAULT 3,
    error_message    VARCHAR(1024),
    created_at       TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),   -- G2: ms precision
    updated_at       TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_job_printer FOREIGN KEY (printer_id) REFERENCES printers(id),
    UNIQUE KEY uq_idem (idempotency_key),
    KEY idx_queue (printer_id, status, priority, created_at, id),  -- G2: priority-then-FIFO, id tiebreak
    KEY idx_status (status)
);


ALTER TABLE print_jobs
    DROP FOREIGN KEY fk_job_printer;

ALTER TABLE print_jobs
    ADD CONSTRAINT fk_job_printer
        FOREIGN KEY (printer_id) REFERENCES printers(id) ON DELETE CASCADE;