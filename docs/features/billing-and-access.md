# Billing, Access, and Quotas

> Current-state billing and access reference, refreshed 2026-06-20 from migrations, services, and public pricing copy.

## Current Billing Model

ExecuteSpec uses tiered access plus run credits. Runs consume quota at the submission/approval boundary depending on the flow, and failed runs are refunded through the quota refund path.

| Tier | Listed shape | Enforcement notes |
| --- | --- | --- |
| FREE | 5 runs/month, small specs, fixed model default, $3/run cap | V248 force-corrects `tier_config.monthly_run_limit` and `billing_plan.run_limit` to 5 so stale `-1` unlimited sentinels cannot leak through entitlements. FREE has no model matrix. |
| PRO | $29/month or $289/year, 30 managed-key runs/month, $5/run cap | V176/V177/V185/V207 make PRO listed and remove stale $20 duplicates. BYOK lifts the cap. |
| TEAM | $39/seat/month, from 1 seat, 1-20 self-serve seats, BYOK, unlimited runs against customer provider quota | V165 makes Team the canonical $39 seat plan; V206 aligns INR to ₹3,399; no platform per-run cap. |
| ENTERPRISE | Custom contract, DPA/SLA path, org model controls, expanded admin/security review | Custom/commercial path; do not imply self-serve SOC 2, SAML enforcement, or dedicated region. |

Run credits are charged only when the plan is approved. The current run-credit explanation is: 1 run credit roughly maps to a $5 model-spend budget, and a run can cost 1-N run credits based on estimate.

Managed AI credits are separate from run credits. For launch, `$1 = 100 ES AI credits`; successful platform-key LLM calls debit `ceil(raw_cost_usd * retailMultiplier * 100)` from the user's current managed-AI period. PRO is configured with 2000 included ES AI credits, a $16 raw COGS cap, 1.25x retail multiplier, value mode after $4, cheap-only after $12, and hard stop at $16. BYOK calls bypass managed debit; managed platform-key calls can step down to cheaper same-provider models mid-execution, and critical planner/reviewer calls in value mode can step up to a same-provider frontier model while frontier budget remains. Routing-state recomputation and allow/block rules live in `ManagedAiRoutingPolicy`; ledger/DTO shape conversion lives in `ManagedAiUsageMapper`; current-period/user resolution and transition persistence live in `ManagedAiUsagePeriodService`; notification channel/copy policy lives in `ManagedAiNotificationPolicy`; `ManagedAiUsageService` owns call authorization, ledger mutation, event persistence, and policy response assembly.

## Key Capabilities

| Capability | Status | Code |
| --- | --- | --- |
| Tier plans and lifecycle | Live | `billing/lifecycle/`, `BillingService`, `BillingSubscriptionPolicy` |
| Stripe checkout/subscription | Live | `billing/gateway/StripeGateway`, `BillingService` |
| Razorpay checkout/top-ups | Live, with manual mid-cycle Team seat adjustment | `billing/gateway/RazorpayGateway`, `RunTopupService`, `OrgService.adjustSeats` |
| Payment audit | Live | `PaymentAuditController`, `PaymentAuditService` |
| Payment history | Live | `docs/payment-history.md`, `billing/` |
| Run quota enforcement | Live | `TierEnforcementService`, monthly counters |
| Failed-run refund | Live | `RunQuotaRefundListener` |
| One-seat TEAM | Live | `BillingSubscriptionPolicy`, `BillingService`, `OrgService` |
| Org/user API keys | Live | `OrgService`, `AuthController`, `OrgController` |
| Personal access tokens | Live | `PersonalAccessTokenService` |
| Local-hands run submit | Enabled by V244 flag default, PAT-only | `worker.local-hands.enabled`, `RunController.submit`, `tool/remote/` |
| External sharing flag | Live | `Org.external_sharing_enabled` |
| External-invite effective entitlement | Live | `entitlements/EntitlementResolver`, `TierEnforcementService`, `ProjectMemberRepository` |
| Account-scoped provider preference | Live | `app_user.preferred_ai_provider` (V242), `/api/auth/me/provider-preference` |
| Agent-session usage ledger (separate from run credits) | Live | `agent_usage_ledger` (V240), `agentsession/` |
| Durable run LLM usage ledger | Live | `run_step_usage` (V243), `RunStepUsageRecorder` |
| Pending subscription creation | Live | `BillingSubscriptionWriter`, `BillingService.subscribe` |
| Managed AI credits and raw COGS ledger | Live | `managed_ai_usage_*` (V245), `ManagedAiUsageService`, `ManagedAiController` |
| Billing spend status aggregation | Live | `BillingSpendService`, `BillingService.getStatus` |
| Billing status usage and bonus runs | Live | `BillingStatusUsageService`, `RunTopupRepository`, `UsageTrackingService` |
| Billing period windows | Live | `BillingPeriodService`, `BillingService` |
| Billing tier/auth bridge | Live | `BillingTierBridgeService`, `BillingService` |
| Rate limiting | Live | `ratelimit/RateLimitFilter` |

## Access Rules

- JWT and PAT auth resolve to the same `AppUserDetails` model, but the request is tagged JWT vs PAT so server-only capabilities can distinguish them.
- `localExecution=true` run submit is PAT-only. Browser/JWT sessions are rejected because the web UI cannot enforce local command/file approvals.
- Project/run write actions must use write guards, not read guards.
- Cross-org project members can read shared projects, and the project's runs, repos, and chat, according to their project role.
- Accepted external `ProjectMember` collaborators inherit the host project org's highest active tier as an effective entitlement through `EntitlementResolver`/`TierEnforcementService`; their personal `app_user.tier` is not mutated.
- Provider preference is account-scoped (`app_user.preferred_ai_provider`); explicit per-run/chat provider input wins and is remembered.
- Org settings and model matrix changes are ORG_ADMIN or SUPER_ADMIN operations.
- Enterprise-only aggregator/org key settings must remain tier-gated.

## Billing Source-Of-Truth Rules

- Public plan listing should come from active `billing_plan` rows and tier/plan lifecycle state.
- Pending checkout subscriptions must be written through `BillingSubscriptionWriter` so terms
  version, founder flag, seat defaults, and `CREATED` state stay consistent across gateways.
- Run quota should resolve from `tier_config.monthly_run_limit` via the effective tier, not stale `billing_plan_limit` rows.
- Per-run platform cost cap comes from `TierEnforcementService.resolveRunCostBudget`.
- Managed AI credit state comes from `managed_ai_usage_period`; UIs must use `/api/billing/managed-ai/*`, not infer remaining credits from run totals.
- Billing-page `spentUsd` comes from `BillingSpendService`, which aggregates V2 pipeline-run and
  execution-run spend for the current billing window and fails soft per source with a warning.
- Billing status usage rows and bonus-run limit adjustments come from `BillingStatusUsageService`;
  `RUNS` max includes active user/org top-up balance when present.
- Billing period ends and billing-status spend-window starts come from `BillingPeriodService`; do
  not duplicate date math in checkout, webhook, or status handlers.
- Billing subscription activation/downgrade must sync `app_user.tier`, `org.tier`, and
  `org.key_source_mode` through `BillingTierBridgeService`; checkout/webhook handlers should not
  duplicate auth tier mapping inline.
- Team seats are validated in `BillingSubscriptionPolicy` for checkout and `OrgService` for org
  seat adjustment as 1-20 self-serve.
- Pro and Free managed-key cost caps must fail closed.
- BYOK routes must not silently fall back to platform keys.

## Recent Hardening Captured In Code

- Payment history is org-scoped for non-super users.
- Deployment list/live reads are org-scoped for non-super users.
- Invite/share-link revoke rejects IDs outside the requested project.
- Top-up activation uses a conditional update to avoid double-credit side effects.
- Stripe upgrade flow does not cancel/refund the old subscription before the new checkout is paid.
- TEAM no longer falls through to FREE rate limits.
- Tier fallback constants now match current Free/Pro quotas if `tier_config` lookup fails.
- Managed platform-key calls are authorized before provider dispatch and debited only after successful provider response; failed calls do not debit ES AI credits.
- Billing spend aggregation no longer swallows repository errors silently; each failed source logs a
  warning and the other source still contributes to `spentUsd`.

## Not Yet Fully Proven Live

- Real Stripe/Razorpay sandbox abandonment and webhook ordering.
- Real top-up purchase-to-run-to-invoice path.
- Authenticated one-seat Team adjustment through Stripe plus the manual Razorpay plan-swap workflow.
- Real local-hands CLI run after V244 default-on.
- Managed AI transition email delivery through production Gmail OAuth. Use the SUPER_ADMIN-only
  `POST /api/admin/test-managed-ai-email` smoke endpoint after deploy via
  `scripts/managed-ai-email-smoke.sh`; the endpoint/script exist, but the deployed Gmail delivery
  smoke is still pending.
- Cloudflare/GCP forwarded-header chain for rate limiting.

## Billing Assessment

Current business assessment lives in [`../business/billing-plan-assessment.md`](../business/billing-plan-assessment.md). Use that file for investor/strategy discussion; use this file for implementation truth.
