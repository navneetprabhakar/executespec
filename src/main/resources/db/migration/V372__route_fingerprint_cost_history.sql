ALTER TABLE run_cost_history
    ADD COLUMN IF NOT EXISTS route_fingerprint VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS task_complexity_fingerprint VARCHAR(64);

-- Upgrade only rows whose physical successful provider/model set and real persisted task shape can
-- be reconstructed. Rows without this proof stay schema v1 and are ignored by the v2 query.
WITH successful_routes AS (
    SELECT
        run_id,
        string_agg(
            DISTINCT upper(provider) || ':' || lower(model),
            ',' ORDER BY upper(provider) || ':' || lower(model)
        ) AS route_fingerprint
    FROM run_step_usage
    WHERE upper(coalesce(attempt_status, '')) = 'SUCCESS'
      AND provider IS NOT NULL
      AND btrim(provider) <> ''
      AND model IS NOT NULL
      AND btrim(model) <> ''
    GROUP BY run_id
), task_shapes AS (
    SELECT
        run_id,
        count(*)::INTEGER AS task_count,
        'LOW:' || count(*) FILTER (WHERE upper(coalesce(nullif(btrim(complexity), ''), 'MEDIUM')) = 'LOW')
            || ',MEDIUM:' || count(*) FILTER (
                WHERE upper(coalesce(nullif(btrim(complexity), ''), 'MEDIUM')) NOT IN ('LOW', 'HIGH')
            )
            || ',HIGH:' || count(*) FILTER (WHERE upper(coalesce(nullif(btrim(complexity), ''), 'MEDIUM')) = 'HIGH')
            AS task_complexity_fingerprint
    FROM agent_task
    GROUP BY run_id
)
UPDATE run_cost_history h
SET route_fingerprint = routes.route_fingerprint,
    task_count = tasks.task_count,
    task_complexity_fingerprint = tasks.task_complexity_fingerprint,
    schema_version = 2
FROM successful_routes routes, task_shapes tasks
WHERE h.run_id = routes.run_id
  AND h.run_id = tasks.run_id
  AND h.status = 'COMPLETE'
  AND tasks.task_count > 0;

CREATE INDEX IF NOT EXISTS idx_run_cost_history_route_v2
    ON run_cost_history (
        org_id,
        plan_strategy,
        provider,
        route_fingerprint,
        repo_count,
        task_count,
        task_complexity_fingerprint,
        completed_at DESC
    )
    WHERE schema_version = 2 AND status = 'COMPLETE';
