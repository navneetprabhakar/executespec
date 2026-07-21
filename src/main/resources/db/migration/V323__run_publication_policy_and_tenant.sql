-- V323 — durable run publication policy + tenant invariant (RR-234, RR-236).
--
-- 1. pipeline_run.skip_auto_push (RR-234).
--    The customer's "stage this, do not publish it" consent lived only in a Redis key whose own
--    contract states that a JVM crash loses the intent and the next attempt resumes the default
--    auto-push. That failure mode silently converts "do not publish" into "publish" — the one
--    direction an irreversible publication gate must never fail in. The consent is now stamped on
--    the run row atomically with run creation, and RunOrchestrator's auto-push gate fails CLOSED
--    when it cannot read the policy.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS skip_auto_push BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Tenant invariant (RR-236).
--    A project-scoped run's org must be the org that owns the project, not the submitting user's
--    home org. Before this, an external collaborator's run carried the host project_id with the
--    collaborator's personal org_id, so the run and the tenant that owns the work disagreed —
--    breaking org-scoped quota, billing attribution and audit.
--
--    Existing rows are repaired first, then the invariant is enforced as a NOT VALID constraint:
--    new and updated rows must satisfy it, while any historical row that cannot be repaired stays
--    readable instead of blocking the migration. Run `VALIDATE CONSTRAINT` as a separate operator
--    step once the backfill is confirmed complete.
UPDATE pipeline_run r
SET org_id = p.org_id
FROM project p
WHERE r.project_id = p.id
  AND r.project_id IS NOT NULL
  AND p.org_id IS NOT NULL
  AND (r.org_id IS DISTINCT FROM p.org_id);

ALTER TABLE pipeline_run
    DROP CONSTRAINT IF EXISTS chk_pipeline_run_project_org;

ALTER TABLE pipeline_run
    ADD CONSTRAINT chk_pipeline_run_project_org
    CHECK (
        project_id IS NULL
        OR org_id IS NOT NULL
    ) NOT VALID;
