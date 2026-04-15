-- V102 — Spec templates (Phase 4.2 / UX-2.3).
--
-- Reusable requirement skeletons scoped to either a project or an org. Used by
-- ExecutionSetup's "Templates" picker so the user doesn't retype CRUD
-- patterns / auth additions / pagination skeletons each time.
--
-- Slot interpolation ({{ENTITY}}, {{ENDPOINT}}, ...) is purely client-side:
-- the backend stores the raw text and lets the user fill slots in the
-- textarea before submission.

CREATE TABLE IF NOT EXISTS project_spec_template (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- PROJECT-scoped templates pin to a project; ORG-scoped templates pin to
    -- an org and surface across every project in that org.
    owner_type            VARCHAR(10) NOT NULL CHECK (owner_type IN ('PROJECT', 'ORG')),
    owner_id              VARCHAR(64) NOT NULL,
    name                  VARCHAR(255) NOT NULL,
    description           TEXT,
    requirement_template  TEXT NOT NULL,
    created_by            VARCHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_spec_template_owner
    ON project_spec_template(owner_type, owner_id) WHERE deleted_at IS NULL;
