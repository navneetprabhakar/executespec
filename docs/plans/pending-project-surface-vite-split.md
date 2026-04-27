# Project surface — separate Vite app split (Phase 2 of v2.7 product split)

> **Status:** PENDING. Multi-week effort, not yet scheduled.
> **Author:** 2026-04-27 — written after v2.7.2 shipped the first
> in-place IA differentiation (Repos as a top-level project tab).
> **Companion to:** [`docs/cookie-domain-switch.md`](../cookie-domain-switch.md),
> [`docs/plans/phase-2-3-deferred.md`](phase-2-3-deferred.md), and
> the deployment-topology answer at `/tmp/executespec-deployment-plan.md`.

## Why this exists

v2.7 (`feature/v2.7-foundation`) shipped the *backend plumbing* for the
two-product split: `product_id` discriminator (V143),
`ProductSurfaceFilter`, `OAuthSurfaceResolver`, `TierProductSurfaceMapper`,
CORS allow-list pre-extension, Stripe per-product price-ID env slots.
v2.7.1 + v2.7.2 then shipped the *first user-visible Project IA win*
(Repos promoted to a top-level tab + first-class "Add repo" surfaces).

What v2.7 did NOT ship — and what this doc plans:

- A second Vite app under `frontend/build/` (or wherever) that is the
  actual Project surface, served at `build.executespec.ai`.
- DNS for `build.executespec.ai` (and the dev clone
  `build.dev.executespec.ai`).
- Cookie-domain switch to `.executespec.ai` so login is shareable
  across `app.executespec.ai` and `build.executespec.ai`.
- Backend repository reads filtered by `currentProduct`.
- Subscribe-time write of `org.product_id`.

## Scope

This is Phase 2 of the product split. Phase 1 was v2.7's plumbing.
Phase 3 is the consumer surface (`app.executespec.ai` for FREE / PRO /
MAX) which depends on Phase 2 landing first.

## What gets built

### Frontend

- New directory `frontend/build/` — a standalone Vite app that consumes
  the same backend at `api.executespec.ai`.
- Shared primitives + lib + stores imported via TypeScript path aliases
  (`@/lib/*`, `@/stores/*`, `@/components/shared/*`) so we don't fork
  the design system.
- Project-centric IA — Repos / Architecture / Standards / ADRs /
  Invariants / Contracts as top-level navigation, not buried in a Code
  tab. Pulse / Runs stay. Drop the consumer-surface affordances
  (sample-spec gallery, `/onboarding`, vibe view-mode toggle).
- Header simplification — drop view-mode toggle, drop "Missions"
  rename (Project surface uses "Runs" verbatim).
- Sidebar — only Project-relevant nav: Pulse, Repos, Runs, Code,
  Settings, Audit, Org admin. Drop Pricing / Integrations marketing
  affordances; promote Standards / Contracts to sidebar.
- Build output to `src/main/resources/static/build/` (not
  `static/`) so the existing single-app deploy keeps working
  unchanged during the cutover.

### Backend

- `WebMvcConfigurer` (or `ResourceHandlerRegistry`) — add a
  `Host`-aware static resource handler:
  - Host matches `build.*executespec.ai` → serve
    `static/build/index.html` for any non-`/api/**` request.
  - Host matches `app.*executespec.ai` (Phase 3) → serve
    `static/app/index.html`.
  - Default (current single-host) → serve `static/index.html`.
  Implementation: `WebMvcConfigurer.addInterceptors` rewriting the
  forwarded path before resource resolution. Must not change `/api/**`
  routing — those still hit the same Spring controllers.
- `JwtUtil.issueRefreshTokenCookie` — accept `auth.cookie.domain`
  config knob (per [cookie-domain-switch.md:33-44](../cookie-domain-switch.md)).
  Empty/null = current single-host behavior; `.executespec.ai` =
  parent-domain scope.
- `BillingService.handleStripeCheckoutCompleted` (and Razorpay
  equivalent) — call `TierProductSurfaceMapper.defaultSurfaceFor(tier)`
  and persist `org.product_id`.
- Repository read filtering by `ProductSurfaceFilter.ATTR_CURRENT_PRODUCT`
  on hot paths: `PipelineRunRepository.findByOrgId` + ~5 others.
  ArchUnit test pinning that no repository skips the filter.

### Build pipeline

- `deploy.yml` — add `npm --prefix frontend/build run build` step
  parallel to the existing `npm --prefix frontend run build`. Both
  outputs go into the JAR (separate `static/` and `static/build/`
  directories).
- `frontend/build/package.json` — separate dependency surface so the
  consumer surface doesn't need every B2B-only dep (and vice versa).
  Shared deps stay in the root `frontend/package.json`.

### DNS + cert

- `build.executespec.ai` → CNAME or A → same backend host as
  `api.executespec.ai`.
- `build.dev.executespec.ai` → same for dev.
- TLS — Cloudflare `*.executespec.ai` cert covers both, no new request.

### Cookie maintenance window

Per [cookie-domain-switch.md:55-98](../cookie-domain-switch.md):
30-min window, 14-day notice, single env-var flip
(`AUTH_COOKIE_DOMAIN=.executespec.ai`), force-logout every existing
session, ~10-min documented rollback. Run this AFTER both surfaces
are deployed and verified.

## Phasing

Order of operations (each step ships independently to dev first,
soak ≥48h, then prod):

1. **Backend Host-aware static handler** (~1 day) — feature-flag
   `surface.host-routing.enabled`. With flag OFF, current behavior.
   With flag ON, Host header drives the `static/` directory chosen.
   Smoke: `curl -H 'Host: build.dev.executespec.ai'` returns the
   build app's index.html.
2. **Backend repo-scope filter wiring** (~2 days) — apply
   `ProductSurfaceFilter.ATTR_CURRENT_PRODUCT` on the 5-6 hot-path
   repository queries. ArchUnit guard.
3. **`org.product_id` write at subscribe time** (~1 day) —
   `BillingService` calls `TierProductSurfaceMapper`. Tests cover
   every tier × gateway permutation.
4. **`frontend/build/` skeleton** (~3-5 days) — standalone Vite
   project, shared primitives, project-centric sidebar, top-level
   Repos tab, drops consumer affordances. Builds cleanly; doesn't
   yet serve outside the build pipeline (the static-handler flag
   stays OFF in prod).
5. **Cutover dry-run on dev** (~2 days) — flip the static-handler
   flag ON for `dev.executespec.ai`; smoke-test
   `build.dev.executespec.ai` works; verify cross-subdomain auth
   doesn't break (cookie still single-host at this stage).
6. **Cookie-domain switch on dev** (~1 day, plus 14-day notice) —
   per [cookie-domain-switch.md](../cookie-domain-switch.md). Verify
   login at `dev.executespec.ai` is recognized at
   `build.dev.executespec.ai`.
7. **Prod cutover** (~1 day) — repeat steps 5+6 on prod with the
   30-min maintenance window. Send the 14-day customer notice.
8. **Phase 3 follow-up** — stand up `app.executespec.ai` consumer
   surface. Same template, different IA + copy, no new backend work.

Total Phase 2 effort: **~2-3 weeks of focused work** plus the 14-day
customer-notice clock for the maintenance window.

## What's NOT in this plan

- The consumer Vibe surface (`app.executespec.ai`) — that's Phase 3.
- Per-surface admin tooling (Theo's `/admin` panels split by product) —
  separate ticket once both surfaces are live.
- SCIM provisioning across both surfaces — see M-037 backlog.
- Per-surface marketing sites — kept on Cloudflare Pages, decoupled
  from the app builds.

## Risks + rollback

- **Risk:** the static-handler change accidentally breaks the existing
  single-host serve. **Mitigation:** behind feature flag, ArchUnit
  pin, dev-soak ≥48h before prod.
- **Risk:** cookie maintenance window invalidates every active
  session. **Mitigation:** 14-day customer notice, in-app banner,
  10-min rollback documented.
- **Risk:** path-alias drift between `frontend/` and `frontend/build/`
  causes silent type errors at build time. **Mitigation:** shared
  `tsconfig.base.json` extended by both apps; CI runs `tsc --noEmit`
  on both before the JAR step.
- **Risk:** the second app's bundle pulls in unexpected dependencies
  (e.g. a shared component dragging consumer-only deps). **Mitigation:**
  vite-bundle-analyzer in the CI pipeline; alert on bundle-size delta.

## Acceptance criteria

- [ ] `curl -H 'Host: build.dev.executespec.ai' https://dev.executespec.ai/`
      returns a different index.html than `curl https://dev.executespec.ai/`.
- [ ] Login at `dev.executespec.ai` is recognized at
      `build.dev.executespec.ai` without re-auth (post cookie switch).
- [ ] Pulse / Repos / Runs / Code (or new Project equivalents) all
      load on the new surface.
- [ ] No `/api/**` route changes behavior.
- [ ] PostHog `product` property is populated on every event from the
      new surface.
- [ ] ArchUnit: no repository read on the hot-path list bypasses
      `ProductSurfaceFilter.ATTR_CURRENT_PRODUCT`.
- [ ] Stripe checkout completion stamps `org.product_id` correctly
      for every tier × gateway permutation.
- [ ] Rollback runbook (revert the static-handler flag + cookie env
      var) verified on dev within 10 minutes.

## Out of scope (do NOT bundle into this plan)

- IA changes to the existing single-app dashboard beyond what v2.7.2
  already shipped. Once the second app exists, the existing app
  becomes the "consumer" template — IA changes there belong in a
  Phase-3 ticket.
- Visual redesign of either surface. Keep the existing token system,
  primitives, and 4-band color palette. Phase 2 is structural.
- Marketing-site changes. The website is its own deploy.

## References

- [docs/cookie-domain-switch.md](../cookie-domain-switch.md) — operator runbook for the cookie scope flip.
- [docs/plans/phase-2-3-deferred.md](phase-2-3-deferred.md) — broader Phase 2/3 deferral context.
- `src/main/java/com/navneet/executespec/auth/security/ProductSurfaceFilter.java` — request-scoped surface attribute.
- `src/main/java/com/navneet/executespec/auth/service/TierProductSurfaceMapper.java` — tier → surface policy.
- `src/main/resources/db/migration/V143__product_split.sql` — discriminator schema.
- `/tmp/executespec-deployment-plan.md` — full deployment topology answer (Option B picked, Phase 2 + 3 outlined).
