-- V2: Add must_reset_password flag to users table
-- Set to TRUE when:
--   (a) Admin creates a new user (they set a temp password)
--   (b) Admin resets a user's forgotten password via admin-reset-password endpoint
-- Cleared to FALSE when the user successfully completes a self-service password change.
-- Default FALSE keeps existing rows (including the bootstrap admin) unaffected.

ALTER TABLE users
    ADD COLUMN must_reset_password BOOLEAN NOT NULL DEFAULT FALSE;
