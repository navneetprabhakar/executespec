-- V179__drop_product_split.sql
-- Single-surface retirement (2026-05-06) — reverse of V143__product_split.sql.
--
-- We're not building two consumer/B2B surfaces. Pre-GA every account is
-- on the single 'executespec' surface; the V143 ENUM + product_id columns
-- + app_user_product_access table sat dormant (NOT NULL DEFAULT
-- 'executespec' on every row). Carrying schema we never use is a footgun
-- — every JPA insert had to thread an unused column, ColumnTransformer
-- cast, etc.
--
-- This migration drops:
--   - product_id column from 6 tables (pipeline_run, project, org,
--     billing_subscription, invoice, notification)
--   - app_user_product_access table
--   - product_surface ENUM type
--   - 3 hot-path indexes (idx_pipeline_run_product_user etc) — Postgres
--     auto-drops these when the column goes
--
-- Idempotent: every drop guarded with IF EXISTS so re-runs are safe.
-- Ordering: drop columns BEFORE the ENUM type; Postgres won't drop a
-- type that's still referenced as a column.

BEGIN;

-- 1. Drop the 6 columns. Postgres cascades the indexes that depend on them.
ALTER TABLE pipeline_run        DROP COLUMN IF EXISTS product_id;
ALTER TABLE project             DROP COLUMN IF EXISTS product_id;
ALTER TABLE org                 DROP COLUMN IF EXISTS product_id;
ALTER TABLE billing_subscription DROP COLUMN IF EXISTS product_id;
ALTER TABLE invoice             DROP COLUMN IF EXISTS product_id;
ALTER TABLE notification        DROP COLUMN IF EXISTS product_id;

-- 2. Drop the join table (no FK dependencies block this).
DROP TABLE IF EXISTS app_user_product_access;

-- 3. Drop the ENUM type — must be after every column referencing it is gone.
DROP TYPE IF EXISTS product_surface;

COMMIT;
