# `memory/` — Project memory and ledger

Project-scoped run ledgers are compacted into versioned memory snapshots. Memory generation is
best-effort: a failed model call must never fail the customer run.

## Routing contract

`MemoryCompactionService` is a text-only metadata summarizer. It resolves the lightweight
`RunLedgerAgent` model and calls `LlmRouter.callText` with an explicit empty tool set. The canonical
text seam owns provider transport, usage accounting, retry, failover, and telemetry. The retired
compatibility router is not part of the ExecuteSpec 2.0 runtime.

`RunLedgerAgent` also uses `LlmRouter.callText`; its run-completion path is best-effort and catches
all transport failures, while canonical retry/failover and managed-AI accounting remain centralized
in the router. The call uses an explicit empty tool set and stable `run-ledger` / `generate`
attribution.

`MemoryCompactionService` resolves its provider through `ProjectProviderResolver`, preserving the
project setting and legacy account preference before the canonical Anthropic fallback. It no
longer hardcodes OpenAI for compaction.

Project chat compaction and spec compilation use the same resolver, so all project-chat phases keep
the selected provider instead of falling back independently to OpenAI.

Project chat also tracks tool name+arguments per turn. If a provider repeats every read call, the
loop gives it one final no-tools answer turn instead of spending the remaining round budget on
duplicate reads; this is especially important for Gemini project-chat tool selection.

## Persistence

`ProjectRunLedger` stores one idempotent digest per run. `MemoryCompactionService` only marks
entries compacted after a bounded, non-empty snapshot is produced; failures return without
mutating ledger state.

`ProjectMemoryProvider` enforces a conservative 12,000-token (48,000-character) planner-memory
budget. The durable snapshot is selected before recent ledger entries; recent entries are included
newest-first only when complete entries fit, then rendered chronologically. Provider-side context
truncation is therefore not relied upon for project memory.
