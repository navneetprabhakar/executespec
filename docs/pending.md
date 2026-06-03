# Pending items

> **The single consolidated open-work register for the project.** Created 2026-06-01 for the
> agent-quality hardening branch (`claude/agent-competitive-review-F4boG`, PR #199); broadened
> 2026-06-03 by folding in the four session/index handoff docs (agent-quality, SWE-bench eval,
> audit-deferred, CLI/Terminal-Bench) that were deleted, so there is one place to look.
> Ordered/sequenced view: [`ROADMAP.md`](ROADMAP.md). Durable technical records live in
> [`reason-log/2026-05.md`](reason-log/2026-05.md) (RL ids). Newest context first.
>
> **Map:** sections **A–G** = the PR #199 agent-quality branch · **H–K** = the folded-in tracks ·
> **Constraints** = standing founder directives.
>
> **Legend:** 🔴 blocks / highest-priority · 🟠 should do soon · 🟡 planned / eval-gated · ⚪ optional / pre-existing · ✅ done.

## Priority snapshot (2026-06-03)

> Ordered TODO with full rationale + `[severity · impact · effort]` tags: [`ROADMAP.md`](ROADMAP.md).
> **Founder priority (2026-06-03): build the CLI first; only a critical bug pre-empts it.** SWE-bench (§H) on hold.
> Recently cleared: §A1–A3 (PR #199 merged) · §K1 (keys rotated). Quick win now unblocked: §C1 (toggle `router.failover` ON — 5-min SUPER_ADMIN action). Tiers:
>
> - **P0 — pre-empts everything: critical bugs.** None currently tracked; a critical bug jumps ahead of the CLI.
> - **P1 — CURRENT FOCUS: build the CLI** — the unified `spec` client per ADR-0001 + [`plans/cli-vscode-unified-client-execution-2026-05-30.md`](plans/cli-vscode-unified-client-execution-2026-05-30.md): §J1 (chat surface + local-hands wiring + VS Code sidebar) + §J4 (CLI-local embedder)
> - **P2 — keystone:** `verifyBoot` ([`plans/preview-phase1-verifyboot-spec.md`](plans/preview-phase1-verifyboot-spec.md)) — de-LLM-judges the score; leads the preview/score sub-tree
> - **P3 — latency:** §D1–D3 (Phase 0, eval-gated)
> - **P4 — rebuild + refactor:** preview rebuild core ([`plans/preview-live-environment-rebuild.md`](plans/preview-live-environment-rebuild.md)) · §I2 (batch wiring) · §E1/I1 (RunOrchestrator split) · §D4 (parallel exec) · §J2 (drop ProgramBench) · §I3 (C3 tenant ORM isolation)
> - **P5 — optional / end:** §I5 (dead-code) · mobile · §G (frontend / circuit breaker / JAVADOC) · §I4 (DeepSeek smoke) · §L1 (test latest Qwen coder via OpenRouter)
> - **⏸ Held:** §H (SWE-bench) · §J3 (Terminal-Bench) · §J5 (patchBytes fix) · §F0 (rebrand) · §F1 (score weighting) · §F2 (head-to-head)

## Update 2026-06-01 (pass 2 — "fix all")
Fixed everything feasible without a compiler/network (the sandbox blocks maven repos — 403):
**B1 ✅** EditFileTool integration test · **B2 ✅** isTransientError 429-by-type · **B3 ✅** gold-source leak fully removed (residual noted) · **D4 ✅ (core)** `TaskWaveScheduler` + test, standalone-validated 10/10.
Still genuinely blocked here: **A1–A3** (need a network/compiler — your machine or CI), **E1** (god-class split must be by-hand *with a compiler*, per its plan), **C/D1–D3** (founder said keep flags admin-toggleable; eval-gated), **F** (founder-parked).

---

## A. ✅ PR #199 merge — DONE (merged 2026-06-03)

> A1–A3 were the merge blockers; **PR #199 is merged**, so all three are resolved/moot. Bodies kept for history.

### A1 ✅ Local build + format gate
- **What:** Run `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q` and `mvn spotless:apply` on the branch.
- **Why:** the review sandbox blocked `repo.spring.io` (parent POM `spring-boot-starter-parent`), so the Spring-wired changes were **never compiled** here. `StringEditMatcher` is validated standalone (13/13), but `LlmRouter`, `SearchFilesTool`, and `SwebenchTaskService` need a real `javac` + spotless pass.
- **Watch for:** unused-param warnings on the two neutralised `SwebenchTaskService` methods (intentional — signatures preserved); spotless line-length / import-order on the new files (`StringEditMatcher`, `LlmRouterTest`, `StringEditMatcherTest`, the `SearchFilesTool` rg helpers).
- **Owner action:** founder local run. This is the single most important follow-up.

### A2 ✅ Confirm the 4 re-enabled worker tests are green
- **What:** `mvn test -Dtest=WorkerAgentStagnationTest,WorkerAgentProgressNudgeAndWallClockTest`.
- **Why:** the off-by-one was root-caused (verify-before-done nudge, RL-2026-05-063) and expectations bumped +1, but the iteration accounting was hand-traced (sandbox can't run the suite). If any count is off, the fix is a one-line expectation tweak.
- **Refs:** `WorkerAgentStagnationTest#writeFile_resets_stagnation_counter` (→17), `WorkerAgentProgressNudgeAndWallClockTest` (→9 / →4 / →3).

### A3 ✅ CI may hit the same `repo.spring.io` access
- **What:** if CI fails resolving the parent POM, that's an environment/network policy issue, not the diff.
- **Owner action:** confirm CI has milestone-repo access (or a mirror); otherwise the new-code verification falls to A1.

---

## B. Owed from this branch (small, in-scope)

### B1 ✅ `EditFileTool` integration test — DONE (RL-2026-05-065)
- Added `tool/EditFileToolTest.java` (5 cases): exact edit, whitespace-drift-through-the-tool, read-before-edit gate block, missing-file NOT_FOUND, no-partial-apply on a failed 2nd edit. Drives the real tool through `FileSandboxPolicy` + `ReadFileStateStore` against a `@TempDir`. (Not run here — needs A1.)

### B2 ✅ `isTransientError` robustness — DONE (RL-2026-05-065)
- Added a typed `HttpClientErrorException.TooManyRequests` instanceof to `agent/LlmRouter.java#isTransientError` (robust 429); substrings kept as fallback. 400/401/403/404 still non-transient.

### B3 ✅ Oracle-removal follow-up — DONE-with-note (RL-2026-05-065)
- The gold-test **source** leak is fully removed (RL-2026-05-064): `formatTestPatchSnippet` + `captureFailingTestFilesFallback` emit nothing → `captureFailingTestSource` returns "" → the "# Gold tests" prompt block is skipped (blank source). **Residual decision (not fixed):** the "# Failing test output" block in `prefixWithFailingTestOutput` still injects captured FAIL_TO_PASS *run output* — a lesser oracle signal on a path likely dead now that `SwebenchController` is gone. Left as-is rather than risk an unverifiable edit to a possibly-dead flow; revisit if the swebench event flow (`onRunStarted`) is confirmed reachable.
- **Where:** `swebench/SwebenchTaskService.java`, `swebench/SwebenchBootstrap.java`.

---

## C. Feature-flag toggles (SUPER_ADMIN, no migration needed)

### C1 🟡 New flags are seeded default-OFF and admin-toggleable
Per founder call, defaults are NOT flipped; toggle in the SUPER_ADMIN flag console (`POST /api/feature-flags/{key}`) after a no-regression eval:
- `router.failover.enabled` (V226) — multi-provider failover. Fail-safe/additive; lowest risk to flip.
- `worker.tool-compactor.enabled` (V216) + `worker.history-compaction.enabled` (V222) — context discipline. Prereq verified satisfied (WorkerAgent threads `req.provider()` into both compactors). Changes every run's context → flip behind an eval.
- `worker.cross-provider-fallback.enabled` (V220) — task-level cross-provider retry.

---

## D. Latency (see [`plans/latency-reduction-phased.md`](plans/latency-reduction-phased.md))

### D1 🟡 Phase 0a — context-compaction default-ON
- Flip C1's compaction flags after an eval. Prereq done. Wall-clock + verifiedScore comparison via `scripts/v2-eval.sh medium+high × 3 providers`.

### D2 🟡 Phase 0b — skip redundant per-task builds
- **What:** only run the per-task `buildProject` smoke when a task wrote build-relevant files (or at phase boundaries), not unconditionally.
- **Why:** N tasks = N full builds today; the final-run build/test gate preserves the correctness signal.
- **Where:** `harness/SequentialExecutor.java`. Eval-gated (catch-rate regression check).

### D3 🟡 Phase 0c — per-tier budget re-tune
- Revisit wall-clock / iteration caps once context is smaller (D1) so legit long tasks finish without the eval-script timeout.

### D4 🟡 Phase 1 — parallel task execution (the big win)
- **Core ✅ DONE (RL-2026-05-065):** `harness/TaskWaveScheduler.java` — the pure Kahn-levels scheduler (waves of `dependsOn`-independent tasks; cycle → reported in `unscheduled`, not thrown; unknown deps recorded). Standalone-validated 10/10 + `TaskWaveSchedulerTest` (7 cases). Additive, **not wired** to the executor.
- **Remaining (the risky, eval-gated half):** wire into `SequentialExecutor#execute` behind `harness.parallel-tasks.enabled` (default OFF), bounded concurrency `min(3, waveSize)`, thread-safe outcome aggregation, per-strategy task-count caps. **Blockers:** shared-workspace file races (only a live multi-task run can prove absence) + task-count band coupled to the legacy classifier. Eval-gated before the flag flips.
- **Prereq:** RL-2026-05-001 (real test exec — done).

---

## E. Larger refactors

### E1 🟠 RunOrchestrator god-class — next break
- **What:** continue splitting the ~4,456-LOC `RunOrchestrator` per [`plans/runorchestrator-god-class-split.md`](plans/runorchestrator-god-class-split.md) (6 by-hand increments; candidate next: the validation-report / PR-push run-end orchestration into a focused service with its own tests).
- **Why deferred:** the plan doc records that **automated extraction corrupted the file twice** — must be done by-hand, increment-by-increment, with the build running after each step. Not safe to attempt in a no-compile sandbox.
- **Owner action:** execute locally, one increment + full test per commit.

---

## F. Explicitly parked by founder (tracked, not actioned)

### F0 ⚪ Rebrand — rename all packages, drop personal name (name TBD)
- **Decision (2026-06-01):** rename the product/agent away from "ExecuteSpec/Spec". Founder will choose a new name. Scope when it happens: **rename ALL packages** — including the Java base package `com.navneet.executespec` → `com.<newname>…` (**drop the personal `navneet` segment**) and the client packages (`@executespec/*`, VS Code id/publisher, CLI bins `executespec`/`es`). "Rest remains as-is."
- **Constraints:** compiler-gated (~1,076 Java files + 29 client files); must run with a build (spotless/mvn) — not a blind sed. Do it as one clean pass (clients are slated to split to their own repos, so align the rename with that).
- **Finding:** "Forge" was considered and **rejected-for-now** — it collides with **Atlassian Forge** (the `@forge` npm scope is likely taken; SEO/mindshare clash for a dev-tool CLI). Pick a collision-free mark + verify npm scope / VS Code publisher availability before committing.
- **Where:** repo-wide; `com.navneet.executespec` (base pkg), `clients/{cli,vscode,core}`, domain/config strings.

### F1 ⚪ Grading / `verifiedScore` strategy
- Parked — "we will figure out a better strategy." Review finding: 40–53% of the headline score is LLM-self-graded whenever real tests don't resolve, and the anti-gaming guard keys off the same LLM judge. No change made.

### F2 ⚪ Claude Code / Codex head-to-head report
- Handled on the founder's local setup; no work owed here. (The review's gap — no committed re-runnable head-to-head — is closed on the founder's machine.)

---

## G. Pre-existing debt surfaced by the review (optional, not this branch)

- ⚪ **Frontend:** no code-splitting (single ~800KB bundle), effectively zero running frontend unit tests (`api/core.ts` ~2K LOC untested). See `docs/tech-debt.md`.
- ⚪ **`ProviderHealthTracker` circuit breaker** on the V2 `LlmRouter` failover path — the failover (RL-2026-05-063) has retry + chain but no circuit breaker yet (the legacy `FallbackLlmRouter` has one). Noted in the M#68 javadoc.
- ⚪ **`JAVADOC.md` stale** — full rewrite queued (head ref was bumped, content not regenerated).

---

# Folded-in tracks (consolidated 2026-06-03)

> The four session/index handoff docs were deleted on 2026-06-03 and their open items folded here.
> Durable technical records stay in `reason-log/2026-05.md`; surviving implementation/ops plans are
> linked inline (those are real design docs, kept on purpose).

## H. SWE-bench eval-improvement track

> **⏸ ON HOLD (founder, 2026-06-03)** — deprioritized for now; not on the active TODO. Also covers the benchmark-harness fix **J5** and gates Terminal-Bench **J3**. Resume after the P0–P1 product work lands.
>
> Was `handoff/2026-06-01-eval-gpt-crack.md`. Durable record: **RL-2026-05-061**. Harness: `~/swebench-bench/` + GCP env `infra/benchmark/` (`EVAL-ENV-PLAN.md`).

Standings (post-diff-strip, verification OFF, /50): **Sonnet 23 · Gemini 22 · DeepSeek 21 · GPT 17→~19–20 projected**. Frontier ceiling = Claude Code · Opus 4.7 **35/50**. The GPT lever (symptom-anchored REPRODUCE/VERIFY rewrite of `SequentialExecutor.SYSTEM_PROMPT_BASE` points 2+4) shipped on `fix/cross-org-invitee-access`; control passed (3/4 flips reproduce, 0 regression on every provider's passing set).

- 🔴 **H1 — Confirm GPT @ full-50 (the owed gate).** The control only sampled the passing set; the net full-50 × {GPT, Sonnet, Gemini, DeepSeek} re-run (all flips − any regression) is still unconfirmed. Overnight run on the GCP env; runbook `infra/benchmark/EVAL-ENV-PLAN.md` (VM must carry the prompt — clone `fix/cross-org-invitee-access`; `deploy.sh` sets the flags).
- 🟡 **H2 — Per-provider gap analysis (Sonnet / Gemini / DeepSeek).** Same unbiased "I failed but ≥1 other solved" gap study each provider; does the generic symptom-verify lever lift them, and what are their distinct failure modes?
- 🟡 **H3 — GPT's remaining 7.** django-11283/15061 (wrong/incomplete strategy), sympy-23262 (mislocated), the matplotlib-24265 puzzle (correct-looking patch still failed). flask-4992 is likely unwinnable blind (`mode` vs hidden `text=`).
- 🟡 **H4 — Token reduction (task #14).** Per-iteration token breakdown; flip + measure `tool/ToolOutputCompactor` (only fires on multi-tool-call turns) + `tool/WorkerHistoryCompactor` (60k trigger); quality must hold. Overlaps **C1/D1**.
- 🟡 **H5 — Sonnet → 25.** Its 2 no-edits; validate the built-but-unproven `AnthropicForcedToolClient` salvage (V224 force-tool-choice).
- ⚪ **H6 — Harness ceiling.** Run Opus 4.7 through our harness (CC·Opus 35 vs our best 23) to size the harness-vs-model gap.
- **Methodology guardrails (carry forward):** score from the real `submission.patch` / `logs/run_evaluation/...` artifacts, NOT manifest `patchBytes` (10 s poll → false 0 B); addressable gap = "I failed but ≥1 other solved"; disprove your hypothesis with evidence first; patch-reading over-predicts — scoring is the only judge; don't overfit (generic levers + a control); **eval never provider-shifts** (`cross-provider-fallback` OFF — a 429 is a failed instance).

## I. Audit-remediation deferred items

> Was `plans/audit-deferred-handoff-2026-05-30.md` (index). Companion changelog kept: `audit/REMEDIATION-2026-05-29.md`.

- 🟠 **I1 — RunOrchestrator god-class split.** Same item as **E1**; concrete 6-increment by-hand plan in [`plans/runorchestrator-god-class-split.md`](plans/runorchestrator-god-class-split.md) (auto-extraction corrupted the file twice — by-hand + full build per increment).
- 🟡 **I2 — B1 batch coordinator wiring (P4) + live smokes.** All 3 provider batch clients shipped + unit-tested + inert; remaining = wire `BatchCoordinator` into `RunOrchestrator` + per-provider live smokes (Gemini also needs batch-envelope shape verification) + human submit-gate. ~50% LLM-cost lever. Plan: [`plans/v2-batch-execution-plan.md`](plans/v2-batch-execution-plan.md).
- 🟡 **I3 — C3 tenant ORM read-isolation.** High-risk Hibernate `@Filter` retrofit; C2 (app-layer guards) is the finding's hard requirement and is closed. Approach + the 5 exemption landmines + flag-gated entity-by-entity rollout: [`plans/c3-tenant-orm-isolation.md`](plans/c3-tenant-orm-isolation.md).
- 🟡 **I4 — B3 DeepSeek native-client smoke.** Client lives on `feat/benchmark-codegen-harness` (`LlmProvider.DEEPSEEK` + `LlmRouter.callDeepSeek` + the `deepseek-v4-pro-native` matrix entry) — reasoned-not-smoked. To close: `export DEEPSEEK_API_KEY=…` → single-shot `aiProvider=deepseek modelName=deepseek-v4-pro-native` (or `scripts/tier-eval-v2.sh --providers deepseek`) → assert the call returns native (no OpenRouter hop), `RunCostTracker` records the native rate, and latency beats the OpenRouter path. Validation only — no code change expected; run it where the client lives.
- ⚪ **I5 — D-residual dead-code deletions.** Per-item *dynamic-ref* check first (the audit's "deletable" list was unreliable: `planner.v1-v14.txt` are loaded by filename pattern → **NOT** deletable; `validator/` is boot-active). Genuine candidates: `signal/pages/ExtraPages.tsx` (split per-page), dead `@Deprecated` `commitToMain`/`buildSystemPromptInlineDeprecated` methods, the 3 dual-variant panels still shipping dead `'signal'` branches, the superseded swebench oracle adapter (PR #88). Small commits + full build per item; never batch-delete blind. Tracked in [`tech-debt.md`](tech-debt.md).

## J. CLI / VS Code / Terminal-Bench

> Was `plans/cli-and-terminal-bench-handoff-2026-05-29.md`. The CLI phase plan is now governed by **ADR-0001** + [`plans/cli-vscode-unified-client-execution-2026-05-30.md`](plans/cli-vscode-unified-client-execution-2026-05-30.md) (those supersede the older shape-B phases). RL-2026-05-052.

- 🟡 **J1 — Unified `spec` client: chat surface + local-hands wiring.** Governed by ADR-0001 + [`plans/cli-vscode-unified-client-execution-2026-05-30.md`](plans/cli-vscode-unified-client-execution-2026-05-30.md).
  - ✅ **Phase 0 — Foundation hardening (DONE 2026-06-03).** All six design-§10 defects fixed + verified: backend write-guards / executionMode / 4xx error-mapping / `/api/spec` `AccessGuardCoverageTest` pin (was task #106's guard piece); client dead-`localExecution`-field removal + `run local` gated until Phase 3; monorepo builds+typechecks clean; `package-lock.json` committed; `core/capabilities.ts` parity registry + `core/test/parity.test.mjs` (4/4) + `.github/workflows/clients.yml` CI.
  - ✅ **Phase 1 — Backend chat loop (DONE 2026-06-03).** `SpecChatService` replaces the placeholder emitter: a per-round `LlmRouter` (LIGHTWEIGHT) manual tool-loop binding `ProjectChatActionTools`, stateless (audit-only), per-round SSE (`token`/`tool_call`/`tool_result`/`done`), flag-gated `spec.chat.enabled` (V229, default OFF → not-enabled `done` event). 7 unit tests green (loop shapes + the #1 write-guard through the real tools). **Known follow-ups (code-grounded):** read-only `ProjectChatTools` need PAT propagation to bind alongside the action tools; true token streaming needs an `LlmRouter` streaming API (it's synchronous today); move the loop to a virtual thread (`ContextSnapshotFactory`) before prod; deterministic human-confirm token for `approve_plan` (design N14).
  - 🟡 **Phase 2 — CLI chat REPL (in progress).** ✅ **Slice 1 landed (2026-06-03):** `core/chat.ts` (POST-SSE consumer capturing the `event:` discriminator + `ChatSession` stateless conversation manager) + `HttpClient.stream()` + `cli/repl.ts` (readline REPL: `/help` `/provider` `/clear` `/exit`; Ctrl-C cancels the in-flight reply, exits at the idle prompt) wired so `spec` with no command on a TTY drops into chat; 5 SSE-parser tests green; parity F5 (chat) flipped CLI→shipped. **Remaining (Phase 2):** local conversation persistence/resume (`core/localState.ts`), explicit link (`core/link.ts`), the shared surface-agnostic render model (`core/render.ts`, for VS Code parity), wiring the deterministic verbs through the same `core` calls, the Phase-2 cost gate.
  - 🟡 **Then:** Phase 3 (`RunOrchestrator` LOCAL-mode activation: register/unregister + skip-clone + skip-push), Phase 4 (VS Code sidebar webview). Backend tool-proxy + PAT auth already landed dormant.
- 🟡 **J2 — Drop ProgramBench (cleanup).** SWE-bench (BUILD-mode `benchmark/`) + Terminal-Bench cover the slot; ProgramBench is paid-for complexity production never touches. ~1–2d, one PR. Surface: the `programbench/*` package; the `programBenchContainers != null` branch in `ShellTool`/`BuildProjectTool`/`RunTestsTool`/`ShellSandboxPolicy`; refs in `RunOrchestrator`/`SequentialExecutor`/`WorkspaceTestRunner`/`WorkerAgent`/`SmartClassifierAgent`; `RunMode`/`PipelineRun` enum values; swebench import cleanup; tests (`AsyncAllowlistTest`, `BuildProjectToolWrapTest`, `ShellSandboxPolicyTest`); optional follow-up migration `DROP TABLE program_trial` (V129). Cleanest after J1 so the action-tool surface doesn't churn under it.
- 🟡 **J3 — Terminal-Bench via CLI.** Validates the local-hands story (vs server-side container plumbing SWE-bench already proves). **Depends on J1.** Build: `clients/cli/src/bench/terminalbench/` (task.yaml loader + `docker run`/`stop` lifecycle + `docker exec tests/run.sh` verify dispatch) + `spec bench terminal-bench --case|--set|--all` + a minimal server `terminalbench/` adapter mirroring `benchmark/` + categorized RESOLVED% scoring matching the SWE-bench score-JSON shape. ~1 focused week, separate branch. SOTA ≈ 52% (Sonnet 4.6); 40%+ on the 2.1 representative-20 slice = a real signal. Start with representative-20.
- ⚪ **J4 — CLI-local embedder (T3.8).** Server-side code-chunk RAG already shipped (`search_code` / `project/rag/CodeIndexingService` / `project_code_chunk` V223). Remaining: a local-only embedder for the CLI/Plugin via `xenova/transformers` (~1d). Founder signed off.
- ⚪ **J5 — `run-core-codegen.sh` patchBytes race fix (#91 / T4.15).** Local-only fix in `~/swebench-bench/`, never committed (the script isn't in the repo). Fold into the published copy if/when the harness is published.

## K. Security / operational

- ✅ **K1 — Rotate the 3 keys exposed in chat on 2026-05-29 (OpenRouter / DeepSeek / Google-Gemini) — DONE (rotated 2026-06-03).** Was: pasted into a chat transcript while updating the dev GCS api-keys file → treated as compromised; revoked → reissued → re-updated `gs://executespec-dev-deploy/config/api-keys` → redeployed. (The `#92` Gemini-key task row was actioned via the GCS file — close it.)

## L. Model / provider experiments

- ⚪ **L1 — Test the latest Qwen coding model via OpenRouter (founder request, 2026-06-03).** Pick up *at the end* (after the CLI, P1). Code-grounded state (verified in `router/OpenRouterModelMapper.java`, not assumed from docs): the allow-list currently maps only `qwen3-coder → qwen/qwen3-coder` (alongside `deepseek-v4-pro/flash`), and the mapper **fail-fasts on any unverified slug**. To test the newest Qwen coder: (1) confirm the current best Qwen coding slug against OpenRouter's live catalog (`https://openrouter.ai/api/v1/models`) — don't assume the name; (2) add `Map.entry("<matrix-id>", "qwen/<slug>")` to the allow-list; (3) add the matching `model-matrix.model-pricing` + context-window entry in `application.yml`; (4) run it through the real pipeline (`scripts/tier-eval-v2.sh` and/or a small BUILD/PATCH spec) to gauge coding quality + latency + cost vs the current providers. OpenRouter-only (no native client), ENTERPRISE BYOK; needs `OPENROUTER_API_KEY`.

## Constraints to remember (standing founder directives)

- **Commits: no Claude co-author / attribution footer** (founder ask, 2026-06-01) — overrides the harness default commit trailer.
- **Feature flags stay SUPER_ADMIN-toggleable** — don't auto-flip defaults; eval-gate first (see **C1**). No automated flag-flip script.
- **Rebrand is compiler-gated** — see **F0** (drop the `navneet` package segment; "Forge" rejected for the Atlassian collision; verify npm scope / VS Code publisher before committing a name).
