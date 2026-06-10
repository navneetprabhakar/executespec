# be-tools-reviewer — raw findings

Scope: `src/main/java/.../tool/` (43 files, 7,937 LOC) + `src/main/java/.../reviewer/` (12 files, 2,529 LOC) + matching tests under `src/test/.../{tool,reviewer}` (25 files, 3,767 LOC). Branch `benchmark`, as-is. Every file read in full. No uncommitted changes inside the review scope. Conclusions from code/config only.

## 1. Inventory

**tool/ — V2 `Tool`-interface tools (LLM-bound via `ToolRegistry`):** `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListFilesTool`, `SearchFilesTool`, `ShellTool`, `BuildProjectTool`, `RunTestsTool`, `GitStatusTool`, `GitDiffTool`, `GitCommitTool`, `CompleteTaskTool`, `WorkspaceRunTestsTool`, `VerifyBootTool`, `ReadCodeOutlineTool` (FlagGated), `BackgroundJobTool` (FlagGated).

**tool/ — infra/helpers:** `Tool`, `ToolResult`, `ToolOutput`, `ExecutionContext`, `ToolRegistry` (+ inner `ContextBoundToolCallback`), `ToolArgValidator`, `FlagGatedTool`, `PathSafety`, `FileSandboxPolicy`, `ShellSandboxPolicy`, `ReadFileStateStore`, `StringEditMatcher`, `GitInvoker`, `GitToolResult`, `ToolOutputCompactor`, `WorkerHistoryCompactor`, `EditDiagnosticsService`, `MavenDependencyValidator`, `JobRegistry`, `FileLockTool`, `WorkspaceContext`, `WorkspaceContextThreadLocalAccessor`, `remote/RemoteToolController`, `remote/RemoteToolDispatcher`.

**tool/ — legacy (Spring-AI `@Tool`-annotated, NOT bound to any ChatClient):** `FileSystemTool` (494 LOC), `GitTool` (1,120 LOC). Invoked as plain Java by `AccuracyAssessmentService`, `ApiTestSuiteAgent`, `PreviewAgent`, `ScmPushService`, `ContextAgent`.

**reviewer/:** `ReviewerAgent` (multi-pass + third fix-build pass + PostEditFormatter + final build), `PostEditFormatter`, `AccuracyAssessmentService` (LLM-judge, uses deprecated `FallbackLlmRouter`), `ReviewerRequest`, `ReviewerResult`, `model/{AccuracyReport, ReviewReport, ReviewIssue, FixInstruction, IssueCategory, IssueSeverity, ReviewVerdict}`.

Binding: `ToolRegistry.bind(ctx)` binds all tools; `bindForWorker(ctx)` additionally drops `WORKER_DENY_TOOLS={gitCommit}`. Both skip a `FlagGatedTool` whose flag is OFF (absent from schema). Remote (TB/local-hands) dispatch via `RemoteToolDispatcher` keyed by runId; container (SWE-bench `/testbed`, ProgramBench `/workspace`) dispatch via `docker exec` consulted per-tool in `ShellTool`/`BuildProjectTool`/`RunTestsTool`/`EditDiagnosticsService`.

## 2. Findings

### [RISK] Shell sandbox: newline-separated commands evade the per-segment allowlist — `ShellSandboxPolicy.java:437-487` + `ShellTool.java:154`
`validate()` checks the blocklist/patterns on the full string, then splits on `&&`, `||`, `;`, `|` and validates each segment's **first token** against `ALLOWED_PREFIXES`. `splitChainedCommands` (line 437) treats `\n` as an ordinary char (appended to `current`, only `;|&` trigger a split). A newline is a real command separator for `/bin/sh -c` (ShellTool.java:154 runs `new ProcessBuilder("/bin/sh","-c",command)`). So a command like `echo ok\nperl -e '...'` becomes one segment; `validateSegment` splits on `\s+`, takes `tokens[0]="echo"` (allowed), and never validates `perl` (not in the allowlist, not in `BLOCKED_COMMANDS`' contains-match, no `BLOCKED_PATTERN`) — it executes. This defeats exactly what the allowlist exists for (running a binary not on the list). Host product BUILD runs are where this is the real boundary (cleanroom only exists for SWE-bench/ProgramBench, where the host policy is deliberately skipped — `ShellTool.java:134`). No test feeds a multiline command (verified: `ShellSandboxPolicyTest`/`...LevelTest` have zero `\n` cases). Mitigating context: allowlisted interpreters (`python`, `node`) already permit arbitrary code, so the *incremental* exposure is "non-allowlisted binaries," but that is precisely the control being bypassed.

### [RISK][INCONSISTENT] V2 worker write path bypasses `CodeAbuseScanner` — `WriteFileTool.java` / `EditFileTool.java` vs `FileSystemTool.java:209`
`CodeAbuseScanner.scan(path, content)` is invoked in exactly one place — the legacy `FileSystemTool.writeFile` (`FileSystemTool.java:209`), which is **not** LLM-bound in V2 (no `.tools(fileSystemTool)` / `ToolCallbacks.from` anywhere; grep-verified). The actual V2 worker write tools (`WriteFileTool`, `EditFileTool`) do **not** reference `CodeAbuseScanner` — they enforce only `PathSafety` + `FileSandboxPolicy` (a dir allowlist that is a no-op at guardrail LOW, the default). So the "code abuse scanning" layer (documented as layer 4 of the 8-layer defense) does not run on V2 worker writes — a worker can write reverse-shell / crypto-miner / exfil content (caught later only if/when executed via the command sandbox, or never if it lands in the pushed PR). `VerifyBootTool` only mentions the scanner in a javadoc comment (its `PreviewBootEngine` bean does not exist yet). This is a genuine defense-in-depth gap, not a documented exception.

### [BUG] `AccuracyAssessmentService.verifyMissingByGrep` passes Java-regex-quoted tokens to a `grep` subprocess — `AccuracyAssessmentService.java:565`
The anti-hallucination "demote MISSING→UNKNOWN if the token is grep-able in the workspace" pass calls `fileSystemTool.searchFiles(runId, java.util.regex.Pattern.quote(tok), ".")`. `Pattern.quote("foo")` returns the Java literal `\Qfoo\E`, which `FileSystemTool.searchFiles` hands to `grep -r ... <pattern>` (`FileSystemTool.java:471-489`). GNU grep BRE does not understand `\Q`/`\E` — it interprets the pattern as the literal text `QfooE`, which essentially never matches the real token `foo`. So `found` stays ~0, the `found >= ⌈K/2⌉` threshold is never met, and the safety net **never demotes** — the layer-3 grep-verify is inoperative. Untested (`AccuracyAssessmentExtractKeywordsTest` covers `extractKeywords` only, not the grep call), which is why it went unnoticed. Impact is bounded by how much weight `missingRequirements`→synthetic CRITICALs still carry in the additive score, but the guard does not do what it claims.

### [BUG] `JobRegistry.cleanup(runId)` is never called — background jobs + temp logs leak past run-end — `JobRegistry.java:111`, `BackgroundJobTool`
`JobRegistry` is injected nowhere except `BackgroundJobTool` (grep-verified). The documented run-end hygiene (`cleanup` "wired at run-end beside the context-store cleanup", JobRegistry.java:31-33) is not wired, so a started background process is only reaped by JVM-shutdown `deleteOnExit` (logs) / process death, and the `jobs` `ConcurrentHashMap` grows unboundedly (no LRU). Gated by `worker.background-jobs.enabled` (V233, default OFF), so latent — but if flipped on, forgotten servers/builds survive the run. Contrast `RemoteToolDispatcher.register/unregister`, which *is* wired (TerminalBench + RunOrchestrator, with `unregister` in an always/finally path — verified).

### [BUG][INCONSISTENT] `WorkspaceContextThreadLocalAccessor` drops the worktree override across thread boundaries — `WorkspaceContextThreadLocalAccessor.java:29-46`
The class javadoc states the captured value format is `"runId::worktreePath|taskId"`, but `getValue()` (line 29) returns only `WorkspaceContext.getTaskId()` — it never reads the `CTX` ThreadLocal that holds `runId::worktreePath` (the comment at line 30-34 admits it can't, "Capture via the task ID as a proxy"). `setValue(taskId)` restores only the taskId. The accessor is registered (`ObservabilityConfig.java:39`). So when a child virtual thread is spawned via a context snapshot, the worktree redirect is silently lost — `FileSystemTool`/`GitTool` on the child thread fall back to the shared run-root. V2 is sequential ("no worktrees" per `ExecutionContext`) so this is largely latent today, but it's a real doc/code contradiction and a correctness trap for any code path that does rely on the worktree override.

### [INCONSISTENT][RISK] `verifyBoot` and `runWorkspaceTests` are bound-but-inert, not `FlagGatedTool` — `VerifyBootTool.java:40`, `WorkspaceRunTestsTool.java:48`
`BackgroundJobTool` and `ReadCodeOutlineTool` implement `FlagGatedTool` so they are **absent** from the model schema when their flag is OFF — explicitly to avoid the RL-2026-05-061 class of reasoning-only stall on schema-sensitive providers (DeepSeek), and pinned by `ToolRegistryWorkerBindTest`. But `verifyBoot` (`worker.verify-boot.enabled`) and `runWorkspaceTests` (`worker.build-tdd.enabled`) implement only `Tool` and return `{"available":false}` when OFF — i.e. they remain in **every** worker's schema (BUILD + PATCH + bench + TB) at all times. This is the very pattern `FlagGatedTool` was created to replace (and `runWorkspaceTests` / V217 was reverted partly for a DeepSeek schema-stall, per the migration log). Two always-present inert tools in the schema.

### [INCONSISTENT][RISK] `RunTestsTool` dispatches via `/bin/sh -c`, contradicting its own "arg-array" comments — `RunTestsTool.java:187-200, 330`
`pytestCmd = "python -m pytest " + String.join(" ", testIds) + ...` is run via `/bin/sh -c pytestCmd` (line 199) and `docker exec ... /bin/sh -c pytestCmd` (line 197). But the code comments at lines 146-147 ("Process is still dispatched via ProcessBuilder arg-array, not /bin/sh") and 327-328 ("if a later refactor stops using ProcessBuilder arg arrays") claim the opposite. So `containsShellMetachar` (line 330) is the **sole** shell-injection defense, not the "layered defence" the comment frames it as. The filter does not block spaces or `-`/`=`, so a test id like `"-p no:cacheprovider"` or `"--rootdir=/etc"` injects pytest args (not a shell escape, but arg injection; low severity as the worker is the actor). The stale comment is the load-bearing problem.

### [RISK] `BackgroundJobTool` runs host-side even on bench/TB runs — `BackgroundJobTool.java:115-121`, `JobRegistry.java:52-60`
`BackgroundJobTool.start` validates against `ShellSandboxPolicy` and `JobRegistry.start` always runs `new ProcessBuilder("/bin/sh","-c",command)` on the server host — no `docker exec` / remote routing (acknowledged TODO in the class javadoc). For a SWE-bench/ProgramBench/TB run, `shell` dispatches into the cleanroom container (and skips the host policy), but `backgroundJob` would run the command on the **host** under the host policy — a divergence in both execution location and sandbox. Flag OFF, so latent.

### [SMELL][DEAD] `PostEditFormatter.invokeFormatter` builds-then-discards the command — `PostEditFormatter.java:227-239`
Lines 228-229 add `bin` + `args.subList(0, min(1))` to `cmd`, then **both** the `if ("npx")` and `else` branches call `cmd.clear()` and rebuild `cmd = [bin] + args`. The pre-build (228-229) is dead, and the if/else branches are byte-identical (both do `cmd.clear(); cmd.add(bin); cmd.addAll(args)`). Functionally correct, but ~12 lines of dead/redundant logic.

### [SMELL] `black --quiet` makes its reformatted-count telemetry always 0 — `PostEditFormatter.java:86, 287`
black is invoked with `--quiet` (line 86), which suppresses the `reformatted N files` line, so `parseReformattedCount("black", output)` (line 287) returns 0 even when black reformats. Telemetry-only (doesn't drive a decision), but `Result.filesReformatted` is misleading for Python.

### [SMELL] depth-limit mismatch in `PostEditFormatter` — `PostEditFormatter.java:148 vs 174`
`hasFiles` walks depth 6; `scopedRoots` walks depth 4. A `.py` file deeper than 4 is detected by `hasFiles` (so the formatter is "triggered") but produces no root from `scopedRoots`, so black is never actually aimed at it.

### [SMELL] swallowed `IOException` in `FileLockTool.writeLockFile` — `FileLockTool.java:153-155`
`catch (IOException e) { // non-fatal — log only }` — the comment says "log only" but there is no log call; the exception is silently dropped. Violates the project's no-swallowed-exceptions rule.

### [SMELL][PERF] `MavenDependencyValidator` runs on every `buildProject`, with serial network HEADs from the server even for `--network none` runs — `BuildProjectTool.java:86`, `MavenDependencyValidator.java:109-149`
`buildProject` calls `mavenDependencyValidator.validate(workspaceRoot)` on every invocation (worker self-checks + reviewer pre-build + post-third-pass rebuild). On a Maven pom it fires a serial HEAD to `repo1.maven.org` per explicit-version dep (~250ms typical) from the *server*, even when the build itself runs in a cleanroom `--network none` container. No-op on non-Maven projects, fail-open on network error, but it's host-side outbound I/O attributed to a sandboxed run, repeated per build call.

### [SMELL] Reviewer third pass fires on a tool-error build output — `ReviewerAgent.java:329`
The gate is `!buildPassed && thirdPassEnabled && buildOutput != null && !buildOutput.isBlank()`. When the final build returns a `ToolResult.Err`, `buildOutput` is set to `"tool error: <msg>"` (line 310), which is non-blank — so the fix-build third pass runs against an infra error the LLM cannot fix, burning up to 35 iterations / 360s. Should gate on a real build exit, not any non-blank string.

### [DUPLICATION] `resolveProvider` copied verbatim — `ToolOutputCompactor.java:267` + `WorkerHistoryCompactor.java:296`
Identical provider-string→`LlmProvider` parser (null/blank/unknown→ANTHROPIC) duplicated across both compactors (acknowledged in a comment, but still copy-paste).

### [DUPLICATION][LAYERING] Two parallel file-tool stacks with divergent semantics — `FileSystemTool.java` vs `ReadFileTool`/`WriteFileTool`/`EditFileTool`
Legacy `FileSystemTool.editFile` (`FileSystemTool.java:235-256`) uses `current.replace(oldStr, newStr)` — replaces **all** occurrences, with `contains()` as the only guard — whereas V2 `EditFileTool`/`StringEditMatcher` replaces a single occurrence by default and *fails loudly* on multiplicity. Two write paths with different correctness + security (FileSystemTool has CodeAbuseScanner + FileLock but no FileSandboxPolicy; V2 has FileSandboxPolicy but no CodeAbuseScanner — see finding #2). The split is a maintenance + safety hazard.

### [SMELL] `RunTestsTool.UNUSED_REF` — `RunTestsTool.java:322-323`
A `@SuppressWarnings("unused")` field `UNUSED_REF = Arrays.toString(new String[0])` "avoids unused-warning when stream/stdlib alternates compile" — dead scaffolding.

## 3. Dead code & junk (grep-verified)

- **`FileSystemTool` + `GitTool` `@Tool`/`@ToolParam` annotations are never bound to an LLM.** Grep for `.tools(`, `.defaultTools(`, `ToolCallbacks`, `MethodToolCallback` over `src/main` finds zero binding of these beans (only the V2 `ToolRegistry.ContextBoundToolCallback` path + `LlmRouter`/`FallbackLlmRouter` `.toolCallbacks(callbacks)`). Their methods are called as plain Java by `AccuracyAssessmentService`/`ApiTestSuiteAgent`/`PreviewAgent`/`ScmPushService`/`ContextAgent`. The annotations imply an LLM surface that does not exist — misleading.
- **`ReadFileStateStore.recordRead(String,Path,String)` (public, line 68) has zero external callers** — only used internally by `recordReadFromDisk`. Grep: production call sites are `recordReadFromDisk` (ReadFileTool) + `recordWrite` (Write/EditFileTool) only.
- **`ReadFileStateStore.clear(String)` (line 93) has zero callers** (grep `readState.clear` / `ReadFileStateStore` → none). Class javadoc says "explicit clear(String) is also available at run-end"; it isn't wired. Not a leak (LRU `MAX_RUNS=200` bounds it), but the documented run-end clear doesn't happen.
- **Stale `@link` to a nonexistent class:** `ReviewReport.java:14` — `{@link com.navneet.executespec.reviewer.ReviewerAgentV2}`. No `ReviewerAgentV2.java` exists (grep finds only comment/javadoc mentions in `RunValidationKickoff`, `CostEstimator`, `StateStore`, `ValidationReportService`, `ReviewReport`). `WorkspaceContext.java:7-18` javadoc also references the V1 worktree/file-lock lifecycle that V2-sequential no longer uses.
- **No stale `TODO`/`FIXME` of note in scope.** The only TODO-ish text is the documented HIGH-guardrail follow-up in `ShellSandboxPolicy.java:306-307` ("HIGH-mode strict verb checks … TODO when someone flips the org to HIGH guardrails") and the BackgroundJob container-routing follow-up in `JobRegistry.java:26-28` — both real, both intentional.

## 4. Model / LLM touchpoints

No hardcoded `claude-*` / `gpt-*` / `gemini-*` / `deepseek` / `qwen` / `kimi` / `mimo` model literals anywhere in scope (grep clean) — model IDs are resolved through `ModelResolver` from `application.yml model-matrix`/`agent-tier-map`. Precise mechanisms:

- **`ReviewerAgent.runPass` (`ReviewerAgent.java:424-431`)** chooses the reviewer *tier* in Java: `GEMINI → WORKHORSE`, every other provider `→ PLANNING`, then `modelResolver.resolve("ReviewerAgent", provider, tier)`. This overrides the YAML `agent-tier-map: ReviewerAgent: PLANNING` (verified at `application.yml:350`) for Gemini — so the YAML entry is partially inaccurate for Gemini (a hardcoded provider-conditional in code). Provider parsed from `req.provider()` with ANTHROPIC fallback on bad value (line 411-415).
- **`ToolOutputCompactor` / `WorkerHistoryCompactor`** route their summariser call to the **run's own** provider's LIGHTWEIGHT model (`resolveProvider(workerProvider)` → `modelResolver.resolve(AGENT_NAME, provider)`), AGENT_NAMEs `ToolOutputCompactor`/`WorkerHistoryCompactor` confirmed present in `application.yml:371-372` (LIGHTWEIGHT). Hardcoded fallback provider = `LlmProvider.ANTHROPIC` on null/blank/unknown (`ToolOutputCompactor.java:269,278`; `WorkerHistoryCompactor.java:298,307`). Cost auto-recorded via `RunCostTracker` inside `LlmRouter.call`; `ToolOutputCompactor` additionally calls `costTracker.computeCost(...)` for its log line (`ToolOutputCompactor.java:228`). Both set `temperature(0.0)`, `toolsOverride(emptyList)`, bounded `maxTokens`.
- **`AccuracyAssessmentService`** (`AccuracyAssessmentService.java:217-238`) uses the **deprecated** `FallbackLlmRouter` (per project conventions, allowed for non-tool agents) with the PLANNING `ResolvedModel` passed in from `RunValidationKickoff`. Anthropic-specific caching is gated on a **string compare**: `"ANTHROPIC".equalsIgnoreCase(provider)` → `AnthropicCacheOptions(SYSTEM_ONLY)` (line 218-226). Tokens recorded via `stateStore.addTokensToRun` (line 246). No provider failover (RL-2026-05-041 noted inline at line 146 of `RunValidationKickoff`).
- **`EditDiagnosticsService` / `ReadCodeOutlineTool` / `RunTestsTool` / `ShellTool` / `BuildProjectTool`** select runtime (host vs `docker exec` into SWE-bench `/testbed` or ProgramBench `/workspace`) per the container stores, not per model. `RunTestsTool` uses a bash login shell only for SWE-bench (`useLoginShell`, line 170-177) so conda activation resolves pytest — a SWE-bench-specific behavior.

## 5. Libraries

- **JGit** (`org.eclipse.jgit`) — `GitTool` worktree/merge/diff/tag; falls back to shell `git` for linked worktrees (documented JGit `getWorkTree()` quirk, GitTool.java:979-984).
- **JavaParser** (`com.github.javaparser.StaticJavaParser`) — `ReadCodeOutlineTool` structural outline (Java-only first cut).
- **Spring AI** — `ToolCallback`/`ToolDefinition`/`DefaultToolDefinition` (`ToolRegistry`), `AnthropicChatOptions`/`AnthropicCacheOptions`/`AnthropicCacheStrategy` (`AccuracyAssessmentService`), chat `Message` types (compactors).
- **Jackson** — envelope serialization (`ToolRegistry`, `RunTestsTool`, `RemoteToolDispatcher`, the verifyBoot/workspace-test tools), `ToolArgValidator`.
- **`java.net.http.HttpClient`** — `MavenDependencyValidator` HEAD probes to Maven Central (5s connect/request timeouts).
- **External binaries** — `git`, `docker exec`, `python3 -m py_compile` (EditDiagnosticsService), `python -m pytest` (RunTestsTool/WorkspaceTestRunner), `rg` (SearchFilesTool fast path, probed once/JVM + cached), `grep` (legacy FileSystemTool.searchFiles), `black`/`prettier`/`npx`/`gofmt` (PostEditFormatter), `command -v` (PATH probe).
- **Lombok**, **Micrometer** `ThreadLocalAccessor`, **SLF4J**.

## 6. Test coverage

**Strong:** `ShellSandboxPolicyTest` (32) + `ShellSandboxPolicyLevelTest` (10), `StringEditMatcherTest` (13), `ToolArgValidatorTest` (9), `ReviewerAgentVerdictParsingTest` (19 — `parsePassVerdict`/`determineOverall`/write-aware downgrade), `AccuracyAssessment{InferKeyFilePaths(13),ExtractKeywords(9),TruncateForSnippet(6)}`, `ToolOutputCompactorTest` (9), `WorkerHistoryCompactorTest` (7), `FileSandboxPolicyLevelTest` (8), `ToolRegistryWorkerBindTest` (5 — pins deny-list + FlagGated absence for `backgroundJob`), `BuildProjectToolWrapTest` (5), `MavenDependencyValidatorTest` (7), `EditDiagnosticsServiceTest` (7), `ReadCodeOutlineToolTest` (6), `RemoteToolDispatcherTest` (4).

**Gaps (no dedicated test):**
- `PathSafety` — security-critical path-escape guard, **untested directly**.
- `ShellTool` — container-routing + the "skip host sandbox inside container" branch (`ShellTool.java:134`) + env sanitization, **untested**.
- The **newline-bypass** of the segment allowlist (finding #1) — no test exercises a multiline command.
- `AccuracyAssessmentService.verifyMissingByGrep` (the buggy `Pattern.quote`→grep path, finding #3) — **untested**; a test would have caught it.
- `ReadFileStateStore` — the read-before-write/staleness ledger that gates `editFile`, **no direct test** (only indirectly via `EditFileToolTest`'s 5 cases).
- `SearchFilesTool` (ripgrep + Java walk), `WriteFileTool`, `ListFilesTool`, `GitStatusTool`/`GitDiffTool`/`GitCommitTool`/`GitTool` — **untested**.
- `WorkspaceContextThreadLocalAccessor` — the dropped-worktree propagation (finding #5) — **untested**.
- `PostEditFormatter` — `parseReformattedCount`/root-detection/dead-branch (`PostEditFormatter.java`) — **untested**.
- `ReviewerAgent` multi-pass flow / third-pass gating / timeout wrapper — only verdict parsing is unit-tested.
- `verifyBoot`/`runWorkspaceTests` FlagGated-vs-bound inconsistency (finding #6) — `ToolRegistryWorkerBindTest` pins the FlagGated contract for `backgroundJob` but there is no equivalent assertion that `verifyBoot`/`runWorkspaceTests` are (intentionally) always bound.

## 7. Security notes (parked)

1. **Newline sandbox bypass** (finding #1) — multiline `shell` commands escape the per-segment allowlist on host runs.
2. **V2 writes bypass `CodeAbuseScanner`** (finding #2) — content abuse scan (documented layer 4) does not run on `writeFile`/`editFile`.
3. **`RunTestsTool` shell interpolation** (finding #7) — test ids are interpolated into `/bin/sh -c "python -m pytest <ids>"`; `containsShellMetachar` is the sole shell-injection defense (comment claims arg-array dispatch). Pytest-arg injection (spaces/flags) is not blocked.
4. **PAT/credentials embedded in git remote URLs** — `GitTool` builds `https://oauth2:<pat>@…` / `https://<user>:<appPassword>@…` for push (`GitTool.java:589,592,601,620-657,702,756,793`). On failure it logs the merged `git push` output (`log.warn(..., output)`); the auth URL is also visible in process args momentarily. Modern git redacts the password in its own messages, but worth confirming no token reaches logs.
5. **`ShellSandboxPolicy` blocklist is `contains()` on a lowercased string** — entries with uppercase silently never match (the code itself documents a prior latent bug from this at lines 124-129); the deny list is order-1 brittle vs obfuscation (the segment-allowlist is the stronger control, but see #1).
6. **`MavenDependencyValidator`** makes server-side outbound HEAD requests to a fixed host (`repo1.maven.org`) for any workspace pom — minimal SSRF surface (fixed host), fail-open.
7. **`RemoteToolController.submitToolResult`** is correctly guarded by `RunAccessGuard.requireRunWriteAccess` (owner-only) — no issue, noted as the positive control on the remote-tool ingress.

---

FINAL: see below.
