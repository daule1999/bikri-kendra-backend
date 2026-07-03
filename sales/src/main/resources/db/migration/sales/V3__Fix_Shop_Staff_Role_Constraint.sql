-- V3: Fix shop_staff_assignment uniqueness constraint
--
-- Problem: uk_shop_role UNIQUE(shop_id, role_code) prevents:
--   1. Multiple CASHIERs / BILLING_OPERATORs in the same shop (valid use case)
--   2. Re-assigning any role after a previous holder was unassigned (row stays as inactive)
--
-- Root cause of Flyway error 1553:
--   MySQL was using uk_shop_role as the backing index for fk_shop FOREIGN KEY (shop_id).
--   Dropping uk_shop_role while fk_shop exists fails with "needed in a foreign key constraint".
--
-- Fix sequence:
--   1. Add a standalone index on shop_id so MySQL has an alternative backing index for the FK
--   2. Drop the FK constraint fk_shop (temporarily)
--   3. Now drop uk_shop_role safely
--   4. Re-add the FK constraint (backed by the new standalone index)
--   5. Add uk_shop_user UNIQUE(shop_id, user_id)

-- Step 1: Give MySQL an alternative backing index for the FK before we drop uk_shop_role
ALTER TABLE shop_staff_assignment
    ADD INDEX idx_ssa_shop_id (shop_id);

-- Step 2: Drop the FK that was relying on uk_shop_role as its backing index
ALTER TABLE shop_staff_assignment
    DROP FOREIGN KEY fk_shop;

-- Step 3: Now drop the unique constraint safely
ALTER TABLE shop_staff_assignment
    DROP INDEX uk_shop_role;

-- Step 4: Re-add the FK (will now use idx_ssa_shop_id)
ALTER TABLE shop_staff_assignment
    ADD CONSTRAINT fk_shop FOREIGN KEY (shop_id) REFERENCES shop(id);

-- Step 5: Add the correct uniqueness constraint
--   A user can only hold one assignment row per shop (active OR inactive).
--   Upsert logic in ShopStaffAssignmentService.assign() reactivates the inactive row
--   on re-assign instead of inserting a new one — so this constraint is safe.
--   SHOP_SUPERVISOR one-per-shop enforcement is handled at the application layer.
ALTER TABLE shop_staff_assignment
    ADD CONSTRAINT uk_shop_user UNIQUE (shop_id, user_id);
