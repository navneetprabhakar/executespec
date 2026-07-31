# `chat/` — terminal-run Ask

`ChatService` is the live post-run Ask copilot. It admits terminal runs only, assembles bounded
workspace and validation evidence, delegates the bounded provider/tool conversation to
`RunChatTurnExecutor`, and persists the sanitized transcript on
`pipeline_run.chat_history_json`. It does not receive write, shell, push, deploy, or rerun
authority.

`RunChatTurnExecutor` owns at most four read-only tool rounds, callback dispatch, provider
finish-reason inspection, private tool-protocol suppression, and the final tool-free synthesis.
Keep provider-turn mechanics there; admission, route resolution, evidence, transcript durability,
and customer-facing policy errors belong to `ChatService`.

`ChatHistoryCodec` owns the transcript storage boundary: JSON serialization/deserialization,
malformed-history fail-safe behavior, and the deterministic provider-visible history window. The
full retained transcript remains bounded at 50 user turns, while a provider call receives only the
newest 16 prior messages within 32,000 characters. Keep storage/window policy in this helper rather
than growing `ChatService` again.

`RunChatTools` owns the grounded read surface (`list_run_files`, `read_run_file`,
`search_run_files`). File manifests and file contents remain explicitly bounded; tool results are
untrusted evidence, never system instructions.

`RunChatEvidence` owns the bounded server-side lifecycle block injected into every Ask turn. It
includes the specific `failureReason`, effective planner provider/model, aggregate and
phase-specific planner tokens, cost, and persisted outline/final-plan/requirement artifact state.
The requested-provider row prefers the immutable `requestedProvider` route field and uses legacy
`aiProvider` only as a fallback; it must not label the effective or fallback route as the request.
`statusReason=PLANNER_UNAVAILABLE` is only an umbrella lifecycle category: Ask must not reinterpret
it as provider downtime when deterministic plan-contract or coherence validation rejected output
after successful calls. An empty code workspace also says nothing about whether an outline existed,
because file generation starts only after planning is accepted.

Classifier, outline, task expansion, and coherence are planning phases; task expansion elaborates
implementation instructions and is not worker code generation. The evidence block therefore emits
`workerCodeGenerationState=NOT_STARTED` only for a BUILD run terminally rejected as
`PLANNER_UNAVAILABLE` before any final plan was persisted. All other shapes remain `UNKNOWN` unless
stronger worker/artifact evidence is available, preventing Ask from turning planning-token activity
into a false claim that generated-code execution ran.

`RunChatDeterministicAnswer` answers planning-failure lifecycle questions without provider spend
only when `failureReason` carries the server-owned, sanitized `Plan validation rejected:` marker
and no final plan exists. It accepts both the persisted umbrella `PLANNER_UNAVAILABLE` reason and
its customer-facing `PLAN_VALIDATION_REJECTED` projection, keeping Web and CLI Ask on the same
zero-provider path after run-detail projection.
The generic hierarchical-coherence wrapper is insufficient because it also wraps provider, model,
prompt, transport, and protocol failures; those cases continue through the grounded provider path
and Ask must not claim provider availability was irrelevant.

Each bounded Ask provider call allows 3,000 output tokens. Reasoning-capable providers count hidden
reasoning against this ceiling; the former 1,200-token limit could consume the entire allowance
without producing visible text. The per-turn durable ceiling is 15,000 output tokens / 415,000
total tokens so four full tool rounds plus one forced synthesis remain admissible at the declared
per-call ceiling; the raw-cost ceiling remains $2. Direct and forced-synthesis responses inspect
the provider finish reason. `length` / `max_tokens` responses retain their diagnostic prefix but
append an actionable incomplete notice and return `complete=false` with
`incompleteReason=PROVIDER_OUTPUT_LIMIT`; they are never persisted or projected as a complete Ask
answer.

The line-ceiling remediation extracted `ChatHistoryCodec` and `RunChatTurnExecutor` without
changing the chat API, durable execution-context budgets, model route, or tool authority. Do not
put storage or provider-turn responsibility back into the orchestration service.

`ApplyService` is fail-closed behind `correction.transactional-apply.enabled`; V368 force-sets that
platform flag to false even when a stale row was previously true. Do not enable it until Apply uses
the checkpoint → evidence invalidation → deterministic gates → read-only review → accept/rollback
lifecycle. The dormant legacy implementation publishes edits only through
`ScmPushService.pushApplyChanges`; its aggregate and each `ApplyRepoOutcome` retain a stable code,
blocking gate, retryability, and remediation. Mixed multi-repository results are
`SCM_APPLY_PUSH_PARTIAL`; all-failed results are `SCM_APPLY_PUSH_FAILED`. Neither shape may claim
publication eligibility merely because Git was attempted.
