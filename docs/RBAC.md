# RBAC And Access Control

> Current access-control contract. Tier gates decide what features a user may use; role and tenant guards decide which rows they may touch.

## Axes

| Axis | Values | Purpose |
| --- | --- | --- |
| Tier | `FREE`, hidden/grandfathered `PRO`, `TEAM`, `ENTERPRISE` | Product entitlement, quotas, caps, BYOK/model controls. |
| Global role | `SUPER_ADMIN`, normal user roles | Operator/admin capabilities. |
| Org role | `ORG_ADMIN`, `BILLING_ADMIN`, member | Org settings, members, billing, model matrix. |
| Project role | `PROJECT_ADMIN`, `PROJECT_DEVELOPER`, `PROJECT_VIEWER` | Project row access and project-scoped actions. |

## Tier Rules

- FREE: fixed default model, limited monthly runs, no model matrix.
- PRO: hidden/grandfathered compatibility tier.
- TEAM: self-serve BYOK-capable tier, 1-20 seats.
- ENTERPRISE: org-level model overrides, aggregator org keys, custom limits.

Tier limits are enforced in `TierEnforcementService`, `BillingService`, `OrgService`, and `tier_config`.

## Project Access

Project access must go through `ProjectAccessGuard` or equivalent controller guard:

| Action | Required guard |
| --- | --- |
| Read project/run/report/code-review/PR-review data | `assertCanRead` |
| Create/update/delete/link/upload/dispatch/fix/share | `assertCanWrite` |
| Public share-link read | share-token validation plus scope ownership |
| Cross-org invite acceptance | token/email validation plus `ProjectMember` row |

`PROJECT_VIEWER` is read-only. Cross-org viewers must not pass write handlers.

## Run Access

Run reads and writes are tenant-scoped. Run ownership fields are stamped before async pipeline work starts:

- `createdByUserId`
- `orgId`
- `projectId`

Run event streams use scoped SSE tokens.

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
- OAuth2 login.
- Personal access token (`es_pat_...`) for CLI/VS Code/CI style clients.

PAT auth resolves to the same `AppUserDetails` shape as JWT auth.

## Guard Coverage

`AccessGuardCoverageTest` pins protected controller methods so new project/run/repo/execution/launch handlers must call a tenant guard, and write-shaped handlers must use write guards.

## Known Gaps

- Tenant ORM filters (`C3`) remain deferred; app-layer guards are the hard requirement and are currently the tested boundary.
- Some older docs and comments still mention historical audit findings; current access truth is this doc plus code/tests.
