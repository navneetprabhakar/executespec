# ExecuteSpec RBAC Map

> Last updated: 2026-04-25 (v2.6.1 — TEAM tier + 3-state lifecycle from Wave 1; LlmProvider rename to GEMINI from v2.6.0).

## Roles

### Global Roles (AppUser.globalRole)
| Role | Description |
|------|-------------|
| `SUPER_ADMIN` | Full system access. Manages feature flags, admin panels, seller config. |
| `FINANCE` | Read-only access to invoices, accounting, credit notes, GSTR, audit logs. |
| `USER` | Default role. Access depends on tier + org membership. |

### Organization Roles (OrgMember.orgRole)
| Role | Description |
|------|-------------|
| `ORG_ADMIN` | Manages org settings, members, integrations, billing, AI keys. |
| `BILLING_ADMIN` | Org-scoped payment history access. |
| `DEVELOPER` | Standard org member. Runs, chat, metrics, live logs. |

### Project Roles (ProjectMember.projectRole)
| Role | Description |
|------|-------------|
| `PROJECT_ADMIN` | Manages project settings, members, archival. |
| `PROJECT_DEVELOPER` | Standard project member. |

---

## Subscription Tiers (v2.6.1)

| Tier | Price | Runs (credits) | Seats | Key Mode | Lifecycle | Notes |
|------|-------|----------------|-------|----------|-----------|-------|
| `FREE` | $0 | 5/month, 1-credit specs only | 1 | Managed | LISTED | Lifetime quota gating; no HIGH-complexity plans |
| `PRO` | $20/mo | 20/month | 1 | Managed | **HIDDEN** | Grandfathered from 2026-04-28 — existing subscribers untouched, hidden from new buyers (V122) |
| `TEAM` | $49/seat/mo | 50/seat shared pool | 2-20 self-serve | Managed | LISTED | Wave 1 (v2.6.1) — seat-aware Stripe checkout, 180d retention |
| `MAX` | $29/mo | Unlimited fair-use | 1 | BYOK (required) | LISTED | Own keys; 90d retention |
| `ENTERPRISE` | Custom (from $1,500/mo) | Unlimited + SLA | Unlimited | BYOK/Managed | LISTED | Custom contract, audit log export, SSO + SCIM (future) |

Legacy: `STARTER` maps to `FREE` in auth system.

**Tier lifecycle states** (Wave 1, V121): `LISTED` (visible + buyable), `HIDDEN` (invisible to new buyers, existing subscribers untouched), `SUNSETTING` (scheduled for removal — gateway side-effects gated by `billing.lifecycle.sunsetting.enabled` feature flag, default OFF).

---

## Backend Endpoint Authorization

### Spring Security (SecurityConfig.java)

| Endpoint | Rule |
|----------|------|
| `POST /api/auth/register`, `/login`, `/refresh`, `/forgot-password`, `/reset-password` | `permitAll` |
| `GET /api/auth/verify-email`, `/oauth2/**` | `permitAll` |
| `GET /api/auth/me`, `/api/auth/me/**` | `authenticated` |
| `POST /api/webhooks/razorpay`, `/api/webhooks/stripe` | `permitAll` (signature-verified) |
| `GET /api/health/**` | `permitAll` |
| `GET /actuator/health/**`, `/actuator/prometheus` | `permitAll` |
| `GET /actuator/**` (other) | `hasRole('SUPER_ADMIN')` |
| `POST /api/integrations/*/webhook` | `permitAll` (signature-verified) |
| `GET /api/integrations/slack/oauth/callback` | `permitAll` |
| `GET /api/runs/*/events` (SSE) | `permitAll` (scoped token in query param) |
| `GET /api/reports/shared/*` | `permitAll` (public share token) |
| Static assets (`/`, `/index.html`, `/assets/**`) | `permitAll` |
| `/api/admin/**` | `hasRole('SUPER_ADMIN')` |
| All other `/api/**` | `authenticated` |

### @PreAuthorize Annotations

| Controller | Endpoint | Required Role |
|------------|----------|---------------|
| **SuperAdminController** (class-level) | All | `SUPER_ADMIN` |
| **ConfigAdminController** (class-level) | All | `SUPER_ADMIN` |
| **PaymentAuditController** | `GET /api/admin/payment-audit` | `SUPER_ADMIN` |
| | `GET /api/admin/payment-audit/export` | `SUPER_ADMIN` |
| | `GET /api/admin/webhook-events` | `SUPER_ADMIN` |
| | `GET /api/billing/payment-history` | `ORG_ADMIN` or `BILLING_ADMIN` or `SUPER_ADMIN` |
| **OrgController** | `PUT /api/org` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `DELETE /api/org/members/{id}` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `POST /api/org/members/{id}/suspend` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `PUT /api/org/members/{id}/role` | `ORG_ADMIN` or `SUPER_ADMIN` |
| **ProjectController** | `POST /api/projects` | `ORG_ADMIN` or `DEVELOPER` or `SUPER_ADMIN` |
| | `PUT /api/projects/{id}` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `POST /api/projects/{id}/archive` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `POST /api/projects/{id}/members` | `ORG_ADMIN` or `SUPER_ADMIN` |
| | `DELETE /api/projects/{id}/members/{uid}` | `ORG_ADMIN` or `SUPER_ADMIN` |
| **TierLifecycleController** (v2.6.1 Wave 1) | `GET /api/admin/tiers` | `SUPER_ADMIN` |
| | `PUT /api/admin/tiers/{tierId}/lifecycle` | `SUPER_ADMIN` |
| | `PUT /api/admin/billing-plans/{id}/lifecycle` | `SUPER_ADMIN` |
| **OnboardingController** (v2.6.1 Wave 2) | `GET /api/onboarding/sample-specs`, `POST /pick-sample`, `GET /sandbox-status` | `authenticated` |
| **AdminReconciliationController** | `POST /api/admin/executions/reconcile` | `SUPER_ADMIN` (operational cleanup for stuck `ExecutionRun` rows) |

---

## TierPermissions Matrix

Resolved by `TierPermissionService.resolve(user)`. Priority: SUPER_ADMIN > FINANCE > Org member > Solo user.

| Permission | SUPER_ADMIN | FINANCE | ORG_ADMIN | DEVELOPER (org) | Solo FREE | Solo PRO | Solo MAX/ENT |
|------------|:-----------:|:-------:|:---------:|:---------------:|:---------:|:--------:|:------------:|
| `canManageAiKeys` | Y | - | Y | - | - | - | Y |
| `canViewPricing` | Y | - | Y | - | - | - | - |
| `canManageIntegrations` | Y | - | Y | - | - | - | - |
| `canViewMetrics` | Y | - | Y | Y | Y | Y | Y |
| `canViewCostBreakdown` | Y | - | - | - | - | - | - |
| `canViewTokenUsage` | Y | - | Y | Y | Y | Y | Y |
| `canUseChat` | Y | - | Y | Y | Y | Y | Y |
| `canCreateOrg` | Y | - | Y | - | - | - | - |
| `canInviteMembers` | Y | - | Y | - | - | - | - |
| `canViewOrgMembers` | Y | - | Y | Y | - | - | - |
| `canViewAudit` | Y | Y | Y | - | - | - | - |
| `canViewLiveLog` | Y | - | Y | Y | Y | Y | Y |
| `canViewInvoices` | Y | Y | Y | - | Y | Y | Y |
| `canViewAccounting` | Y | Y | - | - | - | - | - |
| `canManageCreditNotes` | Y | Y | - | - | - | - | - |
| `canExportGstr` | Y | Y | - | - | - | - | - |
| `canManageSellerConfig` | Y | - | - | - | - | - | - |
| `canManageBillingProfile` | Y | Y | Y | - | Y | Y | Y |
| `canDeploy` | Y | - | tier-based | tier-based | Y | Y | Y |

**Y** = granted, **-** = denied, **tier-based** = from `tier_config` table.

---

## Tier Enforcement (TierEnforcementService)

| Check | FREE | PRO (HIDDEN) | TEAM | MAX | ENTERPRISE |
|-------|------|--------------|------|-----|------------|
| Run submission | 5/month, 1-credit specs only | 20/month (billing cycle) | 50 credits/seat shared pool | Unlimited (fair-use) | Unlimited (custom) |
| Complexity gate (HIGH plans) | Blocked | Allowed | Allowed | Allowed (with BYOK) | Allowed |
| Credit charge at approval | Yes (max 1 credit) | Yes (N credits) | Yes (N credits, seat-pooled) | Skip (BYOK) | Skip (BYOK) |
| Cost budget ceiling | credits x $5 x 1.5 | credits x $5 x 1.5 | credits x $5 x 1.5 | Unlimited | Unlimited |
| API key requirement | System keys | System keys | System keys | Own keys required | Org policy |
| Monthly counter reset zone | Asia/Kolkata 00:00 IST on 1st (`MonthlyRunCounterResetJob`) | Billing cycle | Billing cycle | — | — |

**Seat enforcement (TEAM only):** `InvitationService` returns 402 Payment Required when active members + pending invites would exceed `org.seat_count`.

---

## Frontend Access Controls

### Navigation (Sidebar)
| Path | Label | Visible When |
|------|-------|-------------|
| `/` | Runs | Always |
| `/dashboard` | Dashboard | `adminOnly` (ORG_ADMIN or SUPER_ADMIN) |
| `/pricing` | Pricing | `canViewPricing` |
| `/integrations` | Integrations | `canManageIntegrations` |
| `/invoices` | Invoices | `canViewInvoices` |
| `/accounting` | Accounting | `canViewAccounting` |
| `/audit` | Audit | `canViewAudit` |
| `/deployments` | Deploy | `superAdminOnly` |
| `/settings` | Settings | Always |

### Header & Sidebar Tier Pills
| Element | Visible When |
|---------|-------------|
| Upgrade pill (Header) | `FREE` or `PRO` |
| Top-up pill (Header) | Paid tier, NOT `MAX` or `ENTERPRISE` |
| Upgrade button (Sidebar) | NOT `MAX` or `ENTERPRISE` |
| Top-up button (Sidebar) | Paid tier, NOT `MAX` or `ENTERPRISE` |
| Usage badge (Sidebar) | `max !== -1` (hidden for unlimited) |

### Settings Page
| Section | Gated By |
|---------|----------|
| AI Provider Keys | `canManageAiKeys` (MAX, ENTERPRISE, ORG_ADMIN, SUPER_ADMIN) |
| System health metrics | `canViewMetrics` |

### Run Submission (NewRunModal)
| Guard | Condition |
|-------|-----------|
| BYOK warning banner | `tier === 'MAX'` and no API keys configured |
| Launch button disabled | MAX without keys |
| Provider pills greyed out | Provider key not configured (MAX/BYOK) |
| 402 billing limit | Run count exhausted (FREE/PRO) |

### Billing Page
| Section | Visible When |
|---------|-------------|
| Current plan | Always (authenticated) |
| Cancel subscription | Paid tier, NOT `ENTERPRISE` |
| Usage bars | Always (shows "Unlimited" for `max === -1`) |
| Bonus runs | Has active top-ups |
| Buy Extra Runs | NOT `MAX` or `ENTERPRISE` |

---

## Provider Naming Convention

| Internal ID | Display Label | Notes |
|-------------|--------------|-------|
| `anthropic` / `ANTHROPIC` | **Anthropic** | Claude models |
| `gemini` / `GEMINI` | **Gemini** | Gemini models. Renamed from `google`/`GOOGLE` in v2.6.0 (V120 migration). `GOOGLE` is still accepted as a back-compat alias on `RunRequest.aiProvider` / `CreateProjectRequest.aiProvider` input but is rewritten to `GEMINI` before persistence. |
| `openai` / `OPENAI` | **OpenAI** | GPT models |

YAML keys (`model-matrix.gemini`, `failover-order.gemini`, `model-pricing[].provider: gemini`, `provider-pool.GEMINI`) must match the canonical `GEMINI` spelling. Spring AI library keys (`spring.ai.google.genai.*`) and OAuth registration (`spring.security.oauth2.client.registration.google`) are deliberately untouched — those refer to Google the company, not the LLM provider rename. See `FailoverOrderEnumAlignmentTest` for the regression guard.

---

## Key Mode Mapping (Billing → Auth)

| Plan Key Mode | Org keySourceMode | Behavior |
|---------------|-------------------|----------|
| `BYOK` | `OWN_KEYS` | User/org keys only, no system fallback |
| `MANAGED` | `SYSTEM` | Platform keys only, user keys ignored |
| `PLATFORM` (legacy) | `SYSTEM` | Same as MANAGED |
| (default) | `HYBRID` | User/org keys first, system fallback |
