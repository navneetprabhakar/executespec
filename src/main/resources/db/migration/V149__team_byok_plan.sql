-- M-051 (Wave 3) — TEAM-BYOK plan at $39/seat (parked decision #2).
--
-- Adds two new billing_plan rows alongside the existing TEAM
-- managed-key plans:
--   * "Team Monthly INR (BYOK)"  — INR per-seat pricing, BYOK key mode
--   * "Team Monthly USD (BYOK)"  — USD per-seat pricing, BYOK key mode
--
-- Same TEAM tier, same product_access set, same shared-pool seat
-- semantics — the only differences vs. the managed-key Team plans
-- are key_mode='BYOK' and the $39 / ₹3,199 per-seat price (vs $49
-- / ₹3,999 managed). Heavy users with their own Anthropic + OpenAI
-- keys avoid our managed-key markup; per parked decision #2 this
-- closes the margin risk on the top end without giving up the
-- per-seat shared-pool revenue model.
--
-- BYOK enforcement happens at run time via ApiKeyResolver — orgs on
-- a BYOK plan must have at least one provider key set on the org
-- (org.openai_api_key / anthropic_api_key / gemini_api_key) or via
-- one of their members. NewRunModal already surfaces the
-- AddApiKeysDrawer (M-008) when the keys are missing.
--
-- Annual rows for TEAM-BYOK are deliberately deferred — the
-- managed-key TEAM annual landed in V146 first; the BYOK annual
-- variant can ship in a follow-up once the monthly BYOK has demand
-- signal. Adding more billing_plan rows on day-one without a buyer
-- bloats the PlansPage card grid.
--
-- Reverse migration:
--   DELETE FROM billing_plan_limit WHERE billing_plan_id IN (
--       SELECT id FROM billing_plan WHERE name LIKE 'Team Monthly % (BYOK)');
--   DELETE FROM billing_plan WHERE name LIKE 'Team Monthly % (BYOK)';

-- ── Team Monthly INR (BYOK) ──────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Monthly INR (BYOK)', 'TEAM', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 319900, 0,         -- ₹3,199 per seat in paise
    2, -1, 0, FALSE, TRUE,
    'LISTED', NOW(), 'V149', 'M-051 Wave 3 — TEAM-BYOK launched per parked decision #2',
    NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan
     WHERE name = 'Team Monthly INR (BYOK)' AND tier = 'TEAM'
);

-- ── Team Monthly USD (BYOK) ──────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Monthly USD (BYOK)', 'TEAM', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 0, 3900,           -- $39 per seat in cents
    2, -1, 0, FALSE, TRUE,
    'LISTED', NOW(), 'V149', 'M-051 Wave 3 — TEAM-BYOK launched per parked decision #2',
    NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan
     WHERE name = 'Team Monthly USD (BYOK)' AND tier = 'TEAM'
);

-- billing_plan_limit rows mirror the managed Team monthly plans
-- (RUNS unlimited, PROJECTS=5, STORAGE_MB=5120). Inserted once each
-- via the same NOT EXISTS pattern V144 uses — so re-running is safe.

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'RUNS', -1
  FROM billing_plan bp
 WHERE bp.name LIKE 'Team Monthly % (BYOK)' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'RUNS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'PROJECTS', 5
  FROM billing_plan bp
 WHERE bp.name LIKE 'Team Monthly % (BYOK)' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'PROJECTS'
   );

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), bp.id, 'STORAGE_MB', 5120
  FROM billing_plan bp
 WHERE bp.name LIKE 'Team Monthly % (BYOK)' AND bp.tier = 'TEAM' AND bp.active = TRUE
   AND NOT EXISTS (
        SELECT 1 FROM billing_plan_limit l WHERE l.billing_plan_id = bp.id AND l.metric = 'STORAGE_MB'
   );
