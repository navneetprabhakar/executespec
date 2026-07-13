# ExecuteSpec backend adversarial assessment — engineering personas (historical)

> **Historical snapshot only.** This document was captured against an earlier tuple and must not be used as current release evidence. In particular, its approval-gate finding predates the durable non-blocking `GateStore` implementation. Re-run the scenarios in [`post-goal-adversarial-assessment-protocol-2026-07-13.md`](post-goal-adversarial-assessment-protocol-2026-07-13.md) against a sealed current tuple.

Date: 2026-07-12  
Scope: backend worktree `executespec-2.0`, current branch `codex/executespec-2.0`  
Method: read-only inspection of production paths, tests, migrations, release-readiness rows, and the deletion register. No provider calls were made.

This is an engineering risk assessment, not a benchmark or SOTA claim. Findings are ranked by release impact, evidence strength, and likelihood of harming a daily user.

## Findings

### P0 — Approval-gate execution still has a blocking wait path

Evidence: `src/main/java/com/navneet/executespec/harness/GateStore.java:252` exposes `awaitPlanGate(String)` and its class contract documents registering a `CompletableFuture` and waiting. The master plan identifies this as a fixed orchestration-pool risk (`docs/plans/executespec-2.0-master-plan.md:31-32`), while release readiness still lists non-blocking suspension as incomplete.

Impact: a plan gate held for a long period can occupy an executor thread and reduce unrelated run throughput. An Engineering Manager will see this as an availability/SLO risk; SDET cannot reliably stress concurrent runs without a starvation test.

Remediation: replace request-thread waiting with persisted `AWAITING_APPROVAL` state plus event/job resumption. Add a concurrency test that holds one gate for the configured maximum while independent runs complete, and a restart test proving the gate survives JVM loss. Do not merely increase the pool.

### P1 — Automatic verification is still incomplete for the core BUILD loop

Evidence: release readiness states VBC is enabled but edit-burst auto-verification remains off (`docs/plans/executespec-2.0-release-readiness.md:12`). `SequentialExecutor` invokes the VBC flag only at the final task (`src/main/java/com/navneet/executespec/harness/SequentialExecutor.java:1805-1815,2172-2174,2396-2398,4828-4830`). The worker VBC path runs one server-owned suite check and then fails open after one refusal (`src/main/java/com/navneet/executespec/engine/WorkerAgent.java:259,1122-1138`).

Impact: a worker can make several edits without a fresh executable signal; a red suite receives one repair opportunity and may still complete. SDET/SDET Manager should treat “tests mandatory” as an observed control, not a prompt claim.

Remediation: graduate the existing schema-free auto-verify path only after an ablation with per-provider latency/cost limits. Keep the one-refusal cap, but add a deterministic completion metric (`red-at-complete`, `repair-pass-rate`, `verification-run-latency`) to run evidence and regression tests for no infinite retry.

### P1 — Model-visible subagent compatibility wording is stale, though the implementation is real

Evidence: `src/main/java/com/navneet/executespec/specchat/SubagentChatTools.java:57-127,152-212` binds `spawn_subagent` and `spawn_subagents` to `SubagentExecutionService`; eligibility is checked before execution and coordinator mode is feature-flagged. `src/main/java/com/navneet/executespec/agentsession/AgentStreamTurnSupport.java:17-35` exposes both names as read-only tools. The old “fake tool” claim was corrected in the master plan/deletion register by this assessment.

Impact: deleting these names now would break the current persisted child-session protocol and cross-surface chat clients. The remaining risk is contract confusion, not fake execution.

Remediation: keep the tools, add an explicit tool-contract test asserting a persisted child-session id and bounded reservation are returned, and deprecate only through a versioned replacement. Track coordinator-auto flag adoption before changing defaults.

### P2 — Prompt/orphan coverage is intentionally partial and can miss dynamic paths

Evidence: the earlier PARTIAL finding is closed. `scripts/validate-prompt-manifest.mjs` and `scripts/prompt-manifest.test.mjs` now validate executable Java literals plus the dynamic provider/build/patch/terminal resources and every project-type/scaffold alias, including the `java-spring-boot` normalization used by `SequentialExecutor`.

Impact: a renamed or removed dynamic fragment can fail only for a provider/project-type combination. Staff Engineering should treat this as a release-integrity gap rather than a cosmetic documentation issue.

Residual: migration recipe content is database catalog data rather than a classpath prompt; it remains covered by its own catalog/schema tests and is intentionally outside the prompt-resource manifest.

### P2 — Release evidence mixes last-recorded and current-SHA results

Evidence: `docs/plans/executespec-2.0-release-readiness.md` now labels backend and CLI rows “last recorded”; `docs/plans/evidence/executespec-2.0-inventory.json` records backend SHA `da086ab...` and CLI SHA `46481333...`, not the current worktree heads. Frontend/backend test counts have changed since earlier rows.

Impact: Product/Engineering management can mistake historical green evidence for current release proof. SDET cannot reproduce a green claim without a sealed SHA and command output.

Remediation: make release-capture generation mandatory immediately before release; include SHA, dirty state, command, test count, and artifact paths. Reject a PASS row when its recorded SHA differs from the release candidate.

### P3 — Legacy compatibility surfaces are broad and not time-boxed

Evidence: deprecated `/api/runs/{id}/push-github` remains in `RunController` (`src/main/java/com/navneet/executespec/api/controller/RunController.java:715-780`) and is still referenced by orchestration, rate limiting, and service comments. `TokenBudgetMonitor` retains deprecated overloads with no production callers (`src/main/java/com/navneet/executespec/agent/TokenBudgetMonitor.java:102-110,213-217`).

Impact: compatibility is valuable, but unmeasured legacy traffic increases test surface and makes cleanup claims ambiguous. This is not a release blocker.

Remediation: add usage telemetry and a removal date per endpoint/overload. Remove only after client-repo and persisted-row compatibility checks are green.

## Persona-specific conclusions

| Persona | Adversarial conclusion |
|---|---|
| Staff Engineer | The architecture has strong evidence seams (typed search, persisted sessions, Flyway contracts), but gate waiting and mixed-SHA evidence are systemic correctness/operability risks. |
| Engineering Manager | The release dashboard currently needs a strict “current SHA” rule; otherwise green historical rows mask unfinished suspension, verification, and visual work. |
| SDET | The highest-value missing tests are gate non-starvation, red-at-complete/repair metrics, dynamic prompt-key coverage, and current-SHA release-capture validation. |
| SDET Manager | Require every PASS claim to carry command, SHA, environment, and artifact evidence; classify fail-open behavior as a measured outcome, not an implicit pass. |

## Recommended order

1. Remove approval-gate thread blocking and add starvation/restart tests (P0).
2. Add current-SHA release-capture enforcement and dynamic prompt corpus coverage (P1/P2).
3. Measure and then graduate automatic verification with explicit red-at-complete metrics (P1).
4. Time-box legacy endpoint/overload removal after telemetry (P3).
