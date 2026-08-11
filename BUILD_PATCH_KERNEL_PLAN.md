# BUILD/PATCH shared-kernel execution plan

Status: M0-M6 remain implemented. The 2026-08-11 approach correction adds durable typed workspace
origin, model-independent required-artifact authority, immutable external bootstrap tests and
failure-first composition gates. Python/FastAPI and plain Node JavaScript adapters now pass real
provider-free build/test/held-out behavior checks. TypeScript, Java/Maven and React are deliberately
fail-closed under the rollout flag until equally external, non-no-op adapters are qualified. M7
still has provider-free mechanism evidence but real paired code-generation quality remains open.
The first authorized candidate pair stopped in planner recovery before any task or shared-kernel
attempt, so it is diagnostic evidence only. Deployment `31421957285` and the live canonical
`/opt/app/RUNNING_SHA` attestation prove only the older qualification release; the shared-kernel
flag remains off and no current branch deployment or paid replacement pair has run.

## Non-negotiable invariants

1. BUILD orchestration never invokes PATCH orchestration as a black box.
2. BUILD and PATCH execute mutations through one provider-neutral `CodeChangeLoop` port.
3. Only a verified workspace generation may advance the accepted frontier.
4. A rejected or unresolved generation cannot feed another task, reviewer, checkpoint, validation,
   preview, deployment, or SCM publication.
5. Provider calls and mutating tools remain bound to the exact active generation/LLM lease.
6. Server-resolved commands, not planner/model-authored commands, are acceptance authority.
7. Shell activity alone is not progress. Progress requires a mutation, changed deterministic
   evidence, or acceptance-check advancement.
8. Route changes create a separately fenced physical attempt and never inherit accepted authority.
9. Existing PATCH, benchmark, and autofix callers retain their current mode-specific policy.
10. Streaming, prompt tuning, token increases, reviewer mutation, or internal score changes cannot
    substitute for these invariants.

## Milestone gates

| Milestone | Outcome gate | Status |
| --- | --- | --- |
| M0 | Baseline, invariants, corpus, thresholds and rollback-safe flag are frozen. | COMPLETE |
| M1 | BUILD and PATCH depend on the shared kernel port; PATCH behavior is characterized. | COMPLETE |
| M2 | Supported BUILD tasks begin with a typed contract, deterministic baseline and actually observed executable failure signal. | COMPLETE |
| M3 | Shared BUILD loop runs inside V374/V375 accept-or-rollback authority. | COMPLETE |
| M4 | Multi-task/repo, routing, context, tool, termination and reviewer semantics are proven. | COMPLETE |
| M5 | API/UI/SSE/metrics expose independently inspectable attempt, observed initial failure and per-check acceptance evidence. | COMPLETE |
| M6 | Full compatibility, crash, race, rollback and regression gates pass. | COMPLETE |
| M7 | Provider-free held-out A/B/C mechanism evaluation clears transaction gates; real code-generation quality comparison remains required. | PARTIAL |
| M8 | Documentation, recovery runbook, separately authorized live proof and completion audit close. | LIVE PROOF PENDING |

## Authoritative baseline

- PATCH entry: `engine.CodeGenEngineImpl` constructs PATCH policy and dispatches one prepared
  workspace into the mutation loop.
- BUILD entry: `harness.SequentialExecutor` constructs per-task BUILD policy and delegates the
  entire attempt to `BuildTaskGenerationCoordinator`.
- Shared implementation before M1: both callers directly depended on `WorkerAgent`; reuse existed
  but was not an enforceable architecture port.
- Transaction authority: `workspacegeneration.WorkspaceGenerationLifecycle`, coordinated through
  `BuildTaskGenerationCoordinator`.
- Acceptance authority: `TaskAcceptanceContractResolver` plus `TaskAcceptanceVerifier`; the M0
  schema is server-owned but does not yet freeze the complete runtime/check/command contract before
  mutation.
- Live negative-path baseline: Sonnet run `df74e82f-f593-44e3-8e81-eed3b42b6c5e` and GLM run
  `1572b2eb-bb24-4b3b-84eb-a5c34375827e` both wrote three files, failed deterministic tests and
  reached `ROLLED_BACK` without downstream leakage.
- Positive live accepted-generation proof remains absent and cannot be claimed from unit tests.

## Implemented kernel

- `engine.CodeChangeLoop` is the provider-neutral inspect/edit/test/diagnose port. `WorkerAgent` is
  its production implementation; PATCH `CodeGenEngineImpl` and BUILD `SequentialExecutor` depend
  on that port while keeping separate orchestration and policy.
- With `build.shared-code-change-loop.enabled`, BUILD freezes a schema-v2 acceptance contract and
  deterministic greenfield bootstrap before mutation. Workspace origin comes from durable
  submission/repository admission facts, never incidental `.gitignore`/`.gitattributes` files.
  Python and plain Node JavaScript receive external executable red surfaces without product
  implementation; supported Python/Node multi-repository combinations use the same rule.
  TypeScript, Java/Maven, React, ambiguous artifact sets and unsupported mixed repositories stop
  before provider dispatch.
- Deterministic failure deltas are typed as INITIAL_FAILURE, IMPROVED, REGRESSED, CHANGED,
  UNCHANGED, or PASSED. V378's exact red count/signature seeds the first post-edit comparison;
  subsequent active failure evidence is pinned outside generated compaction summaries. Only
  changed/improved/passed evidence resets progress; repeated identical failure is surfaced instead
  of treating shell activity as progress.
- Every provider/model change re-enters a new V374 generation attempt from the accepted frontier.
  A route cannot inherit and later legitimize another route's speculative candidate.
- V377 freezes contract, planned initial-failure, requested/effective route,
  iteration/tool/call/token and cost evidence. V378 separately freezes the failure actually
  executed before provider dispatch. Run detail, typed SSE, metrics, and the Evidence UI expose
  candidate, verification, per-check outcomes, and rollback as separate trust facts.
- Hidden HTTP acceptance targets stay internal: the worker receives generic actionable failure
  context and the public API exposes only the hidden check outcome/generic label.
- Customer workspace export obtains publication authority, repeats the accepted-frontier proof
  under that lease and holds it through traversal. The download response and qualification harness
  bind the ZIP to the accepted generation; lease loss aborts and removes any partial archive.
- Acceptance commands are classified as reproduction, narrowest-available iteration evidence or
  broad verification. Metrics keep those classes, attempt/route outcomes and authority/recovery
  controls independently visible.

## Qualification comparison

- A: current BUILD behavior at the M0 baseline.
- B: BUILD through the shared failure-first kernel.
- C: current PATCH behavior where the case has an existing-repository/failing-check equivalent.

The evaluator must be held out from the model and must not read ExecuteSpec's verified score.

The provider-free scripted mechanism run is reproducible with:

```bash
node scripts/build-kernel-provider-free-qualification.mjs
node --test scripts/build-kernel-provider-free-qualification.test.mjs
```

The 10-category result was A=30%, B=100%, C=100% (70 percentage-point uplift), with zero
candidate wrong accepts, unresolved attempts, rollback mismatches, or PATCH regressions. This is
mechanism evidence using scripted mutations and zero model calls; it is deliberately not presented
as real code-generation quality evidence and does not close M7 by itself. The generated report is
`target/build-kernel-qualification/provider-free-v1.json`.

The paid qualification manifest is schema v2 and deliberately permits exactly two fresh
`LIVE_CANDIDATE` runs: Anthropic `claude-sonnet-5` and OpenRouter `glm-5.2`, each capped at $5. The
pinned historical Sonnet and GLM `ROLLED_BACK` runs are immutable controls, not additional paid
runs. The harness validates those controls, release/spec/harness digests, exact routes, frontier
restoration and independently reviewed rejected diffs before it can dispatch either candidate.
Those controls rolled back before `beginVerification`; their null candidate generation is recorded
as `LEGACY_REJECTED_DIFF_ONLY` and corroborated by durable failed task-smoke evidence. The
SUPER_ADMIN-only no-store qualification endpoint exposes the preserved rejected diff for review
without widening customer run detail.
After the candidate release is deployed, `scripts/capture-paired-build-controls.sh` performs only
read-only API capture and writes review templates; it never submits a run or certifies the rejected
diff. `run-paired-build-qualification.sh --execute` refuses both candidate submissions until the
completed manual reviews make the controls-only validator pass. It also authenticates and reads the
global rollout flag immediately before each paid submission; `false` or missing aborts before run
creation. The same preflight reads the no-store SUPER_ADMIN release identity backed by
`/opt/app/RUNNING_SHA`; a missing, malformed or mismatched running SHA aborts before run creation.
The executor passes that exact SHA, canonical authority and server read timestamp into the
candidate artifact; validation requires the attested SHA to equal the frozen candidate release, so
the evidence is independently inspectable instead of relying only on the executor's control flow.
A candidate record is inadmissible unless the accepted attempt proves
`FAILURE_OBSERVED`, names the initial failure code, and exposes at least one frozen acceptance
check. The harness never mutates the flag.

The first live candidate pair produced runs `52a21e50-c9dd-4330-92e4-eb6845e79706` and
`1ee17f50-52d0-4a94-8bec-1b47cf60e0ee`. Both failed in planner recovery with zero tasks and zero
workspace-generation attempts. Root-route normalization was corrected in `b7630ce7f`; these runs
cannot serve as either ACCEPTED or ROLLED_BACK kernel proof and are not silently reused.

Required corpus categories: empty Python library, empty REST API, empty TypeScript library, empty
frontend component, brownfield feature addition, brownfield bug fix, multi-file wiring, database
migration, multi-task dependency, and multi-repository contract change.

## Predeclared go/no-go thresholds

- leaked or wrongly accepted generations: exactly zero;
- unresolved attempts at terminal: exactly zero;
- held-out correctness: at least 20 percentage points above baseline BUILD once the corpus has an
  adequate comparable sample;
- PATCH regressions: zero on deterministic existing passing cases;
- positive proof: at least one accepted case per supported ecosystem;
- negative proof: at least one exact rollback per major failure class;
- identical failing command repetition: must stop at the configured threshold;
- acceptance receipt, accepted frontier and checkpoint generation: exact equality;
- cost and wall-clock: reported by route and bounded by the request; no hidden fail-open budget.

## Authorization boundary

Local edits and tests are authorized. Push, deployment, benchmark execution, paid provider calls,
and live qualification require separate explicit authorization. A later live approval must state the
provider/model matrix and per-run cost cap.
