# User Onboarding, Org Management, Tiers & RBAC — Requirements Document

**Project:** executespec
**Date:** 2026-03-03
**Status:** Draft v2.0 (major revision — multi-org, project-scoped runs)
**Author:** Navneet Prabhakar

---

## Table of Contents
1. [Architecture Overview](#1-architecture-overview)
2. [Entity Hierarchy](#2-entity-hierarchy)
3. [User Onboarding](#3-user-onboarding)
4. [Org Onboarding](#4-org-onboarding)
5. [User Tiers & Quotas](#5-user-tiers--quotas)
6. [RBAC — Roles & Permissions](#6-rbac--roles--permissions)
7. [Projects (Run Scoping within Orgs)](#7-projects-run-scoping-within-orgs)
8. [Run Tagging & Visibility](#8-run-tagging--visibility)
9. [Default Super Admin](#9-default-super-admin)
10. [Email Infrastructure](#10-email-infrastructure)
11. [Payment Audit (Stub)](#11-payment-audit-stub)
12. [Database Schema](#12-database-schema)
13. [API Endpoints](#13-api-endpoints)
14. [Frontend Pages & Components](#14-frontend-pages--components)
15. [Backend Implementation Notes](#15-backend-implementation-notes)
16. [Open Questions / TBDs](#16-open-questions--tbds)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────┐
│  SUPER ADMIN  (admin@executespec.ai)            │
│  • Manages all orgs, approves cross-domain      │
│    invitations, handles escalations             │
└─────────────────────────────────────────────────┘
          │ creates / manages
          ▼
┌──────────────────────────────────────────────┐
│  ORGANIZATION   (e.g. Acme Corp)            │
│  • Domain pattern: @acme.com                │
│  • Plan: PRO or ENTERPRISE (org-level)      │
│  • Shared run quota across all members      │
│  ┌──────────────┐   ┌──────────────────┐   │
│  │  Project A   │   │    Project B     │   │
│  │  (members)   │   │    (members)     │   │
│  └──────────────┘   └──────────────────┘   │
└──────────────────────────────────────────────┘
          │ invited by Org Admin
          ▼
┌───────────────────────────────────────────┐
│  ORG USERS                               │
│  • Role: ORG_ADMIN / DEVELOPER /         │
│          BILLING_ADMIN                   │
│  • See only runs in their projects       │
│  • Consume from org's shared quota       │
└───────────────────────────────────────────┘

┌───────────────────────────────────────────┐
│  FREE USERS  (standalone, no org)        │
│  • Self-registered after email verify   │
│  • Own personal quota (10 runs/month)   │
│  • See only their own runs              │
│  • Can later be invited into an org     │
└───────────────────────────────────────────┘
```

**Key principles:**
- Billing unit = **Organization** (shared pool). Free users have an individual quota only.
- Run visibility = **Project-scoped** within orgs. Free users see only their own runs.
- Org creation = available to any **PRO or ENTERPRISE user** (they become Org Admin).
- Cross-domain invitations are **escalated to Super Admin** for manual approval.

---

## 2. Entity Hierarchy

```
super_admin (1)
  └─ org (0..*)
       ├─ org_member (user × role, 1..*)
       └─ project (1..*)
            ├─ project_member (user × role, 0..*)
            └─ pipeline_run (0..*)
                  └─ agent_task, agent_message, ...

free_user (standalone, no org)
  └─ pipeline_run (personal, no org/project)
```

---

## 3. User Onboarding

### 3.1 Free Tier Self-Registration

Anyone can create a FREE account directly.

```
1. Visit /register
2. Fill in: first name, last name, email, password (+ confirm)
3. POST /api/auth/register
   → Create app_user (status = PENDING_VERIFICATION, tier = FREE, org_id = null)
   → Send verification email from admin@executespec.ai
4. UI: "Check your inbox — verification link sent to <email>"
5. Click link → GET /api/auth/verify-email?token=<uuid>
   → Token valid (24h TTL) → user status = ACTIVE
   → Auto-login → redirect to /
```

**Password Rules:**
- Min 8 characters, at least 1 uppercase, 1 digit, no whitespace
- Stored as bcrypt (strength 12)

**Business Rules:**
- Email must be globally unique
- Cannot log in until email is verified
- PENDING_VERIFICATION accounts > 7 days eligible for cleanup

### 3.2 Org-Invited User Onboarding

When an Org Admin invites someone:

```
1. Org Admin sends invite: POST /api/org/invite  { email, projectIds[], role }
2. Server checks email domain against org's domain_patterns
   a. Domain matches → OrgInvitation (status = PENDING) → send invite email
   b. Domain doesn't match → OrgInvitation (status = ESCALATED)
      → notify Super Admin at admin@executespec.ai
      → Super Admin reviews & approves/rejects via Admin UI
      → On approval → send invite email to the user
3. Invited user clicks link → /accept-invite?token=<uuid>
   a. If existing account found (same email) → link account to org → done
   b. If no account → show abbreviated register form (email pre-filled, name + password only)
      → Create account (status = ACTIVE, email_verified = true — skipped, link is proof)
      → Associate with org + assigned projects
4. Redirect to / — user is now an org member
```

**Invitation Expiry:** 7 days. Resend available from Admin UI.

### 3.3 Login Flow

```
POST /api/auth/login { email, password }
→ Validate credentials + status = ACTIVE
→ Check must_change_password
→ Issue JWT access token (15 min) + refresh token cookie (7 days)
→ JWT payload: { sub: userId, email, globalRole, tier, orgId, orgRole, iat, exp }
```

### 3.4 Password Reset

```
/forgot-password → enter email → POST /api/auth/forgot-password
→ Generate reset token → send email (1h TTL)
/reset-password?token=<uuid> → enter new password → POST /api/auth/reset-password
→ Token invalidated → redirect to /login
```

### 3.5 Forced Password Change (Admin Seeded Accounts)

When `must_change_password = true` on login:
- Fullscreen modal blocks all routes until password is changed
- Current password + new password + confirm required
- On success: `must_change_password = false`, modal dismissed

---

## 4. Org Onboarding

### 4.1 Org Creation

Only a **PRO or ENTERPRISE user** can create an org. A FREE user must upgrade their own account first.

```
1. PRO/Enterprise user visits /settings/create-org (or prompt after upgrade)
2. Fills in:
   - Org name (required)
   - Email domain(s) (required, e.g. "acme.com" — can add multiple)
3. POST /api/org
   → Create org record (status = ACTIVE, plan = creator's tier)
   → Associate creator as ORG_ADMIN
   → Creator's free-user quota now replaced by org quota
4. Redirect to /org/settings
```

**Domain patterns:** stored as comma-separated list (e.g., `acme.com,acmecorp.com`). Exact match on the domain portion of email addresses (no wildcards in v1).

### 4.2 Domain Validation on Invitation

```java
// Pseudocode
boolean domainMatches = org.getDomainPatterns()
    .stream()
    .anyMatch(d -> inviteeEmail.endsWith("@" + d));

if (domainMatches) {
    createPendingInvitation(invite);          // normal path
} else {
    createEscalatedInvitation(invite);        // → Super Admin review
    notifySuperAdmin(invite, org);
}
```

**Super Admin Escalation Flow:**
- Email to `admin@executespec.ai`: "Org [Acme Corp] wants to invite [jane@contractor.com] who is outside their registered domains. Approve?"
- Super Admin clicks Approve / Reject link in email (or uses Admin UI)
- On Approve → invitation email sent to invitee as normal
- On Reject → Org Admin notified

### 4.3 Org Settings (managed by Org Admin)

| Setting | Description |
|---|---|
| Org name | Display name |
| Domain patterns | Email domains allowed to join without super-admin approval |
| Members | View/manage all members; change their project assignments and roles |
| Projects | Create/archive projects |
| Billing | View current plan, usage, invoices (details deferred) |

---

## 5. User Tiers & Quotas

### 5.1 Tier Overview

| Feature | FREE (personal) | PRO (org-level) | ENTERPRISE (org-level) |
|---|---|---|---|
| **Billing** | $0 | $TBD/month/seat | Custom |
| **Runs per month** | 10 | 100 (shared org pool) | Unlimited |
| **Concurrent runs** | 1 | 3 per org | 10 per org |
| **Models available** | Haiku only | All models | All models + model override |
| **History retention** | 30 days | 90 days | Unlimited |
| **Workspace storage** | 500 MB | 5 GB | Unlimited |
| **Active previews** | 1 | 5 | Unlimited |
| **Priority queue** | Standard | Priority | Dedicated slot |
| **External integrations** | ❌ | ✅ | ✅ |
| **Projects** | ❌ (no org) | ✅ | ✅ |
| **Org creation** | ❌ | ✅ | ✅ |
| **Custom domain invite** | N/A | Requires Super Admin approval | Requires Super Admin approval |
| **Support** | Community | Email | Dedicated |

> **⚠️ TBD:** Exact pricing, seat model (per-seat vs flat org), and enterprise custom limits to be confirmed.

### 5.2 Quota Ownership

| User Type | Quota Owner | Counter Resets |
|---|---|---|
| FREE (no org) | Individual user | 1st of each calendar month |
| Org member | Organization (shared pool) | 1st of each calendar month |

When a free user joins an org:
- Their personal quota counter is ignored
- All their future runs count against the org's pool

### 5.3 Tier Enforcement Points

| Check | Where enforced |
|---|---|
| Monthly run count | `TierEnforcementService.assertCanSubmitRun(userId)` — checks user's org (if any) or personal counter |
| Concurrent runs | Same service — queries active runs for org or user |
| Model access | `RunController` — validates model against tier's allowed list |
| Org creation | `OrgController` — rejects if user tier = FREE |
| Integration access | `IntegrationController` — checks org tier |

---

## 6. RBAC — Roles & Permissions

### 6.1 Role Taxonomy

Roles operate at two levels: **global** (system-wide) and **org-scoped**.

**Global Roles** (stored on `app_user.global_role`):
| Role | Who | Scope |
|---|---|---|
| `SUPER_ADMIN` | admin@executespec.ai only | Full system — all orgs, all users, all runs |
| `USER` | All other registered users | Own data + org/project scoped data |

**Org Roles** (stored on `org_member.org_role`):
| Role | Who | Scope |
|---|---|---|
| `ORG_ADMIN` | Org creator + promoted members | Full control over their org |
| `DEVELOPER` | Standard org member | Submit runs, approve/merge within assigned projects |
| `BILLING_ADMIN` | Finance/ops member | View costs, change org plan, manage invoices |

> A user can only belong to **one org** at a time (v1). Their effective permissions = global role + org role.

### 6.2 Permission Matrix

| Action | SUPER_ADMIN | ORG_ADMIN | DEVELOPER | BILLING_ADMIN | FREE User |
|---|---|---|---|---|---|
| **Runs** | | | | | |
| Submit run | ✅ | ✅ (own org projects) | ✅ (assigned projects) | ❌ | ✅ (own only) |
| View run | ✅ | ✅ (org runs) | ✅ (assigned projects only) | ✅ (list + cost) | ✅ (own only) |
| Approve plan | ✅ | ✅ | ✅ | ❌ | ✅ (own only) |
| Trigger merge | ✅ | ✅ | ✅ | ❌ | ✅ (own only) |
| Cancel/delete run | ✅ | ✅ | ✅ (own runs) | ❌ | ✅ (own only) |
| **Org Management** | | | | | |
| Create org | ✅ | ✅ (already have one) | ❌ | ❌ | ❌ |
| Invite member | ✅ | ✅ (domain-match only; else → escalate) | ❌ | ❌ | ❌ |
| Approve cross-domain invite | ✅ | ❌ | ❌ | ❌ | ❌ |
| Remove member | ✅ | ✅ | ❌ | ❌ | ❌ |
| Change member org role | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Projects** | | | | | |
| Create project | ✅ | ✅ | ❌ | ❌ | ❌ |
| Add member to project | ✅ | ✅ | ❌ | ❌ | ❌ |
| Archive project | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Billing / Pricing** | | | | | |
| View org cost/usage | ✅ | ✅ | ❌ | ✅ | ❌ |
| Change org plan/tier | ✅ | ❌ | ❌ | ✅ | ❌ |
| Edit model pricing | ✅ | ❌ | ❌ | ✅ | ❌ |
| **Admin** | | | | | |
| View all users (system) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Suspend any user | ✅ | ❌ | ❌ | ❌ | ❌ |
| View/manage all orgs | ✅ | ❌ | ❌ | ❌ | ❌ |

---

## 7. Projects (Run Scoping within Orgs)

### 7.1 What is a Project?

A Project is a named group within an Org that:
- Has an assigned set of members (subset of org members)
- Scopes run visibility: members only see runs submitted within their assigned projects
- Has a display name and optional description
- Can be archived (runs preserved, no new runs allowed)

**Free-tier users have no projects** — their runs are personal and unscoped.

### 7.2 Project Membership Roles

| Role | Description |
|---|---|
| `PROJECT_ADMIN` | Can add/remove project members; inherits DEVELOPER permissions |
| `PROJECT_DEVELOPER` | Can submit, approve, merge runs within this project |

> Org Admins implicitly have PROJECT_ADMIN on all projects in their org.

### 7.3 Project Lifecycle

```
Create (Org Admin) → Active → Archived
                         ↑ unarchive possible
```

Archived projects: read-only; existing runs still visible; no new runs.

---

## 8. Run Tagging & Visibility

### 8.1 Run Ownership Fields

Every `pipeline_run` will carry:

| Field | Type | Description |
|---|---|---|
| `created_by_user_id` | VARCHAR(255) | ID of the user who submitted the run |
| `org_id` | VARCHAR(255) | Org the run belongs to (NULL for free users) |
| `project_id` | VARCHAR(255) | Project the run belongs to (NULL for free users) |

### 8.2 Visibility Rules (enforced server-side)

```
GET /api/runs
  ↓
  if SUPER_ADMIN            → return all runs
  if ORG_ADMIN              → return all runs where org_id = user.orgId
  if BILLING_ADMIN          → return all runs where org_id = user.orgId (summary/cost fields only)
  if DEVELOPER (org member) → return runs where project_id IN user's assigned projects
  if FREE user              → return runs where created_by_user_id = user.id
```

All run-fetching queries in `RunRepository` and `StateStore` will be filtered by calling
`SecurityContext.getRunFilter(currentUser)` which returns a JPA `Specification<PipelineRun>`.

### 8.3 Run Submission Association

When `POST /api/runs` is called:
```java
run.setCreatedByUserId(currentUser.getId());
run.setOrgId(currentUser.getOrgId());           // null for free users
run.setProjectId(request.getProjectId());        // required for org members, null for free
```

> **Business rule:** Org members MUST specify a `projectId` when submitting. The server validates that the user is a member of that project.

---

## 9. Default Super Admin

Seeded via Flyway `V5__seed_admin_user.sql` at startup. The super admin is a **system-level account**, not an org member.

```sql
-- V5__seed_admin_user.sql
INSERT INTO app_user (
    id, email, first_name, last_name,
    password_hash, global_role, tier, status,
    must_change_password, email_verified,
    org_id, created_at, updated_at
) VALUES (
    'admin-00000000-0000-0000-0000-000000000001',
    'admin@executespec.ai',
    'Super', 'Admin',
    -- bcrypt of 'admin', strength 12
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TdxAlt5FwvZSNHJsQi79t1R1K0Cm',
    'SUPER_ADMIN',
    'ENTERPRISE',
    'ACTIVE',
    true,          -- must change password on first login
    true,          -- pre-verified
    null,          -- no org — super admin is system-level
    NOW(), NOW()
) ON CONFLICT (email) DO NOTHING;
```

**First Login:** Login with `admin@executespec.ai` / `admin` → forced password change modal.

---

## 10. Email Infrastructure

### 10.1 Provider
- **From:** `executespec <admin@executespec.ai>` (Google Workspace / GSuite)
- **Protocol:** SMTP via Gmail relay (port 587, STARTTLS)
- **Credential:** GSuite App Password (not the account password)

### 10.2 application.yml additions

```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME:admin@executespec.ai}
    password: ${MAIL_PASSWORD:}     # GSuite App Password — set via env var
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.starttls.required: true

app:
  mail:
    from: "executespec <admin@executespec.ai>"
    base-url: ${APP_BASE_URL:http://localhost:5173}
```

> **Action required:** Enable 2FA on `admin@executespec.ai` → Google Admin Console → Security → App Passwords → generate for "Mail".

### 10.3 Email Templates (Thymeleaf)

| Trigger | Subject | Recipient |
|---|---|---|
| Self-registration | "Verify your executespec account" | Registering user |
| Org invitation (domain-match) | "You've been invited to [Org Name] on executespec" | Invitee |
| Org invitation (cross-domain, on approval) | "You've been invited to [Org Name] on executespec" | Invitee |
| Escalation to Super Admin | "Cross-domain invite requires your approval: [email] → [Org]" | admin@executespec.ai |
| Escalation rejected → Org Admin | "Invite for [email] was rejected by executespec admin" | Org Admin |
| Password reset | "Reset your executespec password" | User |
| Welcome (post-verify) | "Welcome to executespec" | New user |
| New user alert | "New user registered: [email]" | admin@executespec.ai |

### 10.4 Email Sending Rules
- All email sending is `@Async` — never blocks the HTTP response
- Max 3 verification emails per address per hour (rate-limited in Redis or in-DB counter)
- Errors are logged as WARN — never propagate to caller

---

## 11. Payment Audit (Stub)

**Full billing implementation is deferred. The following is the minimal schema to support auditing.**

The requirement is to track:
1. Whether a user/org has paid (boolean verified status)
2. A payment reference ID that can be correlated against an external payment provider (Stripe, etc.) later
3. A basic audit log of payment-related events

```sql
-- Stub table — to be expanded when billing is implemented
CREATE TABLE IF NOT EXISTS payment_audit (
    id              VARCHAR(255)    PRIMARY KEY,
    entity_type     VARCHAR(32)     NOT NULL,  -- 'USER' | 'ORG'
    entity_id       VARCHAR(255)    NOT NULL,  -- user_id or org_id
    event_type      VARCHAR(64)     NOT NULL,  -- PLAN_ASSIGNED | PLAN_UPGRADED | PLAN_DOWNGRADED
                                               -- | PAYMENT_RECEIVED | PAYMENT_FAILED
                                               -- | SUBSCRIPTION_CANCELLED | MANUAL_OVERRIDE
    old_tier        VARCHAR(32),
    new_tier        VARCHAR(32),
    payment_ref_id  VARCHAR(255),              -- External payment provider reference (Stripe charge ID, etc.)
    performed_by    VARCHAR(255),              -- User ID who triggered the event
    notes           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_audit_entity ON payment_audit (entity_type, entity_id);
```

**Org payment status fields** (added to `org` table):
```sql
is_payment_verified     BOOLEAN  NOT NULL DEFAULT FALSE,
payment_ref_id          VARCHAR(255),          -- latest successful payment reference
plan_expires_at         TIMESTAMP WITH TIME ZONE,
```

**Rules (manual for now):**
- `is_payment_verified = true` → org has access to its tier's features
- `is_payment_verified = false` → org runs in degraded mode (FREE tier limits apply)
- Super Admin and Billing Admin can set `is_payment_verified` manually until Stripe is integrated
- Every change to `is_payment_verified` or `tier` writes a row to `payment_audit`

---

## 12. Database Schema

### 12.1 New Tables (V4 Migration)

```sql
-- V4__user_auth_and_org.sql

-- ── app_user ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_user (
    id                   VARCHAR(255)  PRIMARY KEY,
    email                VARCHAR(255)  NOT NULL UNIQUE,
    first_name           VARCHAR(100)  NOT NULL,
    last_name            VARCHAR(100)  NOT NULL,
    password_hash        VARCHAR(255)  NOT NULL,
    global_role          VARCHAR(32)   NOT NULL DEFAULT 'USER',
                                       -- SUPER_ADMIN | USER
    tier                 VARCHAR(32)   NOT NULL DEFAULT 'FREE',
                                       -- FREE | PRO | ENTERPRISE
    status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING_VERIFICATION',
                                       -- PENDING_VERIFICATION | ACTIVE | SUSPENDED | DELETED
    must_change_password BOOLEAN       NOT NULL DEFAULT FALSE,
    email_verified       BOOLEAN       NOT NULL DEFAULT FALSE,
    org_id               VARCHAR(255),  -- FK to org.id (nullable — free users have no org)
    personal_run_count   INTEGER       NOT NULL DEFAULT 0,  -- used only for FREE users (no org)
    run_count_reset_at   TIMESTAMP WITH TIME ZONE,
    last_login_at        TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_user_email  ON app_user (email);
CREATE INDEX IF NOT EXISTS idx_user_org_id ON app_user (org_id);
CREATE INDEX IF NOT EXISTS idx_user_status ON app_user (status);

-- ── org ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS org (
    id                   VARCHAR(255)  PRIMARY KEY,
    name                 VARCHAR(255)  NOT NULL,
    domain_patterns      TEXT          NOT NULL,  -- comma-separated, e.g. "acme.com,acmecorp.com"
    tier                 VARCHAR(32)   NOT NULL DEFAULT 'PRO',
                                       -- PRO | ENTERPRISE
    status               VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
                                       -- ACTIVE | SUSPENDED | DELETED
    monthly_run_count    INTEGER       NOT NULL DEFAULT 0,
    run_count_reset_at   TIMESTAMP WITH TIME ZONE,
    is_payment_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    payment_ref_id       VARCHAR(255),
    plan_expires_at      TIMESTAMP WITH TIME ZONE,
    created_by_user_id   VARCHAR(255)  NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP WITH TIME ZONE
);

-- ── org_member ────────────────────────────────────────────────────────────
-- Explicit join table: a user's role WITHIN a specific org
CREATE TABLE IF NOT EXISTS org_member (
    id          VARCHAR(255)  PRIMARY KEY,
    org_id      VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    user_id     VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    org_role    VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPER',
                               -- ORG_ADMIN | DEVELOPER | BILLING_ADMIN
    joined_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_org_member_user ON org_member (user_id);
CREATE INDEX IF NOT EXISTS idx_org_member_org  ON org_member (org_id);

-- ── org_invitation ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS org_invitation (
    id              VARCHAR(255)  PRIMARY KEY,
    org_id          VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    invited_email   VARCHAR(255)  NOT NULL,
    invited_by      VARCHAR(255)  NOT NULL,  -- user_id of org admin who invited
    org_role        VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPER',
    token           VARCHAR(255)  NOT NULL UNIQUE,  -- the invite link token
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
                                   -- PENDING | ACCEPTED | REJECTED | EXPIRED | ESCALATED
    escalation_note TEXT,          -- populated when status = ESCALATED
    project_ids     TEXT,          -- comma-separated project IDs to auto-assign on accept
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_invitation_token ON org_invitation (token);
CREATE INDEX IF NOT EXISTS idx_invitation_org   ON org_invitation (org_id);
CREATE INDEX IF NOT EXISTS idx_invitation_email ON org_invitation (invited_email);

-- ── project ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(255)  PRIMARY KEY,
    org_id          VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    name            VARCHAR(255)  NOT NULL,
    description     TEXT,
    status          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
                                   -- ACTIVE | ARCHIVED
    created_by      VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_project_org ON project (org_id);

-- ── project_member ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS project_member (
    id              VARCHAR(255)  PRIMARY KEY,
    project_id      VARCHAR(255)  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id         VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_role    VARCHAR(32)   NOT NULL DEFAULT 'PROJECT_DEVELOPER',
                                   -- PROJECT_ADMIN | PROJECT_DEVELOPER
    assigned_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_member_user    ON project_member (user_id);
CREATE INDEX IF NOT EXISTS idx_project_member_project ON project_member (project_id);

-- ── email_token ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_token (
    id          VARCHAR(255)  PRIMARY KEY,
    user_id     VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token       VARCHAR(255)  NOT NULL UNIQUE,
    type        VARCHAR(32)   NOT NULL,  -- EMAIL_VERIFICATION | PASSWORD_RESET
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_email_token_token ON email_token (token);

-- ── refresh_token_blocklist ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_token_blocklist (
    jti         VARCHAR(255)  PRIMARY KEY,
    user_id     VARCHAR(255)  NOT NULL,
    blocked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_blocklist_expires ON refresh_token_blocklist (expires_at);

-- ── payment_audit ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_audit (
    id              VARCHAR(255)  PRIMARY KEY,
    entity_type     VARCHAR(32)   NOT NULL,  -- 'USER' | 'ORG'
    entity_id       VARCHAR(255)  NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    old_tier        VARCHAR(32),
    new_tier        VARCHAR(32),
    payment_ref_id  VARCHAR(255),
    performed_by    VARCHAR(255),
    notes           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_audit_entity ON payment_audit (entity_type, entity_id);
```

### 12.2 Amendments to Existing Tables (V4 continued)

```sql
-- Tag every run to its owner, org, and project
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS created_by_user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS org_id             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS project_id         VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_run_created_by ON pipeline_run (created_by_user_id);
CREATE INDEX IF NOT EXISTS idx_run_org_id     ON pipeline_run (org_id);
CREATE INDEX IF NOT EXISTS idx_run_project_id ON pipeline_run (project_id);
```

### 12.3 Super Admin Seed (V5 Migration)

```sql
-- V5__seed_admin_user.sql  (see Section 9 for full SQL)
```

---

## 13. API Endpoints

### 13.1 Auth Endpoints (public, `/api/auth/**`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Self-register (FREE tier) |
| `POST` | `/api/auth/login` | Authenticate → JWT + refresh cookie |
| `POST` | `/api/auth/refresh` | Renew access token via refresh cookie |
| `DELETE` | `/api/auth/logout` | Invalidate refresh token |
| `GET` | `/api/auth/verify-email?token=` | Verify email address |
| `POST` | `/api/auth/resend-verification` | Resend verification email |
| `POST` | `/api/auth/forgot-password` | Send password reset email |
| `POST` | `/api/auth/reset-password` | Apply password reset |
| `POST` | `/api/auth/change-password` | Change password (authenticated) |
| `GET` | `/api/auth/me` | Get current user profile |

### 13.2 Org Endpoints (`/api/org/**` — authenticated)

| Method | Path | Who | Description |
|---|---|---|---|
| `POST` | `/api/org` | PRO/Enterprise user | Create org |
| `GET` | `/api/org/me` | Org member | Get own org detail |
| `PUT` | `/api/org/me` | ORG_ADMIN | Update org name/domain |
| `GET` | `/api/org/me/members` | ORG_ADMIN | List org members |
| `DELETE` | `/api/org/me/members/{userId}` | ORG_ADMIN | Remove member |
| `PUT` | `/api/org/me/members/{userId}/role` | ORG_ADMIN | Change member org role |
| `POST` | `/api/org/invite` | ORG_ADMIN | Invite user by email |
| `GET` | `/api/org/me/invitations` | ORG_ADMIN | List pending/escalated invites |
| `DELETE` | `/api/org/me/invitations/{id}` | ORG_ADMIN | Cancel invitation |
| `POST` | `/api/org/accept-invite?token=` | Anyone | Accept an org invitation |

### 13.3 Project Endpoints (`/api/projects/**` — authenticated, org members only)

| Method | Path | Who | Description |
|---|---|---|---|
| `POST` | `/api/projects` | ORG_ADMIN | Create project |
| `GET` | `/api/projects` | Org member | List projects user belongs to |
| `GET` | `/api/projects/{id}` | Project member | Get project detail |
| `PUT` | `/api/projects/{id}` | ORG_ADMIN | Update project |
| `PUT` | `/api/projects/{id}/archive` | ORG_ADMIN | Archive project |
| `GET` | `/api/projects/{id}/members` | ORG_ADMIN or PROJECT_ADMIN | List project members |
| `POST` | `/api/projects/{id}/members` | ORG_ADMIN or PROJECT_ADMIN | Add member to project |
| `DELETE` | `/api/projects/{id}/members/{userId}` | ORG_ADMIN or PROJECT_ADMIN | Remove from project |

### 13.4 Super Admin Endpoints (`/api/admin/**` — SUPER_ADMIN only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/users` | List all users (paginated) |
| `GET` | `/api/admin/users/{id}` | Get user detail |
| `PUT` | `/api/admin/users/{id}/status` | Activate / suspend / delete |
| `PUT` | `/api/admin/users/{id}/tier` | Change user tier |
| `PUT` | `/api/admin/users/{id}/role` | Change global role |
| `GET` | `/api/admin/orgs` | List all orgs |
| `GET` | `/api/admin/orgs/{id}` | Get org detail |
| `PUT` | `/api/admin/orgs/{id}/status` | Suspend / restore org |
| `PUT` | `/api/admin/orgs/{id}/tier` | Change org plan |
| `GET` | `/api/admin/invitations/escalated` | List pending cross-domain invites |
| `POST` | `/api/admin/invitations/{id}/approve` | Approve escalated invite |
| `POST` | `/api/admin/invitations/{id}/reject` | Reject escalated invite |

### 13.5 Amendments to Existing Endpoints

- All `/api/runs/**` now require `Authorization: Bearer <token>`
- `POST /api/runs` body gets new optional fields: `projectId` (required for org members)
- `GET /api/runs` filtered by `SecurityContext.getRunFilter(currentUser)` — see §8.2

---

## 14. Frontend Pages & Components

### 14.1 New Pages

| Route | Component | Access |
|---|---|---|
| `/login` | `LoginPage.tsx` | Public |
| `/register` | `RegisterPage.tsx` | Public |
| `/forgot-password` | `ForgotPasswordPage.tsx` | Public |
| `/reset-password` | `ResetPasswordPage.tsx` | Public (token) |
| `/verify-email` | `VerifyEmailPage.tsx` | Public (token) |
| `/accept-invite` | `AcceptInvitePage.tsx` | Public (token) |
| `/org/settings` | `OrgSettingsPage.tsx` | ORG_ADMIN |
| `/org/members` | `OrgMembersPage.tsx` | ORG_ADMIN |
| `/org/projects` | `OrgProjectsPage.tsx` | ORG_ADMIN |
| `/admin` | `SuperAdminPage.tsx` | SUPER_ADMIN |
| `/admin/users` | `AdminUsersPage.tsx` | SUPER_ADMIN |
| `/admin/orgs` | `AdminOrgsPage.tsx` | SUPER_ADMIN |
| `/admin/invitations` | `AdminEscalationsPage.tsx` | SUPER_ADMIN |

### 14.2 New Components

| Component | Purpose |
|---|---|
| `ProtectedRoute.tsx` | Redirects to `/login` if unauthenticated |
| `RoleGuard.tsx` | Renders children only if user has required role; shows 403 otherwise |
| `ChangePasswordModal.tsx` | Fullscreen overlay blocking app when `mustChangePassword = true` |
| `UserMenu.tsx` | Header dropdown: name, tier badge, org name, change password, logout |
| `TierBadge.tsx` | FREE / PRO / ENTERPRISE chip |
| `OrgBadge.tsx` | Org name + domain shown in header/profile |
| `ProjectSelector.tsx` | Dropdown for selecting a project when submitting a run (org members only) |
| `OrgMembersTable.tsx` | Paginated member list with inline role editing |
| `PendingInvitesTable.tsx` | List of sent invitations + status chips |
| `EscalatedInvitesTable.tsx` | Super Admin view of cross-domain invites (approve/reject actions) |
| `QuotaUsageBar.tsx` | Shows monthly run usage vs. limit (runs page header) |

### 14.3 Auth Store (Zustand)

```typescript
// frontend/src/stores/useAuthStore.ts
interface AuthUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  globalRole: 'SUPER_ADMIN' | 'USER';
  tier: 'FREE' | 'PRO' | 'ENTERPRISE';
  mustChangePassword: boolean;
  // Org context (null for free users)
  orgId: string | null;
  orgName: string | null;
  orgRole: 'ORG_ADMIN' | 'DEVELOPER' | 'BILLING_ADMIN' | null;
}

interface AuthStore {
  accessToken: string | null;
  user: AuthUser | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshAccessToken: () => Promise<void>;
  clearAuth: () => void;
}
```

### 14.4 Run Submission Changes

`NewRunModal.tsx` gains a **Project selector** dropdown visible only to org members:
```tsx
{user.orgId && (
  <ProjectSelector
    orgId={user.orgId}
    value={selectedProjectId}
    onChange={setSelectedProjectId}
    required
  />
)}
```

`RunList` gains a **QuotaUsageBar** at the top:
```tsx
<QuotaUsageBar used={quota.used} limit={quota.limit} tier={user.tier} />
```

---

## 15. Backend Implementation Notes

### 15.1 New Dependencies (pom.xml)

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT (JJWT 0.12.x) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>

<!-- Spring Mail -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>

<!-- Thymeleaf for email templates -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

### 15.2 New Package Structure

```
com.navneet.agentteam
├── auth/
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── OrgController.java
│   │   ├── ProjectController.java
│   │   └── SuperAdminController.java
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── OrgService.java
│   │   ├── ProjectService.java
│   │   ├── InvitationService.java         # handles invite logic + domain validation
│   │   ├── TierEnforcementService.java    # quota checks
│   │   ├── MailService.java
│   │   └── PaymentAuditService.java       # logs tier/payment changes
│   ├── entity/
│   │   ├── AppUser.java
│   │   ├── Org.java
│   │   ├── OrgMember.java
│   │   ├── OrgInvitation.java
│   │   ├── Project.java
│   │   ├── ProjectMember.java
│   │   ├── EmailToken.java
│   │   ├── RefreshTokenBlocklist.java
│   │   └── PaymentAudit.java
│   ├── repository/  (one per entity)
│   ├── security/
│   │   ├── SecurityConfig.java
│   │   ├── JwtUtil.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── UserDetailsServiceImpl.java
│   │   └── RunVisibilitySpec.java         # JPA Specification for run filtering
│   └── dto/
│       ├── RegisterRequest.java
│       ├── LoginRequest.java / LoginResponse.java
│       ├── UserDto.java / OrgDto.java / ProjectDto.java
│       ├── InviteRequest.java
│       └── ChangePasswordRequest.java
```

### 15.3 JWT Payload

```json
{
  "sub": "user-uuid",
  "email": "jane@acme.com",
  "globalRole": "USER",
  "orgId": "org-uuid",
  "orgRole": "DEVELOPER",
  "tier": "PRO",
  "iat": 1234567890,
  "exp": 1234568790
}
```

### 15.4 CORS Hardening

Change `WebConfig.java` from `allowedOrigins("*")` to:
```yaml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
    # Production: https://app.executespec.ai
```

### 15.5 Run Submission Quota Check

```java
// TierEnforcementService
public void assertCanSubmitRun(String userId) {
    AppUser user = userRepo.findById(userId).orElseThrow();
    if (user.getOrgId() != null) {
        Org org = orgRepo.findById(user.getOrgId()).orElseThrow();
        assertOrgQuota(org);     // checks org.monthlyRunCount vs tier limit
        assertOrgConcurrency(org);
    } else {
        assertUserQuota(user);   // checks user.personalRunCount vs FREE limit
        assertUserConcurrency(userId);
    }
}
```

---

## 16. Open Questions / TBDs

| # | Question | Impact | Assumption Used |
|---|---|---|---|
| 1 | Exact run/concurrency limits for PRO and ENTERPRISE? | Tier enforcement thresholds | 100/3 (PRO), unlimited/10 (Enterprise) — placeholder |
| 2 | Can an org have multiple ORG_ADMINs? | Org admin count enforcement | Yes — no limit on ORG_ADMIN count in one org |
| 3 | When a user joins an org, do their previously created FREE runs become visible to the org? | Run migration logic | No — personal runs stay personal; only new runs go to org/project |
| 4 | Can a user leave an org (self-service)? Or only removed by ORG_ADMIN? | Offboarding flow | Only removable by ORG_ADMIN or SUPER_ADMIN (v1) |
| 5 | What happens to a user's runs when removed from the org? | Data ownership | Runs remain attributed to org/project; user loses access |
| 6 | Payment: Stripe or another provider? Per-seat or flat org fee? | Billing schema expansion | Deferred — Stripe assumed when ready |
| 7 | Can an org be downgraded while members are actively running? | Tier downgrade logic | Downgrade effective at next billing cycle; in-flight runs not interrupted |
| 8 | Should FREE users see an "Upgrade" CTA when hitting quota limits? | Frontend UX | Yes — upsell modal (copy TBD) |
| 9 | Org run count: does it reset on 1st of calendar month or org creation anniversary? | Counter reset logic | Calendar month (1st) assumed |
| 10 | Is email domain matching case-sensitive? | Invitation validation | No — lowercase both sides for comparison |
| 11 | Should the Super Admin be able to see run content (plan JSON, files) for any run? | Admin privacy policy | Yes — SUPER_ADMIN has full system access |
| 12 | Project-level quota? Or only org-level? | Quota granularity | Org-level only (v1) — no per-project limits |
| 13 | Can a PRO org upgrade to ENTERPRISE self-serve, or does it require Super Admin? | Tier upgrade flow | Self-serve via Billing Admin (post-Stripe); manual via Super Admin until then |

---

*Document owner: Navneet Prabhakar*
*Status: v2.0 — pending tier limit confirmation (Q1), then ready for implementation sprint.*
