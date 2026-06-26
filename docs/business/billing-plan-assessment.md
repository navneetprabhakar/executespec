# Billing Plan Assessment

> Assessed 2026-06-26 against migrations, backend enforcement, public website copy, and billing docs.

## Current Implemented Ladder

| Tier | Listed offer | Runtime/enforcement truth | Assessment |
| --- | --- | --- | --- |
| FREE | $0, 5 runs/month, small specs, $3/run cap | V248 force-sets `tier_config.monthly_run_limit=5` and `billing_plan.run_limit=5` when stale rows carry another value, especially `-1` unlimited. `TierEnforcementService` reads `tier_config`; its Java fallback/comment path still needs a cleanup pass because it still mentions 15. | Tighter acquisition tier with lower managed-spend exposure. Keep complexity gate and cap strict. |
| PRO | $29/month or $289/year, 30 managed-key runs, $5/run cap, BYOK can lift cap | V176 bumps PRO to 30 and seeds $29/₹2,499 rows; V177 adds annual; V185 restores Pro name; V207 deactivates stale $20 duplicate rows. `resolveRunCostBudget(PRO,false)=5`; PRO+BYOK is uncapped. | Viable as solo bridge, but gross margin needs active run-cost monitoring. Heavy managed-key users must be nudged to Team BYOK. |
| TEAM | $39/seat/month, ₹3,399/seat, from 1 seat, monthly only, BYOK, no platform cost cap | V165 makes Team the canonical $39 seat tier and deactivates old yearly rows; V206 aligns INR to ₹3,399; `BillingService` and `OrgService` enforce 1-20 seats; `resolveRunCostBudget(TEAM,*)=-1`. | Best margin-safe self-serve plan. Missing annual Team plan is a deliberate gap but may reduce cash collection and procurement fit. |
| ENTERPRISE | Custom, unlimited policy, DPA/SLA path, org model controls | `resolveRunCostBudget(ENTERPRISE,*)=-1`; org model overrides and provider key lanes exist; enterprise compliance claims remain bounded by docs. | Correct for custom sales, but do not oversell SAML/SOC2/dedicated region. |

## Code Truth Checked

- `src/main/resources/db/migration/V200__bump_free_tier_to_15_runs.sql` (historical; superseded for current FREE quota by V248)
- `src/main/resources/db/migration/V248__fix_free_tier_run_limit_sentinel.sql`
- `src/main/resources/db/migration/V176__unhide_pro_as_solo_tier.sql`
- `src/main/resources/db/migration/V177__solo_annual_and_founder_pricing.sql`
- `src/main/resources/db/migration/V185__retire_solo_branding.sql`
- `src/main/resources/db/migration/V207__deactivate_stale_pro_monthly_dupes.sql`
- `src/main/resources/db/migration/V165__drop_max_tier_team_at_39.sql`
- `src/main/resources/db/migration/V206__inr_pricing_usd_alignment.sql`
- `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java`
- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/main/java/com/navneet/executespec/auth/service/OrgService.java`
- `website/index.html`

## Issues Found During Assessment

| Issue | Severity | Fix in this pass |
| --- | --- | --- |
| Fallback quotas/comments in `TierEnforcementService` still mention Free=15 after V248 made the DB source of truth Free=5. | Medium | Not changed in this docs-only pass. Runtime normally reads DB, but fallback/comment drift should be fixed in code. |
| Several code comments and DTO comments still said Team requires 2-20 seats. | Low | Updated comments to 1-20. Enforcement was already correct. |
| Core docs had stale migration-head references at assessment time. | Low | Updated active docs in that pass; current migration head is tracked in the root docs and CODEMAP. |
| `docs/billing-workflows.html` still described PRO as hidden/grandfathered. | Medium | Needs HTML refresh or replacement; do not use as current pricing truth until updated. |
| `website/pricing-analysis.md` is explicitly pre-V141 and contains old MAX/Pro economics. | Medium | Treat as historical. Current assessment supersedes it for business planning. |

## Plan Economics View

### Free

Free is intentionally narrow after V248: 5 small runs/month with a $3 cap. The risk is still managed-key cost exposure, not conversion friction. Keep Free limited to small specs and enforce the $3 cap. Do not increase Free until activation and cost data justify it.

### Pro

Pro is the questionable plan economically. It is attractive for solo developers, but managed-key costs can erase margin if users run complex tasks near the $5 cap. The current design protects through:

- 30-run quota,
- $5 per-run cap,
- credit confirmation at plan approval,
- BYOK option for users who need higher caps,
- run-cost logging and per-call usage attribution.

Recommendation for brainstorm: keep Pro for now, but decide whether it is a sustainable plan or an acquisition bridge. If sustainable, define explicit guardrails by run complexity and average COGS. If acquisition, make Team BYOK the intended upgrade path.

### Team

Team is commercially clean: seat revenue is platform revenue, model spend sits with the customer, and unlimited runs are bounded by the customer's own provider quota and org governance. The weak point is packaging:

- no annual Team row after V165,
- Razorpay mid-cycle seat changes are manual/501,
- provider cost controls are currently product/governance language more than a complete procurement-grade spend-management suite.

Recommendation for brainstorm: keep Team as the primary self-serve plan, but decide whether to add annual Team and whether "managed Team add-on" exists at all.

### Enterprise

Enterprise should sell governance, model control, security posture, and custom support. Keep compliance language precise. The credible current pitch is: DPA/SLA path, org model matrix, BYOK/aggregator lanes, access guards, audit/payment logs, and project governance. The non-credible pitch is: SOC 2 done, SAML enforced, dedicated region available self-serve.

## Recommended Brainstorm Questions

1. Should Pro stay at 30 managed runs, or should it become lower quota plus paid top-ups?
2. Should Team get annual pricing now, or wait for demand?
3. Should Team remain BYOK-only in messaging, even though backend can support HYBRID key mode?
4. What is the minimum proof packet before quoting benchmark performance in the investor deck?
5. Should credits be explained as "1 credit ~= $5 model budget" everywhere, or should public copy hide that and emphasize plan approval plus caps?
6. Should Enterprise pricing anchor to seats, platform fee, run volume, or custom usage?
