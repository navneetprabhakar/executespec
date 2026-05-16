# Role-Based Access Control

This document describes RBAC as implemented in code, not as aspired-to. Every claim here is traceable to a file path under `src/main/java/com/navneet/executespec/`.

Two orthogonal axes govern every authorisation decision:

1. **Tier** (`FREE` / `PRO` / `TEAM` / `ENTERPRISE`) — what the user has paid for.
2. **Role** (global on the user, org-scoped on org-member, project-scoped on project-member) — what the user is allowed to do.

Tier gates feature availability (e.g. "can I open a PR review?"). Role gates row-level access (e.g. "can I read this specific project?").

---

## 1. The Tier Axis

Tier lives in two places that must stay in sync (`V199__sync_org_tier_with_subscription_tier.sql` exists specifically to enforce that):

| Column                         | File                                                                                                              | Default        |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------- | -------------- |
| `app_user.tier`                | [`auth/entity/AppUser.java`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java)                   | `FREE`         |
| `org.tier`                     | [`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java)                           | `PRO`          |
| `billing_subscription.tier_*`  | seeded by [`auth/service/AuthService.java`](../src/main/java/com/navneet/executespec/auth/service/AuthService.java) `createFreeSubscription` on register | derived        |

Constants are plain Java strings, validated against `tier_config` rows (`auth/entity/TierConfig.java`). Per-tier numeric caps come from `tier_config`:

- `monthly_run_limit` (`-1` = unlimited)
- `max_org_members`
- `concurrent_run_limit`
- `max_chat_messages`
- `max_apply_suggestions`
- `max_preview_starts`
- `max_run_auto_retries`
- `can_deploy` / `max_deployments`
- `max_run_cost_usd` (per-run BYOK cost cap)
- `retention_days`
- `pr_review_enabled` / `pr_review_monthly_quota` (V172)
- `code_review_enabled` / `code_review_monthly_quota` (V172)
- `share_link_active_cap` (V175)
- `lifecycle_state` (LISTED / HIDDEN / SUNSETTING — v2.6.0)
- `execution_view_enabled`

Tier history:

- **V145** (later) raised FREE 5 → 10 runs/month, **V167** reverted to 5.
- **V165** (`drop_max_tier_team_at_39.sql`) collapsed legacy MAX tier into TEAM, deleted MAX from `tier_config` and `billing_plan`.
- **V176** rebranded PRO display as "Solo" (display only).
- **V185** rolled that branding back; tier identifier remained `PRO`.

Enforcement lives in [`auth/service/TierEnforcementService.java`](../src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java) (`assertAndIncrementRunCount`, `assertCanCreateProject`, `assertCanLinkRepoToProject`, `assertComplexityAllowed`, etc.) under pessimistic-write locks on `app_user` / `org`. Tier resolution for personal-org users routes through `freeUserPermissions` in [`auth/service/TierPermissionService.java`](../src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java).

---

## 2. The Role Axes

### 2.1 Global role (`app_user.global_role`)

Declared in `AppUser` as a plain string with default `USER`. Two values in production:

- `SUPER_ADMIN` — platform operator. Bypasses every guard except payment-webhook signature verification.
- `USER` — everyone else.

There is no on-disk constants enum; the strings are compared directly. Spring authorities are minted in [`auth/security/AppUserDetails.java`](../src/main/java/com/navneet/executespec/auth/security/AppUserDetails.java) as `ROLE_<globalRole>`.

### 2.2 Org role (`org_member.org_role`)

Declared in [`auth/entity/OrgMember.java`](../src/main/java/com/navneet/executespec/auth/entity/OrgMember.java). Default `DEVELOPER`.

| Value           | Capabilities                                                                                          |
| --------------- | ----------------------------------------------------------------------------------------------------- |
| `ORG_ADMIN`     | Org settings, member CRUD, role changes, invitations, BYOK key config, JIRA/Confluence/SSO config, external-sharing flag toggle (V198), seat adjustments. |
| `BILLING_ADMIN` | Read billing for own org, view analytics scoped to billing.                                           |
| `DEVELOPER`     | Submit runs, create projects, read org-wide runs.                                                     |

[`AppUserDetails`](../src/main/java/com/navneet/executespec/auth/security/AppUserDetails.java) adds `ROLE_<orgRole>` alongside `ROLE_<globalRole>` so `@PreAuthorize("hasRole('ORG_ADMIN')")` works.

### 2.3 Project role (`project_member.project_role`)

Declared in [`auth/entity/ProjectMember.java`](../src/main/java/com/navneet/executespec/auth/entity/ProjectMember.java). Default `PROJECT_DEVELOPER`. The row also carries `is_external` (V175) to mark cross-org collaborators.

| Value                | Capabilities                                                          |
| -------------------- | --------------------------------------------------------------------- |
| `PROJECT_ADMIN`      | Add/remove project members, update project settings.                  |
| `PROJECT_DEVELOPER`  | Submit runs against project, upload docs, manage repos.               |
| `PROJECT_VIEWER`     | Read-only — runs, reports, architecture, validation, chat history.    |

`PROJECT_VIEWER` was added in V175 to support cross-org read-only sharing without granting org membership.

---

## 3. JWT Claim Shape

Tokens are minted in [`auth/security/JwtUtil.java`](../src/main/java/com/navneet/executespec/auth/security/JwtUtil.java). HS256 over an HMAC secret. Three token types share the signing key.

### 3.1 Access token (`generateAccessToken(AppUser, OrgMember)`)

```
sub:        <userId>
email:      <email>
globalRole: <SUPER_ADMIN | USER>
tier:       <FREE | PRO | TEAM | ENTERPRISE>
orgId:      <orgId or null for solo>
orgRole:    <ORG_ADMIN | BILLING_ADMIN | DEVELOPER | null>
iat:        <issuedAt>
exp:        <iat + access-token-expiry-minutes>
```

Default expiry is `15` minutes per `JwtUtil` constructor default (`${app.jwt.access-token-expiry-minutes:15}`); `application.yml` and prod override to `360` minutes. The root `CLAUDE.md` claim of "1h access token" is stale relative to code default.

### 3.2 Refresh token (`generateRefreshToken(AppUser)`)

```
sub:  <userId>
jti:  <random UUID>
type: refresh
iat:  <issuedAt>
exp:  <iat + refresh-token-expiry-days> (default 7 days)
```

Revocation: on `POST /api/auth/logout` the JTI is inserted into `refresh_token_blocklist` ([`auth/entity/RefreshTokenBlocklist.java`](../src/main/java/com/navneet/executespec/auth/entity/RefreshTokenBlocklist.java)). `JwtAuthenticationFilter` consults this list. Native upsert avoids duplicate-PK errors on race.

### 3.3 SSE-scoped token (`generateSseToken(userId, runId)`)

```
sub:   <userId>
scope: sse
runId: <runId>
iat:   <issuedAt>
exp:   <iat + 5 minutes>
```

Carried in the `?token=` query parameter on `/api/runs/{id}/events`. Permitted in `SecurityConfig`'s public-path matcher; validated inline in `SseController`.

### 3.4 Per-user JWT epoch invalidation

`app_user.token_invalidated_at` (Instant). When set (via `POST /api/auth/invalidate-sessions`), `JwtAuthenticationFilter` rejects every token whose `iat` is older. There is also a feature-flag-gated global force-logout epoch read by `JwtUtil.isTokenValid(String)`.

### 3.5 Authentication filter

[`auth/security/JwtAuthenticationFilter.java`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java) is a `OncePerRequestFilter` that:

1. Reads `Authorization: Bearer <token>` or, for SSE, `?token=`.
2. Validates signature + expiry + per-user invalidation.
3. Loads `AppUserDetails` via `UserDetailsServiceImpl.loadUserById`.
4. Sets `SecurityContextHolder`.

It runs before `UsernamePasswordAuthenticationFilter`; `RateLimitFilter` runs after JWT.

---

## 4. Authorization at the controller layer

### 4.1 `@PreAuthorize` surfaces

`@EnableMethodSecurity` is on (see [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java)). The following controllers gate methods declaratively.

**Class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`:**

- [`auth/controller/SuperAdminController.java`](../src/main/java/com/navneet/executespec/auth/controller/SuperAdminController.java) — every endpoint under `/api/admin` (guardrail diagnostic, force-fail-task, halt-all, audit-chain verify, user/org status/tier changes, tier_config edits, escalated invitations, audit-logs, top-up packages, grant-runs).
- [`folder/ExecutespecMigrationController.java`](../src/main/java/com/navneet/executespec/folder/ExecutespecMigrationController.java) — `POST /api/admin/projects/{projectId}/migrate-codemaps`.

**Method-level `@PreAuthorize("hasRole('SUPER_ADMIN')")`:**

- `PricingController#upsert` — `PUT /api/pricing/{provider}/{model}`.

**Method-level `@PreAuthorize("hasRole('ORG_ADMIN')")` (or equivalent doc-comment in OrgController):**

- `OrgController#updateOrg` — `PUT /api/org/me`
- `OrgController#removeMember` — `DELETE /api/org/me/members/{userId}`
- `OrgController#suspendMember`, `changeMemberRole`
- `OrgController#createInvitation` — `POST /api/org/invite`
- `OrgController#getAuditLogs` — `GET /api/org/me/audit-logs`
- `OrgController#updateExternalSharing` — `PUT /api/org/me/settings/external-sharing` (V198 — must be ORG_ADMIN of own org)
- `OrgController#adjustSeats` — `POST /api/org/me/seats/adjust`
- `ProjectController#archive`, `unarchive`, `softDelete`, `restore`, `addMember`, `removeMember`, `updateSettings`
- `SlackIntegrationController#install`, `disconnect`, `listChannels`, `addProjectChannel`

**Method-level multi-role gate** (e.g. `hasAnyRole('ORG_ADMIN','SUPER_ADMIN')`):

- `SsoConfigController` — see [`sso-saml-wiring.md`](sso-saml-wiring.md).

**Internal admin (shared-secret, not JWT):**

- `AdminReminderController` — `POST /api/internal/reminder/send`, gated by constant-time compare of `X-Admin-Reminder-Token` header against env-configured shared secret.

### 4.2 Programmatic guards

For row-level access (tenant isolation), declarative `@PreAuthorize` is not sufficient. Three programmatic guards run inline:

- **[`auth/security/RunAccessGuard.java`](../src/main/java/com/navneet/executespec/auth/security/RunAccessGuard.java)** — `requireRunAccess(runId, userDetails)` / `requireRunWriteAccess(...)` / `assertAccess(PipelineRun, ...)`. Throws 401 (no auth), 403 (wrong tenant), 404 (run not found).
- **[`project/security/ProjectAccessGuard.java`](../src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java)** — `assertCanRead(projectId, userDetails)` and `assertCanWrite(projectId, userDetails)`.
- **[`auth/security/RunVisibilitySpec.java`](../src/main/java/com/navneet/executespec/auth/security/RunVisibilitySpec.java)** — for list endpoints; builds a JPA `Specification<PipelineRun>` filtered by global/org role.

### 4.3 The `RunAccessGuard` logic

```
SUPER_ADMIN                        => allowed for any run.
ORG_ADMIN / BILLING_ADMIN          => allowed if run.orgId == userOrgId.
DEVELOPER (org member)             => allowed if run.orgId == userOrgId.
FREE user (no org)                 => allowed only if run.createdByUserId == userId.
```

Write access (`requireRunWriteAccess`) tightens BILLING_ADMIN — they get read-only on runs, plus full read/write on billing.

### 4.4 The `ProjectAccessGuard` logic

```
assertCanRead(projectId, userDetails):
  1. 401 if userDetails is null.
  2. 404 if project not in DB.
  3. SUPER_ADMIN  ->  allow.
  4. project.orgId == user.orgId  ->  allow (org-scoped membership).
  5. project_member row exists for (projectId, userId)  ->  allow (V175 cross-org).
  6. else 403.

assertCanWrite(projectId, userDetails):
  1. assertCanRead(...).
  2. Additionally: reject if project_member.project_role == PROJECT_VIEWER.
```

Org-match wins over membership row. The `project_member.is_external=true` row from V175 lets a user in `org_B` read `project_A` whose org is `org_A`, without any org-membership change.

### 4.5 Known guard-coverage gap

The 2026-05-12 audit identified controllers calling `assertCanRead` on endpoints that mutate state. As of head V199 the following endpoints still bypass `assertCanWrite` for `PROJECT_VIEWER`:

- `ProjectRepoController#linkToProject`, `#unlinkFromProject`, `#updateRelationships`
- `SpecTemplateController#createForProject`, `#delete`
- `ProjectDocumentController#upload`, `#delete`
- `PullRequestController#refreshProjectPrs`
- `ProjectDashboardController#fixTechDebt`, `#dismissPostMergeBar`

A `PROJECT_VIEWER` cross-org collaborator would currently pass these. Closing them is a follow-up; see [`project/security/ProjectAccessGuard.java`](../src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java) doc-comment for the original intent.

---

## 5. `AccessGuardCoverageTest` — the architecture pin

[`src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java`](../src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java) is an ArchUnit test that fails the build when a controller method whose `@RequestMapping` URL contains any of `/runs/{`, `/projects/{`, `/executions/{`, `/launches/{`, `/repos/{` does NOT call one of:

- `RunAccessGuard.<any method>`
- `ProjectAccessGuard.<any method>`
- A verb-named guard (`requireXxx`, `requiresXxx`, `assertXxx`, `ensureXxx`, `checkXxx`, `authorize`, `loadFor*`)

Two named lists carve out exceptions:

### 5.1 `ALLOWLIST` (public-by-design + SUPER_ADMIN-gated)

| Class                              | Method                | Reason                              |
| ---------------------------------- | --------------------- | ----------------------------------- |
| `ProjectSharePublicController`     | x 6 endpoints         | Public read-by-token, no auth.      |
| `SuperAdminController`             | x 2 endpoints         | SUPER_ADMIN class-level annotation. |
| `ExecutespecMigrationController`   | x 1 endpoint          | SUPER_ADMIN class-level annotation. |
| `RolloutRunController`             | x 1 endpoint          | V2 canary smoke probe.              |

### 5.2 `KNOWN_LEGACY_DRIFT`

Empty in code as of the 2026-05-06 entry in the test file. The root `CLAUDE.md` mentions ten remaining legacy guard sites; that count appears to be drift between docs and the file — the test as committed has zero `KNOWN_LEGACY_DRIFT` entries.

### 5.3 The test asserts

1. Every protected-segment endpoint outside `ALLOWLIST + KNOWN_LEGACY_DRIFT` calls a guard.
2. Stale `KNOWN_LEGACY_DRIFT` entries (a method now calling a guard) are flagged so the list shrinks toward zero.

Adding a new endpoint without a guard fails the build.

---

## 6. Cross-org sharing (V175 + V198)

### 6.1 V175 — `ProjectMember.is_external` + `project_invite`

Two tables enable a user in `org_A` to access a project owned by `org_B`:

- `project_member` (already existed; V175 added `is_external BOOLEAN NOT NULL DEFAULT FALSE`).
- `project_invite` (new in V175). Columns: `id, project_id FK CASCADE, email, project_role DEFAULT 'PROJECT_VIEWER', token UNIQUE, invited_by_user_id, invited_org_id, status DEFAULT 'PENDING', expires_at, accepted_at, accepted_by_user_id, revoked_at, revoked_by_user_id, created_at`.

Flow:

1. `POST /api/projects/{id}/invites` (tier PRO+, ORG_ADMIN of own org or SUPER_ADMIN, V198 flag enabled — see §6.3) creates a `project_invite` row with `status=PENDING` and a random URL-safe token.
2. `MailService.sendProjectInviteEmail(...)` mails the invitee the accept URL.
3. `GET /api/invites/preview?token=...` returns the invitation metadata (public, no auth).
4. `POST /api/invites/accept?token=...` (requires JWT auth) verifies the authenticated user's email matches the invited email (case-insensitive), inserts a `project_member` row with `is_external=true`, flips `project_invite.status='ACCEPTED'`.
5. Project owners can list/delete invites via `DELETE /api/projects/{id}/invites/{inviteId}`.

The invitee is allowed in `org_B`'s project rows by `ProjectAccessGuard.assertCanRead` step (5) above — the `project_member` row admits them even though `project.orgId != user.orgId`.

### 6.2 Public share links (V175 — `project_share_link`)

Independent of the invite flow. Anonymous read-only access via signed URL token:

- `POST /api/projects/{id}/share-links` — creates a row in `project_share_link` (token, surface, scope_id, label, expires_at, optional revoked_at, view counter).
- `GET /api/shared/{token}/meta`, `/run`, `/validation-report`, `/code-review`, `/pr-review`, `/architecture` — public, no auth, view counter increments per access.

`tier_config.share_link_active_cap` enforces simultaneous-link limit: FREE = 3, PRO = 25, TEAM+ENT = -1 (unlimited).

### 6.3 V198 — per-org `external_sharing_enabled` flag

[`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java) has `external_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE` (`V198__org_external_sharing_flag.sql`).

`PUT /api/org/me/settings/external-sharing` toggles it. The handler in `OrgController` requires:

- Caller's `globalRole == SUPER_ADMIN`, OR
- Caller's `orgRole == ORG_ADMIN` AND the org being modified is the caller's own org.

`PROJECT_ADMIN` alone is no longer enough to invite cross-org collaborators since V198 — the org's flag must also be on. `OrgService.updateExternalSharing` records an `ORG_EXTERNAL_SHARING_CHANGED` row to `activity_audit` (with hash chain) so the toggle is forensically auditable.

The public-share-link surface (§6.2) is independent of this flag — only the `project_invite` cross-org membership path is gated.

---

## 7. Solo registration → personal org (V162 + V163)

Solo users who register without an org get a personal org auto-provisioned so every code path can assume `orgId != null`.

- **V162** added `org.is_personal BOOLEAN NOT NULL DEFAULT FALSE` and a partial index `WHERE is_personal=TRUE` for the SUPER_ADMIN console to filter them out of "real" org listings.
- **V163** is a PL/pgSQL `DO` block that backfills: for every `app_user` with `org_id IS NULL AND status='ACTIVE' AND email_verified=TRUE`, it inserts a personal org (`tier=user.tier`, `is_personal=TRUE`, `key_source_mode='HYBRID'`, `domain_enforcement_mode='OPEN'`, `block_common_domains=FALSE`, `seat_count=1`) and an `org_member` row with `org_role='ORG_ADMIN'`.

Runtime equivalent is `OrgService.bootstrapPersonalOrg(userId)`, idempotent, called from `AuthService.register` and `AuthService.findOrCreateFromOAuth`. The user becomes `ORG_ADMIN` of their personal org.

`TierPermissionService.getPermissions` checks `org.is_personal` and routes personal-org users through `freeUserPermissions` so the personal-org doesn't accidentally inherit TEAM/ENTERPRISE billing-admin capabilities.

---

## 8. RBAC reference matrix (selected endpoints)

| Endpoint                                                | Tier gate          | Role gate                                                |
| ------------------------------------------------------- | ------------------ | -------------------------------------------------------- |
| `POST /api/auth/register`                               | none               | none (public)                                            |
| `POST /api/auth/login`                                  | none               | none (public)                                            |
| `GET /api/auth/me`                                      | any                | authenticated                                            |
| `GET /api/auth/me/permissions`                          | any                | authenticated                                            |
| `GET /api/auth/me/data-export`                          | any                | authenticated; SUPER_ADMIN for others' export            |
| `POST /api/runs`                                        | tier-driven quota  | authenticated; quota enforced by `TierEnforcementService`|
| `POST /api/runs/{id}/approve`                           | none               | `RunAccessGuard.assertWriteAccess`                       |
| `DELETE /api/runs/{id}` (cancel)                        | none               | `RunAccessGuard.assertWriteAccess`                       |
| `GET /api/projects`                                     | any                | authenticated                                            |
| `POST /api/projects`                                    | tier-cap V140      | `DEVELOPER` or `ORG_ADMIN` or `SUPER_ADMIN`              |
| `PUT /api/projects/{id}/archive`                        | none               | `ORG_ADMIN`                                              |
| `POST /api/projects/{id}/invites` (V175 cross-org)      | PRO+               | `ORG_ADMIN` of own org or `SUPER_ADMIN`; V198 flag on    |
| `POST /api/projects/{id}/share-links`                   | tier-cap V175      | `ProjectAccessGuard.assertCanRead` (any project member)  |
| `PUT /api/org/me/settings/external-sharing` (V198)      | none               | `ORG_ADMIN` of own org or `SUPER_ADMIN`                  |
| `POST /api/org/me/seats/adjust` (TEAM only)             | TEAM               | `ORG_ADMIN`                                              |
| `PUT /api/pricing/{provider}/{model}`                   | none               | `SUPER_ADMIN`                                            |
| `GET /api/admin/audit-logs`                             | none               | `SUPER_ADMIN`                                            |
| `POST /api/admin/grant-runs`                            | none               | `SUPER_ADMIN`                                            |
| `POST /api/admin/projects/{id}/migrate-codemaps`        | none               | `SUPER_ADMIN`                                            |
| `POST /api/pr-reviews`                                  | PRO+ (V172)        | `ProjectAccessGuard.assertCanRead`                       |
| `POST /api/code-reviews`                                | PRO+ (V172)        | `ProjectAccessGuard.assertCanRead`                       |
| `POST /api/deployments`                                 | `can_deploy=true`  | `RunAccessGuard.assertWriteAccess`                       |
| `GET /api/admin/analytics/*`                            | none               | `SUPER_ADMIN`, `ORG_ADMIN` (own org), `BILLING_ADMIN`    |
| `POST /api/webhooks/{razorpay,stripe}`                  | none               | HMAC signature (no JWT)                                  |
| `POST /webhooks/github`                                 | none               | HMAC SHA-256 (no JWT)                                    |
| `GET /api/runs/{id}/events` (SSE)                       | none               | 5-min scoped SSE token                                   |
| `GET /api/shared/{token}/*`                             | none               | public-by-token                                          |
| `POST /api/internal/reminder/send`                      | none               | shared-secret header                                     |

Public path matchers are declared in [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) `filterChain(HttpSecurity)`. Everything not on the public list defaults to `authenticated`.

---

## 9. Audit trail

Every privileged action emits an `activity_audit` row (V22 baseline + V147 SHA-256 hash chain + V151 column widen). [`auth/service/ActivityAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java) is the only writer; it serialises every write through a JVM monitor, reads the chain tail, computes `row_hash = SHA-256(prev_hash || canonical_row_content)`, and assigns a monotonic `chain_seq` from `activity_audit_chain_seq`. Tamper-evidence: a forensic auditor can walk every row, recompute hashes, and detect any inserted, deleted, or modified row.

`GET /api/admin/audit-chain/verify` returns a `ChainVerifyReport` with the first break (if any). `KNOWN_LEGACY_DRIFT` ArchUnit pin (§5.2) is the structural complement of this runtime check.

---

## 10. Files referenced

- [`auth/entity/AppUser.java`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java)
- [`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java)
- [`auth/entity/OrgMember.java`](../src/main/java/com/navneet/executespec/auth/entity/OrgMember.java)
- [`auth/entity/ProjectMember.java`](../src/main/java/com/navneet/executespec/auth/entity/ProjectMember.java)
- [`auth/entity/TierConfig.java`](../src/main/java/com/navneet/executespec/auth/entity/TierConfig.java)
- [`auth/entity/ActivityAudit.java`](../src/main/java/com/navneet/executespec/auth/entity/ActivityAudit.java)
- [`auth/entity/RefreshTokenBlocklist.java`](../src/main/java/com/navneet/executespec/auth/entity/RefreshTokenBlocklist.java)
- [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java)
- [`auth/security/AppUserDetails.java`](../src/main/java/com/navneet/executespec/auth/security/AppUserDetails.java)
- [`auth/security/JwtUtil.java`](../src/main/java/com/navneet/executespec/auth/security/JwtUtil.java)
- [`auth/security/JwtAuthenticationFilter.java`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java)
- [`auth/security/JsonAuthenticationEntryPoint.java`](../src/main/java/com/navneet/executespec/auth/security/JsonAuthenticationEntryPoint.java)
- [`auth/security/UserDetailsServiceImpl.java`](../src/main/java/com/navneet/executespec/auth/security/UserDetailsServiceImpl.java)
- [`auth/security/RunAccessGuard.java`](../src/main/java/com/navneet/executespec/auth/security/RunAccessGuard.java)
- [`auth/security/RunVisibilitySpec.java`](../src/main/java/com/navneet/executespec/auth/security/RunVisibilitySpec.java)
- [`project/security/ProjectAccessGuard.java`](../src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java)
- [`auth/service/AuthService.java`](../src/main/java/com/navneet/executespec/auth/service/AuthService.java)
- [`auth/service/TierEnforcementService.java`](../src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java)
- [`auth/service/TierPermissionService.java`](../src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java)
- [`auth/service/OrgService.java`](../src/main/java/com/navneet/executespec/auth/service/OrgService.java)
- [`auth/service/InvitationService.java`](../src/main/java/com/navneet/executespec/auth/service/InvitationService.java)
- [`auth/service/ActivityAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java)
- [`auth/service/ProjectService.java`](../src/main/java/com/navneet/executespec/auth/service/ProjectService.java)
- [`auth/controller/AuthController.java`](../src/main/java/com/navneet/executespec/auth/controller/AuthController.java)
- [`auth/controller/OrgController.java`](../src/main/java/com/navneet/executespec/auth/controller/OrgController.java)
- [`auth/controller/ProjectController.java`](../src/main/java/com/navneet/executespec/auth/controller/ProjectController.java)
- [`auth/controller/SuperAdminController.java`](../src/main/java/com/navneet/executespec/auth/controller/SuperAdminController.java)
- [`auth/oauth/OAuthSuccessHandler.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthSuccessHandler.java)
- [`auth/oauth/OAuthUserMapper.java`](../src/main/java/com/navneet/executespec/auth/oauth/OAuthUserMapper.java)
- [`auth/sso/SsoConfigController.java`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java) — see [`sso-saml-wiring.md`](sso-saml-wiring.md)
- [`src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java`](../src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java)
- Migrations: V14, V21, V22, V147, V151, V162, V163, V172, V175, V198, V199 (paths under `src/main/resources/db/migration/`).
