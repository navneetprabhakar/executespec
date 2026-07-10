# `memory/` — Project memory and ledger

Project-scoped run ledgers are compacted into versioned memory snapshots. Memory generation is
best-effort: a failed model call must never fail the customer run.

## Routing contract

`MemoryCompactionService` is a text-only metadata summarizer. It resolves the lightweight
`RunLedgerAgent` model and calls `LlmRouter.callText` with an explicit empty tool set. Do not add
new `FallbackLlmRouter` callers here; the canonical text seam owns provider transport, usage
accounting, retry, failover, and telemetry.

`RunLedgerAgent` remains on the legacy router until its ledger-generation behavior has a dedicated
migration/evaluation pass because it runs in the run-completion path and has provider-specific
fallback semantics.

## Persistence

`ProjectRunLedger` stores one idempotent digest per run. `MemoryCompactionService` only marks
entries compacted after a bounded, non-empty snapshot is produced; failures return without
mutating ledger state.
