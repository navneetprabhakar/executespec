-- Dev-only reset: wipes every run + project + workspace row, resets per-user
-- and per-org monthly run counters to 0. Preserves identity + billing tables
-- (app_user, org, org_member, tier_config, billing_*, invoice*, run_topup,
-- topup_package, payment_audit, accounting_entry, model_pricing, feature_flag,
-- prompt_audit*, activity_audit, refresh_token_blocklist, email_token,
-- deployment*, seller_config, org_slack_integration, org_standard,
-- user_notification_pref).
--
-- Idempotent. Safe to run multiple times. Wrap in a single transaction so
-- either all tables go or none do.
--
-- IMPORTANT — TRUNCATE CASCADE semantics: TRUNCATE CASCADE follows every
-- FK pointing AT the truncated table and truncates those tables too,
-- regardless of the FK's ON DELETE action. This means V126's
-- `app_user.sandbox_project_id REFERENCES project(id) ON DELETE SET NULL`
-- still gets app_user swept when we TRUNCATE project CASCADE. The
-- 2026-04-26 cleanup ran into this — see commit 6bd69667.
--
-- FIX (this script): save app_user + billing_subscription rows to TEMP
-- tables before TRUNCATE, then re-INSERT after TRUNCATE finishes. Cheap
-- (a couple hundred rows max) and bulletproof against any future FK
-- additions onto these identity tables.
--
-- USAGE:
--   psql -U postgres -h localhost -d executespec -f scripts/dev-wipe-runs-and-projects.sql
--
-- Or with the helper: scripts/dev-wipe-runs-and-projects.sh

BEGIN;

-- ── Pre-truncate save: snapshot identity tables that TRUNCATE CASCADE
--    will inadvertently sweep through the FK chain (project →
--    app_user.sandbox_project_id; app_user → billing_subscription.user_id).
--    These are restored verbatim after the TRUNCATE finishes.
CREATE TEMP TABLE _saved_app_user             AS TABLE app_user             WITH NO DATA;
CREATE TEMP TABLE _saved_billing_subscription AS TABLE billing_subscription WITH NO DATA;
INSERT INTO _saved_app_user             SELECT * FROM app_user;
INSERT INTO _saved_billing_subscription SELECT * FROM billing_subscription;

-- The saved sandbox_project_id values point at project rows that are about
-- to be wiped — null them in the snapshot so the post-TRUNCATE INSERT
-- doesn't FK-violate.
UPDATE _saved_app_user SET sandbox_project_id = NULL WHERE sandbox_project_id IS NOT NULL;

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
    -- V131-V139 enterprise-readiness run/project artefact tables (added
    -- 2026-04-26 to keep the wipe in sync with the new persistence stripes).
    -- Preserved: org_standard (org policy, not run data) +
    -- user_notification_pref (per-user prefs, not run data).
    activity_feed,
    contract_drift_event,
    contract_consumer,
    contract_version,
    contract_registry,
    standards_violation,
    project_standard_override,
    adr,
    arch_invariant_violation,
    arch_invariant,
    project_config,
    project
RESTART IDENTITY CASCADE;

-- ── Post-truncate restore of the identity tables that CASCADE swept ────────
INSERT INTO app_user             SELECT * FROM _saved_app_user;
INSERT INTO billing_subscription SELECT * FROM _saved_billing_subscription;

-- ── Reset run counters (do NOT touch tier/billing) ───────────────────────────
UPDATE org      SET monthly_run_count  = 0 WHERE monthly_run_count  <> 0;
UPDATE app_user SET personal_run_count = 0 WHERE personal_run_count <> 0;

COMMIT;

-- Summary
\echo '✓ Wiped: runs, tasks, projects, repos, architecture, memory, templates, PRs, executions, documents, notifications, side-effects, workspace artefacts, V131-V139 (activity_feed, contracts, standards violations + project overrides, adr cache, arch invariants + violations, project_config).'
\echo '✓ Reset: org.monthly_run_count + app_user.personal_run_count = 0'
\echo '✓ Preserved: app_user identity (sandbox_project_id nulled before TRUNCATE), org, tier_config, billing_*, invoice*, run_topup, topup_package, payment_audit, accounting_entry, model_pricing, feature_flag, prompt_audit*, org_standard (org policy), user_notification_pref (per-user prefs).'
