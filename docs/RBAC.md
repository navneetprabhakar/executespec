# RBAC And Access Control

> Current access-control contract. Tier gates decide what features a user may use; role and tenant guards decide which rows they may touch.

## Axes

| Axis | Values | Purpose |
| --- | --- | --- |
| Tier | `FREE`, listed `PRO`, `TEAM`, `ENTERPRISE` | Product entitlement, quotas, caps, BYOK/model controls. |
| Global role | `SUPER_ADMIN`, `USER` (`AppUser.globalRole`) | Operator/admin capabilities. |
| Org role | `ORG_ADMIN`, `BILLING_ADMIN`, `DEVELOPER` (`OrgMember.orgRole`) | Org settings, members, billing, model matrix. |
| Project role | `PROJECT_ADMIN`, `PROJECT_DEVELOPER`, `PROJECT_VIEWER` (`ProjectMember.projectRole`) | Project row access and project-scoped actions. |

The seven role values across these axes are `SUPER_ADMIN`, `ORG_ADMIN`, `BILLING_ADMIN`, `DEVELOPER`, `PROJECT_ADMIN`, `PROJECT_DEVELOPER`, `PROJECT_VIEWER`.

## Tier Rules

- FREE: fixed default model, limited monthly runs, no model matrix.
- PRO: listed self-serve tier, managed keys, BYOK lifts the per-run cost cap.
- TEAM: self-serve BYOK-capable tier, 1-20 seats.
- ENTERPRISE: org-level model overrides, aggregator org keys, custom limits.

Tier limits are enforced in `TierEnforcementService`, `BillingService`, `OrgService`, and `tier_config`.

Effective tier is the highest rank across the user's personal tier, their org tier, and any active external project memberships' host-org tiers. `TierEnforcementService.resolveUserTier` and `EntitlementResolver.resolveTier` both fold in `ProjectMemberRepository.findActiveExternalHostOrgTiersByUserId`, so an accepted external collaborator inherits the host project's entitlement without mutating `app_user.tier`. Removing the `ProjectMember` row falls the user back to their own tier.

## Project Access

Project access must go through `ProjectAccessGuard` or equivalent controller guard:

| Action | Required guard |
| --- | --- |
| Read project/run/report/code-review/PR-review data | `assertCanRead` |
| Create/update/delete/link/upload/dispatch/fix/share | `assertCanWrite` |
| Public share-link read | share-token validation plus scope ownership |
| Cross-org invite acceptance | token/email validation plus `ProjectMember` row |

`ProjectAccessGuard.assertCanRead` admits the caller on a project-org match, on SUPER_ADMIN, or on a `ProjectMember` row (the cross-org collaborator path). `assertCanWrite` adds the same admission but rejects a `PROJECT_VIEWER` member — `PROJECT_VIEWER` is read-only and cross-org members must pass the per-`ProjectMember` role check to write.

Accepted external (cross-org) collaborators read the host project's surfaces, not just the project row:

- project-filtered `GET /api/runs` validates `ProjectAccessGuard.assertCanRead(projectId, caller)` then lists that project's runs;
- linked repos under `GET /api/projects/{id}/repos` honour `ProjectMember` rows;
- the repo file-browser endpoints `GET /api/projects/{id}/repos/{repoId}/{files,file}` read GitHub source through the user who linked the repo, falling back to the caller's PAT, so private host repos are visible to invitees who lack direct GitHub access;
- project chat (`/api/projects/{id}/chat`) admits `ProjectMember` callers and gates on the host project org tier, writing chat rows under the host project org.

## Run Access

Run reads and writes are tenant-scoped through `RunAccessGuard`. Run ownership fields are stamped before async pipeline work starts:

- `createdByUserId`
- `orgId`
- `projectId`

`assertAccess` (read) admits SUPER_ADMIN, an org admin/developer of the run's org, the run owner, or a caller holding a `ProjectMember` row for the run's `projectId`. `assertWriteAccess` is stricter: only SUPER_ADMIN, an ORG_ADMIN of the run's org, or the run owner may mutate a run — a cross-org project member can read a run but cannot retry/cancel/edit it.

Run event streams use scoped SSE tokens.

Local-hands run submit is PAT-only. `POST /api/runs` with `localExecution=true` is rejected (409) while `worker.local-hands.enabled` is OFF, and rejected (403) for browser/JWT sessions even when the flag is ON, because the web UI cannot enforce local command/file approvals. CLI and VS Code submit it over a Personal Access Token session; `JwtAuthenticationFilter` tags the request auth source (`JWT` vs `PAT`) and `RunController` admits the local-hands path only for `PAT`. Accepted submit summaries echo `localExecutionRequested` / `localExecutionAccepted`.

## Org Access

ORG_ADMIN or SUPER_ADMIN is required for:

- org model matrix reads/writes,
- org-level provider keys,
- external sharing flag,
- member/seat management,
- org settings.

ENTERPRISE tier is required for org aggregator keys and model matrix writes.

## Auth Mechanisms

- JWT access token.
- Refresh token.
- OAuth2 login (Google, GitHub). Authorization requests are persisted in short-lived signed HttpOnly cookies keyed by `state` (`CookieOAuth2AuthorizationRequestRepository`), so provider callbacks survive missing/stale/duplicate session cookies and parallel OAuth attempts.
- Personal access token (`es_pat_...`) for CLI/VS Code/CI style clients. Minted via `POST /api/auth/me/tokens` (plaintext returned once), listed/revoked via `GET`/`DELETE /api/auth/me/tokens`; stored SHA-256-hashed, never as plaintext.

PAT auth resolves to the same `AppUserDetails` (identical roles + tenant) as JWT auth. `JwtAuthenticationFilter` tags each authenticated request with an auth source (`JWT` or `PAT`) so server-only capabilities (currently local-hands run submit) can require a PAT session; normal RBAC is unaffected by credential type.

Default AI provider is account-scoped (`app_user.preferred_ai_provider`, read/written via `GET`/`PUT /api/auth/me/provider-preference`) and shared across web, CLI, and VS Code. An explicit per-run/per-chat provider still wins and is remembered; absent input resolves to the account preference before project/YAML defaults.

## Guard Coverage

`AccessGuardCoverageTest` pins protected controller methods so new project/run/repo/execution/launch handlers must call a tenant guard, and write-shaped handlers must use write guards.

## Known Gaps

- Tenant ORM filters (`C3`) remain deferred; app-layer guards are the hard requirement and are currently the tested boundary.
- Some older docs and comments still mention historical audit findings; current access truth is this doc plus code/tests.
