# `validator/` — Code validation pipeline

> Last reviewed: 2026-05-12 (1.0.0-M1).

Pluggable validation gate chain: `SYNTAX → TYPE → LINT → TEST`. Surfaces structured pass/fail per gate; results feed into `ValidationReportService` for aggregation. A missing toolchain, unknown project language, missing build/lint/test runner, or intentionally skipped gate is **`UNMEASURED` / `NOT_VERIFIED`**, never a green `PASS`; callers must exclude `measured=false` results from score denominators. A real command failure remains `FAIL`.

## Key files

| File | Purpose |
|------|---------|
| `CodeValidator.java` | Interface for validation. |
| `CodeValidatorImpl.java` | Default implementation. Runs the gate chain, reports pass/fail per gate. |
| `gate/` | Individual gate implementations (syntax, type, lint, test). |
| `language/` | Per-language validation support (Java, TypeScript, Python, React, …). |
| `execution/` | Execution environment for running validators. |
| `model/` | Validation result models. |
| `prompt/` | LLM prompts for validation assistance. |

`ValidationResult.evidenceStatus` is `VERIFIED` only when all required gates actually ran. `GateResult.measured=false` carries the exact unavailable-runner reason and is propagated as `ValidationVerdict.UNMEASURED`.

## Patterns

- Gates run **sequentially**, fail-fast — `SYNTAX` before `TYPE`.
- Validation is deferred to the **review phase** (`ReviewerAgent` + final build smoke), not run per-task. Per-task validation was removed long ago — the per-task `buildProject` smoke test on the final task of multi-task plans is the structural replacement.
- Worker writes a planner-declared `project.json` to `.agent-context/` so the validator + Dockerfile generators know which language toolchain to drive.
