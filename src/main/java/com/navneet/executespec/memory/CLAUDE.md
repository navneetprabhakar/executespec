# `memory/` — Project memory and ledger

Project-scoped run ledgers are compacted into versioned memory snapshots. Memory generation is
best-effort: a failed model call must never fail the customer run.

## Routing contract

`MemoryCompactionService` is a text-only metadata summarizer. It resolves the lightweight
`RunLedgerAgent` model and calls `LlmRouter.callText` with an explicit empty tool set. Do not add
new `FallbackLlmRouter` callers here; the canonical text seam owns provider transport, usage
accounting, retry, failover, and telemetry.

`RunLedgerAgent` also uses `LlmRouter.callText`; its run-completion path is best-effort and catches
all transport failures, while canonical retry/failover and managed-AI accounting remain centralized
in the router. The call uses an explicit empty tool set and stable `run-ledger` / `generate`
attribution.

## Persistence

`ProjectRunLedger` stores one idempotent digest per run. `MemoryCompactionService` only marks
entries compacted after a bounded, non-empty snapshot is produced; failures return without
mutating ledger state.
