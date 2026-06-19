# Billing, Access, and Quotas

> Current-state billing and access reference, refreshed 2026-06-19 from migrations, services, and public pricing copy.

## Current Billing Model

ExecuteSpec uses tiered access plus run credits. Runs consume quota at the submission/approval boundary depending on the flow, and failed runs are refunded through the quota refund path.

| Tier | Listed shape | Enforcement notes |
| --- | --- | --- |
| FREE | 15 runs/month, small specs, fixed model default, $3/run cap | V200 updates `tier_config`, `billing_plan`, and RUNS plan limit to 15. FREE has no model matrix. |
| PRO | $29/month or $289/year, 30 managed-key runs/month, $5/run cap | V176/V177/V185/V207 make PRO listed and remove stale $20 duplicates. BYOK lifts the cap. |
| TEAM | $39/seat/month, from 1 seat, 1-20 self-serve seats, BYOK, unlimited runs against customer provider quota | V165 makes Team the canonical $39 seat plan; V206 aligns INR to ₹3,399; no platform per-run cap. |
| ENTERPRISE | Custom contract, DPA/SLA path, org model controls, expanded admin/security review | Custom/commercial path; do not imply self-serve SOC 2, SAML enforcement, or dedicated region. |

Credits are charged only when the plan is approved. The current public explanation is: 1 credit roughly maps to a $5 model-spend budget, and a run can cost 1-N credits based on estimate.

## Key Capabilities

| Capability | Status | Code |
| --- | --- | --- |
| Tier plans and lifecycle | Live | `billing/lifecycle/`, `BillingService` |
| Stripe checkout/subscription | Live | `billing/gateway/StripeGateway`, `BillingService` |
| Razorpay checkout/top-ups | Live, with manual mid-cycle Team seat adjustment | `billing/gateway/RazorpayGateway`, `RunTopupService`, `OrgService.adjustSeats` |
| Payment audit | Live | `PaymentAuditController`, `PaymentAuditService` |
| Payment history | Live | `docs/payment-history.md`, `billing/` |
| Run quota enforcement | Live | `TierEnforcementService`, monthly counters |
| Failed-run refund | Live | `RunQuotaRefundListener` |
| One-seat TEAM | Live | `BillingService`, `OrgService` |
| Org/user API keys | Live | `OrgService`, `AuthController`, `OrgController` |
| Personal access tokens | Live | `PersonalAccessTokenService` |
| Local-hands run submit | Enabled by V244 flag default, PAT-only | `worker.local-hands.enabled`, `RunController.submit`, `tool/remote/` |
| External sharing flag | Live | `Org.external_sharing_enabled` |
| External-invite effective entitlement | Live | `entitlements/EntitlementResolver`, `TierEnforcementService`, `ProjectMemberRepository` |
| Account-scoped provider preference | Live | `app_user.preferred_ai_provider` (V242), `/api/auth/me/provider-preference` |
| Agent-session usage ledger (separate from run credits) | Live | `agent_usage_ledger` (V240), `agentsession/` |
| Durable run LLM usage ledger | Live | `run_step_usage` (V243), `RunStepUsageRecorder` |
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
- Run quota should resolve from `tier_config.monthly_run_limit` via the effective tier, not stale `billing_plan_limit` rows.
- Per-run platform cost cap comes from `TierEnforcementService.resolveRunCostBudget`.
- Team seats are validated in `BillingService` and `OrgService` as 1-20 self-serve.
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

## Not Yet Fully Proven Live

- Real Stripe/Razorpay sandbox abandonment and webhook ordering.
- Real top-up purchase-to-run-to-invoice path.
- Authenticated one-seat Team adjustment through Stripe plus the manual Razorpay plan-swap workflow.
- Real local-hands CLI run after V244 default-on.
- Cloudflare/GCP forwarded-header chain for rate limiting.

## Billing Assessment

Current business assessment lives in [`../business/billing-plan-assessment.md`](../business/billing-plan-assessment.md). Use that file for investor/strategy discussion; use this file for implementation truth.
