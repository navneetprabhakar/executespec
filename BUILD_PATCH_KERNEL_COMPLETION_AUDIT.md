# BUILD/PATCH shared-kernel completion audit

Audit date: 2026-08-10
Implementation baseline: `86fa732bbb89cb9629f84c64036e1b62c88d3c7c`
Local implementation commit: `dc40635de` plus the uncommitted audit correction described here.

This is a proof ledger, not a launch declaration. `app.executespec.ai` deployment and paid live
qualification remain outside the current authorization. ExecuteSpec score is never acceptance
authority.

## Current verdict

- M0-M5 are implemented locally.
- M6 is locally complete: the final tree passed the full backend/PostgreSQL and frontend gates
  listed below.
- M7 has provider-free transaction/mechanism evidence. It does **not** yet have a real paired-model
  code-delivery-quality result; the scripted 30%/100%/100% comparison cannot prove model quality.
- M8 is open: no push, deployment, paid model call or live run was authorized in this goal turn.
- The product flag remains default-off. This audit does not authorize enabling it in dev.

## Requirement-by-requirement evidence

| # | Requirement | Local status | Direct evidence |
| --- | --- | --- | --- |
| 1 | Shared code-change kernel | Implemented | `engine.CodeChangeLoop`; `WorkerAgent` implements it; `SequentialExecutor` and `CodeGenEngineImpl` depend on the port. `CodeChangeLoopArchitectureTest`. |
| 2 | BUILD orchestration remains distinct | Implemented | BUILD remains in `RunOrchestrator`/`SequentialExecutor`/`BuildTaskGenerationCoordinator`; PATCH remains in `CodeGenEngineImpl`. No BUILD call to PATCH orchestration. |
| 3 | Typed task acceptance contract | Implemented for resolved criteria | Schema-v2 `TaskAcceptanceContract` freezes runtime, baseline, exact server build/test commands and timeouts, required paths/selectors/endpoints, meaningful-diff policy, repository scopes and named prefix/final checks. Digest is persisted and receipt-bound. `TaskAcceptanceContractResolverTest`. |
| 4 | Greenfield baseline adapter | Implemented | `GreenfieldBaselineService` plans without side effects and materializes minimum Python, Node/TS, Java/Maven, React or explicit multi-repo configuration/tests without product code. Unsupported/ambiguous tasks fail closed. `GreenfieldBaselineServiceTest`. |
| 5 | Failure-first BUILD | Implemented, fail-closed | V378 persists the **actually executed** initial probe before provider dispatch. Missing paths and admitted HTTP interface mismatches are red evidence; existing failing build/tests are captured; command drift, unavailable required tests and already-green workspaces stop without a provider call. `TaskAcceptanceVerifierTest`, `BuildTaskGenerationCoordinatorTest`, `WorkspaceGenerationProtocolPostgresTest`. |
| 6 | Test-driven iteration | Implemented | BUILD failure-first enables worker progress-v2, edit-burst auto-verification, deterministic failure deltas, repeated-failure and edit-churn guards. Shell activity alone does not reset post-edit progress. `DeterministicFailureDeltaTest` plus existing worker progress/churn/repetition suites. Final acceptance is independently rerun after the loop. |
| 7 | Transactional generation | Implemented | The loop runs inside one V374/V375 attempt and exact LLM/workspace lease. Verification, checkpoint acknowledgment and accepted-frontier CAS precede acceptance; all other paths roll back. Existing PostgreSQL/crash/recovery suites plus `BuildTaskGenerationCoordinatorTest`. |
| 8 | Multi-task and multi-repository | Implemented | Every task starts at the accepted frontier; route retries create new attempts. Multi-repo contract scopes and combined commands are frozen; generation/checkpoint/rollback remain atomic. Existing archive/protocol/recovery tests and `TaskAcceptanceContractResolverTest`. |
| 9 | Reviewer boundary | Implemented | Initial reviewer remains read-only and downstream admission compares the current generation with the accepted frontier. Corrections retain their separately fenced lifecycle. Existing downstream-admission/correction/reviewer tests. |
| 10 | Routing/model policy | Implemented | Provider-neutral port preserves route pins, overrides, key policy, accounting and continuation controls. A route change is forced into a new physical generation attempt from the accepted frontier. Coordinator/SequentialExecutor route-isolation tests. |
| 11 | Context/token control | Implemented | `ModelOutputTokenBudget` resolves per-model output capacity and reserves completion space; worker compaction retains active deterministic failure; truncation is not completion. `SequentialExecutorTierConfigTest` and existing worker truncation/context tests. |
| 12 | Tool policy | Implemented | Existing tool registry preserves bounded parallel reads and sequential mutations. Server-resolved acceptance commands are rerun independently under exact generation authority. Repeated shell calls do not masquerade as progress. Existing tool/worker authority suites. |
| 13 | Termination semantics | Implemented | Text completion remains provisional. Success requires immutable verification, checkpoint and frontier CAS; failure codes distinguish verifier, rollback/recovery and worker termination causes. Coordinator/lifecycle/worker termination tests. |
| 14 | API/UI evidence | Implemented | Run detail exposes attempt, route, generation, contract, initial acceptance-probe result, usage/cost, receipt, rejected diff and every check outcome. UI distinguishes candidate, failed verification, rollback and acceptance and renders each check. The API redacts hidden check targets/details and raw initial verifier output; immutable internal evidence remains available to verification and operators. Projection and `runResultUtils` tests. |
| 15 | Observability | Implemented for shared-kernel additions | Typed replayable `WORKSPACE_GENERATION` events include the initial acceptance-probe result and lifecycle states; metrics cover states, verification, rollback and failure deltas. Existing SSE/metrics tests and coordinator assertions. |
| 16 | Compatibility/migration | Implemented | V376 rollout flag is default-off; V377/V378 are forward-only. Historical null fields are tolerated. PATCH callers continue using PATCH policy over the same port. Flyway inventory, DTO compatibility and PATCH/autofix tests. |
| 17 | Required tests | Passed locally | Full Maven verification passed 7,056/7,056 with zero failures, errors or skips, including clean V1→V378 PostgreSQL migration and Hibernate validation. Frontend passed 94 files/741 tests, production build, Prettier and ESLint with zero errors (nine pre-existing warnings). |
| 18 | Qualification | Partial | Ten-category provider-free A/B/C mechanism corpus passes transaction/no-leak gates with zero model spend. Because mutations are scripted, it is not evidence of real model correctness. Separately authorized paired model qualification and live ACCEPTED/ROLLED_BACK proof remain open. |
| 19 | Documentation | Implemented locally, external docs pending final sync | Root/package docs, architecture, API, runbook, migration inventory, qualification method and this audit are updated. External docs live in the separate docs worktree and must be committed without absorbing unrelated branch changes. |
| 20 | Definition of done | Not complete | Local architecture and containment are implemented. Full final verification, real paired quality evidence, and separately authorized dev positive/negative proof are still required. |

## Acceptance-strength boundary

The system does not invent semantics it cannot deterministically execute.

- Existing red build/tests are strong brownfield evidence.
- A missing required path is a deterministic structural verifier and is enough to start a bounded
  greenfield repair, but it is not by itself proof of business behavior.
- HTTP tasks additionally freeze method/path expectations and verify them against an admitted
  OpenAPI document. A hidden endpoint check can reject a candidate even when worker-authored tests
  are green.
- If all frozen paths/interfaces and existing tests are already green, or required command
  evidence is unavailable/drifted, provider dispatch is refused. There is no silent fallback to an
  LLM judgment. A future weaker-contract approval workflow would require a separately designed,
  explicit authority receipt; it is not fabricated here.

## Final local verification commands

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q
npm --prefix frontend test -- --run
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint
node scripts/build-kernel-provider-free-qualification.mjs
node --test scripts/build-kernel-provider-free-qualification.test.mjs
```

Final local results: backend 7,056/7,056; frontend 94 files/741 tests; production build and format
green; lint zero errors with nine pre-existing non-null-assertion warnings. The provider-free
mechanism corpus remains A=30%, B=100%, C=100% with zero invariant violations. These counts do not
change the M7/live claim boundary.

## Live authorization gate

A later authorization must explicitly name the provider/model matrix and per-run cap. The live
qualification must run on `app.executespec.ai` using the dev deployment, retain exact route and
held-out evidence, and prove at least one `ACCEPTED` and one `ROLLED_BACK` attempt. Deployment,
push, paid calls, plan approval and benchmark execution are not implied by this document.
