# validator/ — Code Validation Pipeline

Pluggable validation gate chain: SYNTAX → TYPE → LINT → TEST

## Key Files
- `CodeValidator.java` — Interface for validation
- `CodeValidatorImpl.java` — Implementation. Runs gate chain, reports pass/fail per gate.
- `gate/` — Individual gate implementations (syntax, type, lint, test)
- `language/` — Per-language validation support
- `execution/` — Execution environment for running validators
- `model/` — Validation result models
- `prompt/` — LLM prompts for validation assistance

## Patterns
- Gates run sequentially — fail-fast on SYNTAX before TYPE
- Results feed into ValidationReportService for aggregation
- Validation deferred to review phase (CompilerAgent + ReviewerAgent) — NOT run per-task
- Per-task validation was removed from SequentialTaskExecutor (2026-03-28)
