# `sharing/` — External project sharing (V175 + V198)

> Last reviewed: 2026-05-16 (1.0.0-M1). **Merged to dev/main.**
>
> Two complementary surfaces for sharing project visibility outside the host org:
> 1. **Project invites** — cross-org `ProjectMember` rows (collaboration)
> 2. **Public share links** — signed read-only links per surface (one-off viewing)
>
> Both tier-aware. **V198 (2026-05-16)** added a per-org feature flag
> `org.external_sharing_enabled` (default false) plus a role gate (ORG_ADMIN
> or SUPER_ADMIN only) on `ProjectInviteService.createInvite`. PROJECT_ADMIN
> on its own is no longer enough — adding outsiders is an org-level decision.
> Flag is toggled via `PUT /api/org/me/settings/external-sharing`.

## Files

- `entity/ProjectInvite.java` — pending invite (email-addressed, token-based, status machine PENDING → ACCEPTED | REVOKED | EXPIRED).
- `entity/ProjectShareLink.java` — public read-only link with `Surface` enum (PROJECT_OVERVIEW / RUN_DETAIL / CODE_REVIEW / PR_REVIEW / VALIDATION_REPORT / ARCHITECTURE_DOC) + scope id, view counter, revocation.
- `repository/ProjectInviteRepository.java`, `ProjectShareLinkRepository.java`.
- `service/ProjectInviteService.java` — invite lifecycle (create / accept / revoke). Tier-gated to PRO+ for `createInvite`. Email send via `MailService.sendProjectInviteEmail` (best-effort, never fails the invite). **RL-2026-05-066:** `createInvite` ALSO writes an in-app `Notification` (`InAppNotificationService.create`, type `PROJECT_INVITE`, link `/invites/accept?token=…`) when the invitee already has an account — best-effort, never fails the invite. The email link is `{appBaseUrl}/invites/accept?token=…`; the matching SPA route was missing (only `/accept-invite`, the org-invite path, existed) so the magic link dead-ended and the invitee never became a `ProjectMember` — fixed by adding the `/invites/accept` route in `frontend/src/App.tsx` (`AcceptInvitePage` now takes a `kind: 'org' | 'project'` prop so the two flows call their own accept endpoint: org → `/api/org/accept-invite`, project → `/api/invites/accept`).
- `service/ProjectShareLinkService.java` — link lifecycle (create / resolve / revoke / view-counter). Tier-gated by `tier_config.share_link_active_cap` (FREE 3, PRO 25, TEAM/MAX/ENTERPRISE -1=unlimited).
- `controller/ProjectInviteController.java` — auth-protected create/list/revoke + auth-required accept + public preview. **SDET 2026-05-16 (BUG-002/003):** `CreateInviteRequest` now has `@Email` + `@NotBlank` + `@Size(254)` on the email field and `@NotBlank` + `@Pattern("PROJECT_ADMIN|PROJECT_DEVELOPER|PROJECT_VIEWER")` + `@JsonAlias("role")` on the role field (renamed `role` → `projectRole` to match the wire contract used by `frontend/src/lib/api/projects.ts`). `@Valid` on the controller param wires bean validation — garbage emails and unknown roles now fail loud at 400.
- `controller/ProjectShareLinkController.java` — auth-protected admin endpoints (create/list/revoke).
- `controller/ProjectSharePublicController.java` — **no-auth** public read paths (`/api/shared/{token}/...`).

## Schema (V175)

- `project_member.is_external BOOLEAN` — flags cross-org members so the UI badges them and billing skips them when computing seat usage.
- `project_member.project_role` extended to include `PROJECT_VIEWER` (read-only).
- `project_invite` table — id, project_id, email, role, token UNIQUE, invited_by_user_id, invited_org_id, status, expires_at, accepted_at, accepted_by_user_id, revoked_at, revoked_by_user_id, created_at. Partial index on PENDING.
- `project_share_link` table — id, project_id, token UNIQUE, surface (CHECK constraint), scope_id, label, created_by, expires_at, revoked_at, views, last_viewed_at. Partial index on active.
- `tier_config.share_link_active_cap INT` — seeded FREE=3, PRO=25, TEAM/ENTERPRISE=-1 (Solo/MAX tiers retired by V179/V185).

## Access semantics

`ProjectAccessGuard.assertCanRead` consults `project_member` after the org match. Allow if any of:
1. SUPER_ADMIN
2. Caller's `org_id` matches `project.org_id`
3. Caller has a `ProjectMember` row for this project (the V175 cross-org path)

Public share-link reads bypass `ProjectAccessGuard` entirely — the URL token is the bearer.

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/projects/{id}/invites` | PRO+ only. Creates pending invite + emails recipient. |
| GET | `/api/projects/{id}/invites` | List pending invites for project. |
| DELETE | `/api/projects/{id}/invites/{inviteId}` | Revoke pending invite. |
| GET | `/api/invites/preview?token=` | Public — preview invite metadata (no token leakage). |
| POST | `/api/invites/accept?token=` | Authenticated — accept invite. Email match enforced. |
| POST | `/api/projects/{id}/share-links` | Create share link. |
| GET | `/api/projects/{id}/share-links` | List + active count. |
| DELETE | `/api/projects/{id}/share-links/{linkId}` | Revoke link. |
| GET | `/api/shared/{token}/meta` | Public surface meta. |
| GET | `/api/shared/{token}/run` | Public RUN_DETAIL surface. |
| GET | `/api/shared/{token}/validation-report` | Public VALIDATION_REPORT surface. |
| GET | `/api/shared/{token}/code-review` | Public CODE_REVIEW surface. |
| GET | `/api/shared/{token}/pr-review` | Public PR_REVIEW surface. |
| GET | `/api/shared/{token}/architecture` | Public ARCHITECTURE_DOC surface. |

`/api/shared/**` and `/api/invites/preview` are whitelisted in `SecurityConfig`.

## Outstanding follow-ups

- Frontend dashboard panel for managing invites + share links (the API helpers are in `frontend/src/lib/api/projects.ts` + `sharing.ts`; the UI panels would be follow-up commits).
- Public share-link viewer pages at `/shared/:token` route.
- Scheduled cleanup job that flips PENDING invites past `expires_at` to EXPIRED (today the read path checks expiry on accept; a sweeper would keep the table tidy for analytics).
- Per-tier monthly invite cap (today: paid-tier is the only gate; structure is in place to add a count).
