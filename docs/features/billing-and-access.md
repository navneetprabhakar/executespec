# Billing, Access, and Quotas

## Current Billing Model

ExecuteSpec uses tiered access plus run credits:

- FREE: limited monthly runs, fixed default model, no model matrix.
- TEAM: paid BYOK-capable team tier.
- ENTERPRISE: custom, org model overrides, aggregator keys, expanded admin surfaces.

Runs consume monthly quota at submission/approval boundaries depending on the flow, and failed runs are refunded through the quota refund listener.

## Key Capabilities

| Capability | Status | Code |
| --- | --- | --- |
| Tier plans and lifecycle | Live | `billing/lifecycle/`, `BillingService` |
| Stripe checkout/subscription | Live | `billing/gateway/StripeGateway`, `BillingService` |
| Razorpay checkout/top-ups | Live | `billing/gateway/RazorpayGateway`, `RunTopupService` |
| Payment audit | Live | `PaymentAuditController`, `PaymentAuditService` |
| Payment history | Live | `docs/payment-history.md`, `billing/` |
| Run quota enforcement | Live | `TierEnforcementService`, monthly counters |
| Failed-run refund | Live | `RunQuotaRefundListener` |
| One-seat TEAM | Live | `BillingService` |
| Org/user API keys | Live | `OrgService`, `AuthController`, `OrgController` |
| Personal access tokens | Live | `PersonalAccessTokenService` |
| External sharing flag | Live | `Org.external_sharing_enabled` |
| External-invite effective entitlement | Live | `entitlements/EntitlementResolver`, `TierEnforcementService`, `ProjectMemberRepository` |
| Account-scoped provider preference | Live | `app_user.preferred_ai_provider` (V242), `/api/auth/me/provider-preference` |
| Agent-session usage ledger (separate from run credits) | Live | `agent_usage_ledger` (V240), `agentsession/` |
| Rate limiting | Live | `ratelimit/RateLimitFilter` |

## Access Rules

- JWT and PAT auth resolve to the same `AppUserDetails` model, but the request is tagged JWT vs PAT so server-only capabilities can distinguish them.
- `localExecution=true` run submit is PAT-only and gated behind `worker.local-hands.enabled` (default OFF); browser/JWT sessions are rejected because the web UI cannot enforce local command/file approvals.
- Project/run write actions must use write guards, not read guards.
- Cross-org project members can read shared projects (and the project's runs, repos, and chat) according to their project role.
- Accepted external `ProjectMember` collaborators inherit the host project org's highest active tier as an **effective entitlement** through `EntitlementResolver`/`TierEnforcementService`; their personal `app_user.tier` is not mutated, so removing the membership automatically falls back to their own tier.
- Provider preference is account-scoped (`app_user.preferred_ai_provider`); explicit per-run/chat provider input wins and is remembered.
- Org settings and model matrix changes are ORG_ADMIN or SUPER_ADMIN operations.
- Enterprise-only aggregator/org key settings must remain tier-gated.

## Recent Hardening Captured In Code

- Payment history is org-scoped for non-super users.
- Deployment list/live reads are org-scoped for non-super users.
- Invite/share-link revoke rejects IDs outside the requested project.
- Top-up activation uses a conditional update to avoid double-credit side effects.
- Stripe upgrade flow does not cancel/refund the old subscription before the new checkout is paid.
- TEAM no longer falls through to FREE rate limits.

## Not Yet Fully Proven Live

- Real Stripe/Razorpay sandbox abandonment and webhook ordering.
- Authenticated one-seat TEAM adjustment through the full UI.
- Real top-up purchase-to-run-to-invoice path.
- Cloudflare/GCP forwarded-header chain for rate limiting.
