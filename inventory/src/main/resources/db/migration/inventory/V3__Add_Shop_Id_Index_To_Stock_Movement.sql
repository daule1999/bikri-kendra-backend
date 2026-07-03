-- Add index on shop_id in stock_movement table for efficient shop history queries.
-- The getMovementsByShop query (SELECT * FROM stock_movement WHERE shop_id = ?)
-- was doing a full table scan; this index makes it O(log n).

ALTER TABLE stock_movement ADD INDEX idx_sm_shop_id (shop_id);
