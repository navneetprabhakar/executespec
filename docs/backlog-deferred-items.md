# Backlog deferred items — first-slice paths forward

> Captures the 8 Backlog M-* items that are NOT shipping in this branch
> and explains exactly why. Each entry has the canonical "first-slice"
> path so the next engineer can pick the work up cold without
> re-deriving the analysis.
>
> All other Backlog items shipped — see [`execution-checkpoint.md`](execution-checkpoint.md).

## Why these are deferred

These 8 items fall into 4 categories where landing more here would
either be wasted effort or block on input we don't have:

1. **Multi-month engineering** (M-035 / M-036 / M-037 / M-040 /
   M-050) — true platform-architecture work that needs design review +
   a 4-8 week schedule with a sign-off owner. Doing a half-slice now
   would create more rollback risk than value.
2. **Product decisions pending** (M-048 / M-052) — the user has not
   approved a default. Per the auto-execute protocol, we don't pick
   pricing or A/B copy without user direction.
3. **Off-repo compliance work** (M-041) — security questionnaire
   templates live in legal's Google Drive, not the codebase.

## M-035 — Multi-org support

**What it would do:** let a single `AppUser` belong to multiple orgs
with role independence, switch contexts in the header, and bill
separately.

**Why deferred:** today's `AppUser.orgId` is a single FK. Multi-org
means a join table (`app_user_org_membership`), an "active org"
context (cookie or header on every API call), per-org permission
resolution at the auth layer, per-org cookie scoping (compounds with
M-031 cookie-domain switch), and an org-switcher UI. Realistic
estimate: 4-6 weeks engineering + a security review for the
cross-org data-leakage risk.

**First slice:** shadow-table migration that adds
`app_user_org_membership` (UUID, user_id, org_id, role, joined_at,
PK on user_id+org_id). Backfill from `AppUser.orgId`. Don't read it
yet — just dual-write on org-add. Lets us de-risk the data model
before touching the auth layer.

**Owner needed:** an engineer with auth-layer experience + a
security-review buddy.

## M-036 — Custom roles

**What it would do:** ORG_ADMIN can define custom roles beyond the
fixed `DEVELOPER` / `BILLING_ADMIN` / `ORG_ADMIN` / `PROJECT_ADMIN`
/ `PROJECT_DEVELOPER` set. Each role has a permission bitmap.

**Why deferred:** pre-requirement: a clear permission taxonomy. The
existing `TierPermissionService` + `RoleGuard` + `@PreAuthorize`
sites use a string match on `globalRole`/`orgRole`, not a permission
bitmap. Refactoring every check site to a permission-based gate is
~3 weeks of grep-and-patch + the new role-management UI.

**First slice:** add a `Permission` enum + an `OrgRolePermission`
join table (orgRoleName → permission). Map the existing 5 roles to
their canonical permission sets. Build a SUPER_ADMIN tool to mint
new roles + assign permissions but don't expose to ORG_ADMIN yet.
Once the abstraction is stable, cut over the @PreAuthorize sites.

**Owner needed:** same engineer as M-035 (similar shape).

## M-037 — SCIM provisioning

**What it would do:** Okta / Azure AD push user lifecycle events
(create/update/deactivate) to ExecuteSpec via the SCIM 2.0
protocol so org admins don't manually invite/suspend members.

**Why deferred:** SCIM 2.0 is its own spec (RFC 7644). Implementing
correctly means: SCIM endpoints (`/scim/v2/Users`, `/scim/v2/Groups`),
ETag / version handling, the WeMustResolveAttributeMappingDifferently
problem (Okta's `userName` ≠ AD's `userPrincipalName`), and per-IdP
testing rigs. Also blocks on M-016 (SAML wire-in) being live for the
SP metadata exchange.

**First slice:** stub `/scim/v2/Users` + `/scim/v2/Groups`
endpoints returning 501 with a clear "SCIM coming Q3 2026" message.
Document the customer-facing workaround (manual invites via the
existing OrgSettingsPage) in `docs/sso-saml-wiring.md`.

**Owner needed:** engineer post-SAML; usually a 2-week task once
SAML is solid.

## M-040 — Per-team RBAC (within an org)

**What it would do:** within an org, sub-divide members into "teams"
(e.g. `frontend`, `infra`) and gate project access by team membership.

**Why deferred:** depends on M-036 (custom roles). Teams are just
"named groups of members with a shared permission set" — once the
permission framework exists, teams ride on top.

**First slice:** wait for M-036.

**Owner needed:** same as M-036.

## M-041 — Security questionnaire templates

**What it would do:** ENT customers send us a security questionnaire
(SIG, CAIQ, or custom) during procurement. Standardise the answers
so legal/compliance doesn't re-derive them per deal.

**Why deferred:** these documents live in legal's Google Drive +
get updated quarterly with regulatory changes. Storing them in
this repo would create a stale-doc problem.

**First slice:** add a pointer to `docs/enterprise-one-pager.md`
that says "for filled-in security questionnaires email
admin@executespec.ai with the template — we maintain SIG, CAIQ,
and a custom template internally". The compliance team owns the
storage; engineering doesn't need to.

**Owner needed:** legal/compliance, not engineering.

## M-048 — Conversational refinement default

**What it would do:** by default, every NewRunModal opens with the
chat tab pre-selected (vs the current default of the compose tab).
Per parked decision: A/B test required to find out which converts
better.

**Why deferred:** product decision. Switching the default UX is a
copy/funnel change that needs a 2-4 week A/B test (PostHog already
captures the funnel via M-049's V125 ledger). Without test data,
flipping the default could regress activation.

**First slice:** wire the existing PostHog A/B framework
(`provider.smart-routing.enabled`-style flag) to expose
`ux.compose-default` as `compose | chat | A/B`. Run the A/B for 4
weeks; pick the winner. The one-line UI change is trivial — the
4-week measurement window is the actual gate.

**Owner needed:** product / growth.

## M-050 — Multi-tenant seed data

**What it would do:** a Flyway seed migration that pre-populates
demo orgs / users / runs / projects so a fresh `dev` env shows
non-empty UIs out of the box.

**Why deferred:** depends on M-035 (multi-org) — we need the
multi-org data shape settled before we can write a migration that
seeds it correctly. Doing this now means a re-migration once M-035
lands, which is wasted effort.

**First slice:** wait for M-035.

**Owner needed:** any engineer post-M-035; 1-day task.

## M-052 — ENTERPRISE floor public pricing

**What it would do:** publish a hard floor on the
[`/plans`](../frontend/src/pages/PlansPage.tsx) ENTERPRISE card
(e.g. "from $24,000/year") instead of the current "Contact sales".

**Why deferred:** parked decision #3 — until we have 5 paying ENT
customers, the anchor varies per deal. Publishing a floor too early
sets expectations that constrain negotiation.

**First slice:** copy + a `billing_plan` row insertion. Trivial
work; the gate is "have we crossed 5 paying ENT?" Once yes, flip
PlansPage's ENTERPRISE card from "Contact sales" → "from $X/year"
+ add a "Talk to sales" CTA.

**Owner needed:** sales/finance — they call the floor; engineering
ships the copy change.

## What this DOES NOT change

- Every other Backlog item shipped (or was already shipped — see
  M-065 / M-066 in the master list as verify-only).
- The 2-pass final closure (M-067 doc-tree refresh + M-068 full UX
  acceptance) still runs; it covers the 56 items that DID ship.

## When to re-open this doc

- After M-035 ships → M-040 + M-050 unblock; come back to update.
- After M-036 ships → M-040 unblocks separately.
- After M-016 (SAML) goes live → M-037 (SCIM) becomes the next
  ENT-procurement priority; come back.
- After 5 paying ENT customers → M-052 (ENT floor) needs a 1-day
  fix; come back.
- After 4-week A/B run → M-048 needs the winning default committed.
