-- E3.14 — Enterprise readiness, Phase E3 (architecture invariants)
--
-- Per plan §5.12: invariants are continuous architectural properties
-- (e.g. "only payment-service writes to the payments table"). They
-- live in customer git as .executespec/arch/invariants.yaml per repo;
-- DB caches them + records violations.
--
-- Reverse migration:
--   DROP TABLE IF EXISTS arch_invariant_violation CASCADE;
--   DROP TABLE IF EXISTS arch_invariant CASCADE;

CREATE TABLE IF NOT EXISTS arch_invariant (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id      VARCHAR(64) NOT NULL,
  invariant_id    VARCHAR(128) NOT NULL,
  invariant_yaml  TEXT NOT NULL,
  status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, invariant_id)
);

CREATE TABLE IF NOT EXISTS arch_invariant_violation (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  invariant_id      UUID NOT NULL REFERENCES arch_invariant(id) ON DELETE CASCADE,
  detected_at       TIMESTAMP NOT NULL DEFAULT now(),
  detection_phase   VARCHAR(32) NOT NULL,        -- PLAN | ARCH_REGEN
  detail            JSONB
);

CREATE INDEX IF NOT EXISTS idx_arch_invariant_project ON arch_invariant(project_id);
CREATE INDEX IF NOT EXISTS idx_arch_invariant_violation_inv
  ON arch_invariant_violation(invariant_id, detected_at DESC);
