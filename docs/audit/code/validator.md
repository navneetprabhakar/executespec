# Audit: `validator/` — V1 gate-chain validation (DEAD ISLAND)

Status: **DONE** · Updated 2026-05-29 · 27 files / 2,000 lines · all read in full

## Files
- `CodeValidator.java` (iface) + `CodeValidatorImpl.java` (107) — orchestrator ✓
- `gate/GateChain.java` (160) — sequential SYNTAX→TYPE→LINT→UNIT_TEST fail-fast chain ✓
- `language/{LanguageToolchain(iface),JavaToolchain(262),PythonToolchain(181),TypeScriptToolchain(197),JavascriptToolchain(189),ReactToolchain(97)}.java` ✓
- `execution/{ProcessExecutor(175),OutputParser(iface),JavacOutputParser(88),GenericTestOutputParser(83),EslintOutputParser,MypyOutputParser,RuffOutputParser,TscOutputParser}.java` ✓
- `model/{ValidationResult,ValidationError,GateResult,Language,ValidationGate,ValidationLevel,ValidationVerdict,ValidateFileRequest,ValidateProjectRequest}.java` ✓
- `prompt/RetryPromptBuilder.java` (57) ✓

## Headline: the entire package is DEAD CODE
**Zero production callers.** `grep -rln "import com.navneet.executespec.validator" src/main/java | grep -v /validator/` = **empty** — nothing outside the package imports any of it. `CodeValidatorImpl` (a `@Service`) is referenced only by the package's own `CLAUDE.md`; `GateChain` + the toolchains only reference each other. The V2 pipeline replaced this whole subsystem with `smoketest/BuildCommandResolver` + `WorkspaceTestRunner` + `tool/BuildProjectTool` + `ReviewerAgent`. The package's own `CLAUDE.md` even states "Per-task validation was removed long ago" — this gate chain is the orphaned remnant of that removed per-file validation.

## Findings

### Tech-debt / dead code
- 🟡 **~2,000 LOC of unreachable code.** A clean, pluggable per-language gate chain (syntax → type → lint → unit-test, fail-fast, with per-tool output parsers) that nothing invokes. Not bad code — just orphaned. Decision is binary: **delete** (~2,000 LOC + the stale CLAUDE.md), or **revive** (see efficiency angle below).
- 🟡 **It's not even free at runtime — it shells out ~5× on every boot.** The Spring beans ARE instantiated (`@Service`/`@Component`), and `CodeValidatorImpl`'s constructor calls `toolchain.isAvailable()` for each of the 5 toolchains ([CodeValidatorImpl.java:36-46](../../../src/main/java/com/navneet/executespec/validator/CodeValidatorImpl.java)) → each probes a binary (`mvn --version`, `npx --version`, `python3 --version`, `node --version`, …) via `ProcessExecutor`. So every app startup spawns ~5 version-probe subprocesses + logs "toolchain ready/not installed" for a subsystem that never runs. Pure startup waste + log noise.
- 📝 **`validator/CLAUDE.md` is stale.** It claims "results feed into `ValidationReportService` for aggregation" — that wiring does not exist (no validator import anywhere). Whoever deletes/revives must fix or remove this doc.

### Accuracy / Efficiency (the priority) — the revival case
- 🔵 **This is a pre-built per-file fast-feedback gate that Codex/Claude Code-style loops would use.** The single biggest efficiency gap found in Tier 1 ([engine.md](engine.md)) is that the V2 worker only learns about syntax/type errors at the end-of-run `buildProject` (expensive, whole-project). This package already implements an incremental per-file SYNTAX→TYPE gate (e.g. `python3 -m py_compile`, `node --check`, `tsc --noEmit`, `mvn compile`) with structured error parsing + a `RetryPromptBuilder` that formats failures back to the worker. Wiring `validateFile` into the worker loop after each `writeFile`/`editFile` could catch errors in seconds instead of after a 75-iter task + full build — a real latency/cost lever. Caveat: it's been unmaintained since the V2 cutover, so revival needs a correctness re-check (the `isAvailable` probes assume host binaries; cleanroom-container dispatch like `WorkspaceTestRunner` would need adding).

### Security (latent — dead)
- 🟡 `ProcessExecutor` runs an **allowlist** of binaries (`ALLOWED_BINARIES`) via `List<String>` args with **no `/bin/sh -c`** (so no shell-injection surface) — arguably a *tighter* model than the live `/bin/sh -c`+`ShellSandboxPolicy` path. BUT it does **not** strip the environment (`pb.environment().putAll(...)` onto the inherited env), so a revived executor would leak all server env vars (secrets) to the child. Fix env-strip before any revival. Inert today.

### Good (if revived)
- 🟢 Gate chain is clean: fail-fast, level-gated (SYNTAX/TYPE/LINT/TEST/FULL), React hook-rule lint is correctly blocking while other lint is non-blocking. Per-tool parsers (javac/maven, tsc, eslint, mypy, ruff, pytest/jest/surefire) are straightforward regex extractors. `RetryPromptBuilder` truncates raw output at 3K — cache-friendly.

## Cross-refs
Superseded by → [smoketest.md](smoketest.md) (`BuildCommandResolver`/`WorkspaceTestRunner`), [tool.md](tool.md) (`BuildProjectTool`), [reviewer.md](reviewer.md). Per-file fast-gate efficiency lever ties to → [engine.md](engine.md) (worker only sees errors at end-of-run build). Live shell-exec model contrast → [tool.md](tool.md) (`ShellSandboxPolicy`). Two other dead Phase-A scaffoldings found this tier → [scoring.md](scoring.md) (`RunRubricRow`), [report.md](report.md) (`rubricScore`).
