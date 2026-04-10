-- V66: per-user JWT invalidation
-- Allows invalidating all tokens for a specific user without affecting other users.
-- NULL means no per-user invalidation; a timestamp means reject tokens issued before it.

ALTER TABLE app_user ADD COLUMN token_invalidated_at TIMESTAMPTZ;
