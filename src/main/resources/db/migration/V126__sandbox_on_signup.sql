-- Wave 2.2 — sandbox project on signup (TTFV reduction).
--
-- Each new user gets a single disposable sandbox project auto-provisioned
-- on first login. Workspace is GC'd after 24h of inactivity. Sample specs
-- pre-fill the run-submit form so the user can click "Submit" and see a
-- verified score in under 3 minutes.
--
-- Full design: docs/plans/ttfv-reduction-plan.md.

ALTER TABLE app_user
    ADD COLUMN sandbox_project_id VARCHAR(255);

-- FK is deferrable nulls-ok — sandbox is created lazily on first login, so
-- the column stays NULL for pre-existing users and users who skip /welcome.
-- ON DELETE SET NULL so deleting a sandbox project doesn't cascade-delete
-- the user.
ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_sandbox_project
    FOREIGN KEY (sandbox_project_id) REFERENCES project(id) ON DELETE SET NULL;

CREATE INDEX idx_app_user_sandbox ON app_user(sandbox_project_id)
    WHERE sandbox_project_id IS NOT NULL;

-- Flag used by SandboxCleanupJob to GC abandoned sandbox workspaces. Also
-- used by frontend to hide sandbox projects from the /org/projects list
-- (they're user-scoped, not org-scoped).
ALTER TABLE project
    ADD COLUMN is_sandbox BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_project_sandbox ON project(is_sandbox) WHERE is_sandbox = TRUE;
