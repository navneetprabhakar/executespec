# Audit: `tool/` — worker tool implementations

Status: **DONE** · Updated 2026-05-29 · 34 files / 6,211 lines · all read in full

## Files read in full
- `EditFileTool.java` (202) ✓ · `ReadFileTool.java` (215) ✓ · `SearchFilesTool.java` (214) ✓ · `BuildProjectTool.java` (225) ✓ · `ToolOutputCompactor.java` (273) ✓
- **Batch 1 — security/dispatch (2026-05-29):** `ShellSandboxPolicy.java` (559) ✓ · `ShellTool.java` (195) ✓ · `FileSandboxPolicy.java` (187) ✓ · `PathSafety.java` (41) ✓ · `ToolRegistry.java` (197) ✓ · `ToolOutput.java` (84) ✓ · `ReadFileStateStore.java` (130) ✓

- **Batch 2 — test/write/maven/remote (2026-05-29):** `RunTestsTool.java` (357) ✓ · `WorkspaceRunTestsTool.java` (150) ✓ · `WriteFileTool.java` (127) ✓ · `MavenDependencyValidator.java` (255) ✓ · `CompleteTaskTool.java` (65) ✓ · `remote/RemoteToolDispatcher.java` (147) ✓ · `remote/RemoteToolController.java` (51) ✓

- **Batch 3 — git/filesystem/lock (2026-05-29):** `GitTool.java` (1128) ✓ · `FileSystemTool.java` (494) ✓ · `ListFilesTool.java` (150) ✓ · `FileLockTool.java` (157) ✓

- **Batch 4 — git sub-tools + DTOs (2026-05-29) — package complete:** `GitCommitTool.java` (141) ✓ · `GitDiffTool.java` (69) ✓ · `GitStatusTool.java` (55) ✓ · `GitInvoker.java` (42) ✓ · `GitToolResult.java` (28) ✓ · `Tool.java` (45) ✓ · `ToolResult.java` (55) ✓ · `ExecutionContext.java` (28) ✓ · `WorkspaceContext.java` (66) ✓ · `WorkspaceContextThreadLocalAccessor.java` (48) ✓ · `package-info.java` (31) ✓ — **all 34 tool/ files read.**

### Batch 4 notes
- 🟢 `gitStatus`/`gitDiff`/`gitCommit` (Tool-interface beans, V2) go through `GitInvoker` (PATH-only env, 30s timeout, combined stdout/stderr); `gitDiff` truncates at 3K. `gitCommit` has a `VendoredPathGuard` pre-commit purge (defense vs vendored sibling-repo staging) — but it's in `WORKER_DENY_TOOLS`, so only reviewer/orchestration call it.
- 🟢 Clean V2 contracts: `Tool` (name/description/inputSchema/execute, "never throw for expected failures"), sealed `ToolResult` (Ok/Err + 6-kind `ErrorKind` taxonomy), `ExecutionContext` (runId/taskId/absolute workspaceRoot, validated).
- 📝 `package-info.java` states "No FileLockTool — V2 is sequential, no contention possible" — yet `FileLockTool` (157 LOC) **exists** and `WorkspaceContext`/`WorkspaceContextThreadLocalAccessor` carry the worktree-override + file-lock thread-locals. Confirms (Batch 3) these are vestigial V1 parallel-execution machinery the V2 sequential path doesn't use — a doc/code mismatch + dead-weight to retire.
- 🟡 `WorkspaceContextThreadLocalAccessor.getValue()` only propagates `taskId` across virtual-thread boundaries (it can't read the worktree path without a runId) — so the worktree override would NOT survive a thread hop. Latent, but moot on V2 (no worktrees).

## Findings

### Accuracy / Efficiency / Cost — tool quality vs Codex/Claude Code
- 🔵 **`editFile` is strong** (≈ Claude Code `str_replace`): multi-edit, **all-or-nothing** (any oldStr miss → whole batch rejected, no partial writes), **multiple-occurrence guard** (must disambiguate), and a **read-before-write + on-disk drift gate** via `ReadFileStateStore` (worker must have `readFile`'d it this run and the content must not have changed). Hallucinated patches fail loudly.
- 🔵 **`readFile`**: line-range slicing, full-file truncation at **5,000 chars** (head + 1.5k tail, "use startLine/endLine" hint), `project:` prefix (per-project workspace) with DB fallback, records read-state for the edit gate. 📝 doc said 8K.
- 🔵 **`searchFiles`**: native Java `walkFileTree` + regex (no shell-injection surface), paging (default 50 / max 200 / ceiling 1000), glob `filePattern`, binary + hidden-dir skip. Good — BUT reads every file's lines into the JVM (`Files.readAllLines`) → slower than ripgrep on large/project repos; no gitignore awareness beyond the hardcoded HIDDEN_DIRS set.
- 🔵 **`buildProject`**: `BuildCommandResolver` (markers + planner override), **Maven dependency preflight** (fails fast on hallucinated deps before invoking mvn — saved 79k tokens on one eval), container routing (swebench `/testbed`, programbench `/workspace`), PATH/HOME/JAVA_HOME hardening, per-build timeout, `ToolOutput.budget` (6k inline + full log spilled to disk + readFile path). Returns Ok regardless of exit so the loop reads `exit=`. **programbench compile.sh hash-check wrapper** is a neat anti-gaming guard (fails if the executable hash is unchanged = no real compile).

### Correctness / bugs
- 🔴/🔵 **`ToolOutputCompactor.inferProvider` hard-codes `LlmProvider.ANTHROPIC`** ([line 264](../../../src/main/java/com/navneet/executespec/tool/ToolOutputCompactor.java)). When enabled on an OpenAI/Gemini run, the compactor LLM call cross-routes to Anthropic Haiku — breaking cache-prefix affinity, the run's provider boundary, and BYOK key scoping. Must plumb the worker's real provider before flipping the flag. (Flag is OFF today so latent.)
- 🟢 ToolOutputCompactor is otherwise clean: skip rules (immediately-prior call, SKIP_TOOLS write/git/complete, <3KB), fail-safe to raw on any error, no-shrink fallback.

### Tech-debt
- 🟡 `ToolOutputCompactor` does a full extra LIGHTWEIGHT round-trip per bulky tool output — latency cost; worth it only if the context-savings eval pays for it.

## Batch 1 findings — security sandbox + dispatch (2026-05-29)

### Security (the defining posture — read carefully)
- 🟡🔴 **Product (non-benchmark) runs execute the worker's shell on the HOST `/bin/sh`, guarded only by a regex denylist/allowlist — NOT a container.** `ShellTool` dispatches via `docker exec` into a `--network none` cleanroom ONLY when a SWE-bench/ProgramBench container is registered; for ordinary customer runs `containerId==null` → host shell + `ShellSandboxPolicy.validate`. That policy is genuinely thorough (blocks `$(...)`/backticks, `cd /`/`cd ..`, network-exfil/docker-escape/crypto-miner/reverse-shell patterns, chained-segment first-token allowlist, guardrail-level verb tightening), **but its allowlist permits interpreters — `python3`, `python`, `node`, `sed`, `awk`** — so `python3 -c 'import os; os.system(...)'` passes (only the literal `python… -c …import socket` pattern is blocked, and the inner command isn't re-validated). So on host runs the sandbox is a **strong speed-bump, not a hard boundary**: untrusted generated code can run arbitrary host commands within the workspace cwd (env is sanitized to PATH/HOME/DOCKER_HOST; no network block). This is partly inherent (the build step runs untrusted code to verify it), but worth stating plainly: **the "8-layer container hardening" applies to bench runs; product runs lean on the regex sandbox + host-process isolation + the written-code `CodeAbuseScanner`** (security/, pending audit). A run-level `--network none` + container for product runs would close it.
- 🟢 `PathSafety.safeResolve` is the real file-escape guard: rejects absolute paths, `..` escapes, and symlink hops (via `toRealPath` + `startsWith`). Solid.
- 🟢 `FileSandboxPolicy` (guardrail MEDIUM: writes restricted to `src/test/public/…` + known config files; HIGH: dotfile lockdown) is wired into `editFile`/`writeFile` — but **no-op at the default LOW guardrail level**, so out of the box only PathSafety containment applies to writes.
- 🟢 Env sanitization on every shell call (clears all env except PATH/HOME/DOCKER_HOST) prevents secret leakage to subprocesses.

### Efficiency / quality (good)
- 🟢 `ToolOutput.budget` (DEFAULT_INLINE_CAP=6000): output over budget is persisted full to `.agent-context/tool-output/<tool>-<ts>.txt` + a head+tail preview + a `readFile` path — the worker can page in a full failure trace on demand. `.agent-context/` is diff/PR-excluded. Mirrors Claude Code's `maxResultSizeChars` pattern; fail-open to plain truncation.
- 🟢 `ReadFileStateStore` (read-before-write ledger): SHA-256 content hash per (run,file); LRU-bounded 200 runs; fail-open. Backs `editFile`'s read-before-edit + staleness gates. Mirrors Claude Code `readFileState`.
- 🟢 `ToolRegistry`: `bind` vs `bindForWorker` (filters `WORKER_DENY_TOOLS={gitCommit}` to stop the documented git-plumbing runaway); clean JSON envelope; the **remote-dispatch branch** (local-hands) is present but inert unless a run registers a remote session.

## Batch 2 findings — test/write/maven/remote (2026-05-29)

### Verification tooling
- 🔵 **`runTests` (PATCH-mode) vs `runWorkspaceTests` (BUILD-mode) — two test tools.** `runTests` is pytest-only, takes BYO test ids (the harness-supplied failing tests), structured pass/fail/errored envelope, shell-metachar defense on ids + ProcessBuilder arg-array (no shell), SWE-bench conda login-shell. `runWorkspaceTests` wraps the language-neutral `WorkspaceTestRunner` (pytest/mvn/gradle/npm/cargo/go) over the whole suite — **flag-gated OFF** (`worker.build-tdd.enabled`, V217; returns `{available:false}` when off). Confirms the BUILD-mode in-loop TDD lever is built-but-OFF (a top accuracy action item — let the worker validate its own tests before `completeTask`).
- 🟢 `MavenDependencyValidator`: HEAD-checks every explicit-version pom dep against Maven Central before invoking `mvn`, fails fast on hallucinated versions with an mvn-style error (saved 79k tokens on one eval). Best-effort (network fail → assume valid). Resolves `${property}` refs. Smart, cheap pre-flight.

### Tool design (good)
- 🟢 `writeFile` is **new-files-only** (errors if the file exists → forces `editFile` for modifications) — the key token-saving design: the model sends targeted edits, never re-emits whole files. PathSafety + FileSandboxPolicy + read-state record + FILE_WRITTEN SSE.
- 🟢 `completeTask` is a no-op tool the `WorkerAgent` intercepts (replaced V1's fragile JSON-prefix heuristic).

### Local-hands / remote-brain proxy (inert by default)
- 🟢 `RemoteToolDispatcher` + `RemoteToolController`: server emits a `TOOL_CALL` SSE event → client executes against the user's local tree → `POST /api/runs/{id}/tool-results` → a `CompletableFuture` (120s timeout) unblocks the server agent. **Inert until `register(runId)`** (only LOCAL-mode runs call it), so the default server-executed pipeline is byte-identical. `tool-results` POST is run-write-guarded. Clean, well-isolated design for the CLI/VS Code "local hands, remote brain" model.

## Batch 3 findings — git, legacy filesystem, file locks (2026-05-29)

### Security (a real gap to verify)
- 🔴 **`CodeAbuseScanner` write-blocking lives in the LEGACY `FileSystemTool.writeFile`, NOT in the V2 `WriteFileTool`/`EditFileTool`.** `FileSystemTool.writeFile` (the `@Tool`-annotated V1 filesystem tool) calls `codeAbuseScanner.scan()` and **rejects** flagged content (crypto-miner / reverse-shell patterns). The V2 `tool/` beans the production worker actually binds (`WriteFileTool`, `EditFileTool` — Batch 0) do **PathSafety + FileSandboxPolicy only — no CodeAbuseScanner call**. So on the V2 path, **written source files appear NOT to be code-abuse-scanned at write time** (the shell sandbox catches shell *commands*, but a reverse shell written into a generated `.py`/`.js` would land on disk). The "code abuse scanning" layer of the 8-layer defense may be unwired for V2 worker writes — **verify in the `security/` audit** whether a run-end/reviewer scan covers it; if not, it's a genuine hole.

### Tech-debt — substantial V1/parallel-model legacy still resident
- 🟡 **Two parallel filesystem-tool implementations coexist.** `FileSystemTool` (494, `@Tool`-annotated: readFile/writeFile/editFile/listFiles/searchFiles + readFileRange + per-repo recursive listing) is a **full duplicate** of the V2 `tool/` beans. V2 binds the Tool-interface beans via `ToolRegistry`; `FileSystemTool` is the legacy annotation version, **still partly used** (the accuracy assessor calls `listFilesRecursivePerRepo`). Its `editFile` is inferior (full `String.replace` of ALL occurrences, no uniqueness guard) vs the V2 `EditFileTool`'s all-or-nothing + occurrence-count gate.
- 🟡 **`FileLockTool` + `GitTool`'s worktree machinery are V1 parallel-execution legacy.** Per-file ownership locks + git worktrees served the old parallel-worker model; **V2 is strictly sequential** (`PlannedTask` documents "no ownedFiles contract, V2 is sequential"). So that code is largely vestigial on the V2 path — maintenance burden + a confusing second model.

### Good
- 🟢 `GitTool`'s non-`@Tool` orchestration API is the real, solid git layer used by `RunOrchestrator`/`ScmPushService`: worktree lifecycle (create/prune/remove + node_modules symlink + dep-branch `-X theirs` merge), merge-to-main + abort, GitHub + Bitbucket push, multi-repo subdir push (force) + incremental existing-branch push (no-force, safe for PR branches), `getFullDiff` via merge-base (handles progressive-merge empty-diff), Redis-backed per-workspace mutex (fail-open).
- 🟢 `FileSystemTool.safeResolve` has the same two-layer lexical + symlink containment as `PathSafety`; WARNs on `.env`/credentials writes.
- 🟢 `ListFilesTool` (V2): hidden-dir filtering, recursive walk, 500-entry cap. Clean.

## Cross-refs
Compactor consumer + history mgmt → [engine.md](engine.md). Build/test runners → [smoketest.md](smoketest.md) (Tier 2, pending). **CodeAbuseScanner V2-write wiring gap → verify in security (Tier 5)**. GitTool orchestration consumers → integration/`ScmPushService` (Tier 3). Cost attribution → [cost.md](cost.md). Remote proxy → CLI/VS Code clients (`clients/`).
