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
- An active lease cannot be stolen. After expiry, a new owner may claim and increment `attempt`.
- Stale owners cannot heartbeat, complete, or fail after lease loss.
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

## Adopted customer surfaces

- Pipeline planning, worker, reviewer and child compaction calls inherit one run context.
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
