# Phase 2 + Phase 3 — deferred-with-justification

> Companion to the consolidated execution plan in `/tmp/executespec-consolidated-plan.md`.
> Created 2026-04-26 alongside the `feature/v2.7-foundation` branch landing
> Phase 1.0 + 1.1 + 1.2 + (most of) 1.3.
>
> This document tracks what was NOT done in the auto-execute pass, why, and
> when each item should be picked back up.

## Summary

| Phase | Status |
|-------|--------|
| Phase 1.0 (safe groundwork, weeks 0-1) | **Done** — V143, ProductSurfaceFilter, OAuthSurfaceResolver + init endpoint, CORS expansion, dead-endpoint integration test, live cost polling verified, VerdictPill. |
| Phase 1.1 (re-segment IA, weeks 2-4) | **Done** — view-mode toggle retired, PRO hidden + /legacy/billing redirect, /runs/new top-level dropped, empty-state hero pattern across 3 B2B pages, Drawer primitive, Glossary primitive + dictionary + first wire, Mission rename in nav. PROJECT-default closed because V126/V140 already shipped the foundation. |
| Phase 1.2 (Wave-3 enterprise, weeks 4-8) | **Done** — V127 entity fields wired + SsoConfigController + GitHubAppController skeletons; AdminPage Audit + Webhook tabs verified pre-shipped; InvoicesPage CSV; TierLifecyclePage tier-impact preview verified pre-shipped; ExecSummaryPage needs-attention verified pre-shipped; BYOK Test buttons + GitHub PAT Test button (PAT was pre-shipped); per-seat TEAM seat purchase end-to-end functional via V144 + restored adjustSeats path. |
| Phase 1.3 (cross-poll quick wins, weeks 6-8) | **Partial** — V145 loosened FREE 5→10; notification body upgraded; per-seat TEAM reinstated covers most "TEAM→VIBE feature port" rationale by virtue of the surface-mapping policy. Items #27 / #29 / #30 / #32 / #34 / #35 deferred — see below. |
| Phase 2 (split surfaces, months 3-6) | **Deferred** — large, requires DNS + ops + maintenance window. See "Phase 2 deferral" below. |
| Phase 3 (earn the moat, months 6-12) | **Deferred** — multi-month initiatives. See "Phase 3 deferral" below. |

---

## Phase 1.3 items deferred

| # | Item | Why deferred | When to pick up |
|---|---|---|---|
| #27 | Persistent project workspace (lightweight) on Pro | Already exists for all tiers under `/projects` — the "lightweight" variant requires tier-gating decisions (which features to hide on Pro vs full TEAM). | Together with the Phase 2 surface split, when the Vibe surface decides what subset of project workbench it wants. |
| #28 | Generated docs (EXPLANATION + SETUP) on Pro | **Already shipped** — `ProjectDocGeneratorService` + `GeneratedDocsPanel` exist for all paid tiers per v2.5.12. No tier gating needed; close as duplicate. |
| #29 | Architecture viewer simplified on Pro | Same as #27 — needs the surface split to define what "simplified" means. | Phase 2.1 (consumer surface). |
| #30 | Conversational refinement as default input | Currently behind a Sparkles button. Flipping the default is a UX decision that affects every spec submission flow; needs A/B data first. | Phase 2 once activation funnel data (V125 events) shows whether sparkles-by-default lifts time-to-first-run. |
| #32 | Annual billing default toggle | **Backend doesn't support annual plans yet.** V123 explicitly: "Yearly deferred per team-tier-plan.md §11.5". Adding the toggle without the backend would mislead users. | When annual `billing_plan` rows are added (TEAM Annual, MAX Annual). Schema is ready (`billing_plan.period`, `annual_discount_pct`). |
| #34 | Card-grid scaffold/template picker | `EmptyProjectLanding` (v2.6.4) already has a 6-card sample-spec gallery in the empty-state. Apply the same pattern to `NewRunModal`'s spec-input UI. | Standalone UX-sweep PR — small, isolated. |
| #35 | Fork-a-teammate's-run (org-internal) | Requires `forkable` flag on runs + a Fork button. Backend needs a "duplicate run" endpoint that copies the spec/repo/Figma but resets execution state. ~1 day's work, well-scoped. | Standalone backend-+-frontend PR. |

## Phase 2 deferral — split the surfaces (months 3-6)

**Why deferred from this auto-execute pass:**
- DNS + Cloudflare config (point `app.executespec.ai` and `build.executespec.ai`) requires ops access this branch can't make.
- Cookie domain switch to `.executespec.ai` invalidates all existing sessions and needs a maintenance window per the plan's risk register.
- Frontend repo restructure to workspaces (`shared/` / `app/` / `build/`) is a 2-3 day refactor that risks breaking the running app and is best done off a clean branch with no in-flight work.
- Stripe metadata per product needs the actual surface domains live first.
- Migrate grandfathered PRO subscribers needs PRO-customer outreach + comms — not a code-only task.

**Foundation already in place from Phase 1:**
- V143 product_id discriminator on user-scoped tables (call sites can be wired in Phase 2 without schema work).
- `ProductSurfaceFilter` request-scoped attribute (Phase 2 wiring is one-line per repository method).
- `TierProductSurfaceMapper` policy (TEAM→executespec, MAX→vibe).
- `OAuthSurfaceResolver` + `/api/auth/oauth2/init` (per-surface OAuth redirect ready, currently falls back to dashboardUrl).
- CORS allow-list pre-emptively includes both surface subdomains.
- TEAM tier reinstated (V144) so Build surface has its tier; MAX preserved for Vibe.

**Order of operations when Phase 2 starts:**
1. Stand up DNS for both surfaces (ops).
2. Schedule maintenance window for cookie-domain switch.
3. Frontend repo restructure to workspaces.
4. Wire `ProductSurfaceFilter` into every repository method that needs product-scoped data.
5. Update `BillingService.handleStripeCheckoutCompleted` to call `TierProductSurfaceMapper.defaultSurfaceFor()` and stamp `org.product_id`.
6. Update Stripe metadata to include `product` field.
7. Migrate grandfathered PRO subscribers: email + soft redirect from `/legacy/billing`.

## Phase 3 deferral — earn the moat (months 6-12)

**Why deferred:** Phase 3 items are multi-month initiatives that require external dependencies (legal for SOC2, DNS for custom domains, GTM for marketplace), or large engineering investments that aren't viable in a single session. Tracked here so they don't get forgotten.

### Consumer (`app.executespec.ai`)

| Item | Effort | External dependencies |
|---|---|---|
| Fork-a-public-run | Medium | Needs the consumer surface live (Phase 2). |
| Batteries-included infra (DB + auth + hosting wired) | Large | `Bolt Cloud` equivalent — needs an infra service decision (Render? Fly? Custom?). |
| Custom domain registration in-product | Medium | Domain-registrar integration (Namecheap API or equivalent). |
| Live preview during run | Large | Major harness change — incremental SSE of file writes + sandboxed iframe. |
| iOS app | Medium | Apple Developer Account + TestFlight + App Store review timeline. |
| Community expert / consultant marketplace | Large | GTM strategy + matching infra + payments + reviews. |

### B2B (`build.executespec.ai`)

| Item | Effort | External dependencies |
|---|---|---|
| Fleet — parallel multi-repo migrations | Large | Architectural change to the runner — multi-repo workspaces, parallel orchestration. |
| Continuous learning from past trajectories | Large | Embedding store + similarity search infra; per-org isolation legal review. |
| Top 5 enterprise integrations (Linear, Datadog, Snowflake, Stripe, Notion) | Medium per | Per-vendor OAuth apps + adapters. |
| Custom fine-tuning (BYOMD) | Large | LLM provider partnerships + per-customer model storage. |
| PR review (other people's PRs) | Medium | New product surface. |
| Status page + SLA documentation | Small | Statuspage.io / Better Stack subscription + on-call rotation. |
| SOC2 prep | Large org-wide | Auditor engagement + ~6 months of evidence collection. |
| On-prem deployment option | Large | K8s helm charts + license management + support model. |
| VS Code extension (external repo) | Medium | Marketplace publish + auth flow. |
| CLI tool (external repo) | Medium | Distribution + auto-update. |

### Both products

| Item | Effort |
|---|---|
| `⌘K` command palette | Medium |
| Persistent chat sidebar | Medium |
| Cost-budget alerts + spend caps per user | Medium |
| Annual contract self-serve expansion | Medium |

---

## Closing thought

Plan 1's "this-week" items + the auto-execute pass through Phase 1.0/1.1/1.2/1.3
took the foundation from "consumer/B2B surfaces are an unrealized strategic
goal" → "schema, primitives, policy, and call-site stubs are in place; Phase 2
is mostly DNS + frontend restructure + ops orchestration, not new code." The
unrelated TEAM tier reinstatement (V141 → V144) sits cleanly on top because
it touched policy, not code paths.

## 2026-04-26 update — deferred-items batch (round 2)

Picked off another tier of deferred items after the initial pass:

| Item | Disposition |
|---|---|
| #34 Card-grid template picker | **Done** — new `frontend/src/components/execution/TemplateGrid.tsx` 2-3 column card layout for spec template selection in surfaces with horizontal room (NewRunModal, EmptyProjectLanding, project Launch). The compact dropdown TemplatePicker stays for tight surfaces. |
| #35 Fork-a-teammate's-run | **Done** — `POST /api/runs/{runId}/fork` + `forkRun()` lib helper. Inherits source spec/repo/provider; quota gates apply to forker. Visibility piggybacks on RunAccessGuard. |
| #32 Annual billing plans | **Done** — V146 introduces TEAM/MAX Yearly INR + USD billing_plan rows with 17% discount. annual_discount_pct stamped on every TEAM/MAX row so /plans toggle can render savings. Stripe annual price IDs deferred to ops. |
| Phase 2 — BillingService stamps org.product_id at subscribe | **Done** — handleStripeCheckoutCompleted resolves plan tier and writes via TierProductSurfaceMapper. Idempotent. |
| Phase 2 — Stripe metadata per product | **Done** — `product` metadata field added to checkout session params. Webhook handlers can route by surface even before org row reflects new tier. |
| Phase 2 — ProductSurfaceContext static accessor | **Done** — exposes `currentSurface()` to services/repos without plumbing HttpServletRequest. Per-callsite migration of `findByUserId` → `findByUserIdAndProductId` is intentional and incremental (auto-rewrite would break server-to-server jobs). |
| Phase 3 — SLA + Status page docs | **Done** — `docs/status-page.md` companion to `docs/enterprise-sla.md`. Component table, severity matrix, subscription mechanics, incident-authoring flow. |
| Phase 3 — ⌘K Command palette | **Already shipped** — `frontend/src/components/layout/CommandPalette.tsx` (cmdk-based) was pre-shipped. Just synced its labels to the "Missions" rename + added 4 missing routes (Plans, Invoices, Org settings, Projects). |

Still deferred:

| Item | Reason |
|---|---|
| #27 Persistent project workspace lightweight on Pro | Tier-gated UI variant — needs surface-split decisions about which subset of project workbench Vibe wants. Code-doable in a separate PR once Phase 2 lands. |
| #29 Simplified architecture viewer on Pro | Same — needs surface split to define "simplified". |
| #30 Conversational refinement as default input | Needs A/B data on activation lift before flipping the default; no harness ready to A/B yet. |
| Phase 2 — repository method wiring (`findByUserIdAndProductId` etc.) | Foundation (filter + accessor) in place; per-repo migration is incremental and best done callsite-by-callsite with tests, not as a mass auto-rewrite. |
| All Phase 3 multi-month items in original list | Unchanged — external dependencies (legal for SOC2, infra service decisions, GTM for marketplace) put these out of code-only scope. |
