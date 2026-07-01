-- RL-2026-06-070 — AI-credit pricing ladder refresh.
-- New commercial shape:
--   FREE: 200 managed AI credits, low-price native DeepSeek only.
--   PRO: $20/mo, 2,000 managed AI credits.
--   PRO_PLUS: $100/mo, 125,000 managed AI credits, hidden upsell.
--   BYOK: $50/mo, single-person org, own provider keys mandatory.
--   TEAM: $50/seat/mo, two-seat minimum, own provider keys.

ALTER TABLE managed_ai_usage_period
  ADD COLUMN IF NOT EXISTS purchased_ai_credits BIGINT NOT NULL DEFAULT 0;

UPDATE tier_config
   SET display_name = 'Free',
       monthly_run_limit = -1,
       description = 'Free launch plan: 200 AI credits/month, low-price native DeepSeek only',
       max_org_members = 1,
       max_projects_per_org = 1,
       max_repos_per_project = 1,
       concurrent_run_limit = 1,
       max_apply_suggestions = 2,
       max_preview_starts = 2,
       max_run_cost_usd = 3.0,
       lifecycle_state = 'LISTED',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'AI-credit pricing ladder refresh'
 WHERE tier = 'FREE';

UPDATE tier_config
   SET display_name = 'Pro',
       monthly_run_limit = -1,
       description = 'Pro: $20/month with 2,000 managed AI credits and all default models',
       max_org_members = 1,
       max_projects_per_org = 2,
       max_repos_per_project = 2,
       concurrent_run_limit = 3,
       max_apply_suggestions = 10,
       max_preview_starts = 5,
       max_run_cost_usd = 5.0,
       pr_review_enabled = TRUE,
       code_review_enabled = TRUE,
       pr_review_monthly_quota = 10,
       code_review_monthly_quota = 4,
       share_link_active_cap = 25,
       lifecycle_state = 'LISTED',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'AI-credit pricing ladder refresh'
 WHERE tier = 'PRO';

INSERT INTO tier_config (
    tier, display_name, monthly_run_limit, is_fixed, description,
    max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions,
    max_preview_starts, max_run_auto_retries, can_deploy, max_deployments,
    max_run_cost_usd, retention_days, execution_view_enabled,
    max_projects_per_org, max_repos_per_project,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    pr_review_enabled, code_review_enabled, pr_review_monthly_quota, code_review_monthly_quota,
    share_link_active_cap
) VALUES (
    'PRO_PLUS', 'Pro+', -1, FALSE,
    'Hidden Pro+ upsell: $100/month with 125,000 managed AI credits',
    1, 10, 5, -1,
    -1, 2, TRUE, 10,
    NULL, 180, FALSE,
    5, 5,
    'HIDDEN', now(), 'system-migration', 'Hidden Pro+ upsell seeded',
    TRUE, TRUE, -1, -1,
    100
)
ON CONFLICT (tier) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    monthly_run_limit = EXCLUDED.monthly_run_limit,
    description = EXCLUDED.description,
    max_org_members = EXCLUDED.max_org_members,
    max_chat_messages = EXCLUDED.max_chat_messages,
    concurrent_run_limit = EXCLUDED.concurrent_run_limit,
    max_apply_suggestions = EXCLUDED.max_apply_suggestions,
    max_preview_starts = EXCLUDED.max_preview_starts,
    max_run_auto_retries = EXCLUDED.max_run_auto_retries,
    can_deploy = EXCLUDED.can_deploy,
    max_deployments = EXCLUDED.max_deployments,
    max_run_cost_usd = EXCLUDED.max_run_cost_usd,
    retention_days = EXCLUDED.retention_days,
    execution_view_enabled = EXCLUDED.execution_view_enabled,
    max_projects_per_org = EXCLUDED.max_projects_per_org,
    max_repos_per_project = EXCLUDED.max_repos_per_project,
    lifecycle_state = EXCLUDED.lifecycle_state,
    lifecycle_changed_at = now(),
    lifecycle_changed_by = 'system-migration',
    lifecycle_reason = EXCLUDED.lifecycle_reason,
    pr_review_enabled = EXCLUDED.pr_review_enabled,
    code_review_enabled = EXCLUDED.code_review_enabled,
    pr_review_monthly_quota = EXCLUDED.pr_review_monthly_quota,
    code_review_monthly_quota = EXCLUDED.code_review_monthly_quota,
    share_link_active_cap = EXCLUDED.share_link_active_cap;

INSERT INTO tier_config (
    tier, display_name, monthly_run_limit, is_fixed, description,
    max_org_members, max_chat_messages, concurrent_run_limit, max_apply_suggestions,
    max_preview_starts, max_run_auto_retries, can_deploy, max_deployments,
    max_run_cost_usd, retention_days, execution_view_enabled,
    max_projects_per_org, max_repos_per_project,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    pr_review_enabled, code_review_enabled, pr_review_monthly_quota, code_review_monthly_quota,
    share_link_active_cap
) VALUES (
    'BYOK', 'BYOK', -1, FALSE,
    'Single-person BYOK org: $50/month, own provider keys and model matrix required',
    1, 10, 5, -1,
    -1, 2, TRUE, -1,
    NULL, 180, FALSE,
    5, 5,
    'LISTED', now(), 'system-migration', 'BYOK single-person tier seeded',
    TRUE, TRUE, -1, -1,
    -1
)
ON CONFLICT (tier) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    monthly_run_limit = EXCLUDED.monthly_run_limit,
    description = EXCLUDED.description,
    max_org_members = EXCLUDED.max_org_members,
    max_chat_messages = EXCLUDED.max_chat_messages,
    concurrent_run_limit = EXCLUDED.concurrent_run_limit,
    max_apply_suggestions = EXCLUDED.max_apply_suggestions,
    max_preview_starts = EXCLUDED.max_preview_starts,
    max_run_auto_retries = EXCLUDED.max_run_auto_retries,
    can_deploy = EXCLUDED.can_deploy,
    max_deployments = EXCLUDED.max_deployments,
    max_run_cost_usd = EXCLUDED.max_run_cost_usd,
    retention_days = EXCLUDED.retention_days,
    execution_view_enabled = EXCLUDED.execution_view_enabled,
    max_projects_per_org = EXCLUDED.max_projects_per_org,
    max_repos_per_project = EXCLUDED.max_repos_per_project,
    lifecycle_state = EXCLUDED.lifecycle_state,
    lifecycle_changed_at = now(),
    lifecycle_changed_by = 'system-migration',
    lifecycle_reason = EXCLUDED.lifecycle_reason,
    pr_review_enabled = EXCLUDED.pr_review_enabled,
    code_review_enabled = EXCLUDED.code_review_enabled,
    pr_review_monthly_quota = EXCLUDED.pr_review_monthly_quota,
    code_review_monthly_quota = EXCLUDED.code_review_monthly_quota,
    share_link_active_cap = EXCLUDED.share_link_active_cap;

UPDATE tier_config
   SET display_name = 'Team',
       monthly_run_limit = -1,
       description = 'Team: $50/seat/month BYOK, two-seat self-serve minimum',
       max_org_members = 20,
       max_projects_per_org = 10,
       max_repos_per_project = 10,
       concurrent_run_limit = 10,
       max_apply_suggestions = -1,
       max_preview_starts = -1,
       max_run_cost_usd = NULL,
       pr_review_enabled = TRUE,
       code_review_enabled = TRUE,
       pr_review_monthly_quota = -1,
       code_review_monthly_quota = -1,
       share_link_active_cap = -1,
       lifecycle_state = 'LISTED',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'AI-credit pricing ladder refresh'
 WHERE tier = 'TEAM';

UPDATE billing_plan
   SET active = FALSE,
       lifecycle_state = 'HIDDEN',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'Superseded by V253 AI-credit pricing ladder'
 WHERE active = TRUE
   AND tier IN ('PRO', 'PRO_PLUS', 'BYOK', 'TEAM');

INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
) VALUES
    (gen_random_uuid(), 'Pro Monthly INR', 'PRO', 'MANAGED',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     169900, 0, 0, 0, 1, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 Pro launch price', now(), now()),
    (gen_random_uuid(), 'Pro Monthly USD', 'PRO', 'MANAGED',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     0, 2000, 0, 0, 1, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 Pro launch price', now(), now()),
    (gen_random_uuid(), 'Pro+ Monthly INR', 'PRO_PLUS', 'MANAGED',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     849900, 0, 0, 0, 1, -1, 0, FALSE, TRUE,
     'HIDDEN', now(), 'system-migration', 'V253 hidden Pro+ upsell', now(), now()),
    (gen_random_uuid(), 'Pro+ Monthly USD', 'PRO_PLUS', 'MANAGED',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     0, 10000, 0, 0, 1, -1, 0, FALSE, TRUE,
     'HIDDEN', now(), 'system-migration', 'V253 hidden Pro+ upsell', now(), now()),
    (gen_random_uuid(), 'BYOK Monthly INR', 'BYOK', 'BYOK',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     429900, 0, 0, 0, 1, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 single-person BYOK', now(), now()),
    (gen_random_uuid(), 'BYOK Monthly USD', 'BYOK', 'BYOK',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     0, 5000, 0, 0, 1, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 single-person BYOK', now(), now()),
    (gen_random_uuid(), 'Team Monthly INR', 'TEAM', 'BYOK',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     0, 0, 429900, 0, 2, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 Team seat price', now(), now()),
    (gen_random_uuid(), 'Team Monthly USD', 'TEAM', 'BYOK',
     '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
     0, 0, 0, 5000, 2, -1, 0, FALSE, TRUE,
     'LISTED', now(), 'system-migration', 'V253 Team seat price', now(), now());

UPDATE billing_plan
   SET run_limit = -1
 WHERE tier = 'FREE';

UPDATE topup_package
   SET active = FALSE,
       updated_at = now()
 WHERE active = TRUE;

INSERT INTO topup_package (name, run_count, amount_inr, amount_usd, sort_order)
VALUES
    ('500 AI credits', 500, 42500, 500, 1),
    ('1,000 AI credits', 1000, 85000, 1000, 2);
