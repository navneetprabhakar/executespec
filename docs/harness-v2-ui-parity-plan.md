# Harness V2 — UI Parity Migration Plan

> Generated: 2026-04-12
> Supersedes the Phase 11 section of `docs/harness-v2-execution-plan.md` with a more detailed breakdown.
> Goal: make V2 a **drop-in pipeline replacement** behind V1's existing endpoints and frontend — UX does not change, pipeline does.

---

## 1. Principles

1. **Frontend is untouched** in this migration. No new V2 routes, no V2-specific components. The React app keeps calling `/api/runs`, `/api/runs/{id}`, `/api/runs/{id}/files`, etc.
2. **V2 runs persist to the same `run` table as V1** — same entity, same column set, same lifecycle enum. A poll of `GET /api/runs/{id}` can't tell the difference other than a `harnessVersion` tag in the response.
3. **`HarnessVersionResolver` is the only place that decides V1 vs V2** — it sits inside the existing `/api/runs` POST handler. Everything else is routing glue.
4. **Deferred work is explicit, not silent.** Batch, approval gate, preview, deploy, chat, iteration, reports, pause/resume → all marked as out-of-scope for this session and left as `UnsupportedOperationException` or a flag that falls back to V1 when the action is invoked.
5. **No destructive changes to V1.** V1's PipelineHarness, endpoints, entities all keep working. V2 composes on top, it does not replace.

---

## 2. Must-haves for this session (user-confirmed)

1. **Full UX parity** across submit → list → detail → code view → download → GitHub push
2. **Cost tracking** — estimate, credits, live cost, actual cost populated through V2
3. **Code view + download** backed by V2 workspace
4. **GitHub push** integrated into V2 pipeline

## 3. Deferred to subsequent builds

- Plan gate approval (`POST /api/runs/{id}/approve`)
- Batch execution (dual-card gate, `executionMode=BATCH`, batch polling)
- Preview lifecycle (`/preview/*`)
- Deploy to Cloud Run
- Auto-test generation + validation report PDF
- Chat/iterate/apply post-run flows
- Pause/resume/retry-task/rework/clarify/reopen-merge
- OpenAPI/test-plan/test-graph endpoints

When a user triggers any deferred action on a V2 run, the API returns HTTP 501 Not Implemented with a clear "not available on V2 yet" body. This keeps the UI unbroken (buttons show, error toast fires if clicked) without pretending features work.

---

## 4. Architecture — How V2 becomes invisible

```
Existing frontend
     │
     ▼
POST /api/runs          ← unchanged endpoint
     │
     ▼
RunController (V1)      ← existing class
     │
     ├── harness.version = v1  →  V1 PipelineHarness (current behavior, untouched)
     │
     └── harness.version = v2  →  V2RunOrchestrator (new adapter)
                                    │
                                    ├── persist run row via RunRepository (V1 entity)
                                    ├── cost estimate via CostEstimator (V1 service)
                                    ├── run pipeline: PlannerAgentV2 → SequentialExecutorV2 → ReviewerAgentV3
                                    ├── on terminal state: update Run entity with status, actualCost, artifacts
                                    └── on GitHub push: GitHubRepoService (V1 service) against V2 workspace

GET /api/runs/{id}      ← unchanged; reads V1 Run entity populated by whichever pipeline ran it
```

**Key insight:** `V2RunOrchestrator` is the glue layer. `V2RunService` (Phase 9's in-memory orchestrator) is kept for `/api/v2/runs` internal-canary and for isolated testing — but the production path is V1 endpoint → resolver → V2RunOrchestrator → V1 Run entity.

---

## 5. V1 Run entity fields V2 must populate

Based on the audit of V1's `RunDetail` JSON, V2 must write these columns. Grouped by priority:

### 5.1 Must-populate for V2 to show anything useful
- `id` (UUID)
- `goal` / `spec` (user's submission)
- `status` (V1 enum — see §6 for V2→V1 mapping)
- `createdAt`, `updatedAt`, `completedAt`
- `createdByUserId`, `orgId`, `projectId` (scoping — stamped from authenticated user)
- `harnessVersion` (new — `v1` | `v2`; used by frontend to show a badge if needed)

### 5.2 Must-populate for cost UI
- `costEstimateLow`, `costEstimateHigh` (USD)
- `creditsCharged` (integer)
- `hardCapUsd`
- `actualCostUsd` (updated on completion)
- `keySource` (OWN_KEYS | SYSTEM | HYBRID — from `ApiKeyResolver`)

### 5.3 Must-populate for plan + tasks UI
- `plan` (JSON blob of the plan — includes tasks, buildCommand, smokeTestStrategy, per-task hardConstraints)
- `tasks` (list of task entities — V1 has `AgentTask` rows keyed by `runId`; V2 writes AgentTask rows per plan task)
  - `taskId`, `title`, `description`, `status`, `complexity`, `ownedFiles`, `completedAt`

### 5.4 Must-populate for code view + download
- `workspacePath` (server-side path to the run's workspace — used by `/files`, `/file?path=`, `/download`)

### 5.5 Must-populate for GitHub push
- After push: `githubPrUrl`, `scmPrUrl`, `scmType` (github | bitbucket)

### 5.6 Can leave null / default
- `batchCredits`, `batchAvailable`, `batchProgressCompleted`, `batchProgressTotal` — batch deferred
- `reviewReport`, `validationReport` — reviewer populates a `ReviewerResult` JSON stored on the run; V1's report UI may or may not render it (needs check)
- `previewUrl`, `previewStatus` — preview deferred

---

## 6. Lifecycle status mapping

V1's `RunStatus` enum includes (inferred from frontend): `QUEUED | GATE_PENDING | RUNNING | QUEUED_BATCH | IN_PROGRESS_BATCH | COMPLETE | PARTIAL | FAILED | CANCELLED | PAUSED | REWORK_PENDING`.

V2's internal `V2Run.Status`: `SUBMITTED | PLANNING | EXECUTING | REVIEWING | COMPLETE | FAILED`.

V2→V1 mapping:

| V2 internal | V1 external (written to `Run.status`) | Notes |
|---|---|---|
| `SUBMITTED` | `QUEUED` | Immediately after POST, before planner starts |
| `PLANNING` | `RUNNING` | V1 doesn't have an explicit planning state; `RUNNING` is closest |
| `EXECUTING` | `RUNNING` | Same |
| `REVIEWING` | `RUNNING` | Same |
| `COMPLETE` (execution ALL_SUCCESS) | `COMPLETE` | |
| `COMPLETE` (execution PARTIAL_WITH_WARNINGS) | `PARTIAL` | V1 has this bucket — use it |
| `COMPLETE` (execution FAILED) | `FAILED` | |
| `FAILED` (pipeline error) | `FAILED` | |

V2 does NOT emit `GATE_PENDING` (no approval gate yet). Frontend gracefully handles its absence — the plan UI just doesn't appear.

---

## 7. Work breakdown — 5 phases, ~8-12 hours total

Each phase has: scope, files touched, verification step, stop/go decision.

---

### Phase 11.A — V2 Run entity integration (2-3h)

**Goal:** V2 runs persist to V1's `run` table with correct status lifecycle + ownership.

**Scope:**
- New `V2RunOrchestrator` service in `com.navneet.executespec.v2.integration` package
- Constructor: `RunRepository`, `PlannerAgentV2`, `SequentialExecutorV2`, `ReviewerAgentV3`, `HarnessVersionResolver`, `V2Metrics`, `AgentTaskRepository`
- Method `submitRun(RunSubmissionRequest)` mirrors V1's `RunService.submitRun` signature so the existing controller can delegate to it
- Writes `Run` entity row on submission (status QUEUED, harnessVersion=v2)
- Background pipeline thread (Phase 9 pool reused): PLAN → update row to RUNNING → EXECUTE → REVIEW → update row to COMPLETE/PARTIAL/FAILED with `completedAt`
- Writes `AgentTask` rows per plan task (initial status PENDING, updated as executor runs)

**Files touched:**
- new: `v2/integration/V2RunOrchestrator.java`, `v2/integration/V2RunMapping.java` (status/enum mapping helper)
- modify: `RunController.java` (V1) — after request validation, consult `HarnessVersionResolver`; if V2, delegate to `V2RunOrchestrator` instead of `RunService`
- modify: `v2/rollout/V2RunService.java` — mark as internal-canary-only, add Javadoc pointing production callers to `V2RunOrchestrator`

**Verification:**
- `POST /api/runs` with `harness.version=v2` flag → 202 with runId
- `GET /api/runs/{id}` → returns V1-shaped JSON with V2 plan, tasks populated
- `GET /api/runs` (list) → V2 run shows up in the list correctly

---

### Phase 11.B — Cost tracking (2-3h)

**Goal:** Cost UI in RunDetail is populated end-to-end for V2 runs.

**Scope:**
- Wire `CostEstimator.estimateWithCredits(plan)` into `V2RunOrchestrator` post-plan
- Populate `Run.costEstimateLow`, `costEstimateHigh`, `creditsCharged`, `hardCapUsd` fields
- Resolve `keySource` via `ApiKeyResolver` (reuse V1)
- Track live cost during execution — V2 needs per-LLM-call cost attribution:
  - `LlmRouterV2.call()` returns token counts (already in `ChatResponse.metadata.usage`)
  - New `V2RunCostTracker` accumulates running cost per runId (in-memory map; flushes to `run_cost_log` table periodically — reuse V1's `RunCostLog` entity)
  - Exposes `getLiveCost(runId)` so `GET /api/runs/{id}/cost` can return current spend
- On completion: `Run.actualCostUsd = tracker.getLiveCost(runId)`, persist to `RunCostLog`

**Files touched:**
- new: `v2/cost/V2RunCostTracker.java` — accumulates per-run token spend, converts via pricing table
- modify: `LlmRouterV2.call()` — after each successful response, emit cost to tracker (request.context().runId() tag)
- modify: `V2RunOrchestrator` — estimate pre-plan, populate fields, flush tracker to `RunCostLog` at terminal state
- touch: `RunController` (V1) — ensure `GET /api/runs/{id}/cost` checks `harnessVersion` and routes to tracker for V2 runs

**Verification:**
- Submit a V2 run → `GET /api/runs/{id}` shows non-null `costEstimateLow/High`, `creditsCharged`, `hardCapUsd`
- While running → `GET /api/runs/{id}/cost` returns growing `costUsd`
- After completion → `actualCostUsd` is set, `run_cost_log` has a row

---

### Phase 11.C — Code view + download (1-2h)

**Goal:** V1's existing `/files`, `/file?path=`, `/download` endpoints work against V2 workspaces.

**Scope:**
- Check whether V1's endpoints resolve the run's workspace from `Run.workspacePath` column
- If yes: just ensure V2 populates `workspacePath` correctly (likely already via orchestrator) — endpoints work as-is
- If no (e.g. V1 hardcodes a workspace base dir + runId): add a thin adapter — check `harnessVersion`, if v2, resolve from V2's temp workspace convention

**Files touched:**
- investigate: V1's file-browser controller (likely `RunFileController` or similar)
- modify: workspace resolver (if needed) — a single `resolveWorkspace(Run)` method that returns the correct dir regardless of harness
- new: `v2/integration/V2WorkspaceResolver.java` (only if V1 can't be abstracted cleanly)

**Verification:**
- Submit V2 run → open Run detail in UI → click Code tab → file tree renders
- Click a file → content displays in Monaco
- Click Download → browser downloads `workspace-{id}.zip`

---

### Phase 11.D — GitHub push (2-3h)

**Goal:** "Publish to GitHub" button on a completed V2 run works end-to-end.

**Scope:**
- V1's `POST /api/runs/{id}/push-github` dispatches to `GitHubRepoService.pushRun(runId, repoMode, pat)` or similar
- V1 likely reads the run's workspace path + git history to build the PR
- V2 needs to ensure the workspace has committed git history (we do — `GitCommitTool` runs per task)
- Hook: if `harnessVersion=v2`, still route to `GitHubRepoService` — same service, same workspace, should just work
- Any V2-specific adaptation (e.g. PR body from reviewer verdict) can be a follow-up

**Files touched:**
- investigate: `GitHubRepoService.java` behavior on a workspace with existing commits
- modify: push controller or `GitHubRepoService` to accept a Run regardless of harness
- update: `Run.githubPrUrl` / `scmPrUrl` set by V1's service (unchanged) → frontend sees it

**Verification:**
- Complete V2 run → click "Publish to GitHub" in UI → PAT modal if needed → push happens → PR URL appears in Overview tab → click link → PR renders on github.com

---

### Phase 11.E — Deferred-action 501 shim + end-to-end UI smoke (1h)

**Goal:** Buttons that don't work on V2 fail cleanly; overall UX walkthrough confirms parity.

**Scope:**
- Controllers for `/approve`, `/retry`, `/tasks/{t}/retry`, `/pause`, `/resume`, `/preview/*`, `/deploy`, `/chat`, `/iterate`, `/apply`, `/run-tests`, `/report/*`, `/rework`, `/reopen-merge`, `/clarify` etc. check `Run.harnessVersion` and return HTTP 501 with JSON `{error: "not supported on V2 yet", feature: "approval-gate", harnessVersion: "v2"}` for V2 runs. V1 runs unchanged.
- Walk through UI manually: submit → view → code → download → push → confirm each deferred button fails cleanly (toast, not crash)

**Files touched:**
- helper: `V2DeferredActionGuard.java` — one-line call `guard.ensureNotV2(runId, "approval-gate")` at top of each V1 controller method
- modify: each V1 controller for deferred endpoints (~12 endpoints) to call the guard

**Verification:**
- Submit V2 run, wait for completion
- From the UI, verify:
  - Plan displays (read-only, no approve button needed since no GATE_PENDING)
  - Run status flows: RUNNING → COMPLETE/PARTIAL/FAILED
  - Code tab works
  - Download works
  - GitHub push works
  - Retry / pause / preview / deploy buttons → error toast, no crash
  - Archive works (V1 handles the DB row regardless)

---

## 8. Testing strategy

- **Unit**: V2RunOrchestrator status mapping, cost tracker math, deferred-action guard.
- **Integration**: `/api/v2/test/parity` endpoint (new) — submits a V2 run via V1's endpoint, polls to completion, asserts all required fields populated, all deferred endpoints return 501.
- **Manual UI smoke**: run through the RunDetail UI on a completed V2 run. Document any missing pieces in the run log.

---

## 9. Risk register

| Risk | Mitigation |
|---|---|
| V1 `Run` entity has required columns V2 can't fill | Nullable columns preferred; for required ones, use safe defaults (e.g. `executionMode="REALTIME"`) |
| V1's `RunRepository.save` has lifecycle listeners that assume V1 pipeline | Inspect listeners early (Phase 11.A hour 1); add harnessVersion guards if needed |
| Cost attribution per-LLM-call misses overhead (retries, batch) | Phase 11.B ships real-time only; batch tracking deferred. V2 already has no retries that don't go through router, so overhead capture is complete for realtime. |
| `GitHubRepoService` assumes V1's git layout (per-task branches) | V2 commits straight to main-of-workspace; push-GitHub creates new branch from that workspace — should be orthogonal. Verify in Phase 11.D. |
| Deferred-action buttons confuse users | 501 response includes `feature` field; frontend toast reads it and says "Not yet available on V2 runs — pending in next release" |
| Flag flip during an in-flight run splits it across harnesses | `HarnessVersionResolver.resolveForSeed(runId)` already exists — use it when routing actions, so all actions on a run go to the same harness it started on |

---

## 10. Documentation updates (end of session)

- Update `CLAUDE.md` with the UX-parity model (V2 invisible behind V1 endpoints, gaps documented)
- Update `docs/harness-v2-execution-plan.md` Section 8 to mark Phase 11 sub-items done/deferred per this plan
- Update `docs/harness-v2-ui-parity-plan.md` (this file) — final section "What actually shipped" with commit SHAs + what's verified
- Add `docs/harness-v2-deferred.md` — single source of truth for what's not yet on V2, pending in which future build

---

## 11. Eval plan (after UI parity ships)

Once the 5 phases above are merged:

1. **Local eval** — run 5-10 specs from the existing eval corpus through both V1 and V2 via the same UI. Capture: accuracy (via manual artifact check), latency (wall clock), cost (`actualCostUsd`).
2. **Comparison table** — CSV with columns: spec, v1_accuracy, v2_accuracy, v1_latency_s, v2_latency_s, v1_cost_usd, v2_cost_usd, notes.
3. **Decision** — if V2 matches or beats V1 on accuracy with comparable cost, flip the flag to `v2-canary` at 25% for broader testing.

---

## 12. Success criteria for this session

- [ ] User can submit a run via the existing UI. If flag = v2, it runs through V2.
- [ ] Run appears in the list with correct status.
- [ ] Run detail page shows: plan, tasks, cost estimate, credits, live cost, actual cost.
- [ ] Code tab browses the V2 workspace.
- [ ] Download button produces a zip.
- [ ] GitHub push succeeds end-to-end.
- [ ] Deferred buttons (approve/retry/preview/deploy/chat) fail cleanly with 501, no crashes.
- [ ] Docs updated.
- [ ] Local eval scoped (not necessarily run).

---

## 13. Execution order

Go strict in order — each phase unblocks the next:

```
Phase 11.A  V2 Run entity integration  →  2-3h
Phase 11.B  Cost tracking              →  2-3h
Phase 11.C  Code view + download       →  1-2h
Phase 11.D  GitHub push                →  2-3h
Phase 11.E  Deferred 501 shim + smoke  →  1h
Docs + eval plan                        →  1h
────────────────────────────────────────────
Total                                     9-13h
```

If we hit the 10-hour mark without 11.D done, stop there — 11.A-C alone get us to a canary-ready state with code view + cost, which is ~80% of the value.
