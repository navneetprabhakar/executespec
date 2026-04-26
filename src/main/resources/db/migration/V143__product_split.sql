-- V143__product_split.sql
-- Add product_id discriminator for the consumer (VIBE+PRO) vs B2B (TEAM+ENT) split.
-- Existing rows backfill to 'executespec' (the historical surface).
-- New consumer-prod traffic from app.executespec.ai writes 'vibe'.
--
-- Idempotent: safe to re-run. Phase-1 groundwork only — call sites are not
-- yet wired; ProductSurfaceFilter (Phase 1.0 #2) reads the discriminator
-- but does not yet enforce it on every repository query (deferred to Phase 2).

BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'product_surface') THEN
    CREATE TYPE product_surface AS ENUM ('vibe', 'executespec');
  END IF;
END$$;

ALTER TABLE pipeline_run
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

ALTER TABLE org
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

ALTER TABLE billing_subscription
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

ALTER TABLE invoice
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

ALTER TABLE notification
  ADD COLUMN IF NOT EXISTS product_id product_surface NOT NULL DEFAULT 'executespec';

-- One account, two product entitlements (a user can hold both).
CREATE TABLE IF NOT EXISTS app_user_product_access (
  user_id    VARCHAR(255) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  product_id product_surface NOT NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, product_id)
);

-- Backfill: every existing user keeps access to executespec.
INSERT INTO app_user_product_access (user_id, product_id)
SELECT id, 'executespec'::product_surface FROM app_user
ON CONFLICT DO NOTHING;

-- Hot-path indexes (mirror existing query patterns on each table).
CREATE INDEX IF NOT EXISTS idx_pipeline_run_product_user ON pipeline_run(product_id, created_by_user_id);
CREATE INDEX IF NOT EXISTS idx_project_product_org      ON project(product_id, org_id);
CREATE INDEX IF NOT EXISTS idx_billing_sub_product_user ON billing_subscription(product_id, user_id);

COMMIT;
