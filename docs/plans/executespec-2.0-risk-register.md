# ExecuteSpec 2.0 risk register

| ID | Risk | Severity | Control / evidence required | State |
|---|---|---|---|---|
| R-01 | Blocking approval waits exhaust the orchestration pool | P0 | Persisted suspension; load/restart tests | OPEN |
| R-02 | Adaptive routing optimizes API success rather than task quality | P0 | Outcome store and replay-tested single router | OPEN |
| R-03 | Live evaluation exceeds per-provider authority | P0 | Canonical ledger now reserves/dispatches/reconciles and locks on breach; remaining direct transports must join the guard | PARTIAL CONTROL |
| R-04 | Tests accidentally use real provider credentials | P0 | Default Maven forks disable both routers; goal-live LlmRouter calls require exact ledger scope; direct/legacy transport inventory still needs closure | PARTIAL CONTROL |
| R-05 | Client disconnect strands remote tool calls | P0 | `AWAITING_CLIENT`, replay, idempotent result contract | OPEN |
| R-06 | Sarvam remains discoverable or selectable after nominal removal | P0 | Enum/config/catalog/UI/API/docs scan plus forward data migration | OPEN |
| R-07 | Prompt tuning hides search/context/verification defects | P1 | Mechanical milestones precede prompt ablations | CONTROLLED BY ORDER |
| R-08 | Four CLI renderers make behavior and QA non-deterministic | P1 | One semantic state, OpenTUI + plain only, deletion gate | OPEN |
| R-09 | Web redesign preserves backend taxonomy instead of workflow | P1 | Shared protocol state and workflow usability tests | OPEN |
| R-10 | FastContext adds dependency/cost without recall gain | P1 | Adapter-isolated paired experiment and explicit reject path | OPEN |
| R-11 | Child agents multiply cost/context and obscure accountability | P1 | Total-tree budgets, depth/role caps, parent-owned audit/cancel | OPEN |
| R-12 | Exploratory benchmark evidence is marketed as parity | P0 | Frozen comparator, k>=3 where affordable, readiness statuses | OPEN |
| R-13 | Docs publish stale or broken current truth | P1 | Generated facts/map and integrity validators | OPEN |
| R-14 | Cleanup damages user-owned dirty work | P0 | Isolated worktrees, path-scoped staging, pre-commit status audit | CONTROLLED |
| R-15 | Rust rewrite consumes release without measured benefit | P1 | TS-first profiling; Rust only behind proven bottleneck boundary | CONTROLLED |
| R-16 | Frontend toolchain contains a critical Vitest advisory plus 23 lower-severity advisories | P1 | Upgrade with test/build verification; record accepted residuals | OPEN |
