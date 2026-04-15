-- V96 — flip project.runs.auto-push ON by default.
-- Every successful project-scoped V2 run now opens PRs automatically.
-- Manual "Push & open PR" still works as a re-push.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES ('project.runs.auto-push', 'true', 'BOOLEAN', 'GLOBAL',
        'Auto-push successful project runs to SCM + open PRs')
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
