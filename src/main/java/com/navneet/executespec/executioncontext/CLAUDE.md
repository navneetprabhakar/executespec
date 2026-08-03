# Durable LLM execution context

`llm_execution_context` is the secret-free, tenant-scoped identity and lifecycle boundary for
customer and explicitly internal LLM operations. V282 creates the table. Every production
`LlmRouter` dispatch now requires the exact context id plus tenant scope when
`executespec.llm.execution-context-enforcement.enabled=true` (the default). Missing, stale,
terminal, cross-tenant, out-of-policy and exhausted contexts fail before provider transport.

## Invariants

- `CUSTOMER` contexts always carry host-tenant `orgId`, initiating `userId`, immutable
  `credentialOrgId`, and `isolationKey=org:<orgId>`. V350 deliberately allows the credential org
  to differ from the host tenant for external collaborators; only the host org controls data
  isolation, while credential-policy revalidation and managed-AI debit use `credentialOrgId`.
  V350 stores and backfills the split, adds a CUSTOMER not-null check, and installs separate
  PostgreSQL triggers that reject changes to `pipeline_run.billing_org_id` and
  `llm_execution_context.credential_org_id`. Application creation and dispatch guards enforce the
  same immutable binding before spend. The named V350 migration/immutability PostgreSQL gate and
  clean 5,738-test backend verification pass at checkpoint `13ead3f40`.
- `PLATFORM_INTERNAL` is explicit and uses `isolationKey=platform:internal`.
- Provider credentials or encrypted credential envelopes are never persisted here.
- `LlmPolicySnapshot` records allowed providers/models and only the credential source label
  (`USER`, `ORG`, or `PLATFORM`). Dispatch must resolve the current credential later.
- `LlmExecutionPolicyFactory` reconstructs a customer context from the authoritative run, agent
  session or project plus the current org/user policy. OWN_KEYS never adds a PLATFORM source.
- Operator-controlled benchmark runs — SWE-bench + ProgramBench (`pipeline_run.sourceType`
  `benchmark`), **Terminal-Bench (`TERMINAL_BENCH`)**, and defensive `swebench`/`programbench`
  spellings — set `allowProviderFailover=false` and use the distinct `route-v3-controlled-worker`
  policy generation, so `LlmExecutionDispatchGuard` rejects every alternate provider inside
  `LlmRouter.maybeFailover` and a fixed-route campaign cannot be silently served by another
  provider. `isControlledWorkerRun` delegates to the shared `state.BenchmarkSourceType`
  classifier — the exact same set `CodeGenEngineImpl.requireTestVerification` consults — so the
  no-failover and test-gate-off behaviours cannot drift; before RR-1590 each site carried its own
  `benchmark`/`swebench` literal and `TERMINAL_BENCH` matched neither, leaving TB runs on the
  permissive route-v1 policy with failover ENABLED and the internal TB matrix comparing routes, not
  models. The generation key must stay distinct from `route-v1`: contexts are recovered by that
  key, so reusing it would let resumed runs keep the old permissive policy. `!ownKeysProviderLock`
  remains a separate leading term — BYOK containment must not depend on the controlled-worker term.
- Customer `OWN_KEYS` contexts narrow `allowedProviders`, credential provenance and model policy
  to the explicitly selected provider and set `allowProviderFailover=false`. Other configured BYOK
  keys remain stored but are ineligible for dispatch, model/provider continuation and chat/action
  work unless the customer explicitly selects that provider for a new context.
- Chat turns use `forChatTurn`: V283 usage is attributed to the initiating actor, while a
  cross-org project collaborator is bound to the host project's org and credential subject. Its
  V282 budget carries the chat token/USD/provider-call ceilings and is embedded in the immutable
  `ProjectChatPolicy` used at every model/tool boundary. The atomic output reservation uses the
  current request's real `max_tokens` for the call being admitted; already-authorized concurrent
  calls retain the conservative largest-allowed-model reservation because their individual caps
  are not persisted. This prevents a 1.2k chat call from being rejected against a 40k turn budget
  merely because the org can also access a 131k-output model.
- `LlmExecutionDispatchGuard` reloads the context and the current encrypted credential immediately
  before every physical provider attempt. A removed key, moved USER→ORG credential, changed
  key-source mode, disabled provider, invalid model, pin violation or call/token/cost exhaustion is
  a policy rejection, not a platform fallback.
- Provider failover and model step-up/down are re-authorized against the same snapshot. A candidate
  that is not allowed and currently credentialed is skipped before spend.
- Credentials are not stored in V282 or `ExecutionContext`; only the context reference propagates
  through planner/worker/reviewer/compactor children and survives a JVM restart.
- Lifecycle mutations take a tenant scope and use a pessimistic row lock plus JPA `@Version`.
- V351 adds `llm_execution_operation`, the independently leased operation state machine required
  when several agents share one context. An exact `(operation id, invocation, attempt, UUID token)`
  claim fences stale owners, while `not_reclaim_before` prevents a replacement from starting until
  both the lease and any admitted external-attempt safety horizon expire. Explicit retry/re-review
  creates the next immutable invocation; crash recovery reclaims the incomplete invocation. A
  repeated cancellation request terminalizes an ownerless `CANCEL_REQUESTED` operation after both
  horizons, so dead owners cannot strand cancellation forever. V351 also adds versioned, nullable
  operation-fence columns to `llm_usage_attempt_intent`; version 0 preserves historical/unwired
  rows and version 1 requires the exact context, attempt, token, admitted no-reclaim horizon and
  transport deadline, with a database trigger rejecting fabricated or stale linkage.
- **RR-322 / RR-1666 — operation ownership is now wired, while physical-attempt enforcement
  remains rollout-gated.** Pipeline planning/execution/review/patch/validation, retry/re-review,
  agent turns, bounded subagents, architecture jobs, background reviews, preview rendering and
  embedding jobs claim an independently fenced operation, bind its process-local authority into
  `ExecutionContext`/`LlmRequest`, heartbeat bounded work and terminalize only after the business
  result is durable. Explicit retry/re-review begins a new invocation; recovery reclaims or
  cancels the incomplete invocation instead of reconstructing authority from identifiers.
- V354 adds the atomic physical-attempt boundary. `LlmExecutionOperationAdmissionService` locks the
  aggregate context and exact operation in one transaction, validates the opaque authority,
  reserves aggregate call/output budget, extends the lease and no-reclaim horizon, and flushes a
  version-1 immutable attempt intent before transport. `PhysicalProviderAttemptAdmissionService`
  applies the same boundary to chat and capability transports; `LlmRouter`, Gemini's native/cache
  attempts and embeddings select it through `LlmExecutionOperationAdmissionPolicy`. V354 is an
  expand/contract migration: version-0 rows may retain durable-context attribution so the previous
  binary remains rollback-compatible, but no version-0 row may carry operation-fence fields.
- **Active boundary:** `executespec.llm.operation-admission.mode` still defaults to `LEGACY`.
  Therefore current default execution continues to prepare version-0 physical attempt intents even
  when a production root carries authority. `SHADOW` uses atomic version 1 where authority is
  present and records bounded legacy fallbacks; `ENFORCED` rejects durable requests without
  authority before transport. Do not claim runtime at-most-once provider dispatch or zombie-worker
  exclusion until the exact deployment runs in `ENFORCED` and rolling-restart/provider-timeout
  evidence is captured.
- Cancellation is immediate for queued/expired work and cooperative for active work.
- Reusing an idempotency key with a different immutable request fails closed.

## Files

- `LlmExecutionContext` — entity, policy snapshot storage, and lease transition rules.
- `LlmExecutionContextService` — tenant-scoped create/load/claim/heartbeat/terminal operations.
- `LlmExecutionOperation` + `LlmExecutionOperationService` — V351 per-operation
  invocation/attempt/token state machine, including heartbeat, cancellation and recovery horizons.
- `LlmExecutionOperationDescriptor` + `LlmExecutionOperationClaim` — immutable operation identity
  and opaque owner/token fencing capability.
- `LlmExecutionOperationCoordinator` + `LlmExecutionOperationAuthority` — root-facing lease
  lifecycle and process-local authority binding; authority is never serialized or reconstructed.
- `LlmExecutionOperationAdmissionPolicy` — `LEGACY` / `SHADOW` / `ENFORCED` rollout decision.
- `LlmExecutionOperationAdmissionService` + `PhysicalProviderAttemptAdmissionService` — V354
  context+operation locked budget reservation, horizon extension and version-1 attempt-intent
  insertion immediately before a chat or capability transport.
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
- `RunLifecycleService` reconstructs crash-resume, task-retry, approved-continuation and manual
  re-review roots with `LlmExecutionPolicyFactory.forRun(runId)`. A recreated filesystem tool
  context must never omit the durable tenant reference: doing so makes the first recovered provider
  call fail closed with `CONTEXT_REQUIRED` and can incorrectly terminalize untouched tasks.
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
  threw an idempotency-collision 500 on every chat turn (Phase-6 F1, fixed 2026-07-18). Each Ask
  turn is bounded to 20 physical provider attempts (four same-provider attempts across four tool
  rounds plus forced synthesis), 400k input tokens, 15k output tokens, 415k total tokens, and $2 raw
  provider cost. Provider-call count and projected output are reserved before dispatch; input and
  raw cost use the budget gate's recorded-usage check before the next dispatch, so one in-flight
  call can cross those reconciled dimensions. Managed-credit admission remains an independent
  stricter gate for platform keys.
- Static Screens rendering likewise mints one dedicated context per durable job/surface through
  `forRunPreviewSurface(runId, renderAttemptId)`. It inherits the run's tenant, credential source,
  provider preference and exact model pin, but not the terminal pipeline context's consumed cost
  ceiling. Each surface is explicitly bounded to four physical provider attempts, 100k input
  tokens, 8,192 output tokens, 110k total tokens, and $1 raw provider cost. The job/surface key
  makes context creation idempotent without merging separate re-renders into one spend boundary.
- Agent intent, Spec Chat and agent-session streams carry an agent-turn context.
- Project chat/compaction/compiler, architecture, generated docs, project brief/memory, PR Review
  and Code Review create explicit project contexts and resolve the project provider without a
  hidden Anthropic default.
- Ordinary generated project-document calls use compact `project-doc:<uuid>` identities. Durable
  attempts use `project-doc:<job UUID>:<lease token>` so a confirmed replay retains its job identity
  and lifetime attempt count while receiving a distinct per-confirmed-attempt budget. Each attempt
  has hard 200,000-token and two-provider-call limits; its $3 ceiling is reconciled from successful
  usage, so the final admitted call may cross it before another call is denied. Project and feature
  already occupy dedicated context columns, keeping both forms within the 96-character database
  limit without weakening uniqueness.
- `AvailableProviderResolver` no longer reads the dead `RunKeyStore`; it reloads V282 and current
  credential provenance, failing closed to no candidates on missing/stale/tenant-mismatched state.
- Project document/code indexing and query vectors use explicit `EMBEDDING_JOB` contexts through
  `LlmExecutionDispatchGuard.authorizeProviderCapability`. The fixed vector schema supports only
  OpenAI `text-embedding-3-small`, resolved as a dedicated capability rather than inherited from
  the project's code-generation provider. An unavailable or policy-disallowed OpenAI key is an
  honest lexical state, never a global-key fallback.

## Deliberate residual gates

- Operator-only model-eval/router-test callers still use synthetic ids. Enforcement safely blocks
  them today; they require explicit `PLATFORM_INTERNAL` contexts before live diagnostic use.
- Legacy pre-flight `ConversationController`, `ExecutionPlannerService` and file-purpose summaries
  still lack a durable customer identity. Enforcement blocks provider transport instead of guessing
  a provider, but those surfaces require an authenticated run/session/project binding to function.
- Operation admission remains `LEGACY`; provider-timeout, forced-tool/cache and rolling-restart
  evidence in `ENFORCED` is still required before an at-most-once external-attempt claim.

### Exact residual call-site inventory (2026-07-17)

| Surface | Current behavior with enforcement ON | Required disposition |
| --- | --- | --- |
| `project/rag/EmbeddingService` and its document/code/query callers | V290 uses a project EMBEDDING_JOB context, current exact credential source and V283 attempt rows; unsupported routes are lexical | Run V289→V290 PostgreSQL rehearsal and deployed OWN_KEYS zero-platform-attempt journey |
| `integration/RunLifecycleService` resume/retry/re-review roots | V282 is rehydrated through `durableRunRootContext`; missing factory wiring fails before executor/reviewer dispatch | Keep the recovery-root regression test and verify a real rolling-restart run |
| `agentsession/SubagentExecutionService` | Read-only child turns claim and heartbeat a durable operation, but physical attempts still follow the global rollout mode | Prove cancellation/takeover with operation admission `ENFORCED` before claiming cross-node at-most-once transport |
| `terminalbench/TerminalBenchRunService` | Operator benchmark worker has no explicit context | Create a bounded explicit PLATFORM_INTERNAL benchmark context |
| `eval/v2/TierEvalRunner`, `eval/ModelEvaluator`, `eval/LlmJudgeValidator`, `agentsession/OrchestratorRoutingPromptEvalRunner`, router/worker/planner/reviewer test controllers and benchmark fixture harnesses | Synthetic ids are rejected before transport | Create explicit PLATFORM_INTERNAL contexts only on authenticated operator/test paths |
| `api/controller/ConversationController`, `execution/service/ExecutionPlannerService`, `codeintel/FilePurposeSummaryService` | Customer identity is absent, so enforcement rejects before transport; FilePurpose retains a dead Anthropic default | Bind authenticated agent/run/project identity or retire the surface; never restore a global fallback |
| `rollout/RunService` | Legacy in-memory pipeline has no durable tenant identity and is rejected | Retire or persist/tenant-bind before enabling |
| `chat/ApplyService` | Tool-only context is currently safe only while no LLM request uses it | Bind `forRun` before any future model-backed apply step |

`CodeGenEngineImpl` PATCH workers and the SmartClassifier production shadow call were closed in
the earlier context-enforcement pass: both hydrate the persisted run context. The current
operation-authority fixture repairs pass provider-free test compilation and the focused 18-test
lifecycle/context/async-governance cohort. Full current-worktree verification and an
`ENFORCED`-mode transport/restart exercise remain separate release evidence gates.
