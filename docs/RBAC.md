# ExecuteSpec RBAC Map

> Last updated: 2026-05-12. Reflects post-V165 tier ladder (MAX renamed to TEAM @ $39) and V175 cross-org `ProjectMember`.

## Roles

### Global roles (`AppUser.globalRole`)

| Role | Description |
|------|-------------|
| `SUPER_ADMIN` | Full system access. Manages feature flags, admin panels, seller config, every project / org / tier surface. |
| `USER` | Default role. Access depends on tier + org membership + project membership. |

### Organization roles (`OrgMember.orgRole`)

| Role | Description |
|------|-------------|
| `ORG_ADMIN` | Manages org settings, members, integrations, billing, AI keys. |
| `BILLING_ADMIN` | Org-scoped payment history + invoice access. |
| `DEVELOPER` | Standard org member. Runs, chat, metrics, live logs. |

### Project roles (`ProjectMember.projectRole`)

| Role | Description |
|------|-------------|
| `PROJECT_ADMIN` | Manages project settings, members, archival. |
| `PROJECT_DEVELOPER` | Standard project member (read + write). |
| `PROJECT_VIEWER` | **Read-only**. Used by V175 cross-org `ProjectMember` invitees (`is_external=true`). |

Legacy: `STARTER` maps to `FREE` in the auth system; `FINANCE` was retired (its capabilities folded into `BILLING_ADMIN` + `SUPER_ADMIN`).

---

## Subscription tiers (live, post-V165/V179/V185)

| Tier | Price | Runs (credits) | Seats | Key Mode | Lifecycle | Notes |
|------|-------|----------------|-------|----------|-----------|-------|
| `FREE` | $0 | 5 / month, 1-credit specs only | 1 | Managed | LISTED | Lifetime quota gating; no HIGH-complexity plans; personal org auto-provisioned (V162/V163). |
| `PRO` | $20 / mo | 20 / month | 1 | Managed | **HIDDEN** | Grandfathered from 2026-04-28 — existing subscribers untouched, hidden from new buyers. |
| `TEAM` | $39 / seat / mo (min 2) | Unlimited (BYOK) | 2-20 self-serve | BYOK | LISTED | Shared per-seat pool. V165 renamed (was MAX @ $29 BYOK); V149's TEAM-BYOK plan ID. |
| `ENTERPRISE` | Custom (from $1,500/mo) | Unlimited + SLA | Unlimited | BYOK / SYSTEM | LISTED | Custom contract, audit log export, SSO + SCIM (future). |

**The Solo / MAX tiers do not exist on `dev` / `main` today** — V176/V177 introduced "Solo" but V179 + V185 retired both Solo and the V143 product-split discriminator. The 4-tier ladder above is canonical.

**Tier lifecycle states** (V121): `LISTED` (visible + buyable), `HIDDEN` (invisible to new buyers, existing subscribers untouched), `SUNSETTING` (scheduled for removal — gateway side-effects gated by `billing.lifecycle.sunsetting.enabled` feature flag, default OFF).

---

## Backend endpoint authorization

### Spring Security (`SecurityConfig.java`)

| Endpoint | Rule |
|----------|------|
| `POST /api/auth/register`, `/login`, `/refresh`, `/forgot-password`, `/reset-password` | `permitAll` |
| `GET /api/auth/verify-email`, `/oauth2/**` | `permitAll` |
| `GET /api/auth/me`, `/api/auth/me/**` | `authenticated` |
| `POST /api/webhooks/razorpay`, `/api/webhooks/stripe` | `permitAll` (signature-verified) |
| `POST /webhooks/github` | `permitAll` (HMAC SHA-256 verified) |
| `GET /api/health/**` | `permitAll` |
| `GET /actuator/health/**`, `/actuator/prometheus` | `permitAll` |
| `GET /actuator/**` (other) | `hasRole('SUPER_ADMIN')` |
| `POST /api/integrations/*/webhook` | `permitAll` (signature-verified) |
| `GET /api/integrations/slack/oauth/callback` | `permitAll` |
| `GET /api/runs/*/events` (SSE) | `permitAll` (scoped JWT token in query param, `scope=sse` + `runId` claim) |
| `GET /api/reports/shared/*` | `permitAll` (public share token) |
| `GET /api/shared/{token}/**` (V175 project share links) | `permitAll` (token-bearer auth) |
| `GET /api/invites/preview` + `POST /api/invites/accept` (V175 cross-org invites) | `permitAll` (preview) / `authenticated` (accept; email-match required) |
| Static assets (`/`, `/index.html`, `/assets/**`) | `permitAll` |
| `/api/admin/**` | `hasRole('SUPER_ADMIN')` |
| All other `/api/**` | `authenticated` |

### `@PreAuthorize` / Access guards

| Controller | Endpoint | Required role |
|------------|----------|---------------|
| **SuperAdminController** (class-level) | All | `SUPER_ADMIN` |
| **ConfigAdminController** (class-level) | All | `SUPER_ADMIN` |
| **ExecutespecMigrationController** (class-level) | All | `SUPER_ADMIN` |
| **PaymentAuditController** | `GET /api/admin/payment-audit` + `/export` | `SUPER_ADMIN` |
| | `GET /api/admin/webhook-events` | `SUPER_ADMIN` |
| | `GET /api/billing/payment-history` | `ORG_ADMIN` or `BILLING_ADMIN` or `SUPER_ADMIN` |
| **OrgController** | `PUT /api/org` + member-management | `ORG_ADMIN` or `SUPER_ADMIN` |
| **ProjectController** | `POST /api/projects` | `ORG_ADMIN` or `DEVELOPER` or `SUPER_ADMIN` (also tier-gated by `TierEnforcementService.assertCanCreateProject`) |
| | `PUT /api/projects/{id}` / `archive` / member-management | `ORG_ADMIN` or `SUPER_ADMIN` (also `ProjectAccessGuard.assertCanWrite`) |
| **TierLifecycleController** | `GET /api/admin/tiers` + `PUT /{tierId}` | `SUPER_ADMIN` |
| **AdminReconciliationController** | `POST /api/admin/executions/reconcile` | `SUPER_ADMIN` |
| **SwebenchController** + **ProgramBenchController** | `POST /api/admin/swebench/run` + `/programbench/run` | `SUPER_ADMIN` |

### Tenant access guards (per-resource ownership)

| Guard | Resource | Check |
|-------|----------|-------|
| `RunAccessGuard.requireRunAccess(runId, user)` | `pipeline_run` | User must own the run OR be a member of the run's org/project. |
| `ProjectAccessGuard.assertCanRead(projectId, user)` | `project` | Org-match OR `project_member` row (V175 cross-org). |
| `ProjectAccessGuard.assertCanWrite(projectId, user)` | `project` | Same but excludes `PROJECT_VIEWER`. |

`AccessGuardCoverageTest` (ArchUnit, under `src/test/java/com/navneet/executespec/arch/`) pins every controller method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a guard. **10 sites** remain in `KNOWN_LEGACY_DRIFT` — 3 in `ProjectRepoController` (`deleteRepo`, `installRepoWebhook`, `syncRepo` — need a dedicated `RepoAccessGuard`), 2 in `SuperAdminController` + 1 in `ExecutespecMigrationController` (already SUPER_ADMIN-gated), 1 in `RunController#poll` (separate access path), 3 unrelated. New violators fail the build.

---

## Tier permissions matrix

Resolved by `TierPermissionService.resolve(user)`. Priority: SUPER_ADMIN > Org member > Solo user. `EntitlementResolver.resolveTier` picks the higher of (user.tier, org.tier) — fixes the FREE-leak class.

| Permission | SUPER_ADMIN | ORG_ADMIN | DEVELOPER (org) | Solo FREE | Solo PRO (HIDDEN) | Solo TEAM* | Solo ENT |
|------------|:-----------:|:---------:|:---------------:|:---------:|:-----------------:|:----------:|:--------:|
| `canManageAiKeys` | Y | Y | - | - | - | Y | Y |
| `canViewPricing` | Y | Y | - | Y | Y | Y | Y |
| `canManageIntegrations` | Y | Y | - | - | - | Y | Y |
| `canViewMetrics` | Y | Y | Y | Y | Y | Y | Y |
| `canViewCostBreakdown` | Y | - | - | - | - | Y | Y |
| `canViewTokenUsage` | Y | Y | Y | Y | Y | Y | Y |
| `canUseChat` | Y | Y | Y | Y | Y | Y | Y |
| `canCreateOrg` | Y | Y | - | - | - | - | - |
| `canInviteMembers` | Y | Y | - | - | - | Y | Y |
| `canViewOrgMembers` | Y | Y | Y | - | - | Y | Y |
| `canViewAudit` | Y | Y | - | - | - | Y | Y |
| `canViewLiveLog` | Y | Y | Y | Y | Y | Y | Y |
| `canViewInvoices` | Y | Y | - | Y | Y | Y | Y |
| `canManageBillingProfile` | Y | Y | - | Y | Y | Y | Y |
| `canDeploy` | Y | tier-based | tier-based | - | Y | Y | Y |

*Solo TEAM means a single-seat TEAM subscription (technically minimum is 2). **Y** = granted, **-** = denied, **tier-based** = from `tier_config.max_deployments`.

R8 (personal-org permission gap) was fixed in 1.0.0-M1 — TEAM/ENTERPRISE users on personal orgs get `canViewAudit / canInviteMembers / canViewOrgMembers / canManageIntegrations / canViewPricing` correctly.

---

## Tier enforcement (`TierEnforcementService`)

| Check | FREE | PRO (HIDDEN) | TEAM | ENTERPRISE |
|-------|------|--------------|------|------------|
| Run submission | 5 / month, 1-credit specs only | 20 / month (billing cycle) | Unlimited (BYOK) | Unlimited (custom) |
| Complexity gate (HIGH plans) | Blocked | Allowed | Allowed | Allowed |
| Credit charge at approval | Yes (max 1 credit) | Yes (N credits) | Skip (BYOK) | Skip (BYOK) |
| Cost-budget ceiling | min(credits × $5 × 1.5, userCostCap) | min(credits × $5 × 1.5, userCostCap) | userCostCap only (BYOK exempt) | userCostCap only (BYOK exempt) |
| API key requirement | System keys | System keys | Own keys required | Org policy (BYOK or SYSTEM) |
| Monthly counter reset | Asia/Kolkata 00:00 IST on 1st (`MonthlyRunCounterResetJob`) | Billing cycle | — | — |
| Max projects / org | 1 | 2 | 5 | -1 (unlimited) |
| Max repos / project | 1 | 2 | 5 | -1 (unlimited) |
| Share-link active cap (V175) | 3 | 25 | -1 (unlimited) | -1 |
| PR Review monthly quota (V172) | 0 (blocked) | 10 | -1 | -1 |
| Code Review monthly quota (V172) | 0 (blocked) | 4 | -1 | -1 |

**Seat enforcement (TEAM only):** `InvitationService` returns 402 Payment Required when active members + pending invites would exceed `org.seat_count`. `OrgController.adjustSeats` is the chokepoint for raising / lowering.

---

## Frontend access controls

### Navigation

| Path | Visible When |
|------|--------------|
| `/runs` (V2 sidebar) / `/home` (V3) | Always |
| `/projects/:id/dashboard` (V2 sidebar) / `/projects/:slug` (V3) | `ProjectAccessGuard` |
| `/exec` (Exec summary) | `canViewCostBreakdown` (TEAM / ENTERPRISE → FREE / PRO see upgrade wall) |
| `/pricing` | `canViewPricing` |
| `/integrations` | `canManageIntegrations` |
| `/invoices` | `canViewInvoices` |
| `/audit` | `canViewAudit` |
| `/deployments` | `superAdminOnly` |
| `/admin/*` (analytics, pricing, tiers, payment-audit, webhooks) | `superAdminOnly` |
| `/settings` | Always |

### Header + sidebar tier pills

| Element | Visible When |
|---------|--------------|
| Upgrade pill (Header) | `FREE` or `PRO` |
| Top-up pill (Header) | Paid tier, NOT `TEAM` or `ENTERPRISE` (BYOK skips credit purchases) |
| Usage badge (Sidebar) | `max !== -1` (hidden for unlimited tiers) |
| Theme toggle (Sun/Moon) | Always |

### Settings page

| Section | Gated by |
|---------|----------|
| AI Provider Keys (Anthropic / OpenAI / Gemini) | `canManageAiKeys` (TEAM, ENTERPRISE, ORG_ADMIN, SUPER_ADMIN). `POST /api/auth/settings/ai-keys/validate` Test button per provider. |
| System health metrics | `canViewMetrics` |
| GitHub PAT | Always (per-user) |

### Run submission (`NewRunModal`)

| Guard | Condition |
|-------|-----------|
| BYOK warning banner | `tier === 'TEAM' || tier === 'ENTERPRISE'` and no API keys configured |
| Launch button disabled | BYOK without keys; or run count exhausted |
| Provider pills greyed out | Provider key not configured (BYOK) |
| 402 billing limit | Run count exhausted (FREE / PRO) |
| `userCostCap` input | Optional override, $0.10–$1000 (V178) |

### Billing page

| Section | Visible when |
|---------|--------------|
| Current plan | Always (authenticated) |
| Cancel subscription | Paid tier, NOT `ENTERPRISE` |
| Usage bars | Always (shows "Unlimited" for `max === -1`) |
| Bonus runs | Has active top-ups |
| Buy Extra Runs | NOT `TEAM` or `ENTERPRISE` (BYOK has no credit purchases) |

---

## Provider naming convention

| Internal ID | Display label | Notes |
|-------------|---------------|-------|
| `anthropic` / `ANTHROPIC` | **Anthropic** | Claude models. Default provider since 1.0.0-M1. |
| `gemini` / `GEMINI` | **Gemini** | Gemini models. Renamed from `google`/`GOOGLE` in v2.6.0 (V120). `GOOGLE` is accepted as a back-compat alias on `RunRequest.aiProvider` / `CreateProjectRequest.aiProvider` input but is rewritten to `GEMINI` before persistence. |
| `openai` / `OPENAI` | **OpenAI** | GPT models. |

YAML keys (`model-matrix.gemini`, `failover-order.gemini`, `model-pricing[].provider: gemini`, `provider-pool.GEMINI`) must match the canonical `GEMINI` spelling. Spring AI library keys (`spring.ai.google.genai.*`) and OAuth registration (`spring.security.oauth2.client.registration.google`) are deliberately untouched — those refer to Google the company, not the LLM provider rename. See `FailoverOrderEnumAlignmentTest` for the regression guard.

---

## Key mode mapping (billing → auth)

| Plan key mode | Org `keySourceMode` | Behaviour |
|---------------|---------------------|-----------|
| `BYOK` | `OWN_KEYS` | User/org keys only, no system fallback. **Platform cost cap skipped** (customer pays their own API bill); `userCostCap` still honoured. |
| `MANAGED` | `SYSTEM` | Platform keys only, user keys ignored. Platform cap applies. |
| `PLATFORM` (legacy) | `SYSTEM` | Same as MANAGED. |
| (default) | `HYBRID` | User/org keys first, system fallback. Platform cap applies. |
