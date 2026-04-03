# Auth & Multi-Tenancy — Requirements

> Last updated: 2026-04-03

## Overview

JWT-based authentication with OAuth2 social login, organizations, projects, tiered access control, and role-based visibility. Supports free self-registration through enterprise multi-tenant deployments.

---

## Features

### 1. JWT Authentication

**Requirements:**
- Algorithm: HS256, signing key: `${JWT_SECRET}` (min 256 bits)
- Access token: 24 hours, refresh token: 7 days
- Refresh token delivered as HTTP-only cookie, rotated on each refresh
- `POST /api/auth/register` — self-registration (FREE tier)
- `POST /api/auth/login` — returns access token + sets refresh cookie
- `POST /api/auth/refresh` — rotate refresh token, issue new access token
- `GET /api/auth/me` — current user profile
- All REST endpoints require valid JWT (except auth endpoints, health check, OAuth callbacks, shared report URLs)
- Default admin: `admin@executespec.ai` (password seeded by Flyway V5, hash fixed by V7 — change on first login)

**Force Logout:**
- Feature flag `force_logout_epoch` (stored as Unix timestamp)
- Any JWT issued before this epoch is rejected at the filter level
- Allows invalidating all active sessions globally (e.g., after a security incident)
- Set via admin API or environment variable

### 2. OAuth2 Social Login

**Requirements:**
- Supported providers: **Google** and **GitHub**
- OAuth2 flow handled via Spring Security OAuth2 client with custom `OAuthSuccessHandler`
- `OAuthUserMapper` maps OAuth2 profile attributes to `AppUser` entity
  - If email does not exist: creates new user (FREE tier, no password)
  - If email exists with same provider: logs in existing user
  - If email exists with different provider: throws `OAuthEmailConflictException` (HTTP 409) with message indicating the original provider
- OAuth2 endpoints:
  - `GET /api/auth/oauth2/authorize/{provider}` — initiate OAuth flow (redirects to provider)
  - `GET /api/auth/oauth2/callback/{provider}` — handle provider callback
- On successful OAuth: issues JWT access token + refresh cookie (same format as password login)
- OAuth users can later set a password to enable password login alongside OAuth

### 3. Subscription Tiers

| Tier | Monthly Runs | Concurrent Runs | Features |
|------|-------------|-----------------|----------|
| FREE | 5 | 1 | Own runs only, no org/project access |
| PRO | 100 (org-overridable) | 3 | Full org + project management |
| ENTERPRISE | Unlimited* | 10 | API key management, full access |

*Unlimited requires `apiKeysProvided=true` on org, otherwise 5000/month.

**Requirements:**
- TierEnforcementService checks quota at submission time
- Return HTTP 429 if monthly run limit exceeded
- Org-level `monthlyRunLimitOverride` allows custom limits
- TierConfig entity stores configurable limits per tier
- Concurrent run limit enforced via semaphore

### 4. Organization Management

**Requirements:**
- Only PRO/ENTERPRISE users can create organizations
- Org creation requires: name, email domains, admin email (via UI form on /org/settings)
- Org entity stores: name, tier, key_source_mode, enabled_providers, AI API keys, admin_email, JIRA/Confluence credentials
- Org-level API keys (Anthropic, OpenAI, Gemini) — shared across all org members
- Org-level integration credentials (JIRA base URL/email/token, Confluence base URL/email/token) — managed by ORG_ADMIN on Integrations page
- Org-level Slack integration — OAuth connection stored in `org_slack_integration` table (team_id, access_token, verbosity, default_channel); per-project channel routing via `project_slack_channel` table
- Integration status derived from org credentials (not application.yml flags); Slack status derived from `org_slack_integration` record
- CRUD: `POST /api/orgs`, `GET /api/orgs/{id}`, `PUT /api/orgs/{id}/settings`
- Integration settings: `PUT /api/org/me/settings/jira`, `PUT /api/org/me/settings/confluence`
- Connection validation: `POST /api/org/me/settings/jira/validate`, `POST /api/org/me/settings/confluence/validate`
- Slack OAuth: `GET /api/integrations/slack/install`, `GET /api/integrations/slack/oauth/callback`, `DELETE /api/integrations/slack/disconnect`
- Member invitation via email (GmailOAuthService for email delivery)
- Member management: invite, suspend, remove
- Domain validation for org-invited users

### 5. RBAC (Role-Based Access Control)

**Organization Roles:**

| Role | Permissions |
|------|------------|
| SUPER_ADMIN | Global: all orgs, all runs, all admin functions |
| ORG_ADMIN | Manage org settings, projects, members, API keys, integration credentials, Slack connection |
| BILLING_ADMIN | View all org runs, manage billing/tier |
| DEVELOPER | Submit runs, access assigned projects only |

**Project Roles:**

| Role | Permissions |
|------|------------|
| PROJECT_ADMIN | Manage project settings, members, and project-level configuration |
| PROJECT_DEVELOPER | View and create runs within the project |

**Role Assignment:**
- Organization roles assigned by ORG_ADMIN or SUPER_ADMIN via org member management
- Project roles assigned by ORG_ADMIN or PROJECT_ADMIN via project member management
- A user can hold one org role and one project role per project simultaneously

### 6. Run Visibility (RunVisibilitySpec)

**Requirements:**
- SUPER_ADMIN: sees all runs across all orgs
- ORG_ADMIN / BILLING_ADMIN: sees all runs in their org
- DEVELOPER: sees runs only in projects they are a member of
- PROJECT_ADMIN: sees all runs in their administered projects
- PROJECT_DEVELOPER: sees runs in their assigned projects
- FREE users: sees only their own runs
- Enforced on `GET /api/runs` list and `GET /api/runs/{id}` detail
- `listRuns()` accepts optional `projectId` filter

### 7. Projects

**Requirements:**
- Group pipeline runs within an org
- Only ORG_ADMIN or PROJECT_ADMIN can create/archive projects and manage members
- DEVELOPER / PROJECT_DEVELOPER only sees runs in projects they're invited to
- `pipeline_run.project_id` links run to project (nullable)
- DB: `project` table (id, org_id, name, description, status, created_by)
- DB: `project_member` (project_id, user_id, project_role)
- Endpoints: CRUD + member management under `/api/projects/{id}/`
- FREE users have no project access

### 8. Tenant Isolation

**Requirements:**
- TenantInterceptor automatically injects `tenant_id` on all JPA entities
- `tenant_id` column on all core tables (V2 migration)
- Default tenant: "default" for single-tenant deployments
- Multi-tenant queries filter by authenticated user's org

---

## Related Security Documentation

Auth & multi-tenancy is one layer of the broader security model. For the complete picture:

- **[Security Guardrails](security-guardrails.md)** — All 8 defense layers, threat model, input scanning, shell/file sandbox, container hardening
- **[API Key Management](api-key-management.md)** — Per-org provider restrictions, key source modes, encryption at rest

### Security Features in This Domain

- **HTTP security headers:** CSP, HSTS (1 year), X-Frame-Options DENY, CORS allowlist (configured in `SecurityConfig.java`)
- **Rate limiting:** Redis-backed sliding window, tier-based quotas (FREE: 5 runs/min, PRO: 20, ENTERPRISE: 60). See `ratelimit/RateLimitFilter.java`.
- **Webhook signature verification:** Razorpay webhooks verified with HMAC-SHA256 + constant-time comparison. See `billing/security/WebhookSignatureVerifier.java`.

---

## Key Files

| File | Purpose |
|------|---------|
| `auth/entity/AppUser.java` | User entity (email, password, tier, API keys, GitHub settings) |
| `auth/entity/Org.java` | Organization entity (tier, keys, provider settings) |
| `auth/entity/Project.java` | Project entity within org |
| `auth/entity/OrgMember.java` | Org membership with role |
| `auth/entity/ProjectMember.java` | Project membership with role |
| `auth/entity/TierConfig.java` | Configurable tier limits |
| `auth/security/RunVisibilitySpec.java` | RBAC run filtering |
| `auth/security/OAuthSuccessHandler.java` | OAuth2 callback handler, JWT issuance |
| `auth/security/OAuthUserMapper.java` | Maps OAuth2 profile to AppUser |
| `auth/service/AuthService.java` | Registration, login, JWT management |
| `auth/service/TierEnforcementService.java` | Quota checks |
| `auth/service/OrgService.java` | Org CRUD + member management |
| `auth/service/ProjectService.java` | Project CRUD + member management |
| `auth/controller/AuthController.java` | Auth REST endpoints |
| `tenant/TenantInterceptor.java` | Automatic tenant_id injection |

## Database Migrations

| Version | Description |
|---------|-------------|
| V4 | Auth schema (app_user, org, project, org_member, project_member) |
| V5 | Seed admin user |
| V6 | Tier config + org run-limit overrides |
| V7 | Fix admin password hash |
| V8 | Per-user GitHub settings |
| V18 | Org-level integration settings (admin_email, JIRA/Confluence credentials) |
| V19 | Agent token breakdown (per-task input/output token tracking) |
| V20 | Slack integration (org_slack_integration, project_slack_channel, slack_notification_log) |
