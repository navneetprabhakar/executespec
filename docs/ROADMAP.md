# ExecuteSpec Roadmap

> Current ordered roadmap after the 2026-06-10 review-driven fix batch and docs cleanup. Detailed open-work inventory lives in [`pending.md`](pending.md).

## P0 - Prove The Recent Fixes Live

1. Authenticated model-matrix UI/API smoke with real org/user sessions.
2. VM-side Chinese-model latency A/B: OpenRouter default, OpenRouter latency sort, Fireworks, native DeepSeek.
3. Real payment sandbox flows: Stripe/Razorpay checkout abandonment, webhook ordering, top-up activation.
4. Real SCM PATCH autofix run after cancellation/terminal-state hardening.
5. Benchmark event hooks from live harness runs into `/benchmark`.

## P1 - Benchmark Server

Build the dedicated benchmark environment only after the blocker list in [`plans/benchmark-server-creation-deployment-2026-06-10.md`](plans/benchmark-server-creation-deployment-2026-06-10.md) is closed:

- benchmark profile,
- event ingest,
- artifact bucket,
- external harness publishing,
- operator dashboard,
- teardown/runbook.

## P2 - Preview / Verify Runtime

Continue the preview rebuild and verifyBoot path:

- implement Phase 0 buildpack/network/least-privilege preview runner,
- keep egress deny-by-default,
- make run-recipe execution deterministic,
- delete old heuristic preview code only when the replacement path passes.

Plans:

- [`plans/preview-live-environment-rebuild.md`](plans/preview-live-environment-rebuild.md)
- [`plans/preview-phase1-verifyboot-spec.md`](plans/preview-phase1-verifyboot-spec.md)

## P3 - Run Quality And Latency

- Finish verify-before-complete Tiers 2-3.
- Evaluate context compaction default flips.
- Revisit per-task build skipping.
- Reintroduce parallel execution only behind eval proof.

Plans:

- [`plans/verify-before-complete-design-2026-06-07.md`](plans/verify-before-complete-design-2026-06-07.md)
- [`plans/latency-reduction-phased.md`](plans/latency-reduction-phased.md)

## P4 - Remaining Architecture Debt

- Code-gen engine observer/cost/SSE seam.
- V2 batch coordinator wiring and live provider batch smokes.
- C3 tenant ORM read-isolation.
- Tiered codemap phases 2-3.

Plans:

- [`plans/codegen-engine-extraction.md`](plans/codegen-engine-extraction.md)
- [`plans/v2-batch-execution-plan.md`](plans/v2-batch-execution-plan.md)
- [`plans/c3-tenant-orm-isolation.md`](plans/c3-tenant-orm-isolation.md)
- [`plans/tiered-codemap-architecture.md`](plans/tiered-codemap-architecture.md)

## P5 - Product UX

- Decide whether Console becomes default control-plane shell.
- Keep IDE surfaces only where they serve web control-plane workflows.
- Collapse remaining dead Signal-era frontend branches.
- Add change-impact guardrail at plan gate when contract/dependency data is reliable enough.

Plans:

- [`plans/web-control-plane-reframe.md`](plans/web-control-plane-reframe.md)
- [`plans/change-impact-guardrail.md`](plans/change-impact-guardrail.md)

## Cleanup Rule

Before deleting any plan, check [`plan-disposition-2026-06-10.md`](plan-disposition-2026-06-10.md). Shipped behavior belongs in `docs/features/`; unfinished work belongs in `pending.md` or an active plan.
