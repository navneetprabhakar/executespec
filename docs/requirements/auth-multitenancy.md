# Auth & Multi-Tenancy — Requirements

## Overview

JWT-based authentication with organizations, projects, tiered access control, and role-based visibility. Supports free self-registration through enterprise multi-tenant deployments.

---

## Features

### 1. JWT Authentication

**Requirements:**
- Access token: 1440 minutes (24 hours), refresh token: 7 days
- `POST /api/auth/register` — self-registration (FREE tier)
- `POST /api/auth/login` — returns access + refresh tokens
- `POST /api/auth/refresh` — refresh access token
- `GET /api/auth/me` — current user profile
- All REST endpoints require valid JWT (except auth endpoints + health check)
- Signing key: `${JWT_SECRET}` (min 256 bits for HS256)
- Default admin: `admin@executespec.ai` (password seeded by Flyway V5, hash fixed by V7 — change on first login)

### 2. Subscription Tiers

| Tier | Monthly Runs | Concurrent Runs | Features |
|------|-------------|-----------------|----------|
| FREE | 10 | 1 | Own runs only, no org/project access |
| PRO | 100 (org-overridable) | 3 | Full org + project management |
| ENTERPRISE | Unlimited* | 10 | API key management, full access |

*Unlimited requires `apiKeysProvided=true` on org, otherwise 5000/month.

**Requirements:**
- TierEnforcementService checks quota at submission time
- Return HTTP 429 if monthly run limit exceeded
- Org-level `monthlyRunLimitOverride` allows custom limits
- TierConfig entity stores configurable limits per tier
- Concurrent run limit enforced via semaphore

### 3. Organization Management

**Requirements:**
- Only PRO/ENTERPRISE users can create organizations
- Org entity stores: name, tier, key_source_mode, enabled_providers, AI API keys
- Org-level API keys (Anthropic, OpenAI, Gemini) — shared across all org members
- CRUD: `POST /api/orgs`, `GET /api/orgs/{id}`, `PUT /api/orgs/{id}/settings`
- Member invitation via email (GmailOAuthService for email delivery)
- Member management: invite, suspend, remove
- Domain validation for org-invited users

### 4. RBAC (Role-Based Access Control)

**Organization Roles:**

| Role | Permissions |
|------|------------|
| SUPER_ADMIN | Global: all orgs, all runs, all admin functions |
| ORG_ADMIN | Manage org settings, projects, members, API keys |
| BILLING_ADMIN | View all org runs, manage billing/tier |
| DEVELOPER | Submit runs, access assigned projects only |

**Project Roles:**

| Role | Permissions |
|------|------------|
| PROJECT_ADMIN | Manage project settings and members |
| PROJECT_DEVELOPER | View and create runs in the project |

### 5. Run Visibility (RunVisibilitySpec)

**Requirements:**
- SUPER_ADMIN: sees all runs across all orgs
- ORG_ADMIN / BILLING_ADMIN: sees all runs in their org
- DEVELOPER: sees runs only in projects they are a member of
- FREE users: sees only their own runs
- Enforced on `GET /api/runs` list and `GET /api/runs/{id}` detail
- `listRuns()` accepts optional `projectId` filter

### 6. Projects

**Requirements:**
- Group pipeline runs within an org
- Only ORG_ADMIN can create/archive projects and manage members
- DEVELOPER only sees runs in projects they're invited to
- `pipeline_run.project_id` links run to project (nullable)
- DB: `project` table (id, org_id, name, description, status, created_by)
- DB: `project_member` (project_id, user_id, project_role)
- Endpoints: CRUD + member management under `/api/projects/{id}/`
- FREE users have no project access

### 7. Tenant Isolation

**Requirements:**
- TenantInterceptor automatically injects `tenant_id` on all JPA entities
- `tenant_id` column on all core tables (V2 migration)
- Default tenant: "default" for single-tenant deployments
- Multi-tenant queries filter by authenticated user's org

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
