# Audit: `smoketest/` — build + real-test resolution/execution

Status: **DONE** · Updated 2026-05-29 · 6 files / 820 lines · all read in full

## Files
- `WorkspaceTestRunner.java` (173) — runs worker-written tests in the workspace → real passed/total for the TESTS slot ✓
- `TestCommandResolver.java` (105) — infers the test command from on-disk markers ✓
- `TestOutputParser.java` (162) — per-framework stdout → `TestExecutionSummary` ✓
- `BuildCommandResolver.java` (232) — infers the build command (planner-declared > framework > no-op) ✓
- `CliSmokeRunner.java` (126) — best-guess CLI entry-point invocation (advisory) ✓
- `BuildCommand.java` (22) — DTO ✓

All live: `RunOrchestrator` + `SequentialExecutor` + `tool/WorkspaceRunTestsTool` (BUILD-TDD) consume `WorkspaceTestRunner`; `BuildProjectTool` + `Plan` consume `BuildCommandResolver`; `RunOrchestrator` consumes `CliSmokeRunner`.

## What it does
This is the deterministic ground-truth layer under the verifiedScore. `BuildCommandResolver` picks the build smoke (planner-declared command, else framework-inferred, else no-op `true`); `TestCommandResolver` picks the test command from markers (pom/gradle/Cargo/go/package.json/pytest); `WorkspaceTestRunner` runs the worker's committed tests (sandbox-validated, cleanroom-aware) and `TestOutputParser` turns the output into a real `passed/total` — which feeds the verifiedScore TESTS slot when `scoring.real-test-exec` is on, replacing the LLM coverage guess.

## Findings

### Security / correctness — the exemplar pattern
- 🟢 **`WorkspaceTestRunner` is the secure-shell template the dead `RunRubricVerifier` should have copied.** It never executes worker free-text: the command is **resolver-derived**, then re-validated through `ShellSandboxPolicy.validate` (defense-in-depth), dispatched inside the `--network none` cleanroom container when one exists (SWE-bench/ProgramBench), and env-hardened (`hardenEnv` keeps only PATH/HOME/JAVA_HOME). Contrast directly with `scoring/RunRubricVerifier.verifyShell` which does none of this ([scoring.md](scoring.md)) — the safe idiom exists right here in the same tier.
- 🟢 **`TestOutputParser` never fabricates a count.** Every uncertain path → `Optional.empty()` so the caller falls back to LLM coverage ("a wrong hard count is worse than no count"). Per-framework grammars (pytest/jest/surefire-sum/cargo-sum/go PASS-FAIL lines); "no tests ran"/"no test files" parsed as a real 0/0. Sound.

### Accuracy / scoring honesty (the priority)
- 🔵 **Multi-repo BUILD runs never get real test execution.** `TestCommandResolver.resolve` only checks **top-level** `workspaceRoot` markers — it has no `repos/<name>/` handling, unlike `BuildCommandResolver` which explicitly walks `repos/*` and chains `cd repos/<n> && …`. So a multi-repo run (project lives under `repos/<name>/`, no root pom/package.json) resolves to empty → the TESTS slot always falls back to LLM-judged coverage. Multi-repo is a marketed feature; its runs silently can't earn the real-test path. Mirror the `BuildCommandResolver` repos/ logic into `TestCommandResolver`.
- 🔵 **Unrecognized stacks get a free green BUILD slot.** `BuildCommandResolver` falls back to `"true"` (no-op exit 0) when no marker matches (logged WARN). The covered set is maven/gradle/cargo/go/expo-rn/npm/python — so **Ruby, PHP, C#/.NET, Elixir, Java-without-pom, etc. compile to `true`** and the verifiedScore BUILD factor reads 1.0 (passed) with nothing actually built. Honest-scoring gap for exotic stacks; consider returning "unmeasured" (skip the BUILD slot) instead of a passing no-op so the additive formula drops it rather than crediting it.
- 🟡 **`CliSmokeRunner` is advisory-only.** It logs PASSED/FAILED/exit but its `Result` does not feed the verifiedScore (the stdin/stdout fixture harness "hasn't landed"). So pure-CLI projects get neither a test runner (no markers) nor a scored smoke — only buildFactor + LLM coverage. Fine as a baseline-signal today, but the CLI surface is under-measured.

### Tech-debt / convention
- 🟢 `CliSmokeRunner` runs a fixed (non-LLM) entry command of worker code on the host shell with env-hardening but **no `ShellSandboxPolicy`** — same inherent trust boundary as `buildProject` (running worker code), so acceptable; noted for symmetry with `WorkspaceTestRunner` which does validate.
- 🟢 Resolvers are side-effect-free, conservative, and `normalizePython` (bare `python` → `python3`) is a careful word-boundary regex. Clean.

## Cross-refs
TESTS-slot consumer → [report.md](report.md) (`ValidationReportService` real-test path) + [scoring.md](scoring.md) (`ReviewScoringService`). Secure-shell contrast → [scoring.md](scoring.md) (`RunRubricVerifier.verifyShell`), [tool.md](tool.md) (`ShellSandboxPolicy`, `BuildProjectTool`). Cleanroom containers → `swebench/`+`programbench/` (Tier 6). Callers → `harness/SequentialExecutor` (per-task smoke) + `integration/RunOrchestrator` (Tier 3) + `tool/WorkspaceRunTestsTool` (BUILD-TDD lever).
