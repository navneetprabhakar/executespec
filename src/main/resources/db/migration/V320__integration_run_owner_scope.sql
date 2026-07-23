-- V320 — bind every integration-triggered run to an explicit owning org + actor.
--
-- RR-339: `GET /api/integrations/runs` listed EVERY integration run to any authenticated user.
-- `tenant_id` was populated opportunistically by the JPA entity listener from a request-scoped
-- thread local (defaulting to the literal 'default' for unauthenticated webhook threads), so it
-- was never a trustworthy ownership fact. These columns are the explicit, non-default ownership
-- record that the read path filters on.
--
-- RR-340: manual Jira/Confluence triggers submitted runs under a single globally configured
-- runner identity, so any authenticated caller could cause spend in the runner's tenant.
-- `created_by_user_id` records the actor that actually caused the run.
--
-- Legacy rows keep NULL org_id. The read path treats NULL as "unknown owner" and fails closed:
-- only SUPER_ADMIN can see them. Backfilling them would require inventing an owner we do not know.

ALTER TABLE integration_run ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
ALTER TABLE integration_run ADD COLUMN IF NOT EXISTS created_by_user_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_integration_run_org_created
    ON integration_run (org_id, created_at DESC);
