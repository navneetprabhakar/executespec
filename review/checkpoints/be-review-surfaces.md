# be-review-surfaces — raw findings

> Batch: prreview / codereview / finding / testgen / apitests / chat / specchat / optimiser (+ matching tests).
> Branch `benchmark`, working tree as-is, 2026-06-10. Every claim below verified against code (file:line); docs deliberately not used.

## 1. Inventory

| Package | Files | LOC | Read |
|---|---|---|---|
| `prreview/` (+ `inbox/`, `entity/`, `repository/`) | 12 | 1,904 | all, fully |
| `codereview/` (+ `entity/`, `repository/`) | 10 | 2,453 | all, fully |
| `finding/` | 3 | 240 | all, fully |
| `testgen/` (+ `model/`, `event/`, `repository/`) | 29 | 3,720 | all, fully |
| `apitests/` | 2 | 385 | all, fully |
| `chat/` | 4 | 1,269 | all, fully |
| `specchat/` | 5 | 1,063 | all, fully |
| `optimiser/` | 5 | 542 | all, fully |
| tests (`codereview` 3, `chat` 3, `specchat` 2) | 8 | 1,128 | all, fully |
| **Total** | **78** | **~12.7K** | |

Cross-referenced (partial reads, to verify claims): `LlmRouter`, `RunCostTracker`, `RunController`, `RunOrchestrator` (gate + testgen + terminate sections), `RunAccessGuard`, `SseController`, `RunConversationController`, `WorkspaceFileService`, `WorkspaceDiskCleanupJob`, `FallbackLlmRouter` (options handling), `PipelineRunRepository`, `ModelResolver`, V172 migration, `application.yml` + profile ymls, frontend `lib/api/*`.

Wiring status (the "is it still alive?" questions):
- **testgen IS live**: `RunOrchestrator.java:1635` invokes `runTestGenPipeline` for `RuntimeSurface.HTTP` plans (skipped for CLI/LIBRARY/STATIC). Results feed `TestExecutionCompletedEvent` → `scoring/TestCoverageRescorer`. Controllers (`/test-plan`, `/test-graph`, `/test-report`, `/run-tests`) are used by the frontend (grep-verified).
- **apitests IS live**: `harness/event/PostMergeOrchestrator.java:54` → `generateAsync`; `agent.api-test-suite.enabled: true` (application.yml:490).
- **optimiser is wired but functionally vestigial** — see §3.
- **specchat is dormant by flag** (`spec.chat.enabled` default OFF; controller short-circuits) but the code paths behind it have real gaps — see #3.

## 2. Findings

### Real bugs first

**[BUG] B1 — PR-review and code-review cost/tokens are double-counted (2× on every row).**
`LlmRouter.dispatchWithRetry` already records every response into the context bucket: `costTracker.record(runId, taskId, …)` at `agent/LlmRouter.java:230`, where `runId = request.context().runId()` = `"pr-review-{id}"` / `"code-review-{id}"` (`prreview/PrReviewAgent.java:108-110`, `codereview/CodeReviewAgent.java:105-107`). The agents then record the SAME response a second time: `PrReviewAgent.java:118-119` and `CodeReviewAgent.java:113-114`. The 4-arg `record` delegates straight into the same accumulator (`cost/RunCostTracker.java:95-97`). Result: `pr_review.cost_usd/total_input_tokens/total_output_tokens` and `code_review_scan.*` (read via `snapshot()` at `PrReviewService.java:321-324`, `CodeReviewService.java:369-372`) are exactly double. These inflate the project-dashboard rollups `sumCostUsdByProject`/`sumTokensByProject` (`PrReviewRepository.java:43-53`, `CodeReviewScanRepository.java:51-59`) and anything billing-shaped built on them. Fix: delete the agent-side `record` calls.

**[BUG][SECURITY] B2 — `PrCommentInboxController.dismiss` mutates before it checks run ownership (IDOR on sequential IDs).**
`prreview/inbox/PrCommentInboxController.java:97-109`: `service.dismiss(commentId)` runs first; the `.filter(c -> runId.equals(c.getRunId()))` belt-and-braces happens AFTER the write. `PrCommentInboxService.dismiss` (`PrCommentInboxService.java:134-144`) does `findById(id)` with no run scoping and saves `DISMISSED`. `PrCommentInbox.id` is `GenerationType.IDENTITY` (sequential Long, `PrCommentInbox.java:32-34`), so any user with access to ANY run can dismiss inbox comments belonging to other runs/tenants by enumerating ids — the caller just sees a 404 while the row is already mutated. The correct helper exists and is used by `apply` two methods down: `service.findForRun(commentId, runId)` (`PrCommentInboxController.java:126`). Fix: `findForRun` first, then dismiss.

**[BUG] B3 — spec-chat action tools bypass quota, governance, and gate concurrency (flag-gated OFF today, V229).**
- `submit_run` (`specchat/ProjectChatActionTools.java:116-135`) calls `runOrchestrator.submitRun` directly. The monthly quota gate + counter exists ONLY in the REST path — `tierEnforcementService.assertAndIncrementRunCount` at `api/controller/RunController.java:153` — and the idempotency dedupe (`RunIdempotencyService`, consulted in `RunController.submit`) is likewise skipped. A FREE user on the CLI gets unmetered, uncounted runs the day `spec.chat.enabled` flips ON.
- `approve_plan` (`ProjectChatActionTools.java:171-183`) calls `gateStore.setPlanGate(runId, true)` directly, skipping everything the REST approve does (`RunController.java:362-476`): credits-confirmed validation (378-400), org governance cost approval + project spend caps `assertCanApproveCost`/`assertProjectSpendCap` (404-411), the restart-detection path `isPlanGateWaiting` (433-456 — chat-approving after a server restart writes the gate with no pipeline thread listening and leaves the run silently stuck instead of failing it cleanly), the atomic claim `tryClaimPlanGate` (459-463), and the analytics event.
The write-guard itself is correct (and pinned by `ProjectChatActionToolsTest`), but guard ≠ billing/governance. This needs a shared approve/submit service before the flag ever flips.

**[BUG] B4 — test generation runs synchronously on the pipeline thread, before the run goes terminal; its javadoc claims the opposite.**
`integration/RunOrchestrator.java:1631-1639` calls `testGenOrchestrator.runTestGenPipeline(...)` inline (step 5); `terminate(runId, finalStatus, …)` — the COMPLETE/PARTIAL stamp — and the auto-push (step 7b) only happen afterwards (~1681+). `runTestGenPipeline` (`testgen/TestGenOrchestrator.java:58-192`) does scaffold → conventions → an LLM planning call → N parallel LLM generations **with `t.join()`** (147-153) → host-side `mvn test-compile` (120s budget) → a possible full retry round (more LLM calls + second compile). The class javadoc says "Runs in parallel to the code generation pipeline on a virtual thread" (`TestGenOrchestrator.java:26-27`) and "Triggered after plan approval" — both false at the only call site (triggered post-review, inline). Every HTTP-surface run's terminal status + PR creation is delayed by the entire testgen phase (minutes). This bites the published terminal-in-15-min SLO and makes "run looks stuck after review" reports likely.

**[BUG] B5 — process-output read before `waitFor(timeout)` makes the timeout unenforceable (×3).**
`testgen/TestCompilationGate.java:81-93`, `testgen/TestExecutionService.java:91-102`, `testgen/HeadlessTestExecutor.java:100-111`: all three do `reader.lines().collect(...)` on the process stdout BEFORE `process.waitFor(N, SECONDS)`. The read blocks until the process closes stdout, so a hung `mvn`/`docker run` that keeps the pipe open is waited on forever — the 120s/120s/300s timeouts only fire in the rare case the process closes stdout but doesn't exit. Compare `codereview/SemgrepRunner.java:124-166` in the same review scope, which does it correctly (gobbler threads + `waitFor(timeout)` + `destroyForcibly`). Given B4, a hung compile blocks the run's pipeline thread indefinitely.

**[BUG] B6 — `@Transactional` on self-invoked methods never fires; the "atomic" persistence step isn't.**
`PrReviewService.runReview` (pool thread) self-invokes `markRunning`/`completeReview`/`failReview` (`prreview/PrReviewService.java:233, 288, 213`); `CodeReviewService.runScan` self-invokes `setStatus`/`failScan`/`completeScan` (`codereview/CodeReviewService.java:220, 240, 264, 319-326`). All are `this.`-calls from inside the bean, so the Spring proxy never engages and the `@Transactional` annotations are decorative. `completeScan`'s javadoc — "Dedicated transactional method so the persistence step is atomic" (`CodeReviewService.java:344`) — is wrong: `issueRepo.deleteAll(...)` (375), `issueRepo.saveAll(...)` (437) and `scanRepo.save(row)` (442) each run in their own repository-level transaction. A crash between them yields a COMPLETE scan with zero or partial issues, or a markdown report inconsistent with the issue rows. Same shape in `completeReview` (`PrReviewService.java:309-346`: row save / comment deleteAll / comment saveAll are three separate txs).
Related race: `submitReview`/`submitScan` are `@Transactional` and `pool.submit(...)` fires INSIDE the uncommitted transaction (`PrReviewService.java:139+200`, `CodeReviewService.java:150+201`) — the pool thread's first `markRunning`/`setStatus` may not see the row yet and silently no-ops (`ifPresent`), leaving status skew (e.g. RUNNING never recorded). Usually masked by the slow GitHub fetch/clone, but it's a real ordering bug.

**[BUG] B7 — run-chat file-tree "noise filter" only inspects the last path segment, so `.git` internals flood the prompt.**
`chat/ChatService.renderFileTree` (`ChatService.java:418-439`): the filter checks `p.getFileName()` — `.git` itself is excluded (`startsWith(".")`) but its CHILDREN (`.git/HEAD`, `.git/refs/heads/...`, fileName "HEAD"/"refs") pass, as do `node_modules/<pkg>/...` children within the depth-3 walk. Because paths are sorted on the relativized string and `.` sorts before letters, `.git/...` entries occupy the FIRST lines of the 120-line budget (`MAX_FILE_TREE_LINES`, line 64). Net effect: the post-run chat copilot's system-prompt tree is mostly VCS noise and real source files get crowded out — directly degrading the feature the prompt block exists for. Fix: filter on any segment (`rel.startsWith(".git/")` etc.), as `CodeReviewService.shouldSkip` (`CodeReviewService.java:552-571`) already does.

**[BUG] B8 — BATCH-run "headless unit tests" execute the full live-HTTP test suite with `--network=none` → guaranteed all-error summaries.**
`testgen/HeadlessTestExecutor.java:92-97` passes `-DskipITs=true` claiming "skip integration tests (require live app)". `skipITs` is a **failsafe-plugin** flag; the generated classes are named `*Test` and run by **surefire** regardless. The generator prompts force RestAssured live-HTTP tests for non-CLI/non-static projects (`TestWorkerAgent.java:243-271`), so in a `--network=none` container everything fails with connection errors. The resulting `TestExecutionCompletedEvent` (published by `TestPreviewCoordinator.onTestsReady`'s BATCH branch, `TestPreviewCoordinator.java:84-115`) feeds `scoring/TestCoverageRescorer` — i.e. BATCH runs get systematically punished test summaries fabricated by the harness, not the code.

**[BUG] B9 — `TestResult.suggestion` is never written; the suggestion-driven UI/fix surface reads a permanently-empty column.**
Grep-verified: zero `setSuggestion(...)` calls and no builder `.suggestion(...)` on the entity anywhere in `src/main` (the only `.suggestion(` is on the DTO in `TestReportGenerator.java:200`). Consumers that depend on it: `TestResultController.getTestReport` root-cause groups (`TestResultController.java:96-118` — `suggestion` always `""`, `hasSuggestion` always `false`) and the (already-410) `apply-test-fixes` flow. The LLM fix-suggestions ARE generated (`TestReportGenerator.generateFixSuggestions`) but only emitted on a transient SSE event (`testReportReady`, line 121) and never persisted — a refresh loses them.

**[BUG] B10 — spec-chat audit upsert doesn't check session ownership; `cost_usd`/`closed_at` never written.**
`specchat/SpecChatController.upsertAudit` (`SpecChatController.java:175-201`) loads by `sessionId` and increments `turnCount` / overwrites `modelUsed` WITHOUT verifying the row's `userId` matches the caller. Session ids are client-generated (`SpecChatRequest.sessionId`), so user B reusing/guessing user A's session id pollutes A's audit row (B's turns counted against A) and blends both users' LLM spend into one `RunCostTracker` bucket keyed by sessionId (`SpecChatService.java:147`). The read endpoint `getAudit` DOES check ownership (`SpecChatController.java:163-172`) — the asymmetry is the bug. Additionally `SpecChatAudit.costUsd` (entity javadoc: "enterprise per-session billing") and `closedAt` have no writers anywhere — permanently 0/null.

**[BUG][SECURITY] B11 — tenant-guard gaps on prreview/codereview endpoints (the ArchUnit pin doesn't cover these URL shapes).**
- `POST /api/pr-reviews` (`PrReviewController.java:52-84`) and `POST /api/code-reviews` (`CodeReviewController.java:52-80`) accept an arbitrary `projectId` in the body with NO `ProjectAccessGuard` check — any paid-tier user can plant review/scan rows into another org's project (they surface in that org's `listForProject`, which IS guarded, ironically). Quota is charged to the caller, but the data lands cross-tenant.
- `GET /api/pr-reviews/{id}` (121-133), `GET /api/code-reviews/{id}` (118-130), `GET /api/code-reviews/{id}/report` (177-201) fetch by UUID with no tenant check (comments/issues/report markdown included). Random UUIDs make exploitation hard, but `listForProject`/`latest` in the same controllers got guards under "GA-readiness P4.1" while these were missed.
`arch/AccessGuardCoverageTest` pins only `/runs/{`, `/projects/{`, `/executions/{`, `/launches/{`, `/repos/{` URL fragments — `/api/pr-reviews/**` and `/api/code-reviews/**` fall outside the pin, so nothing fails the build.

**[BUG][SECURITY] B12 — code-pushing "apply" endpoints sit behind the READ guard.**
`RunAccessGuard.requireRunAccess` = visibility only; `requireRunWriteAccess` = owner/ORG_ADMIN/SUPER_ADMIN (`auth/security/RunAccessGuard.java:42-62`). Yet:
- `POST /api/runs/{runId}/apply` → `guard.requireRunAccess` (`api/controller/RunConversationController.java:139-145`), then `ApplyService.apply` runs a full-tool worker and PUSHES commits to the run's PR branches.
- `POST /api/runs/{runId}/pr-comments/{id}/apply` → `runAccessGuard.requireRunAccess` (`PrCommentInboxController.java:118-123`), same push path.
The same class of issue was explicitly fixed elsewhere: `markInvalid` was upgraded to the write guard (audit B#18, `TestResultController.java:154-157`) and PR-review autofix to `assertCanWrite` (C2, `PrReviewController.java:110-113`). Any org member (or cross-org viewer with run visibility) can currently drive pushes via apply.

**[BUG-lite] B13 — apply pushes even when the worker failed.**
`ApplyService.apply` calls `scmPushService.pushApplyChanges` unconditionally after the worker returns (`chat/ApplyService.java:245-254`), including `ITERATION_LIMIT_EXCEEDED`/`COMPLETED_BY_STAGNATION`/partial-edit outcomes (non-clean outcome is merely logged at 266-273). Half-applied suggestions land on the customer's live PR branch.

### Risks

**[RISK] R1 — provider hardcoded to `"anthropic"` in both paid review surfaces.** `PrReviewService.java:257` (`// default for now; PROJECT-aware override in slice 3` — never landed) and `CodeReviewService.java:303`. Project provider selection and the F3 per-org model matrix are ignored for PR review + code review; if the platform Anthropic key has an outage, both features hard-fail for everyone regardless of org BYOK/provider choice.

**[RISK] R2 — code-review file sampling loads every text file (≤1MB) fully into memory before picking 20.** `CodeReviewService.sampleFiles` (`CodeReviewService.java:494-548`) accumulates full contents of up to 5,000 files in `List<FileMeta>`; with the pool sized up to 8 concurrent scans (`CodeReviewService.java:101-108`) a few large repos can hold hundreds of MB of heap each. Score-then-read-top-20 would avoid it.

**[RISK] R3 — unbounded static-findings section in the code-review prompt.** `CodeReviewAgent.composeUserPrompt` (`CodeReviewAgent.java:160-183`) appends EVERY semgrep finding (message + snippet, no count cap). `p/ci` on a big repo can emit thousands → token-cost blowup on a PLANNING-tier model. The class javadoc's claimed safeguard — "caps the file-sample payload at a configurable budget (default ~150KB chars)" (`CodeReviewAgent.java:47-48`) — does not exist; the only caps are the hardcoded 20 files × 12KB.

**[RISK] R4 — `workspace-test/{runId}` is never cleaned up.** Created per HTTP run (`TestProjectScaffold.java:152`, relative path), with maven `target/` output. `compliance/WorkspaceDiskCleanupJob` sweeps only `agent.workspace` (`WorkspaceDiskCleanupJob.java:96-139`), and nothing else references workspace-test for deletion (grep). Unbounded disk growth on the VM. (The codereview `scan-{id}` clones DO get reaped as NO_DB_ROW dirs, but only after the multi-day age threshold — each is a full repo clone in the meantime, `CodeReviewService.java:225-230`.)

**[RISK] R5 — `TestPlanRepository.findByRunId` returns `Optional` but nothing guarantees one plan per run.** (`testgen/repository/TestPlanRepository.java:13`.) `runTestGenPipeline` unconditionally inserts a new `TestPlan` (`TestPlannerAgent.java:74-80`); any re-trigger (resume/retry path reaching step 5 again) creates a second row, after which every `findByRunId` consumer (`TestGenController:39,82`, `TestResultController:60,196`) throws `IncorrectResultSizeDataAccessException` → those tabs 500 permanently for that run.

**[RISK] R6 — macOS dev path baked into the prod compile fallback.** `TestCompilationGate.java:59-62`: if `JAVA_HOME` is unset, it becomes `/opt/homebrew/opt/openjdk@21` — wrong on the GCE Linux VM. Also `mvn` resolved from inherited PATH.

**[RISK] R7 — compile-failure attribution depends on the class being named `*Test`.** `ERROR_FILE_PATTERN` (`TestCompilationGate.java:39-40`) only matches `([A-Z][A-Za-z]+Test)\.java`; an LLM emitting `FooIT`/`TestFoo` means failures map to no task — combined with the optimistic `COMPILED` stamp at generation time (`TestWorkerAgent.java:119`) the per-task statuses go green while the build is red. Also after a SUCCESSFUL retry-recompile (`TestGenOrchestrator.java:236-241` early-returns), retried tasks are left in `GENERATING` forever (only `markFailedTasks` flips `GENERATING→COMPILED`, and it's not called on that path).

**[RISK] R8 — concurrent PR-review submits on the same SHA 500 instead of deduping.** `submitReview`'s check-then-insert (`PrReviewService.java:155-192`) races against the V172 unique index `uq_pr_review_repo_pr_sha` — the loser gets an unhandled constraint violation. Code review has no in-flight dedupe at all (only COMPLETE-with-same-SHA reuse, `CodeReviewService.java:169-181`), so double-clicks run two full clones+scans.

**[RISK] R9 — the `pr-review-{id}` / `code-review-{id}` SSE channels are unsubscribable; all emits are dead letters.** The only subscription surface is `/api/runs/{runId}/events` + `/api/runs/{runId}/sse-token`, and BOTH call `guard.requireRunAccess(runId)` → `stateStore.findRun("pr-review-…")` → 404 (`SseController.java:34-40, 48-76`). The ~12 `broadcaster.emit("pr-review-…"/"code-review-…")` sites in `PrReviewService`/`CodeReviewService` reach no client; `PrReviewController`'s javadoc (line 32-35) telling the frontend to subscribe is wrong — the frontend in fact polls (`frontend/src/lib/api/reviews.ts`).

**[RISK] R10 — `GET /api/runs/{id}/apply/status` has no frontend caller.** The endpoint (`RunConversationController.java:185`) + `ApplyResultStore` exist precisely so the UI can re-attach after a severed POST ("Fix I", `ApplyResultStore.java:17-30`), but grep over `frontend/src` finds zero usage of the path or any wrapper. The re-attach loop was never wired client-side (possibly consumed by the now-external CLI repo — unverifiable here). Until wired, the original lost-outcome failure mode persists.

**[RISK] R11 — two surfaces can run concurrent applies against one run's git workspace.** `ApplyService`'s documented concurrency stance is "the UI only surfaces one button at a time" (`ApplyService.java:48-50`), but `RunConversationController.applySuggestion` (4-thread `applyExecutor`) and `PrCommentInboxController.apply` (2-thread pool, `PrCommentInboxController.java:56-68`) are independent entry points; simultaneous use yields dirty-index/garbled commits on the same workspace.

**[RISK] R12 — spec-chat accepts client-supplied `role:"system"` messages.** `SpecChatService.translate` (`SpecChatService.java:271-286`) maps `"system"` conversation entries to `SystemMessage`, letting the CLI inject system-level text AFTER the governance prompt — trivially defeating the "never approve speculatively" instruction (same-user authority, so no privilege escalation, but it nullifies the documented human-gate design and the N14 refinement).

**[RISK][PERF] R13 — `RunCostTracker` bucket leak from chat surfaces.** `ChatService` (post-run chat, runId bucket) and `SpecChatService` (sessionId bucket) record cost via the router but never call `finalizeRun`, unlike `PrReviewService.java:345` / `CodeReviewService.java:445`. Each chatted run / CLI session leaves an `Accumulator` in the in-memory `byRun` map until JVM restart.

**[RISK] R14 — `FindingPatchSubmitter` stamps the dedupe key after `submitRun` returns.** `finding/FindingPatchSubmitter.java:119-125`: between dispatch and the `triggered_by_finding` save there's a window where a duplicate webhook/click passes the dedupe query (`PipelineRunRepository.java:331-335`) → double patch runs/PRs. Also a lost-update hazard: the async pipeline thread saves the same `PipelineRun` row concurrently; a stale full-entity save can wipe the stamp. This is exactly the "setting run fields AFTER submitRun() returns" anti-pattern the codebase warns about for ownership fields.

### Smells / inconsistencies / duplication / layering / perf

- **[INCONSISTENT] Field injection** — forbidden by convention, present twice: `TestGenOrchestrator.java:53` and `TestResultController.java:51` (`@Lazy @Autowired private PreviewService`). `TestPreviewCoordinator.java:49-58` demonstrates the sanctioned `ObjectProvider` alternative in the same package and even names the anti-pattern.
- **[INCONSISTENT] Hardcoded workspace paths** — `Path.of("workspace", runId)` in `TestGenOrchestrator.java:197` and `TestPlannerAgent.java:103` (vs `workspaceInitService.runWorkspacePath` used at `TestGenOrchestrator.java:98` in the same method flow, and `agentProps.getWorkspace()` in chat/apitests). Works only because `agent.workspace: ./workspace` (application.yml:389) and CWD cooperate; breaks silently on any config change. `"workspace-test"` is similarly hardcoded ×4 (`TestProjectScaffold.java:152,168`, `TestCompilationGate.java:50`, `TestExecutionService.java:42`, `HeadlessTestExecutor.java:60`) and is CWD-relative.
- **[INCONSISTENT] Prompt conventions** — testgen/apitests load `.txt` prompts via `Resource.getContentAsString` (`TestPlannerAgent.java:266-272`, `TestWorkerAgent.java:358-364`, `TestReportGenerator.java:230-236`, `ApiTestSuiteAgent.java:117`) — the stated convention is `PromptAssembler` + `.md`. `PrReviewAgent.java:77-84`/`CodeReviewAgent.java:77-84` use raw `StreamUtils.copyToString(ClassPathResource)`. `ApplyService.java:148-179`, `SpecChatService.java:65-86`, `PromptOptimiserAgent.java:30-51` keep multi-paragraph prompts as Java string literals.
- **[SMELL] AnthropicChatOptions built unconditionally for ALL providers** in `TestPlannerAgent.java:211-218`, `TestWorkerAgent.java:295-302`, `ApiTestSuiteAgent.java:119-126`. Harmless only because `FallbackLlmRouter` re-wraps options per provider (`FallbackLlmRouter.java:696-780`), but it misleads readers and silently discards the cache options for non-Anthropic.
- **[INCONSISTENT] Default-provider drift** — testgen falls back to `LlmProvider.OPENAI` when the run row is missing (`TestPlannerAgent.java:119-120`, `TestWorkerAgent.java:100-101,147-148`, `TestReportGenerator.java:164-165`); chat/specchat/apply fall back to `ANTHROPIC`; the platform default is Anthropic.
- **[BUG-lite] Severity ordered alphabetically** — `findByScanIdOrderBySeverityAsc...` (`CodeReviewIssueRepository.java:13`) yields CRITICAL→INFO→MAJOR→MINOR (string sort). The markdown renderer re-orders canonically, but the JSON issue lists served by `GET /api/code-reviews/{id}` and `/latest` ship in that wrong order.
- **[SMELL] Semgrep severities can never be CRITICAL** — mapping collapses `ERROR/CRITICAL → MAJOR` (`SemgrepRunner.java:233-239`), so the autofix "N CRITICAL" bucket is LLM-only by construction, while `SemgrepRunner.Finding` doc (line 56) and the issue entity advertise CRITICAL for STATIC. Also the timeout path logs "returning partial findings" while returning an empty list (`SemgrepRunner.java:152-163`).
- **[DUPLICATION]** (a) `ApiSurfaceExtractor.extract` vs `extractPaths` — two near-identical 40-line walks (`ApiSurfaceExtractor.java:48-77` vs `86-127`). (b) `PrReviewService` vs `CodeReviewService`: tier/quota gate, dispatch pool, status machine, fail/complete writers, exception pairs — ~150 copy-shaped lines each. (c) `resolveLinkedRepoId` duplicated verbatim (`PrReviewAutofixService.java:130-145`, `CodeReviewAutofixService.java:124-139`), both with an N+1 `findById` per project link. (d) `shortSha` ×3 (`CodeReviewService.java:671`, `CodeReviewController.java:203`, `CodeReviewMarkdownRenderer.java:137`). (e) The manual tool-execution loop exists three times — `ChatService.runToolLoop` (`ChatService.java:188-271`), `SpecChatService.runToolLoop` (`SpecChatService.java:166-227`), plus `ProjectChatService` per their own comments — along with two private `SimpleToolCallback` clones (`RunChatTools.java:214-250`, `ProjectChatActionTools.java:382-419`) that both say "mirrors ProjectChatTools.SimpleToolCallback" (a third copy). This loop is exactly the duplicated LLM-call pattern `LlmRouter` (or a shared `ToolLoopRunner`) should own — tool-call aggregation, unknown-tool envelopes, round caps, and the "(hit my tool-call limit)" tail differ subtly across copies already.
- **[SMELL] `emitTestEvent` discards its `eventType` argument** (`TestGenOrchestrator.java:300-302`) — all the `TEST_GEN_STARTED`/`TEST_PLANNING_*` names are decorative; everything emits as a generic log line.
- **[SMELL] Bogus graph edge** `"test-planner" → "test-worker-first"` always emitted (`TestGenController.java:155`) — a node id that never exists.
- **[SMELL] Dead locals**: `waitSec` computed and never used (`TestPreviewCoordinator.java:284-287`); `String body = failure.getTextContent()` unused (`SurefireReportParser.java:101`); `output` collected then unused in `TestExecutionService.java:92-96` and `HeadlessTestExecutor.java:101-105` (drain is needed; the variable isn't).
- **[SMELL] `ChatService` constructs its own `ObjectMapper`** (`ChatService.java:75`) instead of injecting the configured bean (every sibling injects). `ApplyResultStore` does the same but with a documented naming-strategy reason (`ApplyResultStore.java:44-47`).
- **[SMELL] `ChatService.send` read-modify-write of chat history with no locking/tx** (`ChatService.java:107-174`) — two concurrent sends lose one message pair (last save wins).
- **[INCONSISTENT] Stale javadoc, load-bearing**: `PrReviewAgent.java:40-41` claims "the service treats it as failure and retries once with a stricter prompt" — no retry exists anywhere (service catches → `failReview`, `PrReviewService.java:276-285`). `PrReviewAgent.java:36-37` says Gemini PLANNING = `gemini-3-pro-preview` (YAML says `gemini-3.5-flash`). `TestPlannerAgent.java:33-34` + `TestWorkerAgent.java:35` claim "CAPABLE tier (Sonnet)" and a comment at `TestPlannerAgent.java:118` says "PLANNING tier" — YAML maps both to WORKHORSE (`application.yml:353-354`). `ChatService` class javadoc (line 40-44) lists two tools; there are three.
- **[SMELL] Planner's per-method test plans never reach the worker** — `{test_methods_json}` is substituted with the task DESCRIPTION via a pointless try/catch whose both branches are identical (`TestWorkerAgent.java:184-190`); the `MAX_METHODS_PER_CLASS` trimming in the planner (`TestPlannerAgent.java:163-173`) therefore only affects the stored `planJson`, not generation.
- **[SMELL] `CodeReviewAutofixService` cap comment wrong** — "keeps the message under ~7 KB" (`CodeReviewAutofixService.java:54`) but 25 issues × (message + 600-char fix) is ~20KB+.
- **[BUG-lite] Draft-PR promise is fictional** — javadoc "the auto-pushed PR is opened as a draft" (`CodeReviewAutofixService.java:42-43`) and the prompt constraint "Mark the dispatched PR as DRAFT" (`:209-210`): grep finds no draft support in `ScmPushService`/`GitHubRepoService`, and the agent has no PR-creation tool anyway (push happens at run end). Reviewer-facing expectation mismatch.
- **[SMELL] `ApplyService.apply` `source` contract** says `"COPILOT" or "REVIEW"` (`ApplyService.java:89-92`) but `PrCommentInboxController` passes `"PR_COMMENT"` (`:146`), silently bucketed as `copilot:` commit prefix (`ApplyService.java:133`).
- **[INCONSISTENT] `ApiTestSuiteAgent` cost accounting** — uses non-tracked `callWithFallback` + manual `stateStore.addTokensToRun` (`ApiTestSuiteAgent.java:136-155`), so tokens land on the run row but no USD is priced through `RunCostTracker` (testgen agents use the `Tracked` variant). `PromptOptimiserAgent.java:183-203` same pattern.
- **[INCONSISTENT] `PromptOptimiserAgent`** — manual constructor instead of `@RequiredArgsConstructor` (`PromptOptimiserAgent.java:61-76`); virtual thread NOT wrapped in `ContextSnapshotFactory.captureAll().wrap(...)` (`:81-83`), violating the stated async pattern (testgen + apitests do wrap); `agent.optimiser.enabled: true` is a YAML literal, not a `FeatureFlagService` flag, for self-modifying-prompt behavior (`application.yml:454-459`).
- **[PERF] `TestPlannerAgent` persists tasks one save per row** (`TestPlannerAgent.java:176-194`); `TestWorkerAgent.accumulateWorkerTokens` is a `synchronized` read-modify-write of the shared `TestPlan` row per generation thread (`TestWorkerAgent.java:319-336`) — fine at N≤6, but a DB-side increment would be simpler and race-free.
- **[PERF] `submitReview`/`submitScan` hold a DB transaction across GitHub network calls** (`PrReviewService.java:139-143` fetches a PR snapshot with a 200KB diff inside the tx; `CodeReviewService.java:150-166` resolves default branch + head SHA) — Hikari connections pinned for seconds per submit.
- **[LAYERING] `PrCommentInboxController` builds the LLM-facing suggestion prompt and owns an executor** (`PrCommentInboxController.java:56-68, 167-186`) — dispatch policy + prompt synthesis in the web layer; the service layer would be the home (`ApplyService`'s own javadoc places async-dispatch responsibility on callers, which spreads it across controllers).

## 3. Dead code & junk (grep-verified, `src/` + `frontend/`)

**The optimiser package is wired-but-vestigial (answering "live or experiment leftovers?": leftovers, still burning cycles).**
- It runs on every completed run (`harness/event/PostMergeOrchestrator.java:51` + `:38`) with `agent.optimiser.enabled: true`, but:
- `resolvePromptPath` targets `src/main/resources/prompts/...` on the FILESYSTEM (`PromptOptimiserAgent.java:242-244`) — that path only exists on a source checkout; in the packaged single-JAR deployment `Files.exists` is false → every optimisation is skipped. Even on a checkout, the running JVM reads prompts from the classpath, so writes wouldn't take effect until rebuild.
- 3 of 4 pattern detectors can never fire: `"FILE_LOCK"` / `"BOUNDARY_VIOLATION"` / `"README_MISSING"` appear in NO writer anywhere in `src/main` (grep: only `PromptPatternAnalyser.java:48,58,104`), and the `"LLM_LOOP"` recovery classification is referenced only by the analyser itself (`PromptPatternAnalyser.java:39`) — no code ever writes such a row. Only the PARTIAL-task planner heuristic (`:75-86`) can trigger.
- `PromptOptimiserProperties.model` + `.provider` (`PromptOptimiserProperties.java:32-34`) — zero readers (the agent resolves via `ModelResolver`); dead config knobs, including the documented 2026-05-06 fall-through behavior that nothing invokes.
- Net: per completed run it costs a `countCompletedRuns` + window query (`PromptOptimiserAgent.shouldRun`, `:208-227`) + the unconditional `recordRunOutcome` sweep (`PostMergeOrchestrator.java:38` → `PromptAuditService.java:65-75`), to do nothing. Either delete or rebuild against V2 signals.

**Other grep-verified dead code:**
- `ApiTestSuiteAgent.hasFrontendFiles` (`ApiTestSuiteAgent.java:181-197`) — zero callers; the class javadoc (lines 39-44) still documents the frontend-skip rule it used to implement, contradicted by the comment at `:93-94`.
- `ApiTestSuiteProperties.getModel()` / `getProvider()` + the injected `ModelRegistry` (`ApiTestSuiteProperties.java:29-38`) — zero external callers; only `isEnabled()` is used.
- Repository methods with zero callers: `CodeReviewIssueRepository.findByFingerprint` (javadoc promises "carry status forward" — feature never built), `countByScanIdAndStatus`, `countByScanIdAndSeverityAndStatus`; `CodeReviewScanRepository.findFirstByRepoFullNameOrderByStartedAtDesc`; `PrReviewCommentRepository.countByPrReviewId`; `TestResultRepository.countByTestPlanIdAndStatus`.
- `TestWorkerAgent.generateTestClass` 6-arg overload (`TestWorkerAgent.java:60-68`) — only self-delegation; the orchestrator calls the 7-arg.
- `ApplyService.tryGitCommit` + `runCmd` (`ApplyService.java:348-397`) — `@SuppressWarnings("unused")`, deliberately retained with a paper-trail javadoc ("currently unused after Fix L").
- `ApplyResultStore.delete` (`ApplyResultStore.java:142-150`) — zero callers; its javadoc claims "Used by the controller's 'kick off a new apply' path" — false.
- `TestResultController`: ~95 lines of commented-out `applyTestFixes` logic (`TestResultController.java:242-338`) behind an unconditional 410, plus the now-unused `conversationAgent` constructor dependency (referenced only inside the comment block).
- `TestPreviewCoordinator.CoordinationState.previewFailed` (`TestPreviewCoordinator.java:343` set at `:201`) — `isPreviewFailed` has zero readers; the flag is set on a state object that's removed from the map on the next line.
- `TestGenOrchestrator`'s `TEST_*` event-type strings (see `emitTestEvent` smell above).
- `Finding.aiProvider` is plumbed but every caller passes `null` (`PrReviewAutofixService.java:116`, `CodeReviewAutofixService.java:111`) — currently decorative.

**Stale TODO/FIXME-style comments worth quoting:**
- `PrReviewService.java:257` — `String aiProvider = "anthropic"; // default for now; PROJECT-aware override in slice 3` (slice 3 never came; same silent default copied into `CodeReviewService.java:303`).
- `TestResultController.java:241` — `// Original logic preserved below for the V2 wire-up follow-up. Currently unreachable.`
- `ApplyService.java:343-347` — "Retained as an internal escape hatch (currently unused after Fix L) … restore the call site here."
- `TestGenOrchestrator.java:26-28` — "Runs in parallel to the code generation pipeline on a virtual thread. Triggered after plan approval" (neither is true — see B4).

## 4. Model/LLM touchpoints

| Caller | Router | Resolution | Tier (YAML `application.yml`) | Caps | Notes |
|---|---|---|---|---|---|
| `PrReviewAgent` | `LlmRouter` | `resolve("PrReviewAgent", p)` | PLANNING (:369) | temp 0.0, 8192 out | double cost record (B1); single forced `submitReview` tool |
| `CodeReviewAgent` | `LlmRouter` | `resolve("CodeReviewAgent", p)` | PLANNING (:370) | temp 0.0, 16384 out | double cost record (B1); unbounded findings input (R3) |
| `PrReviewService`/`CodeReviewService` | — | provider literal `"anthropic"` | — | — | R1; org matrix/project provider ignored |
| `TestPlannerAgent` | `FallbackLlmRouter.callWithFallbackTracked` | `resolve("TestPlannerAgent", run provider, else OPENAI)` | WORKHORSE (:353) | temp 0.0 | Anthropic cache opts passed for all providers; 1 JSON-retry |
| `TestWorkerAgent` | same | `resolve("TestWorkerAgent", …)` | WORKHORSE (:354) | temp 0.0 | one compile-error retry per task |
| `TestReportGenerator` | same | `resolve("TestReportGenerator", …)` | LIGHTWEIGHT (:367) | — | fix suggestions; FAILED-only (ERRORs excluded) |
| `ApiTestSuiteAgent` | `callWithFallback` (untracked) | `resolve("ApiTestSuiteAgent", run provider)` | WORKHORSE (:355) | temp 0.0 | tokens via `stateStore.addTokensToRun`, no USD pricing |
| `PromptOptimiserAgent` | `callWithFallback` (untracked) | `resolve("PromptOptimiserAgent", run provider)` | WORKHORSE (:366) | — | vestigial (§3) |
| `ChatService` | `LlmRouter` manual loop | `resolve("ChatService", provider, LIGHTWEIGHT)` (explicit-tier overload, `ModelResolver.java:170`) | tier override | temp 0.2, 1200 out, 4 rounds | ANTHROPIC fallback; read-only tools |
| `ApplyService` | `engine/WorkerAgent` | `resolve("ApplyService", provider, WORKHORSE)` | tier override | 90/180 iters, temp 0.0 | full worker toolset on existing workspace |
| `SpecChatService` | `LlmRouter` manual loop | `resolve("SpecChatService", provider, LIGHTWEIGHT)` | tier override | temp 0.2, 1200 out, 5 rounds | write action tools; flag-gated |

- No hardcoded model names or prices found in scope (good — names flow through `ModelResolver`/YAML). The only hardcoded model-ish strings are in stale javadocs (`gemini-3-pro-preview`, "Sonnet"/"Haiku" tier comments).
- Cost plumbing is inconsistent across the batch: `LlmRouter`-auto (chat/specchat/reviews — with B1 double-count), `Tracked` fallback (testgen), manual token-add without pricing (apitests/optimiser).
- `codereview.semgrep.*` has no YAML block — `@Value` defaults apply (`p/ci`, 600s, `semgrep` on PATH; `SemgrepRunner.java:71-78`).

## 5. Libraries

- **Spring AI 1.1.x** — `ChatResponse`/`AssistantMessage`/`ToolResponseMessage`/`ToolCallback` (chat loops, review agents); Anthropic-specific `AnthropicChatOptions`+`AnthropicCacheOptions` leak into provider-agnostic call sites (neutralized downstream).
- **Jackson** — everywhere; `ChatService` and `ApplyResultStore` new up local mappers instead of the bean.
- **Lombok** — `@RequiredArgsConstructor`/`@Data`/`@Builder` standard; `PromptOptimiserAgent` hand-rolls its constructor.
- **JDK process/exec** — `ProcessBuilder` for semgrep (correct gobbler pattern), mvn test-compile (env-stripped, good; read-before-wait bug B5), `docker run` sandboxes for test execution (`--read-only --cap-drop ALL --memory --tmpfs` — solid hardening; `maven:3.9-eclipse-temurin-21` image pinned loosely by minor tag), git in dead `ApplyService.runCmd`.
- **`java.net.http.HttpClient`** — `ApiProber` (5s timeouts, bounded 12 probes).
- **DOM XML** — `SurefireReportParser` with `disallow-doctype-decl` (XXE-safe, `SurefireReportParser.java:60`).
- **Redis** — `StringRedisTemplate` in `ApplyResultStore` (1h TTL, best-effort).
- **Micrometer** — `PromptAuditService` counter; `ContextSnapshotFactory` wrapping in testgen/apitests (but not optimiser).
- **Generated-test stack pinned in code** — JUnit 5.10.2, RestAssured 5.4.0, AssertJ 3.25.3, Playwright 1.49.0, Jackson 2.17.0, Surefire 3.2.5 hardcoded in `TestProjectScaffold` POM templates (version-drift surface; nobody owns bumping these).
- Tests: JUnit 5 + Mockito + AssertJ, no Spring context anywhere in this batch's tests (fast, good).

## 6. Test coverage

Covered (8 files, all unit-level, no Spring context):
- `codereview/`: `CodeReviewAgentParseTest` (parser tolerance — good), `CodeReviewServiceTierGateTest` (quota matrix incl. -1/0 semantics — good), `CodeReviewMarkdownRendererTest` (rendering contract — good). One junk test: `object_mapper_classpath_sanity` (`ChatServiceValidationReportTest.java:157-159` — asserts `new ObjectMapper()` non-null).
- `chat/`: `ApplyResultStoreTest` (round-trip + TTL + fail-open), `ApplyServicePushSummaryTest` (pure formatter), `ChatServiceValidationReportTest` (prompt block).
- `specchat/`: `SpecChatServiceTest` (tool loop shapes), `ProjectChatActionToolsTest` (write-guard pins — correctly pins B12's guard distinction for the TOOLS, but no test covers the quota/governance parity gap of B3).

Zero tests for:
- `prreview/` — notably `PrReviewAgent.parseSubmission` has no analogue of `CodeReviewAgentParseTest` despite identical fragility; `PrReviewService` idempotency/quota untested; inbox dismiss/apply untested (B2 would have been caught by a one-line test).
- `finding/` — `FindingPatchSubmitter` dedupe/stamp semantics (R14) and `Finding` validation untested.
- `testgen/` — 29 files, 0 tests. The compile-gate parsing (R7), surefire parsing, coordinator state machine, and headless-vs-integration distinction (B8) are all unpinned.
- `apitests/`, `optimiser/` — none (for optimiser, deletion beats testing).

## 7. Security notes (parked)

- B2 (dismiss IDOR), B11 (pr/code-review tenant gaps + ArchUnit pin blind spot), B12 (apply behind read guard) are the actionable ones above.
- B3 is a governance/billing bypass more than an authz hole (guards are correct; money/gate semantics are not).
- `SpecChatController.clientIp` trusts `X-Forwarded-For` first value blindly (`SpecChatController.java:255-260`) — fine behind Cloudflare, spoofable on direct hits; only feeds an audit hash.
- Spec-chat client-supplied `system` role (R12) — same-user authority, prompt-governance only.
- Semgrep executes on freshly cloned third-party repo content as a host process (no container), with rulesets fetched from the network (`p/ci`); compile gate runs host-side mvn over LLM-generated pom/sources with env stripped (documented residual, `TestCompilationGate.java:24-31`). Both are accepted-risk per in-code audit notes; listing for completeness.
- `RunChatTools` read-only claim verified: `WorkspaceFileService.readFile` normalizes + `startsWith(root)` (path-traversal-safe, `WorkspaceFileService.java:158-160`); no write/shell tool bound (`RunChatTools.bind`, `:60-62`).

---
*Top 5 by priority: B1 (cost 2×, customer-visible numbers), B4 (testgen blocks run-terminal, SLO), B2+B12 (cross-run/role write gaps), B3 (pre-flip billing bypass in spec-chat), B5/B6 (timeout + fake-transaction reliability cluster). Quick wins: delete the two double-`record` lines; `findForRun` in dismiss; write-guard on the two apply endpoints; wrap `runTestGenPipeline` in the sanctioned wrapped virtual thread.*
