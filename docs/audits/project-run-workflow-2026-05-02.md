# Project + Run workflow — what actually happens vs. what's promised

**Audit date:** 2026-05-02
**Triggered by:** user report — multi-repo runs producing empty PRs.
**Scope:** every server-side step from "create project" → "PR opens" for V2 project runs.

> This doc walks through the actual code paths so you can see what fires, what doesn't, what's versioned, and what regressed. Where promises don't match reality, the gap is called out explicitly. The multi-repo bug fixed this morning (`f9ee51f4`'s sibling commit, see end) is one of three concrete gaps surfaced.

---

## 1. Project creation — `POST /api/projects`

`ProjectService.createProject(orgId, createdBy, req)`:

| Step | What fires |
|---|---|
| Auth + tier gate | `tierEnforcement.assertCanCreateProject(userId, orgId)` reads `tier_config.max_projects_per_org` (V140) — 403 if at cap |
| Persist | `Project` row inserted with name, description, ai_provider, scaffold_template, figma_url |
| Activity event | `ActivityEventEmitter.emit(PROJECT_CREATED, ...)` writes to `activity_feed` |
| Activation funnel | `eventTracking.track(userId, EventName.PROJECT_CREATED, ...)` |

**What does NOT fire:**
- ❌ No codemap generated (project has no repos yet — fair)
- ❌ No architecture doc generated
- ❌ No supporting docs auto-created
- ❌ No `.executespec/` folder bootstrap (that fires per-repo on link, not per-project)
- ❌ No project-memory snapshot (project memory is post-run-driven, see §5)

**State after creation:** an empty project shell. The dashboard renders the `EmptyProjectLanding` component (D3 fix landed earlier today: outcome-led "What do you want to build?" copy + tier-aware run cap).

---

## 2. Repo linking — `POST /api/projects/{id}/repos/{repoId}`

`ProjectRepoService.linkToProject(projectId, repoId, callerUserId, callerOrgId)`:

| Step | What fires |
|---|---|
| Auth | `requireProjectAccess(...)` enforces caller is in project's org |
| Tier gate | `tierEnforcement.assertCanLinkRepoToProject(userId, projectId)` reads `tier_config.max_repos_per_project` (V140) — 403 if at cap |
| Owner allowlist (M-039) | `governanceGuard.assertRepoOwnerAllowed(orgId, owner, null)` checks the GH owner is on the project's org allowlist |
| Scope check | ORG repo → must belong to project's org. USER repo → must be owned by caller (no cross-user linking) |
| Dedup | 409 if repo already linked to this project |
| Persist | `ProjectRepoLink` row written |
| **`.executespec/` bootstrap (E1.5)** | `BootstrapService.bootstrapAsync(repoId, projectId, projectName, actorUserId, pat)` — **fires async**. Writes `.executespec/` scaffold to the customer repo via PR (or DIRECT_PUSH per V132) |

**What does NOT fire:**
- ❌ **No codemap generation.** The `repo_code_map` row stays empty until someone calls `POST /api/projects/{id}/analyse` manually OR the next V2 run's lazy refresh kicks in.
- ❌ **No architecture doc.** Same — `project_architecture_doc` + `repo_architecture_doc` are empty until manual `analyse`.
- ❌ **No supporting docs.** `project_generated_doc` (Generated Docs feature, EXPLANATION + SETUP types) is on-demand only — surfaced by the GeneratedDocsPanel on the project Overview tab; user clicks "Generate" to create.
- ❌ **No project-memory rebuild.** That's run-completion-driven (see §5).

**Implication:** linking a repo gives you an empty project with `.executespec/` in customer git. Running a spec on it without first hitting `/analyse` means the planner sees:
- Multi-repo summary block (per-repo subdir paths) — ✅ if `selectedRepoIds` was passed
- No codebase awareness — ❌ unless lazy-refresh kicks in (only fires if a stale codemap *already exists*)
- Project memory — ❌ empty on first run
- Reference docs from RAG — ❌ empty unless user uploaded docs

So **the first run on a freshly-linked-repo project plans against an essentially blind context.** The promise is that codemap + arch are pre-built; the reality is they're lazy.

---

## 3. Codemap + architecture — when do they actually exist?

Three trigger paths, all manual or run-time:

### 3a. Explicit user trigger — `POST /api/projects/{id}/analyse?branch=<name>`

`ProjectArchitectureService.generate(projectId, branch, pat)` runs the **3-step pipeline (v2.5.12)**:

| Step | Output | Idempotency |
|---|---|---|
| **1. Per-repo codemap** | One `project_code_map` row per linked repo. SHA-keyed: if `generatedFromSha` matches current origin HEAD, the existing row is reused. | ✅ skipped on no-SHA-change |
| **2. Per-repo architecture** | One `repo_architecture_doc` per repo (HLD + LLD via `RepoArchitectureGenerator`, LIGHTWEIGHT tier). Idempotent by `sourceCodeMapId` — reused if codemap unchanged. | ✅ skipped on no-codemap-change |
| **3. Combined architecture** | One `project_architecture_doc` synthesised from per-repo arch docs (`ArchitectureDocGenerator`, WORKHORSE tier). Versioned. | new version on every call |

**Versioning:**
- `ProjectCodeMap` — `version` column, monotonically incremented per repo
- `RepoArchitectureDoc` — `version` per repo
- `ProjectArchitectureDoc` — `version` per project
- All carry `generatedFromSha` (or `generatedFromShas` JSON map for multi-repo) so `StalenessService` can check drift via `git rev-parse HEAD`

### 3b. Run-time lazy refresh

`V2RunOrchestrator.runPipeline` (line 780-789):

```java
var staleness = stalenessService.report(projectId, runUserPat);
if (staleness != null && staleness.stale()) {
  log.info("V2 run={} project={} has {} stale codemap(s); regenerating before planner", ...);
  projectArchitectureService.generate(projectId, null, runUserPat);
  stalenessService.invalidate(projectId);
}
```

**Critical caveat:** `staleness.stale()` is true only when an **existing** codemap's `generatedFromSha` differs from current origin HEAD. If the codemap row doesn't exist yet, this returns "clean" → no regen → planner runs without codemap context. The **first run** on a project never triggers it.

### 3c. Per-run codemap (single-repo only)

For single-repo runs (`projectId == null`, `repoMode == "EXISTING"`), `RunCodeMapService.generateForRun(...)` builds a one-shot codemap into `run_code_map`. This path is **PRO/MAX/ENTERPRISE-only** (gated by `RunCodeMapService.isEligible(tier)`).

**Project-mode runs do NOT use this path** — they rely on the project-side codemap from §3a/3b.

### Net answer to "are we creating codemap and architecture or not?"

| Trigger | Codemap | Architecture | When |
|---|---|---|---|
| Project creation | ❌ | ❌ | never |
| Repo linking | ❌ | ❌ | never |
| `POST /api/projects/{id}/analyse` | ✅ | ✅ | manual click on dashboard |
| First V2 run after link (no prior analyse) | ❌ | ❌ | lazy-refresh requires existing row |
| Subsequent V2 runs (analyse already happened) | ✅ refreshed if SHA drifted | ✅ refreshed | lazy-refresh hits |
| Single-repo `/runs` legacy (no project) | ✅ per-run | ❌ | runtime, paid tiers only |

**Recommended fix:** auto-trigger `analyse` async after repo link (similar to `BootstrapService.bootstrapAsync`). Today this is a manual click; users don't know to do it.

---

## 4. Supporting documents

Two distinct types:

### 4a. RAG corpus — `project_document` + `project_document_chunk` (v2.5.0)

User-uploaded PDFs / markdown / text indexed via Tika + pgvector. Surfaced via the Documents tab on the project dashboard.

- Status flow: `UPLOADED → EXTRACTING → EMBEDDING → READY/FAILED`
- Read at run-time: `RetrievalService.retrieve(projectId, query, k=3, cap=1500)` — injected into the planner's `{{REFERENCE_DOCS}}` block + the worker's `{{REFERENCE_DOCS}}` block (k=2/cap=800 in worker)
- **Auto-creation: none.** User uploads explicitly.

### 4b. Generated docs — `project_generated_doc` (v2.5.12)

LLM-synthesised explainer docs (EXPLANATION + SETUP types). Read project memory + arch + codemaps; produce ~500-1200 word markdown.

- Endpoint: `POST /api/projects/{id}/docs/{EXPLANATION|SETUP}/generate` — 409 if no architecture exists yet
- UI: GeneratedDocsPanel on project Overview tab. User clicks "Generate" or "Regenerate"
- **Auto-creation: none.** User triggers manually.

### Net

Supporting docs are **always** user-triggered. There's no auto-population on project create or repo link. The Generated Docs path explicitly errors (409) when there's no architecture, so users have to: link repo → manually analyse → manually generate docs. Three separate clicks.

---

## 5. Project memory — `project_run_ledger` + `project_memory_snapshot` (v2.2.0)

- **Append:** every completed project-scoped run appends a caveman-English digest to `project_run_ledger` via `RunLedgerAgent` (LIGHTWEIGHT tier, post-validation, non-fatal).
- **Compact:** `MemoryCompactionService` rewrites a versioned `project_memory_snapshot` every 10 runs (starting at R20). Keeps planner context bounded as the project ages.
- **Read at run-time:** `ProjectMemoryProvider.buildPlannerContext(projectId)` injects a `{{PROJECT_MEMORY}}` block into the planner's prompt.

**Implication:** the **first** run on a project has zero memory. The Nth run has summarised context from the prior N-1.

---

## 6. Run lifecycle — V2, project mode

### 6a. Submit — `POST /api/runs` with `projectId` + `selectedRepoIds`

`RunController.submitRun(req)` → `V2RunOrchestrator.submitRun(...)`:

1. Tier quota gate — `tierEnforcement.assertCanSubmitRun(userId)`
2. Persist `pipeline_run` row (status = `QUEUED`)
3. Schedule `runPipeline(...)` on the bounded thread pool (CallerRunsPolicy backpressure)
4. Return `runId`

### 6b. Pipeline — `V2RunOrchestrator.runPipeline(...)`

Runs synchronously on a worker thread (the API has already returned).

| Step | Code | Outcome |
|---|---|---|
| 0. BYOK key resolution | `keyContextStore.resolveAndPut(runId, userId)` | resolves AI keys (OpenAI/Anthropic/Gemini), stores per-run keyed by runId |
| 1. Workspace init | `workspaceInit.ensureRunWorkspace(runId)` → `<agent.workspace>/<runId>/` with .gitignore + initial commit | local working dir created |
| 2. **Multi-repo clone** | If `projectId + selectedRepoIds`, `workspaceInit.cloneMultipleRepos(runId, repos, pat)` clones each into `<workspace>/<runId>/repos/<name>/` | per-repo subdirs populated; `run_repo_snapshot` rows record SHA at clone-time |
| 3. Architecture lazy-refresh | `stalenessService.report(projectId)` → if stale, `projectArchitectureService.generate(projectId, ...)` | codemap/arch refreshed (no-op on first run) |
| 4. Project memory build | `projectMemoryProvider.buildPlannerContext(projectId)` | `{{PROJECT_MEMORY}}` block |
| 5. Project brief | `projectBriefService.buildBrief(projectId)` | `{{PROJECT_BRIEF}}` block (2-4 sentence arch summary) |
| 6. Reference docs | `retrievalService.retrieve(projectId, userRequest)` | `{{REFERENCE_DOCS}}` block |
| 7. **Multi-repo summary** | `buildMultiRepoSummary(projectId, selectedRepoIds)` | `MULTI-REPO PROJECT WORKSPACE / repos/<name>/...` block |
| 8. Classifier | `SmartClassifierAgent` (LIGHTWEIGHT) → SINGLE_SHOT \| SMALL \| MEDIUM \| LARGE \| LEGACY | persisted to `pipeline_run.plan_strategy` |
| 9. Planner — strategy-routed | OutlinePlannerAgent (FULL_PLAN) for SMALL/MEDIUM, OutlinePlannerAgent (SKELETON_ONLY) → TaskExpanderAgent → CoherencePlannerAgent for LARGE, single-task synthesis for SINGLE_SHOT | `submitPlan` tool call → `PlanV2` object |
| 10. **Plan gate** | If PRODUCTION mode, status flips to `AWAITING_APPROVAL` and the pipeline thread blocks on `gateStore.waitForPlanApproval(runId, 24h)`. PROTOTYPE bypasses. | UI shows plan + cost estimate |
| 11. **Approve plan** — `POST /api/runs/{id}/approve` | Charges N credits via `tierEnforcement.assertAndIncrementRunCount(userId, credits)`, signals the gate via `gateStore.approve(runId)` | pipeline thread wakes |
| 12. **Sequential executor** | `SequentialExecutorV2.execute(req)` — for each task: compose prompt → `WorkerAgentV2.run()` → `buildProject` smoke (skipped on intermediate tasks of multi-task plans, see v2.5.13 hardening) | per-task outcomes: SUCCESS / COMPLETED_WITH_WARNING / FAILED |
| 13. **Reviewer** | `ReviewerAgentV3.run()` two passes (general + wiring), 50 iter cap, 600s timeout. Project-type-aware wiring fixes (mod.rs / barrels / Spring scan / Express routes / Python decorators / Go imports) | verdict: CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED |
| 14. **Final smoke** | `buildProject` at workspace root | pass = COMPLETE; fail = PARTIAL with `status_reason` (FINAL_BUILD_FAILED) |
| 15. **Push** — `ScmPushService.pushMultiRepo` | For each `repos/<name>/` subdir with diff: create branch `executespec/{runId}-{short}`, commit subdir-relative diff, open PR via `GitHubRepoService.openPullRequest` | per-repo PR URLs persisted to `pull_request_record` |
| 16. **Validation report** | `ValidationReportService.generate(runId)` — verifiedScore from build + tests + reviewer verdict, accuracyScore from LLM (deterministic citation-required), score dimensions | `validation_report` row |
| 17. **Auto-preview** | `PreviewService.startPreview(runId)` (5min TTL Cloud Run / Docker) | preview URL on RunDetail |
| 18. **Project memory append** | `RunLedgerAgent.recordRun(...)` | `project_run_ledger` row |
| 19. **Staleness invalidate** | `stalenessService.invalidate(projectId)` | next run lazy-refreshes |
| 20. **Notification** | `RunCompletionNotifier.onComplete(runId)` writes `notification` row + fires PostHog `RUN_COMPLETE` | NotificationBell shows badge |

### 6c. The bug we just fixed

**Step 7** built the multi-repo summary correctly. **Step 9** received it. **Step 12 didn't.**

The wire path before today's fix:

```
buildMultiRepoSummary
  ↓
PlannerRequestV2.workspaceSummary  ✅ (planner sees it)
  ↓
OutlinePlannerAgent + TaskExpanderAgent + CoherencePlannerAgent  ✅
  ↓
PlanV2  →  ExecutionRequest  ❌ (no workspaceSummary field)
                                ↓
                            SequentialExecutorV2.composeSystemPrompt  ❌ (no parameter)
                                ↓
                            WorkerAgentV2 system prompt  ❌ (no multi-repo context)
                                ↓
                            writeFile("auth.ts", ...)  →  workspace root ❌
                                ↓
                            ScmPushService.pushMultiRepo  →  no diff in any repos/<name>/  ❌
                                ↓
                            run COMPLETE, zero PRs opened
```

After today's fix (commits coming in this branch):
- `ExecutionRequest` gained a `workspaceSummary` field
- `V2RunOrchestrator` passes `buildMultiRepoSummary(projectId, selectedRepoIds)` on the executor build (line 962)
- `SequentialExecutorV2.composeSystemPrompt` accepts the field and prepends the block when it contains `MULTI-REPO PROJECT WORKSPACE`
- `MultiRepoWorkerPromptTest` (4 cases) pins the contract — including a counter-example asserting the codemap-fallback summary doesn't false-trigger as multi-repo

---

## 7. "Have we regressed from V2?" — git archaeology

V2's `ExecutionRequest` was first introduced at commit `372583e0` (V2 Phase 4.x, 2026-04-12) — **never had a `workspaceSummary` field**.

The `V2RunOrchestrator.buildMultiRepoSummary` helper was added at commit `b01247a4` (V2 Phase 11.A, the V2 cutover behind V1's `/api/runs`). The doc-comment correctly predicts the failure mode:

> *"Builds a workspaceSummary that tells the planner (and by extension the worker) ... Without this the worker treats the workspace as an empty scratch dir and writes to the root, producing runs that finish COMPLETE but open no PRs."*

The wire was set up to the **planner** ("and by extension the worker") but the executor side was never plumbed. The author *understood* the requirement, but the implementation stopped at the planner.

**Net answer to "did we regress?":** No, multi-repo project runs in V2 have **never worked correctly since V2 was first stood up.** Every multi-repo run since `b01247a4` produced empty PRs. The "and by extension the worker" promise was prophetic — never fulfilled.

In V1 the path was different: V1's `PipelineHarness` had a different worker contract that did receive workspaceSummary explicitly. When V2 deleted V1 (2026-04-13), the multi-repo wire was implicitly dropped and never reconstructed.

So: **regression vs V1, latent the entire V2 lifetime.** The bug is real. The fix is small. The cost in lost runs over the V2 lifetime is real but unrecoverable.

---

## 8. Other gaps surfaced in this audit (not fixed today)

### 8a. Repo link should auto-trigger analyse
Currently a repo can sit linked for days with no codemap or architecture. First run plans blind. **Fix:** add an async `projectArchitectureService.generate(projectId, null, pat)` call to `BootstrapService.bootstrapAsync`'s success branch, or a separate `OnRepoLinkedListener` that fires it. ~10 lines.

### 8b. Project chat has no live update
`ProjectChatPanel` is local React state with no SSE / polling. New chat messages from the server (or run milestones if those ever get written) are invisible until page refresh. See the chat-update diagnosis answer earlier — root cause is the same: a wire never connected.

### 8c. Run milestones aren't written to project chat
The user expected to see "Plan generated", "Approved", "PR opened" as cards in the chat thread. **No backend code writes those messages today.** `RunCompletionNotifier` writes notifications + activity events, but not chat messages.

### 8d. Pause/resume in V2 returns 410 Gone
Documented in CLAUDE.md as a known gap. Blocked on full-replay recovery design (workspace restoration on a fresh JVM). Multi-day work; not a quick fix.

---

## 9. Did the prior token spend produce real value?

Honest answer: mixed.

- **Wasted:** every multi-repo project run since b01247a4 (early V2). The runs cost LLM tokens, produced no PR. Runs that thought they succeeded and reported high verified scores in fact shipped nothing into customer repos.
- **Real value:** single-repo runs (the legacy `/runs` path with `repoMode=EXISTING` and no `projectId`) — these used `RunCodeMapService.generateForRun` which writes at workspace root and pushes from workspace root. That path works. Most of the V2 runs in the test history are this shape.
- **Real value:** the audit + style-refactor + token-contrast work is independent of this bug. F-14 / F-15-17 / F-21 / D3 are all live, verified, in production.

The multi-repo bug is a discrete, fixable failure mode — not a "the whole system is fake" problem. But it is one that should never have shipped, and the fact that no integration test caught it means the test surface needs broadening (a runtime "after multi-repo run, assert PR opened with non-empty diff" e2e check).

---

## 10. Recommended next moves

| Priority | Action | Lift |
|---|---|---|
| 🔥 P0 | Ship today's multi-repo fix to dev + main | Done — commits coming in this branch |
| 🔥 P0 | Write integration test: 2-repo project, end-to-end, assert each `repos/<name>/` has a non-empty diff before push | ~half-day, mocks GitHub push |
| ⚠ P1 | Auto-trigger `analyse` after `linkToProject` (no manual click) | ~10 lines |
| ⚠ P1 | Wire SSE / polling into `ProjectChatPanel` | ~30 lines |
| ⚠ P1 | Backend hook: `RunCompletionNotifier` → write milestone cards to `project_chat_message` when run has projectId | ~25 lines |
| 📋 P2 | Pause/resume V2 design doc | new plan doc; multi-day implementation |
| 📋 P2 | Document the project-run lifecycle in `docs/USER_MANUAL.md` (currently missing) | ~1 page |

This audit doc + the fix commit are the start of paying down the visibility debt. The next item should be the integration test that would have caught this — a "post-run, assert diff exists per repo" check that runs nightly against a fixture project.
