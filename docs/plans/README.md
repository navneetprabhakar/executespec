# `docs/plans/` — index + post-execution status

> Authoritative as of 2026-04-27 (M-067 doc-tree refresh on
> `feature/v2.7-foundation`). Captures whether each plan is still
> live, has been superseded, or shipped (and where the operational
> truth lives now).

## Status table

| File | Status (M-067 review) | Authority |
|------|------------------------|-----------|
| [`partial-work.md`](partial-work.md) | **STILL PENDING (revised scope)** — Mobile RN+Expo native-Android gap, Figma v2 design-only, Harness V2 Gap 1+2+3, PARTIAL-status removal proposal. M-* execution did NOT touch these — they're post-v2.7 work. | Self |
| [`pending-v2-batch-execution-plan.md`](pending-v2-batch-execution-plan.md) | **STILL PENDING** — V2 batch-execution rewrite. V1 batch was deleted in 2026-04-13 deprecation; the V2 path stays realtime-only until this lands. Multi-week feature. | Self |
| [`pending-distributed-processing-plan.md`](pending-distributed-processing-plan.md) | **STILL PENDING** — Phase A NFS workspace + Phase B worker-lease pool. Multi-quarter scaling work. | Self |
| [`pending-vscode-extension-plan.md`](pending-vscode-extension-plan.md) | **STILL PENDING** — separate-repo VS Code extension scaffold. Stub plan; the actual extension lives outside this repo when it ships. | Self |
| [`pending-complimentary-pro-promo-2026-04-30-plan.md`](pending-complimentary-pro-promo-2026-04-30-plan.md) | **TIME-WINDOWED — 2026-04-30 promo date passed mid-execution.** Worth re-deciding whether to relaunch with a new date or close. Marketing decision; engineering scope unchanged. | Self (but needs a Go/No-Go) |
| [`phase-2-3-deferred.md`](phase-2-3-deferred.md) | **PARTIALLY SUPERSEDED by M-* execution + `docs/backlog-deferred-items.md`.** Phase 1.0 / 1.1 / 1.2 / 1.3 items are now SHIPPED via Wave 1+2+3+Backlog (see `CHANGELOG.md` v2.7 entry). Phase 2 + Phase 3 deferral rationale still authoritative — the cookie-domain switch, multi-org work, etc., remain deferred per the same reasons. | Combined: this file (Phase 2 + 3 reasoning) + [`backlog-deferred-items.md`](../backlog-deferred-items.md) (per-item first-slice + owner) |
| [`ux-overhaul-v4.md`](ux-overhaul-v4.md) | **SHIPPED** on `feature/ux-overhaul-v4-pulse-tab`. Operational truth in `frontend/CLAUDE.md`; this file remains as the design rationale + before/after screenshots. | `frontend/CLAUDE.md` |
| [`eval-2026-04-26-partial-and-openai-degradation.md`](eval-2026-04-26-partial-and-openai-degradation.md) | **HISTORICAL INVESTIGATION** — root-cause analysis of the booking-spec PARTIAL pattern + OpenAI iteration count. Two of the three findings are now closed by code fixes that landed before the v2.7 batch; the smoke-test bug is still pending. Keep as evidence; don't update. | Self |

## Where shipped plans went

The following plan files were removed in earlier cleanup passes
(2026-04-26 + earlier) once the work shipped. Operational truth
lives in `CLAUDE.md` + `ARCHITECTURE.md` + the relevant package
`CLAUDE.md`. Listed here as breadcrumbs in case anyone goes
looking:

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
- `open-work-2026-04-18.md` — stale 8-day audit; superseded by
  `partial-work.md`.

## Rules for new plan files

1. **Always prefix `pending-`** for plans that aren't started.
2. **Drop the prefix once shipped + delete** the file (operational
   truth then lives in `CLAUDE.md` / `ARCHITECTURE.md`).
3. **Don't keep a "shipped" plan file alive** — the design intent
   is captured by the diff in git history; the plan file becomes
   stale fast.
4. **For investigations / postmortems / one-off evals**, name them
   `eval-YYYY-MM-DD-*.md` and leave them as historical record (no
   updates after the investigation closes).
5. **For deferred items**, the canonical home is now
   [`docs/backlog-deferred-items.md`](../backlog-deferred-items.md)
   (per-item first-slice + owner-needed + when-to-re-open). Plans
   in this folder should be self-contained design docs, not
   deferred-tracker entries.
