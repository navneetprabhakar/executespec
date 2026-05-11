# `docs/plans/` — index + post-execution status

> **Last cleanup: 2026-05-11** (`feature/docs-cleanup-2026-05-11`).
> Pruned 7 plan files whose work either shipped, was parked, or
> referenced abandoned directions:
> `eval-2026-04-26-partial-and-openai-degradation.md`,
> `pending-agent-builder-plan.md`,
> `pending-complimentary-pro-promo-2026-04-30-plan.md`,
> `pending-project-surface-vite-split.md`,
> `phase-2-3-deferred.md`,
> `pre-deploy-open-items.md`,
> `ux-overhaul-v4.md`,
> `wave-7-issue-list.md`. The trail lives in git log; operational
> truth lives in `CLAUDE.md`.

## Status table

| File | Status | Authority |
|------|--------|-----------|
| [`partial-work.md`](partial-work.md) | **STILL PENDING** — Mobile RN+Expo native-Android gap, Figma v2 design-only, Harness V2 remaining gaps, PARTIAL-status removal proposal. Post-v2.7 work. | Self |
| [`pending-v2-batch-execution-plan.md`](pending-v2-batch-execution-plan.md) | **STILL PENDING** — V2 batch-execution rewrite. V1 batch was deleted 2026-04-13; V2 stays realtime-only until this lands. Multi-week feature. | Self |
| [`pending-distributed-processing-plan.md`](pending-distributed-processing-plan.md) | **STILL PENDING** — Phase A NFS workspace + Phase B worker-lease pool. Multi-quarter scaling work. | Self |
| [`pending-vscode-extension-plan.md`](pending-vscode-extension-plan.md) | **STILL PENDING** — separate-repo VS Code extension scaffold. The actual extension lives outside this repo when it ships. | Self |
| [`pending-readymade-solutions-plan.md`](pending-readymade-solutions-plan.md) | **W1–W5 SHIPPED** on `feature/readymades-and-migrations`. Library API + 3 POC seeds + frontend gallery + detail + variant resolver + instantiate path (V2 dispatch with V160 tracking). W6 catalog expansion, W7 community moderation, W8 tier-gating polish, W9 FTS search are v1.5 follow-ups. | Self |
| [`pending-migrations-plan.md`](pending-migrations-plan.md) | **W1–W7 SHIPPED** on `feature/readymades-and-migrations`. Library API + dry-run + apply (multi-repo via Execution View) + frontend gallery + halt-and-inspect detail + tier-stratified cost UX + 8 of 11 v1 recipe seeds. W8 (hybrid AST+AI), W9 (PostMergeCascade continuous), W10 (polyglot) deferred per the v1.5+ staging. | Self |
| [`pending-ga-readiness-90-day-execution-plan.md`](pending-ga-readiness-90-day-execution-plan.md) | **PROPOSED** — 90-day Plan B execution doc operationalising the 2026-05-04 GA-readiness audit. P0–P6 phases through 2026-08-04. Awaiting founder approval before kickoff. | Self |
| [`backlog-deferred-items.md`](backlog-deferred-items.md) | **DEFERRED-ITEM TRACKER** — first-slice + owner-needed + when-to-re-open per deferred item across all plans. Canonical home for tracking work that's been intentionally postponed. | Self |

## Where shipped plans went

The following plan files were removed in earlier cleanup passes
once the work shipped. Operational truth lives in `CLAUDE.md` +
`ARCHITECTURE.md` + the relevant package `CLAUDE.md`. Listed here
as breadcrumbs in case anyone goes looking:

- `hierarchical-planner-plan.md` — all 5 slices shipped on
  `feature/hierarchical-planner` (V130). Truth: root `CLAUDE.md`
  v2.6.2-track section + `state/CLAUDE.md` V130 entry.
- `pending-enterprise-readiness-execution-eval.md` — 4 phases /
  40 slices shipped on `feature/enterprise-readiness-eval`
  (V131-V139). Truth: root `CLAUDE.md` v2.6.3-track section.
- `pending-preview-control-plane-plan.md` +
  `preview-engine-rewrite.md` — preview pipeline moved to the
  separate [`preview-engine`](https://github.com/navneetprabhakar/preview-engine)
  repo. New plans authored there.
- `ux-overhaul-v4.md` — shipped on
  `feature/ux-overhaul-v4-pulse-tab`. Truth: `frontend/CLAUDE.md`.
- `eval-2026-04-26-partial-and-openai-degradation.md` —
  investigation; findings closed by code fixes that landed before
  v2.7. Trail in git log.
- `pending-agent-builder-plan.md` — parked 2026-04-29 as wrong
  product shape. Will be re-authored as `pending-agent-builder-v2-plan.md`
  once the corrected scope settles.
- `pending-complimentary-pro-promo-2026-04-30-plan.md` — promo date
  passed. Marketing decision to relaunch is open.
- `pending-project-surface-vite-split.md` — Phase 2 of the v2.7
  product-split. Direction retired with V179 single-surface
  retirement.
- `phase-2-3-deferred.md` — Phase 1 shipped; Phase 2 cookie-domain
  switch + surface split obsolete after V179. Phase 3 items moved
  to `backlog-deferred-items.md`.
- `pre-deploy-open-items.md` + `wave-7-issue-list.md` — snapshots
  for `feature/v3-conversion` work; that branch merged and was
  superseded by V179. Trail in git log.

## Rules for new plan files

1. **Always prefix `pending-`** for plans that aren't started.
2. **Drop the prefix once shipped + delete** the file (operational
   truth then lives in `CLAUDE.md` / `ARCHITECTURE.md`).
3. **Don't keep a "shipped" plan file alive** — the design intent
   is captured by the diff in git history; the plan file becomes
   stale fast.
4. **For investigations / postmortems / one-off evals**, prefer
   summarising findings in the relevant `CLAUDE.md` and letting
   git log carry the rest. If a written record is genuinely
   needed, name it `eval-YYYY-MM-DD-*.md` and leave it as
   historical record.
5. **For deferred items**, the canonical home is
   [`backlog-deferred-items.md`](backlog-deferred-items.md)
   (per-item first-slice + owner-needed + when-to-re-open). Plans
   in this folder should be self-contained design docs, not
   deferred-tracker entries.
