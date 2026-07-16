# Authorized live coding evidence audit

Date: 2026-07-12  
Scope: backend live-provider guard and minimal synthetic coding evidence  
Provider calls dispatched in this audit: **none**

## Existing qualifying evidence

`docs/evidence/deepseek-edit-test-repair-2026-07-12.json` already records the bounded minimal coding task required for this track:

- Native DeepSeek `deepseek-v4-flash-native` through `GET /api/v2/test/worker`.
- Synthetic temporary workspace only; no repository/customer content.
- Exact reservation bound `$0.50`; completed with 5 iterations, 6 tool calls, and deterministic `sumOnDisk=60` / `sumCorrect=true` verification.
- Reconciled charge `$0.001322`; the evidence explicitly limits the claim to transport/edit/verification and does not claim benchmark or SOTA quality.

This is stronger than dispatching a second equivalent task: it avoids duplicate spend while retaining a committed, replayable result and reconciliation record.

## Guard and budget audit

- `src/main/java/com/navneet/executespec/agent/LlmRouter.java` calls `ProviderTestBudgetGuard.authorize(...)` before every live test-mode request.
- `ProviderTestBudgetGuard` invokes `scripts/provider-budget-ledger.mjs authorize-dispatch`, enforcing exact provider/model/run reservation scope.
- The ledger supports atomic reserve, dispatch authorization, reconciliation, unknown close, and cancellation of undispatched reservations. A direct CLI call does **not** inherit the application guard; it would require a wrapper to perform the same reserve/dispatch/reconcile sequence.
- Anthropic/OpenAI/Gemini have ledger entries and remaining cap, but no backend-native synthetic coding evidence is committed for them in this worktree. A future provider comparison must use unique run ids, fixed fixture/prompt/tool schema, conservative per-call reservations, and immediate reconciliation.

## Decision

Do not dispatch another provider call in this audit. The existing DeepSeek artifact satisfies the one-task evidence objective, and repeating it would add spend without new coverage. Treat Anthropic/OpenAI/Gemini as `NOT_VERIFIED` for this specific coding-task evidence until a separately authorized comparator run records the same deterministic fields and reconciliation.
