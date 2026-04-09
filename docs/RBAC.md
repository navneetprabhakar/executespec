# ExecuteSpec RBAC Map

> Last updated: 2026-04-09 (v1.5.1)

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

## Subscription Tiers

| Tier | Runs | Concurrent | Key Mode | Deploy | Org |
|------|------|------------|----------|--------|-----|
| `FREE` | 3 lifetime | 1 | Managed | Yes (3 max) | No |
| `PRO` | 10/month | 2 | Managed | Yes | Yes |
| `MAX` | Unlimited | 3 | BYOK (required) | Yes | Yes |
| `ENTERPRISE` | Custom | Custom | BYOK/Managed | Yes | Yes |

Legacy: `STARTER` maps to `FREE` in auth system.

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

| Check | FREE | PRO | MAX | ENTERPRISE |
|-------|------|-----|-----|------------|
| Run submission | 3 lifetime | 10/month (billing cycle) | Unlimited | 5000/month or custom |
| Complexity gate (HIGH plans) | Blocked | Allowed | Allowed | Allowed (with BYOK) |
| Credit charge at approval | Yes (max 1 credit) | Yes (N credits) | Skip (BYOK) | Skip (BYOK) |
| Cost budget ceiling | credits x $5 x 1.5 | credits x $5 x 1.5 | Unlimited | Unlimited |
| API key requirement | System keys | System keys | Own keys required | Org policy |

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
| `google` / `GOOGLE` | **Gemini** | Gemini models (backend ID stays `google`) |
| `openai` / `OPENAI` | **OpenAI** | GPT models |

Backend `ModelController.PROVIDER_LABELS` maps `"google"` to `"Gemini"`. Frontend displays `ProviderEntry.label` from API.

---

## Key Mode Mapping (Billing → Auth)

| Plan Key Mode | Org keySourceMode | Behavior |
|---------------|-------------------|----------|
| `BYOK` | `OWN_KEYS` | User/org keys only, no system fallback |
| `MANAGED` | `SYSTEM` | Platform keys only, user keys ignored |
| `PLATFORM` (legacy) | `SYSTEM` | Same as MANAGED |
| (default) | `HYBRID` | User/org keys first, system fallback |
