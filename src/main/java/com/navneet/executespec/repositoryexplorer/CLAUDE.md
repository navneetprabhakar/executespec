# Repository explorer contract

`repositoryexplorer/` is a provider-free contract layer for a possible future repository explorer.
It does not call `LlmRouter`, register a Spring bean, invoke a provider, or participate in the
planner, worker, reviewer, CLI, VS Code, or Web execution paths.

## Current boundary

- `ExplorerInvocationPolicy` decides whether an optional explorer would be eligible from explicit
  experiment arms and deterministic ambiguity/size/identifier signals.
- `ExplorerSelection` is a structured selection envelope. Raw model messages, transcripts,
  reasoning, and chain-of-thought are deliberately not representable.
- `TaskEvidenceBundleAssembler` validates selected evidence IDs against the requested repository
  snapshot, sorts and bounds citations deterministically, fingerprints the worker handoff, and
  falls back to deterministic `repositoryintel` evidence on invalid, stale, failed, timed-out,
  empty, or budget-exhausted selections.
- `TaskEvidenceBudget` caps citation count, characters, estimated tokens, and rejected-candidate
  summaries independently of any future provider budget.

## Non-claims

This package is not an LLM service, a provider prompt, a semantic/graph explorer, pipeline
integration, feature graduation, or evidence of generated-code quality improvement. Any future
provider-backed implementation must route through `LlmRouter`, add explicit feature/admission and
cost controls, preserve deterministic fallback, and earn quality/cost evidence before activation.

## Tests

`src/test/java/com/navneet/executespec/repositoryexplorer/` pins the inert/control policy,
selective invocation signals, snapshot-bound stable ordering, independent budgets, deterministic
fallback, and the absence of raw explorer history from the public records.
