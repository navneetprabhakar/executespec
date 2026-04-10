-- V67: Row-Level Security for tenant isolation on core tables
--
-- RLS adds a database-level enforcement layer that prevents cross-tenant data
-- access even if the JPA interceptor is bypassed (e.g. a bug or direct SQL).
--
-- Policy logic: allow access when either:
--   (a) no org context is set (background system threads / migrations / tests), OR
--   (b) the row's org_id is NULL (personal free-tier runs), OR
--   (c) the row's org_id matches the session variable set by TenantConnectionInterceptor
--
-- The session variable is set per-request via:
--   SET LOCAL app.current_org_id = '<orgId>';
-- and cleared at transaction end (SET LOCAL scopes to the current transaction).
--
-- IMPORTANT: Superuser connections and connections with BYPASSRLS privilege bypass
-- RLS. Ensure the application DB user does NOT have SUPERUSER or BYPASSRLS.
-- Background system operations (Flyway, scheduled jobs) should use a separate role
-- with BYPASSRLS or connect before enabling RLS on this table.

-- ── pipeline_run ─────────────────────────────────────────────────────────────
ALTER TABLE pipeline_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE pipeline_run FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pipeline_run
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR org_id IS NULL
        OR org_id = current_setting('app.current_org_id', true)
    );

-- ── agent_task ───────────────────────────────────────────────────────────────
ALTER TABLE agent_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_task FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON agent_task
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR run_id IN (
            SELECT run_id FROM pipeline_run
            WHERE org_id IS NULL
               OR org_id = current_setting('app.current_org_id', true)
        )
    );

-- ── validation_report ────────────────────────────────────────────────────────
ALTER TABLE validation_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE validation_report FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON validation_report
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR run_id IN (
            SELECT run_id FROM pipeline_run
            WHERE org_id IS NULL
               OR org_id = current_setting('app.current_org_id', true)
        )
    );

-- ── background_validation ────────────────────────────────────────────────────
ALTER TABLE background_validation ENABLE ROW LEVEL SECURITY;
ALTER TABLE background_validation FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON background_validation
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR run_id IN (
            SELECT run_id FROM pipeline_run
            WHERE org_id IS NULL
               OR org_id = current_setting('app.current_org_id', true)
        )
    );
