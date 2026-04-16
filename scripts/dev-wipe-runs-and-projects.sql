-- Dev-only reset: wipes every run + project + workspace row, resets per-user
-- and per-org monthly run counters to 0. Preserves identity + billing tables
-- (app_user, org, org_member, tier_config, billing_*, invoice*, run_topup,
-- topup_package, payment_audit, accounting_entry, model_pricing, feature_flag,
-- prompt_audit*, activity_audit, refresh_token_blocklist, email_token,
-- deployment*, seller_config, org_slack_integration).
--
-- Idempotent. Safe to run multiple times. Wrap in a single transaction so
-- either all tables go or none do.
--
-- USAGE:
--   psql -U postgres -h localhost -d executespec -f scripts/dev-wipe-runs-and-projects.sql
--
-- Or with the helper: scripts/dev-wipe-runs-and-projects.sh

BEGIN;

-- ── Run-lifecycle + artefact tables ──────────────────────────────────────────
TRUNCATE TABLE
    agent_inbox,
    agent_message,
    agent_task,
    background_validation,
    compaction_event,
    event_log,
    file_lock,
    gate_event,
    gate_record,
    integration_event_log,
    integration_run,
    merge_conflict,
    model_task_metric,
    notification,
    plan_effectiveness,
    plan_version,
    preview_metrics,
    preview_registry,
    project_architecture_doc,
    project_chat_message,
    project_code_map,
    project_document_chunk,
    project_document,
    project_member,
    project_memory_snapshot,
    project_repo_link,
    project_run_ledger,
    project_slack_channel,
    project_spec_template,
    project_repo,
    pull_request_record,
    run_code_map,
    run_cost_log,
    run_recovery,
    run_repo_snapshot,
    run_repo,
    side_effect_outbox,
    slack_notification_log,
    test_plan,
    test_result,
    test_task,
    triage_outcome,
    validation_report,
    webhook_event_log,
    execution_context_handover,
    execution_solution_dependency,
    execution_solution_repo,
    execution_solution_run,
    execution_solution,
    execution_run,
    pipeline_run,
    project
RESTART IDENTITY CASCADE;

-- ── Reset run counters (do NOT touch tier/billing) ───────────────────────────
UPDATE org      SET monthly_run_count  = 0 WHERE monthly_run_count  <> 0;
UPDATE app_user SET personal_run_count = 0 WHERE personal_run_count <> 0;

COMMIT;

-- Summary
\echo '✓ Wiped: runs, tasks, projects, repos, architecture, memory, templates, PRs, executions, documents, notifications, side-effects, workspace artefacts.'
\echo '✓ Reset: org.monthly_run_count + app_user.personal_run_count = 0'
\echo '✓ Preserved: app_user identity, org, tier_config, billing_*, invoice*, run_topup, topup_package, payment_audit, accounting_entry, model_pricing, feature_flag, prompt_audit*.'
