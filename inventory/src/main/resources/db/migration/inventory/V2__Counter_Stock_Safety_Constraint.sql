-- Bug 2 Fix: Add non-negative constraint on counter stock quantities.
-- This is a safety net below the application-layer check in CounterStockService.decrementStock().
-- If any race condition bypasses the service-layer guard, MySQL will reject the write
-- and the transaction will roll back cleanly instead of allowing negative stock.

ALTER TABLE counter_stocks
    ADD CONSTRAINT chk_counter_stocks_live_qty_non_negative
        CHECK (live_quantity >= 0),
    ADD CONSTRAINT chk_counter_stocks_initial_qty_non_negative
        CHECK (initial_quantity >= 0);
