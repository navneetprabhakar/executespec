# Durable LLM execution context

`llm_execution_context` is the secret-free, tenant-scoped identity and lifecycle boundary for
customer and explicitly internal LLM operations. V282 creates the table. Every production
`LlmRouter` dispatch now requires the exact context id plus tenant scope when
`executespec.llm.execution-context-enforcement.enabled=true` (the default). Missing, stale,
terminal, cross-tenant, out-of-policy and exhausted contexts fail before provider transport.

## Invariants

- `CUSTOMER` contexts always carry `orgId`, `userId`, and `isolationKey=org:<orgId>`.
- `PLATFORM_INTERNAL` is explicit and uses `isolationKey=platform:internal`.
- Provider credentials or encrypted credential envelopes are never persisted here.
- `LlmPolicySnapshot` records allowed providers/models and only the credential source label
  (`USER`, `ORG`, or `PLATFORM`). Dispatch must resolve the current credential later.
- `LlmExecutionPolicyFactory` reconstructs a customer context from the authoritative run, agent
  session or project plus the current org/user policy. OWN_KEYS never adds a PLATFORM source.
- Operator-controlled benchmark/SWE-bench runs (`pipeline_run.sourceType` starting `benchmark` or
  `swebench`) set `allowProviderFailover=false` and use the distinct `route-v3-controlled-worker`
  policy generation, so `LlmExecutionDispatchGuard` rejects every alternate provider inside
  `LlmRouter.maybeFailover` and a fixed-route campaign cannot be silently served by another
  provider. The generation key must stay distinct from `route-v1`: contexts are recovered by that
  key, so reusing it would let resumed runs keep the old permissive policy. `!ownKeysProviderLock`
  remains a separate leading term — BYOK containment must not depend on the controlled-worker term.
- Customer `OWN_KEYS` contexts narrow `allowedProviders`, credential provenance and model policy
  to the explicitly selected provider and set `allowProviderFailover=false`. Other configured BYOK
  keys remain stored but are ineligible for dispatch, model/provider continuation and chat/action
  work unless the customer explicitly selects that provider for a new context.
- Chat turns use `forChatTurn`: V283 usage is attributed to the initiating actor, while a
  cross-org project collaborator is bound to the host project's org and credential subject. Its
  V282 budget carries the chat token/USD/provider-call ceilings and is embedded in the immutable
  `ProjectChatPolicy` used at every model/tool boundary.
- `LlmExecutionDispatchGuard` reloads the context and the current encrypted credential immediately
  before every physical provider attempt. A removed key, moved USER→ORG credential, changed
  key-source mode, disabled provider, invalid model, pin violation or call/token/cost exhaustion is
  a policy rejection, not a platform fallback.
- Provider failover and model step-up/down are re-authorized against the same snapshot. A candidate
  that is not allowed and currently credentialed is skipped before spend.
- Credentials are not stored in V282 or `ExecutionContext`; only the context reference propagates
  through planner/worker/reviewer/compactor children and survives a JVM restart.
- Lifecycle mutations take a tenant scope and use a pessimistic row lock plus JPA `@Version`.
- The `LlmExecutionContext` entity enforces lease transitions correctly (an active lease cannot be
  stolen; after expiry a new owner may claim and increment `attempt`; stale owners cannot heartbeat,
  complete, or fail after lease loss). **These are unit-tested entity rules, not runtime guarantees
  — see the RR-1666 note below.**
- **RR-322 / RR-1666 — the lease/attempt fence is BUILT BUT NOT WIRED.** `LlmExecutionDispatchGuard`
  contains two fences: (1) a context whose lease owner is set and whose lease has EXPIRED is not
  dispatchable, and (2) a caller that declares `LlmRequest.llmExecutionAttempt` must match the
  context's current `attempt`. Both are correct and unit-tested, but **neither can fire in
  production**, because nothing on any real dispatch path populates the state they read:
  `LlmExecutionContextService.claim` (the only thing that sets a lease owner or advances `attempt`)
  has ZERO production callers, and no production caller sets `LlmRequest.llmExecutionAttempt` (it is
  always `null`). Every real context therefore stays `QUEUED` with a null lease and `attempt=0`, so
  a stolen/zombie worker is **not** fenced today — the durable-attempt contract does not hold at
  runtime. This is not closed by the RR-321 budget reservation: a duplicate worker is bounded only
  by shared budget, not fenced off.

  **Exact remaining wiring** (all in context-creation/execution callers, not the guard): (a) `claim`
  the context — RUNNING + lease owner + `attempt` — when a worker takes ownership; (b) heartbeat the
  lease during long work; (c) propagate the claimed `attempt` onto every
  `LlmRequest.llmExecutionAttempt`; (d) release (`complete`/`fail`) on terminal. Only then can
  dispatch be restricted to `RUNNING` and the lease made mandatory. The blocker is that ONE run
  context is inherited by planner/worker/reviewer/compaction/report/chat, so a single per-context
  lease owner conflicts with concurrent multi-agent use — wiring needs per-owner sub-leases or an
  ownership redesign, plus context hydration + re-claim on the three `RunLifecycleService`
  resume/retry/re-review roots (still open per the residual inventory below). Restricting dispatch
  to `RUNNING` today would fail-close all customer dispatch rather than fence a stale worker.
- Cancellation is immediate for queued/expired work and cooperative for active work.
- Reusing an idempotency key with a different immutable request fails closed.

## Files

- `LlmExecutionContext` — entity, policy snapshot storage, and lease transition rules.
- `LlmExecutionContextService` — tenant-scoped create/load/claim/heartbeat/terminal operations.
- `LlmExecutionScope` — customer versus platform-internal isolation boundary.
- `LlmPolicySnapshot` — normalized provider/model/key-source route policy without secrets.
- `LlmExecutionBudget` — optional per-context token/cost/call ceilings.
- `LlmExecutionReference` — inseparable context id + scope carried by `ExecutionContext` and
  `LlmRequest`.
- `LlmExecutionPolicyFactory` — idempotent run/agent/project/chat/platform context construction.
- `LlmExecutionDispatchGuard` — last-mile route, credential-source and durable-budget enforcement.
- `LlmExecutionBudgetGate` — RR-321 (V316). Budget evaluation plus reservation, under a pessimistic
  row lock in its own transaction. What is RESERVED vs reconciled-after-the-fact:
  - **Provider-call count (reserved, hard ceiling).** `reserved_provider_calls` is incremented
    before transport, so concurrent authorizers cannot each observe the same remaining allowance and
    `maxProviderCalls` cannot be exceeded. Worst-case equals actual for a call, so
    `max(reserved, recorded)` self-heals with no release path.
  - **Output tokens (reserved).** A provider call cannot emit more than the dispatched model's
    registered `max-output-tokens` (the API-enforced `max_tokens`, a real bound, not an estimate).
    The gate charges every in-flight (authorized-but-not-recorded) call the worst-case output of the
    largest model the context policy admits, so N concurrent dispatches cannot collectively cross
    `maxOutputTokens`, and tightens the `maxTotalTokens` check by that output half. The in-flight
    count is `reserved_provider_calls` minus recorded terminal rows, so it self-heals as calls
    terminalize — no release path, no extra column, no migration.
  - **Input tokens and dollar cost (reconciled-after-the-fact, NOT reserved).** A call's input-token
    count and its dollar cost are only known once the provider responds; the sole a-priori input
    bound is the full context window, which would deny admission far below the real ceiling and so
    is not a usable reservation. `maxInputTokens`, the input half of `maxTotalTokens`, and
    `maxCostUsd` are evaluated against recorded usage — but now under the same lock as one consistent
    read with in-flight calls visible, so a context that has crossed one of those ceilings stops
    immediately instead of racing. A worst-case dollar reservation is deliberately not invented.

## Adopted customer surfaces

- Pipeline planning, worker, reviewer and child compaction calls inherit one run context.
- `LlmExecutionPolicyFactory.forRun` first recovers the original tenant-scoped idempotent
  `pipeline/PIPELINE_RUN/{runId}/route-v1` reference. `OWN_KEYS` runs use
  `route-v2-own-keys-provider-lock`, deliberately bypassing any legacy multi-provider route-v1
  snapshot so recovery cannot restore the former cross-provider eligibility. Recovery always
  checks for the v2 OWN_KEYS generation before reading the org's current mode; a later
  OWN_KEYS-to-HYBRID change therefore returns the immutable v2 context and dispatch rejects the
  stale mode instead of creating a broader route-v1 context. Within each policy generation, the
  factory must not rebuild the context from mutable run rows after planning persists derived budget
  fields; doing so changes the fingerprint and breaks approval-stage recovery (Phase-6 F7, fixed
  2026-07-18).
- Run report, test generation, prompt optimisation, code-map/assessment and run ledger text calls
  recover the same context from the persisted run primary key. The post-run chat copilot instead
  mints a dedicated per-turn context via `forRunChatTurn(runId, turnId)` — identical
  run-owner/org/project/provider/model/pin policy, but a unique per-turn source id so it never
  collides with the run's own (already-terminal) pipeline context. Reusing `forRun(runId)` here
  threw an idempotency-collision 500 on every chat turn (Phase-6 F1, fixed 2026-07-18).
- Agent intent, Spec Chat and agent-session streams carry an agent-turn context.
- Project chat/compaction/compiler, architecture, generated docs, project brief/memory, PR Review
  and Code Review create explicit project contexts and resolve the project provider without a
  hidden Anthropic default.
- `AvailableProviderResolver` no longer reads the dead `RunKeyStore`; it reloads V282 and current
  credential provenance, failing closed to no candidates on missing/stale/tenant-mismatched state.
- Project document/code indexing and query vectors use explicit `EMBEDDING_JOB` contexts through
  `LlmExecutionDispatchGuard.authorizeProviderCapability`. The fixed vector schema supports only
  OpenAI `text-embedding-3-small`; any other selected provider or unavailable key is an honest
  lexical state, never a global-key fallback.

## Deliberate residual gates

- Operator-only model-eval/router-test callers still use synthetic ids. Enforcement safely blocks
  them today; they require explicit `PLATFORM_INTERNAL` contexts before live diagnostic use.
- Legacy pre-flight `ConversationController`, `ExecutionPlannerService` and file-purpose summaries
  still lack a durable customer identity. Enforcement blocks provider transport instead of guessing
  a provider, but those surfaces require an authenticated run/session/project binding to function.
- Cross-JVM job leasing/recovery for PR Review and Code Review remains separate from route safety.
  Their provider dispatch is governed, but their in-memory scheduling is not restart-safe yet.

### Exact residual call-site inventory (2026-07-17)

| Surface | Current behavior with enforcement ON | Required disposition |
| --- | --- | --- |
| `project/rag/EmbeddingService` and its document/code/query callers | V290 uses a project EMBEDDING_JOB context, current exact credential source and V283 attempt rows; unsupported routes are lexical | Run V289→V290 PostgreSQL rehearsal and deployed OWN_KEYS zero-platform-attempt journey |
| `integration/RunLifecycleService` resume/retry/re-review roots | Recreated contexts do not yet carry `forRun(runId)` | Hydrate V282 on all three recovery roots; add restart/retry tests |
| `agentsession/subagent/ImplementerExecutionService` / `ImplementerMutationService` | Parent-session worker context lacks a turn reference | Bind the approved parent session/turn policy before any model mutation |
| `terminalbench/TerminalBenchRunService` | Operator benchmark worker has no explicit context | Create a bounded explicit PLATFORM_INTERNAL benchmark context |
| `eval/v2/TierEvalRunner`, `eval/ModelEvaluator`, `eval/LlmJudgeValidator`, `agentsession/OrchestratorRoutingPromptEvalRunner`, router/worker/planner/reviewer test controllers and benchmark fixture harnesses | Synthetic ids are rejected before transport | Create explicit PLATFORM_INTERNAL contexts only on authenticated operator/test paths |
| `api/controller/ConversationController`, `execution/service/ExecutionPlannerService`, `codeintel/FilePurposeSummaryService` | Customer identity is absent, so enforcement rejects before transport; FilePurpose retains a dead Anthropic default | Bind authenticated agent/run/project identity or retire the surface; never restore a global fallback |
| `rollout/RunService` | Legacy in-memory pipeline has no durable tenant identity and is rejected | Retire or persist/tenant-bind before enabling |
| `chat/ApplyService` | Tool-only context is currently safe only while no LLM request uses it | Bind `forRun` before any future model-backed apply step |

`CodeGenEngineImpl` PATCH workers and the SmartClassifier production shadow call were closed in
this pass: both now hydrate the persisted run context. Their narrow additions were made after the
149-test coordinated cohort and therefore require inclusion in the parent's next compile/regression
slot before release evidence is claimed.
