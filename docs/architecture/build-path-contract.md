# BUILD execution and evidence contract

Current source contract, reviewed 2026-10-17 for backend version `2.0.0`. This describes the local
working tree; it is not a deployed-release attestation. Migration head is **V403**, with **389**
versioned migration source files; the next allocatable version is **V404**. Applied migrations are
immutable. Historical release identities and test counts remain historical.

## Admission and planning

`run/CustomerRunSubmissionService` owns customer BUILD admission, including actor/tenant access,
idempotency, provider policy and publication-target checks before asynchronous execution.
`integration/RunOrchestrator` coordinates classification, planning and the human plan gate.
Production execution requires the applicable approval and credit confirmation; prototype or
server-authorized automation follows its explicit gate policy.

A shared-kernel plan must pass `BuildPlanCapabilityPolicy` before asking for approval. Bootstrap
qualification currently covers Python and non-HTTP JavaScript Node surfaces, including supported
path-explicit repository combinations. Detectors and materializers for other languages do not imply
qualified bootstrap acceptance. Unsupported plans fail with `BUILD_PLAN_RUNTIME_UNSUPPORTED`.
Read the resolved contract for existing repositories; these bootstrap limits are not a blanket
statement that every brownfield language is unavailable.

Local-hands BUILD is unavailable: clients do not yet provide generation-bound verification and
checkpoint receipts. A PAT and `worker.local-hands.enabled=true` cannot override this boundary.
Customer `localExecution=true` receives 409 when transport is disabled; with transport enabled,
browser/JWT receives 403 and PAT BUILD still receives 409. Capability discovery advertises no local
BUILD submission authority. Submit remote BUILD instead. Separate local-agent capabilities and
operator benchmark transports are not qualified by this restriction or by the fixtures below.

## One baseline, cumulative acceptance

Shared-kernel schema-3 task contracts distinguish `BOOTSTRAP`, `CHANGE`, `REPAIR` and `NON_CODE`.
Bootstrap occurs once per workspace. Later tasks reuse the immutable baseline rather than creating
another skeleton. A healthy initial baseline can record `ALREADY_PASSED` for applicable feature,
refactor or documentation work; the system must not manufacture a failing observation.

`harness/BuildTaskGenerationCoordinator` binds the logical task context before claiming the physical
operation. The worker edits a candidate generation. Completion text and a green smoke command are
provisional evidence. Frozen deterministic verification decides acceptance, including required
artifacts, admitted endpoint expectations and the cumulative accepted-prefix suite. Ignored cache
cleanup removes local ignore files last and fences every entry.

Corrections bind the exact base attempt UUID, accepted task index and workspace generation. V401
makes acceptance replace that precise prefix through a database compare-and-set; ordinary tasks
remain contiguous. A legacy correction preserves its accepted prefix without claiming schema-3
runtime proof. Infrastructure failure blocks model retry; an actual product rejection may enter a
bounded repair path. Provider handoff diagnostics do not grant acceptance authority.

Continuation actions distinguish `RESERVED` from `EXECUTED`; only exact physical attempt evidence
settles execution. Durable switch/step counters include consumed retries and fail closed if unreadable.
Worker context snapshots are physical-attempt-bound, so a stale cache cannot replay into another
attempt even if cache deletion fails.

## Recovery, review and reporting

V403 persists immutable plan-bound `buildExecutionPolicy`. Recovery, manual and approved corrections,
review and validation use the same accepted disposable-verification lifecycle and frozen policy.
The shared-kernel review path is read-only. Legacy review mutation behavior is a separate path and
must not be used to describe shared-kernel acceptance.

Verification owns a durable sandbox lease before exposing its workspace. One owner/token covers
materialization, reads, provider calls and commands. `VerificationLeaseHeartbeat` renews sessions on
a dedicated lifecycle-managed daemon; `executespec.sandbox.verification-heartbeat-interval-ms`
defaults to `10000` and accepts `1..30000`. Renewal never shortens a longer command lease. Loss,
expiry, takeover, cancellation or closure fences subsequent tool/provider dispatch permanently;
a failed session cannot reacquire authority. Recorded command receipts remain readable until close.

Accuracy assessment retains filename prefixes correctly and includes README and dependency pins in
its bounded configuration-evidence lane. These inputs improve grounding; they do not relax frozen
acceptance, report citations, scoring or trust caps.

V402 binds asynchronous concrete test evidence to generation, execution ID and start time. Row locks
reject stale, duplicate or mismatched results; a real concrete-test failure cannot leave a falsely
successful terminal outcome. A report's goal proof may remain `UNCERTAIN` even when workflow status
is `COMPLETE`: candidate-authored tests and LLM assessment are not independent product proof.
Verified score, workflow status, deterministic acceptance and external held-out results are separate
claims.

## Publication and previews

Publication rechecks parent status and exact accepted authority before SCM or deployment effects.
Stopped, superseded or missing parents fail closed. Durable external dispatch uses parent-first
locking; guards apply before push, PR/repository creation, image push and deployment. Terminal events
publish after automatic SCM push settles.

Preview builds use unique staging contexts bound to canonical hashes and session generations. A
stopped build must unwind before restart; functional tests check artifact identity before and after
execution. Generation-bound background validation cannot overwrite newer evidence.

Run usage accumulators remain available for 24 hours after retirement so late consumers do not
observe reset counters. None of these controls makes publication, preview or deployment enabled by
default or independently qualified.

## Provider configuration

YAML `model-matrix` remains authoritative. Benchmark planning defaults use `ModelRegistry` rather
than a copied provider/model switch. Fireworks currently selects `deepseek-v4p1-flash-fw`, mapped to
`accounts/fireworks/models/deepseek-v4p1-flash`; obsolete Fireworks pins require explicit reselection.
Historical usage/pricing records are preserved.

Bounded DeepSeek classification/planning/coherence/forced-tool/reviewer calls disable hidden thinking
through each transport's native parameter: native DeepSeek `thinking`, OpenRouter
`reasoning.enabled=false`, Fireworks `reasoning_effort=none`. Ordinary worker reasoning keeps its
normal policy. OpenRouter retains latency routing and usage hints.

`ANTHROPIC_WORKSPACE_ID` is optional. Both normal and forced-tool Anthropic transports send its header
only when the selected credential matches the configured platform key; unrelated BYOK credentials
do not inherit the platform workspace. Never store keys or real workspace identifiers in examples.

## Local verification checkpoint

The 2026-10-17 frozen-source clean backend verification passed **7,291 tests**, with zero failures,
errors or skips. A separate final PostgreSQL cohort passed **2/2** for verification-session ownership
and command journaling. The provider-free Spring E2E preflight passed four checks and deliberately
skipped four live cases. Default Maven verification excludes the `e2e`, `integration` and `chaos`
tags; these counts are not a claim that all integration suites ran.

| Selected live route | Accepted tasks | Concrete tests | Outcome / external checks |
| --- | ---: | ---: | --- |
| Native DeepSeek Flash | 3 | 35/35 | COMPLETE, CLEAN; held-out checks passed |
| Anthropic Haiku 4.5 | 1 | 29/29 | COMPLETE, CLEAN; held-out checks passed |
| Gemini 3.5 Flash-Lite | 1 | 12/12 | COMPLETE, CLEAN; held-out checks passed |
| OpenRouter DeepSeek V4 Flash | 3 | 9/9 | COMPLETE, CLEAN; held-out checks passed |
| Fireworks DeepSeek V4.1 Flash | 3 | 13/13 | COMPLETE, CLEAN; held-out checks passed |
| OpenAI GPT-5.4 Mini | 3 | 5/5 | COMPLETE, CLEAN; held-out checks passed |

These are selected-route local Python fixture results across successive runs, not a provider-wide
reliability benchmark or a production qualification. Anthropic and Gemini do not establish
multi-task composition. Earlier failures and rejected diagnostic candidates remain negative evidence;
the table lists successful final runs, not every attempt. Auto-push, preview and deployment were
disabled in the live fixtures. Separate deterministic/database coverage does not turn them into
live remote-publication or deployment proof. Private transport captures, credentials and workspaces
remain outside source control.

For reproducible commands, prerequisites, exact-run budget reservations and live-test opt-in, see
[the README verification guide](../../README.md#verification). No provider call is needed for a
normal documentation check. See package-local notes in `workspacegeneration`, `harness`,
`integration`, `sandboxroute`, `report`, `testgen`, `deployment`, `context`, `cost`, `agent`, `router`
and `reviewer` for implementation ownership.
