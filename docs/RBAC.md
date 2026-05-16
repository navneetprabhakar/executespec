# RBAC — Role-Based Access Control

> Last refreshed: 2026-05-16 against the `feature/ux-overhaul-2026-05-14` branch.
> Source of truth: code under `src/main/java/com/navneet/executespec/auth/`.

This document describes the authorisation model exactly as it is implemented in
code. Every claim cites the file that backs it. Where a claim is *contractual*
or *policy* rather than *code-enforced*, it is marked as such.

---

## 1. Two orthogonal axes

ExecuteSpec separates authorisation into two independent axes:

| Axis        | Question it answers              | Backing column                                                                                  |
| ----------- | -------------------------------- | ----------------------------------------------------------------------------------------------- |
| **Tier**    | What features are unlocked?      | `app_user.tier`, `org.tier` (`FREE | PRO | TEAM | ENTERPRISE`)                                  |
| **Role**    | What actions may the user take?  | `app_user.global_role`, `org_member.org_role`, `project_member.project_role`                    |

A FREE user can be a `SUPER_ADMIN` (rare, internal). An ENTERPRISE-tier user
can be a `PROJECT_VIEWER` (read-only on a single project). The two never
combine into a single grant — the tier governs feature availability, role
governs the verb.

This document covers the **role** axis. For tier-gated features see
`docs/enterprise-one-pager.md` and `TierPermissionService`
(`src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java`).

---

## 2. The seven roles

| Role                | Sphere    | Stored on                       | Default? |
| ------------------- | --------- | ------------------------------- | -------- |
| `SUPER_ADMIN`       | global    | `app_user.global_role`          | no       |
| `USER`              | global    | `app_user.global_role`          | yes      |
| `ORG_ADMIN`         | org-scope | `org_member.org_role`           | no       |
| `BILLING_ADMIN`     | org-scope | `org_member.org_role`           | no       |
| `DEVELOPER`         | org-scope | `org_member.org_role`           | yes      |
| `PROJECT_ADMIN`     | project   | `project_member.project_role`   | no       |
| `PROJECT_DEVELOPER` | project   | `project_member.project_role`   | yes      |
| `PROJECT_VIEWER`    | project   | `project_member.project_role`   | no (V175)|

`SUPER_ADMIN` is an operator/staff role. The `USER` default is what every
self-registered account gets at `AuthService.register`
(`AppUser.builder().globalRole("USER")` —
`src/main/java/com/navneet/executespec/auth/service/AuthService.java:79`).
There is also a third global value `FINANCE` referenced by
`TierPermissionService.financePermissions` but it is not seeded in any
migration and is read-only-by-design — treat it as an internal back-office
role.

### Storage details

- **Global role** lives on the `app_user.global_role` column
  (`V4__user_auth_and_org.sql:11`). The column is `VARCHAR(32) NOT NULL DEFAULT
  'USER'`. The entity is `auth/entity/AppUser.java:37-39`.
- **Org role** lives on `org_member.org_role`
  (`V4__user_auth_and_org.sql:60-61`). The entity is
  `auth/entity/OrgMember.java:30-33`. Defaults to `DEVELOPER`.
- **Project role** lives on `project_member.project_role`
  (`V4__user_auth_and_org.sql:112` extended by `V175__external_project_sharing.sql:38`
  to include `PROJECT_VIEWER`). The entity is `auth/entity/ProjectMember.java:30-33`.

### Spring authorities loaded per request

`AppUserDetails.getAuthorities`
(`auth/security/AppUserDetails.java:28-37`) emits **two** authorities for an
authenticated user:

1. `ROLE_<globalRole>` — always present (e.g. `ROLE_USER`, `ROLE_SUPER_ADMIN`).
2. `ROLE_<orgRole>` — present only when `OrgMember` is non-null for the user's
   current `orgId` (e.g. `ROLE_ORG_ADMIN`, `ROLE_DEVELOPER`).

**Project roles are NOT loaded into Spring authorities.** They are checked at
the controller / service layer via `ProjectAccessGuard.assertCanRead` /
`assertCanWrite` rather than via `@PreAuthorize("hasRole('PROJECT_VIEWER')")`.
See §6 below.

---

## 3. The JWT carries org context

Tokens are issued by `JwtUtil.generateAccessToken(AppUser, OrgMember)`
(`auth/security/JwtUtil.java:45-66`). The claims are:

| Claim        | Value                                       |
| ------------ | ------------------------------------------- |
| `sub`        | user id (`AppUser.id`)                      |
| `email`      | user email                                  |
| `globalRole` | `AppUser.global_role`                       |
| `tier`       | `AppUser.tier` (FREE / PRO / TEAM / ENT)    |
| `orgId`      | `OrgMember.org_id` (or null if not in org)  |
| `orgRole`    | `OrgMember.org_role` (or null)              |
| `iat`        | issued-at (1-second resolution)             |
| `exp`        | expiry (default 360 minutes — `app.jwt.access-token-expiry-minutes` in `application.yml:697`) |

The token's `orgId` / `orgRole` claims are informational only — at each
request the filter re-loads the user and the org-member row
(`JwtAuthenticationFilter` → `UserDetailsServiceImpl.loadUserById` →
`AppUserDetails(user, orgMember)`). Role changes therefore propagate on the
next request without needing a token refresh.

---

## 4. Global role grants (`SUPER_ADMIN`)

`SUPER_ADMIN` is enforced via Spring Security's `@PreAuthorize("hasRole('SUPER_ADMIN')")`
or via URL-pattern rules in `SecurityConfig`.

`SecurityConfig.filterChain` lines 167-172 (`auth/security/SecurityConfig.java`)
enforce the URL pattern:

```
/actuator/**          → hasRole("SUPER_ADMIN")   (except /actuator/health, /actuator/prometheus)
/api/admin/**         → hasRole("SUPER_ADMIN")
```

Method-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` is applied at the
following classes:

| Controller                                | Path prefix                       |
| ----------------------------------------- | --------------------------------- |
| `auth/controller/SuperAdminController.java` | `/api/admin`                    |
| `config/ConfigAdminController.java`         | `/api/admin/config`             |
| `readymade/controller/ReadymadeAdminController.java` | `/api/admin/readymades` |
| `folder/ExecutespecMigrationController.java` | `/api/admin/executespec`       |
| `programbench/ProgramBenchController.java`  | `/api/admin/programbench`       |
| `migration/controller/MigrationAdminController.java` | `/api/admin/migration` |
| `analytics/ActivationFunnelController.java` | `/api/admin/analytics`          |
| `swebench/SwebenchController.java`          | `/api/admin/swebench`           |
| `billing/lifecycle/TierLifecycleController.java` | `/api/admin/tiers`         |
| `billing/controller/PaymentAuditController.java` | `/api/admin/payment-audit` |

`SUPER_ADMIN` also short-circuits every project access check
(`ProjectAccessGuard.assertCanRead`
`src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java:62-64`).

There is one act-on-behalf endpoint: `GET /api/dsar/users/{userId}`
(`auth/dsar/DsarController.java:82-83`) exports another user's data for
legal-team requests. It is logged at WARN with the admin's id.

---

## 5. Org-scoped role grants

Org roles guard the org membership graph and shared org settings (AI keys,
SSO, integrations, sharing flags). Every endpoint annotated
`@PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")` is on
`OrgController` (path prefix `/api/org`).

The full list from grepping `@PreAuthorize` on `auth/controller/OrgController.java`:

| Method | Path                                            | Notes                                    |
| ------ | ----------------------------------------------- | ---------------------------------------- |
| PUT    | `/api/org/me`                                   | Update org name + domain patterns        |
| DELETE | `/api/org/me/members/{userId}`                  | Remove a member                          |
| PUT    | `/api/org/me/members/{userId}/suspend`          | Suspend/unsuspend a member               |
| PUT    | `/api/org/me/members/{userId}/role`             | Change member's `org_role`               |
| POST   | `/api/org/invite`                               | Invite by email (same-org)               |
| GET    | `/api/org/me/invitations`                       | List pending org invitations             |
| DELETE | `/api/org/me/invitations/{id}`                  | Revoke org invitation                    |
| POST   | `/api/org/me/invitations/{id}/resend`           | Resend invitation email                  |
| GET    | `/api/org/me/audit-logs`                        | Org-scoped audit log query               |
| PUT    | `/api/org/me/settings/key-source`               | OWN_KEYS / HYBRID / SYSTEM mode + provider allowlist |
| PUT    | `/api/org/me/settings/external-sharing`         | **V198** toggle for cross-org invites    |
| PUT    | `/api/org/me/settings/ai-keys`                  | Org-level LLM API keys                   |
| PUT    | `/api/org/me/settings/jira`                     | Org-level JIRA creds                     |
| POST   | `/api/org/me/settings/jira/validate`            | Test JIRA creds before save              |
| PUT    | `/api/org/me/settings/confluence`               | Org-level Confluence creds               |
| POST   | `/api/org/me/settings/confluence/validate`      | Test Confluence creds                    |
| POST   | `/api/org/me/seats/adjust`                      | TEAM tier — change seat count            |

SSO-related endpoints share the same gate but are owned by
`auth/sso/SsoConfigController.java`:

| Method | Path                       | Annotation                                              |
| ------ | -------------------------- | ------------------------------------------------------- |
| GET    | `/api/auth/sso/config`     | `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")` |
| PUT    | `/api/auth/sso/config`     | same                                                    |
| DELETE | `/api/auth/sso/config`     | same                                                    |

`OrgController#createOrg` (POST `/api/org`) is **not** guarded by `@PreAuthorize`
— anyone authenticated can attempt to create an org. The tier gate lives inside
`OrgService.createOrg` (FREE-tier users are rejected there). Solo users get a
personal org auto-created on signup via `OrgService.bootstrapPersonalOrg` so
they don't need this surface in normal flows.

### `BILLING_ADMIN`

Only one explicit grant: `GET /api/billing/payment-history` allows
`@PreAuthorize("hasAnyRole('ORG_ADMIN', 'BILLING_ADMIN', 'SUPER_ADMIN')")`
(`billing/controller/PaymentAuditController.java:78`).

The role exists for separation-of-duties on procurement-grade orgs (finance
team needs invoice access without org-write authority). The role is not seeded
in any migration; it is granted manually by an ORG_ADMIN via the
member-role-change endpoint.

### `DEVELOPER`

The default org role for any invited member. The only `@PreAuthorize` hit is
in `repo/GitHubAppController.java`:

```
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','DEVELOPER','PROJECT_ADMIN')")
public ResponseEntity<?> listInstallationRepos(...)
```

Every other DEVELOPER capability is enforced at the service layer (via
`ProjectAccessGuard`) or at the tier-permission layer (via
`TierPermissionService`).

### `FINANCE`

Internal-only global role (not org-scoped). Read-only access to invoices,
accounting, audit logs. Built into `TierPermissionService.financePermissions`
(`auth/service/TierPermissionService.java:153-182`). No public registration
path; granted by SUPER_ADMIN via direct DB write or a future admin tool. Not
referenced by any `@PreAuthorize`.

---

## 6. Project-scoped access via `ProjectAccessGuard`

Project roles (`PROJECT_ADMIN`, `PROJECT_DEVELOPER`, `PROJECT_VIEWER`) are
**not** loaded into Spring Security authorities. Enforcement is procedural via
`ProjectAccessGuard.assertCanRead` / `assertCanWrite`
(`project/security/ProjectAccessGuard.java`).

### `assertCanRead(projectId, userDetails)` semantics

Return the `Project` if **any** of these hold; throw 403/404 otherwise:

1. The project does not exist → `404 Not Found` (avoids leaking existence to
   non-members).
2. Caller has global role `SUPER_ADMIN` → admit.
3. Caller's `org_id` equals the project's `org_id` → admit (default in-org
   case).
4. Caller has a `project_member` row for this project — the **V175 cross-org
   path** — → admit regardless of `is_external` or `project_role`.

If none match → `403 Forbidden`.

### `assertCanWrite(projectId, userDetails)` semantics

Inherits everything from `assertCanRead` AND additionally:

- A `project_member` row with `project_role = 'PROJECT_VIEWER'` → `403`. The
  V175 read-only role cannot mutate the project.
- In-org members and SUPER_ADMIN bypass the viewer check (in-org members'
  authority comes from their org membership, not their project_member row).

### Where the guard is wired

`ProjectAccessGuard` is injected by these controllers (verified by grep):

- `sharing/controller/ProjectInviteController.java` — `assertCanWrite` on
  create/revoke, `assertCanRead` on list.
- `sharing/controller/ProjectShareLinkController.java`.
- Plus other project-scoped controllers (full coverage pinned by
  `AccessGuardCoverageTest`, see §8).

### What the guard does NOT do

- It does not check `PROJECT_ADMIN` vs `PROJECT_DEVELOPER` granularity. In-org
  members effectively act as `PROJECT_ADMIN` by virtue of their `ORG_ADMIN`
  or `DEVELOPER` org role. Cross-org members get whatever `project_role` was
  recorded on their `project_member` row at invite-accept time
  (`ProjectInviteService.accept` —
  `sharing/service/ProjectInviteService.java:257-265`).
- It does not call `@PreAuthorize` reflectively. The guard throws
  `ResponseStatusException` directly.

---

## 7. Cross-org sharing (V175 + V198)

External collaboration goes through two surfaces, both gated by tier,
both backed by the `project_invite` and `project_share_link` tables.

### Cross-org `ProjectMember` (V175)

A user from a different org gets a `project_member` row with `is_external =
TRUE` (`V175__external_project_sharing.sql:31-32`). The flow is:

1. ORG_ADMIN of the host org calls `POST /api/projects/{id}/invites` with an
   email + role.
2. `ProjectInviteService.assertCanInvite`
   (`sharing/service/ProjectInviteService.java:99-147`) checks **three** gates,
   updated by V198:
   - **Tier gate**: caller's tier must be in `{PRO, TEAM, MAX, ENTERPRISE}`.
     FREE → 403 with "Inviting external collaborators is a paid feature".
   - **Role gate (V198)**: caller must either have global role
     `SUPER_ADMIN`, OR be `ORG_ADMIN` of their own org (per the `org_member`
     row). `PROJECT_ADMIN` is **not** enough — adding outsiders is treated as
     an org-level decision. SUPER_ADMIN bypasses the role gate.
   - **Org-flag gate (V198)**: the caller's org must have
     `external_sharing_enabled = TRUE` (column added by
     `V198__org_external_sharing_flag.sql`). Default is `FALSE`. SUPER_ADMIN
     bypasses this gate too.
3. Recipient clicks the magic link → `POST /api/invites/accept?token=…`.
4. Email match is enforced (`invite.email.equalsIgnoreCase(caller.email)` —
   `ProjectInviteService.accept:249-253`). On match, a `ProjectMember` row
   with `is_external = TRUE` is created with the role recorded on the invite.
5. The invite row flips to `ACCEPTED` and is no longer redeemable.

`OrgController#updateExternalSharing`
(`auth/controller/OrgController.java:228-237`) flips the V198 flag. Body:
`{ "enabled": true | false }`. Audit row of type
`ORG_EXTERNAL_SHARING_CHANGED` is emitted via `OrgService.updateExternalSharing`.

### Public share links (`ProjectShareLink`)

Independent of V198 — share links bypass `ProjectAccessGuard` entirely. The
URL token is the bearer. Tier-gated by
`tier_config.share_link_active_cap` (FREE=3, PRO=25, TEAM/MAX/ENTERPRISE=-1
unlimited). Created via `POST /api/projects/{id}/share-links`; consumed via
`GET /api/shared/{token}/…`.

The public reads are whitelisted in `SecurityConfig` (lines 193-195):

```
.requestMatchers(HttpMethod.GET, "/api/shared/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/invites/preview").permitAll()
```

`ProjectSharePublicController` is the only controller behind `/api/shared/**`
and is explicitly allowlisted in `AccessGuardCoverageTest` because the bearer
is the URL token, not the JWT.

---

## 8. `AccessGuardCoverageTest` — build-time enforcement

`src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java` is
an ArchUnit test that pins every controller method whose URL contains a
tenant-bound path segment (`/runs/{`, `/projects/{`, `/executions/{`,
`/launches/{`, `/repos/{`) and asserts that the handler body calls a
tenant access guard — either `RunAccessGuard` / `ProjectAccessGuard` directly,
or any method matching the regex `^(require|requires|assert|ensure|check|authorize|loadFor|load[A-Z][a-zA-Z]*For)[A-Z][a-zA-Z]*$`.

### `ALLOWLIST` (whitelisted, with justification)

| Entry                                                                         | Justification                       |
| ----------------------------------------------------------------------------- | ----------------------------------- |
| `ProjectSharePublicController#getMeta`                                        | Public-by-design — URL token bearer |
| `ProjectSharePublicController#getRun`                                         | Same                                |
| `ProjectSharePublicController#getValidationReport`                            | Same                                |
| `ProjectSharePublicController#getCodeReview`                                  | Same                                |
| `ProjectSharePublicController#getPrReview`                                    | Same                                |
| `ProjectSharePublicController#getArchitecture`                                | Same                                |
| `SuperAdminController#forceFailTask`                                          | SUPER_ADMIN gated by URL pattern    |
| `SuperAdminController#reReviewRun`                                            | Same                                |
| `ExecutespecMigrationController#migrate`                                      | Same                                |
| `RolloutRunController#poll`                                                   | V2 canary surface, separate storage |

### `KNOWN_LEGACY_DRIFT`

**Empty as of 2026-05-06.** All 35 legacy guard-drift sites have been closed.
The test now serves as a **forward-protective** wall: any new endpoint
binding a run/project id must call a guard or fail the build with a clear
message.

The test file's docstring records the migration history (Batch 1 / 2 / 3
breakdown of which controllers were swept), so historical intent is preserved.

> **Note** — The project-level `CLAUDE.md` references "10 legacy guard sites"
> in `KNOWN_LEGACY_DRIFT`. The code (`AccessGuardCoverageTest.java:154`)
> currently shows an empty set, suggesting the migration completed on this
> branch but the root doc hasn't caught up. Treat code as authoritative.

---

## 9. Personal org auto-bootstrap (V162 / V163)

Every self-registered user gets a personal org provisioned by
`OrgService.bootstrapPersonalOrg`, called from:

- `AuthService.register` (line 98) — for email + password signups.
- `AuthService.findOrCreateFromOAuth` (line 587) — for new Google / GitHub
  OAuth users.

The personal org carries:

- `org.is_personal = TRUE` (V162 column —
  `V162__org_is_personal.sql`).
- The user is added as `OrgMember(orgId, userId, orgRole = "ORG_ADMIN")` of
  their personal org.
- `app_user.org_id` is set to the personal org id.

This means **every authenticated user has a non-null `orgId`** post-V162, and
their `org_role` is `ORG_ADMIN` of their personal org. The reason: the
project-creation endpoint (`POST /api/projects`) requires
`hasRole('ORG_ADMIN') or hasRole('DEVELOPER') or hasRole('SUPER_ADMIN')`, and
without a personal org a freshly-registered user would hit 403 trying to
create their first project.

`V163__backfill_personal_orgs.sql` retroactively created personal orgs for
pre-V162 users. Personal orgs are filtered out of org switchers, invite
flows, and billing seat counts via `Org.is_personal = TRUE` checks. They
cannot be invited into (`OrgService.invite` rejects with 422 on
`is_personal=true`).

A side effect: `TierPermissionService.getPermissions`
(`auth/service/TierPermissionService.java:104-107`) routes
personal-org users through the **solo-user** permission branch
(`freeUserPermissions`) instead of the multi-member `orgMemberPermissions`
branch. Without this, the [F-03] tier-driven solo features (deploy gating,
cost-breakdown visibility, billing-profile management) would be undone by
the role-driven org branch.

---

## 10. Tier ↔ role interaction examples

| Scenario                                          | Tier        | Global role | Org role        | Project role   | Outcome                                              |
| ------------------------------------------------- | ----------- | ----------- | --------------- | -------------- | ---------------------------------------------------- |
| Solo FREE registers, creates first project        | FREE        | USER        | ORG_ADMIN (personal) | n/a       | Project created — `ProjectAccessGuard` admits via org-id match |
| Solo FREE tries to invite an outside email        | FREE        | USER        | ORG_ADMIN (personal) | n/a       | 403 — `ProjectInviteService.assertCanInvite` tier gate |
| Solo TEAM enables external sharing then invites   | TEAM        | USER        | ORG_ADMIN (personal) | n/a       | Invite created; recipient gets cross-org PROJECT_VIEWER |
| Multi-member PRO org, DEVELOPER tries to invite   | PRO         | USER        | DEVELOPER       | n/a            | 403 — V198 role gate ("only an org admin")           |
| ENTERPRISE org, PROJECT_ADMIN tries to invite     | ENTERPRISE  | USER        | DEVELOPER       | PROJECT_ADMIN  | 403 — V198 role gate (PROJECT_ADMIN alone insufficient) |
| Cross-org PROJECT_VIEWER opens project run page   | any (host)  | USER        | (own org's)     | PROJECT_VIEWER | Read OK via `ProjectAccessGuard` member-row branch   |
| Cross-org PROJECT_VIEWER tries to launch run      | any (host)  | USER        | (own org's)     | PROJECT_VIEWER | 403 — `ProjectAccessGuard.assertCanWrite`            |
| SUPER_ADMIN on FREE tier opens any project        | FREE        | SUPER_ADMIN | any             | none           | Read OK — short-circuit in line 62 of the guard      |

---

## 11. Session invalidation paths

Two mechanisms can invalidate an issued JWT before its natural expiry:

1. **Per-user**: `AppUser.token_invalidated_at` (column added separately —
   see `auth/entity/AppUser.java:188-190`). `JwtUtil.isTokenValid` rejects any
   token whose `iat` is before this timestamp. Set via
   `POST /api/auth/invalidate-sessions` (`AuthController:148-161`).
2. **Global epoch**: the `force_logout_epoch` feature flag holds an ISO
   instant. Any token issued before that instant is rejected
   (`JwtUtil.isTokenValid:135-146`). Set by SUPER_ADMIN via
   `/api/admin/feature-flags`.

Refresh tokens add a third path: `RefreshTokenBlocklist` (table created in
`V4__user_auth_and_org.sql:137-144`). On `POST /api/auth/logout`, the refresh
token's `jti` is inserted into `refresh_token_blocklist`; subsequent refresh
attempts with the same `jti` fail with 401.

---

## 12. References

- `auth/entity/AppUser.java` — user identity, tier, global role.
- `auth/entity/Org.java` — org tier, SSO config (V127), external sharing flag
  (V198), `is_personal` (V162).
- `auth/entity/OrgMember.java` — org_role mapping.
- `auth/entity/ProjectMember.java` — project_role + `is_external` (V175).
- `auth/security/SecurityConfig.java` — filter chain, URL allowlist.
- `auth/security/JwtUtil.java` — token issuance + validation + per-user +
  global epoch invalidation.
- `auth/security/AppUserDetails.java` — Spring authority mapping.
- `auth/sso/SsoConfigController.java` — per-org SSO CRUD (see
  `docs/sso-saml-wiring.md`).
- `auth/service/TierPermissionService.java` — capability matrix per
  tier × role.
- `project/security/ProjectAccessGuard.java` — project read/write gates.
- `sharing/service/ProjectInviteService.java` — V175 + V198 invite gates.
- `src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java`
  — build-time enforcement.

### Relevant Flyway migrations

| Migration                                     | Effect                                                                |
| --------------------------------------------- | --------------------------------------------------------------------- |
| `V4__user_auth_and_org.sql`                   | Initial user / org / org_member / project / project_member / email_token / refresh_token_blocklist |
| `V6__tier_config_and_org_limits.sql`          | `tier_config` table seed; per-org `monthly_run_limit_override`, `api_keys_provided` |
| `V11__org_api_keys.sql`                       | Org-level LLM API key columns (encrypted)                             |
| `V18__org_integration_settings.sql`           | Per-org JIRA / Confluence integration columns                         |
| `V21__tier_permissions.sql`                   | `tier_config` feature columns (`max_org_members`, `max_chat_messages`, `concurrent_run_limit`) + org `domain_enforcement_mode`, `max_members`, `block_common_domains` |
| `V47__oauth_support.sql`                      | `app_user.oauth_provider`, `oauth_id`, `avatar_url` + password_hash nullable |
| `V123__team_billing_plans_and_org_seats.sql`  | TEAM tier per-seat billing schema                                     |
| `V127__wave3_sso_githubapp_trial.sql`         | Org SSO columns + GitHub App install id + team_trial_request table    |
| `V153__referral_invite.sql`                   | Referral signup invites                                               |
| `V162__org_is_personal.sql`                   | `org.is_personal` for solo-user bootstrap                             |
| `V163__backfill_personal_orgs.sql`            | Retroactive personal-org creation for pre-V162 users                  |
| `V175__external_project_sharing.sql`          | `project_member.is_external`, `project_invite` table, `project_share_link` table, `tier_config.share_link_active_cap` |
| `V198__org_external_sharing_flag.sql`         | `org.external_sharing_enabled` for cross-org invite gate              |
