# Plans Disposition - 2026-06-10

> Evaluation of `docs/plans/` after the review-driven implementation and docs cleanup. This is a recommendation ledger, not a delete script.

## Recommendation Summary

| Plan | Recommendation | Reason |
| --- | --- | --- |
| `benchmark-closed-loop-2026-06-09.md` | Keep for now | Phases 1, 2, and 4 are implemented, but Phase 3 event hooks remain. Fold into `docs/features/benchmarks.md` after hooks land. |
| `benchmark-server-creation-deployment-2026-06-10.md` | Keep | Still the active deployment/runbook plan for benchmark environment creation. |
| `benchmark-strategy.md` | Fold then delete | Strategic benchmark selection is now better represented by `docs/features/benchmarks.md`, `docs/operations/benchmark-environment.md`, and benchmark reports. Keep only if actively used for publishing decisions. |
| `c3-tenant-orm-isolation.md` | Keep | Deferred high-risk tenant isolation design; not implemented. |
| `change-impact-guardrail.md` | Keep or move to `docs/features/change-impact-guardrail.md` when implemented | Proposed product feature, not implemented. |
| `codegen-engine-extraction.md` | Keep, trim shipped phases | Phases 1-4 shipped; Phase 5 observer/cost/SSE seam remains. Reduce to remaining phases after `docs/features/run-execution.md` absorbs shipped state. |
| `latency-reduction-phased.md` | Keep | Partially implemented prerequisites, but default flips and parallel execution remain. |
| `openrouter-aggregator-and-model-benchmark-2026-06-07.md` | Fold then delete | The current route/model truth is now in `docs/features/models-and-routing.md`; retain only the VM A/B action item elsewhere. |
| `preview-live-environment-rebuild.md` | Keep | Preview rebuild is not fully implemented; old heuristic path still exists. |
| `preview-phase1-verifyboot-spec.md` | Keep, trim shipped sections | Core verifyBoot pieces landed, but Phase 0 buildpack/network implementation remains. |
| `review-driven-execution-plan-2026-06-10.md` | Delete after this cleanup lands | Execution ordering artifact. Its outcome is captured in the fix report and feature docs. |
| `review-execution-fix-report-2026-06-10.md` | Move to `docs/reports/` or keep temporarily | Valuable evidence ledger, not a long-term plan. |
| `system-docs-and-docs-cleanup-plan-2026-06-10.md` | Delete after cleanup completes | Execution artifact. Keep until this cleanup PR is reviewed. |
| `swebench-harness.md` | Keep or fold into benchmark docs | Contains the BUILD to PATCH pivot and publishability decisions. Fold once benchmark feature/report docs are canonical enough. |
| `tiered-codemap-architecture.md` | Keep | Phase 1 implemented, Phases 2-3 remain. |
| `v2-batch-execution-plan.md` | Keep | Provider clients exist, but coordinator wiring and live smokes remain. |
| `verify-before-complete-design-2026-06-07.md` | Keep, trim shipped Tier 1 | Tier 1 shipped, Tiers 2-3 and build gate remain. |
| `vix-analysis-and-leverage-plan-2026-06-07.md` | Fold heavily then delete | Large research/audit hybrid. The implemented pieces should live in feature docs and reason-log; remaining backlog should move to `pending.md`. |
| `web-control-plane-reframe.md` | Keep, trim shipped Phase 1 | Console shell/home/gate work exists, but default flip and IDE retirement remain. |

## Delete Now Candidates

Do not delete these blindly if you need a review trail in this branch, but they are not long-term docs:

- `review-driven-execution-plan-2026-06-10.md`
- `system-docs-and-docs-cleanup-plan-2026-06-10.md`

## Fold Then Delete Candidates

These should be folded into feature docs, reports, `pending.md`, or `ROADMAP.md` before deletion:

- `benchmark-strategy.md`
- `openrouter-aggregator-and-model-benchmark-2026-06-07.md`
- `review-execution-fix-report-2026-06-10.md`
- `swebench-harness.md`
- `vix-analysis-and-leverage-plan-2026-06-07.md`

## Keep Candidates

These still contain active unimplemented design:

- `benchmark-closed-loop-2026-06-09.md`
- `benchmark-server-creation-deployment-2026-06-10.md`
- `c3-tenant-orm-isolation.md`
- `change-impact-guardrail.md`
- `codegen-engine-extraction.md`
- `latency-reduction-phased.md`
- `preview-live-environment-rebuild.md`
- `preview-phase1-verifyboot-spec.md`
- `tiered-codemap-architecture.md`
- `v2-batch-execution-plan.md`
- `verify-before-complete-design-2026-06-07.md`
- `web-control-plane-reframe.md`

## My Recommendation

Keep fewer plans, but do not delete active design debt just because Phase 1 shipped. The correct cleanup sequence is:

1. Keep active future-design plans.
2. Move shipped feature truth into `docs/features/`.
3. Move open backlog bullets into `docs/pending.md`.
4. Move evidence ledgers into `docs/reports/` only when they are still useful.
5. Delete execution artifacts once the above is done.
