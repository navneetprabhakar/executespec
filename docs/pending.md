# Pending Work

> Single open-work register. Keep this short and actionable; feature truth lives in `docs/features/`, not here.

## P0 Proof Gaps

- Authenticated model-matrix read/write smoke for system default, org override, and user BYOK behavior.
- VM-side latency A/B for Chinese/open models across OpenRouter, Fireworks, and native DeepSeek.
- Fireworks live API smoke with a real key.
- Real Stripe/Razorpay sandbox checkout and webhook ordering tests.
- Real top-up purchase-to-run-to-invoice path.
- Real cancellation/pause race against active worker execution.
- Real PATCH autofix run with SCM auto-push and cancellation guard evidence.

## Benchmarks

- Wire live Terminal-Bench harness events into `BenchmarkTrackingService`.
- Extend tracking events for SWE-bench and ProgramBench.
- Build benchmark environment from the deployment plan.
- Keep official scorer artifacts immutable and separate from app display.

## Models And Routing

- Decide whether OpenRouter slug mapping moves from Java allow-list to YAML-declared verified slugs.
- Decide if Fireworks becomes preferred Chinese-model aggregator after VM data.
- Add user-level model preference only after BYOK key-lane behavior is proven.
- Keep FREE model default in properties only.

## Preview And Verification

- Implement preview Phase 0 buildpack/network/SA runner.
- Complete verifyBoot production runner path.
- Implement verify-before-complete Tiers 2-3 and build gate.
- Evaluate default flips only after cross-provider no-regression runs.

## Latency

- Evaluate context compaction default ON.
- Evaluate skipping redundant per-task builds.
- Revisit parallel execution only after deterministic verification is stronger.

## Security / Access

- C3 tenant ORM read-isolation remains deferred.
- Collapse dead Signal-era branches and orphaned components.
- Remove or replace deprecated `push-github` after frontend callers migrate.
- Continue reducing swallowed exceptions and lazy wiring cycles.

## Documentation

- Delete or fold plan files according to [`plan-disposition-2026-06-10.md`](plan-disposition-2026-06-10.md).
- Keep `docs/features/` updated when shipped behavior changes.
- Keep `review/` preserved unless explicitly instructed otherwise.
