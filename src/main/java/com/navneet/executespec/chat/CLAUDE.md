# `chat/` — terminal-run Ask

`ChatService` is the live post-run Ask copilot. It admits terminal runs only, assembles bounded
workspace and validation evidence, executes at most four read-only tool rounds through
`RunChatTools`, and persists the sanitized transcript on `pipeline_run.chat_history_json`. It does
not receive write, shell, push, deploy, or rerun authority.

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

Each bounded Ask provider call allows 3,000 output tokens. Reasoning-capable providers count hidden
reasoning against this ceiling; the former 1,200-token limit could consume the entire allowance
without producing visible text. The per-turn durable ceiling is 15,000 output tokens / 415,000
total tokens so four full tool rounds plus one forced synthesis remain admissible at the declared
per-call ceiling; the raw-cost ceiling remains $2.

The 2026-07-26 line-ceiling remediation extracted `ChatHistoryCodec` without changing the chat API,
durable execution-context budgets, model route, or tool authority. `ChatService` moved from 604 to
574 lines; the helper is 58 lines. Do not raise the god-class ceiling to put this responsibility
back into the service.
