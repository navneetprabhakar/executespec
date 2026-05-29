# Audit: `sharing/` — cross-org invites + public read-only share links (V175/V198)

Status: **DONE** · Updated 2026-05-29 · 9 files / 1,408 lines · all read in full

## Files
- `service/ProjectInviteService.java` (349) — invite lifecycle ✓ · `service/ProjectShareLinkService.java` (249) — link lifecycle ✓
- `controller/ProjectSharePublicController.java` (274) — **no-auth** public reads ✓ · `controller/ProjectInviteController.java` (169) ✓ · `controller/ProjectShareLinkController.java` (126) ✓
- `entity/ProjectShareLink.java` (99) + `entity/ProjectInvite.java` (83) + 2 repos ✓

## What it does
Two outward-facing visibility surfaces: **cross-org invites** (email-addressed → `ProjectMember(is_external=true)`) and **public read-only share links** (token-bearer URL to one project surface — run/report/code-review/pr-review/architecture). The public link path **bypasses `ProjectAccessGuard` entirely** — the token is the only credential.

## Headline: the best tenant-isolation discipline in the codebase — exactly where it's needed most.
- 🟢 **Two-sided IDOR defense on the public-link path.** Because public reads are unauthenticated (no `ProjectAccessGuard`, and per [tenant.md](tenant.md) there's *no ORM safety net*), this package compensates with explicit checks at **both** ends: (1) **mint side** — `ProjectShareLinkService.assertScopeBelongsToProject` resolves the scope entity (run/scan/pr-review) and rejects (403) if its `projectId` ≠ the link's project, so a project admin can't mint a public link pointing at another org's run; (2) **serve side** — `ProjectSharePublicController.assertSameProject` re-resolves the entity on every read and returns **404 (not 403)** on mismatch so the unauthenticated endpoint never leaks whether a foreign entity exists. Belt *and* suspenders on the highest-risk surface. This is the pattern the rest of the codebase's app-layer-only tenancy should aspire to.
- 🟢 **Public DTOs are deliberately minimal** — every `Shared*` record exposes only a read-safe slice (scores, status, markdown), **never** internal tokens, credentials, or foreign IDs. `mustBe(surface)` prevents surface-confusion (a RUN_DETAIL token can't read a CODE_REVIEW). Revoked/expired → 410 Gone.

## Other findings (all 🟢 / minor)
- 🟢 **Strong tokens + correct write-gating.** Both invites and links use 32-byte `SecureRandom` URL-safe tokens (~256 bits). Every admin mutation (create/revoke invite + link) was hardened to `assertCanWrite` (audit B#9/#10 — previously `assertCanRead` let a `PROJECT_VIEWER` mint invites/public links); list stays `assertCanRead`.
- 🟢 **V198 invite gating is a proper three-gate** (`ProjectInviteService.assertCanInvite`): tier PRO+ **AND** ORG_ADMIN-of-own-org (PROJECT_ADMIN alone is insufficient — adding outsiders is an org decision) **AND** `org.external_sharing_enabled=true`, with a documented SUPER_ADMIN bypass for ops. Accept enforces case-insensitive **email match** (only the addressed recipient can accept), expiry → 410, token rotation on re-invite (old leaked link dies). Idempotent member-add.
- 🟢 Tier-capped active links (`tier_config.share_link_active_cap`: FREE 3 / PRO 25 / TEAM+ unlimited) via a DB `COUNT` query (not a heap scan); `isActive()` = not-revoked + not-expired.
- 🟡 **`GET /api/invites/preview` (public) returns the invitee email + projectId + role for any valid token.** Intentional (signup landing page) and the token is a 256-bit bearer, so low risk — but it is PII (an email) served unauthenticated to whoever holds the link. Acceptable given the token entropy; noting for completeness.
- 📝 BUG-003 fix (`role`→`projectRole` + `@JsonAlias`): Jackson had been silently dropping the `projectRole` wire key (field was `role`) and defaulting to `PROJECT_VIEWER`. Now `@Email`/`@Size(254)`/`@Pattern` bean-validation rejects junk at 400. (The old bug was a privilege *downgrade*, not escalation.)
- 📝 Acknowledged follow-ups (CLAUDE.md): no scheduled sweeper flips PENDING→EXPIRED (expiry only checked on accept) — analytics-only debt; frontend management panels are stubs.

## Cross-refs
Compensates for the missing ORM net → [tenant.md](tenant.md) (no read-side tenant filter). Consumes the cross-org member model + guard → [project.md](project.md) (`ProjectAccessGuard` consults `project_member`), `auth/ProjectMember` (next). V198 org-flag toggled via → `auth/OrgController` (next, `external_sharing_enabled`). Public surfaces read → [state.md](state.md) (run), [report.md](report.md) (validation report), `codereview/`+`prreview/` (Tier 6), [codeintel.md](codeintel.md) (architecture). Whitelisted in → `auth/security/SecurityConfig` (`/api/shared/**`). Activity emit → `activity/` (Tier 7).
