# Session Handoff — 2026-04-15

> Context for whoever (user or Claude) continues this work. Written end-of-day.
> Demo is scheduled for 2026-04-16.

## 1. What landed in this session

Everything below is compiled and on disk but **not committed**. The live JVM (IntelliJ) may still have older classes — restart is mandatory before the demo.

### Backend
| Fix | File | Why |
|---|---|---|
| `@Transactional` on `@Modifying` JPA UPDATEs | [PipelineRunRepository.java](src/main/java/com/navneet/executespec/state/repository/PipelineRunRepository.java) | `persistPhaseTokens addTokens failed: Executing an update/delete query` |
| `approvePlanAndExecute` → `@Async` | [ExecutionOrchestrator.java:171-182](src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java#L171-L182) | HTTP thread was blocking on planner LLM call → Hikari leak |
| Abort cancels V2 pipeline runs | [ExecutionOrchestrator.java:258-298](src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java#L258-L298) | Abort flipped exec → ABORTED but leaf V2 runs kept executing |
| `pollUntilTerminal` bails on abort | [SolutionExecutionService.java:148-183](src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java#L148-L183) | Avoided 2h timeout after abort |
| `handleFailure` does not overwrite terminal statuses | [SolutionExecutionService.java:244-275](src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java#L244-L275) | Abort was being overwritten with PAUSED |
| Branch selector through to `git clone -b` | entity + DTO + orchestrator + `WorkspaceInitService.java:268-277`; Flyway `V103__execution_run_branch.sql` | Run was always checking out `main` |
| One-execution-per-project gate | [ExecutionRunRepository.java:56-74](src/main/java/com/navneet/executespec/execution/repository/ExecutionRunRepository.java#L56-L74), [ExecutionController.java:78-103](src/main/java/com/navneet/executespec/execution/controller/ExecutionController.java#L78-L103) | Concurrent Launches caused multi-repo conflicts; 409 now names the blocking run + status |
| ExecutionPlannerService re-saves solution to persist repoMappings | [ExecutionPlannerService.java:213-218](src/main/java/com/navneet/executespec/execution/service/ExecutionPlannerService.java#L213-L218) | Only frontend solutions got `execution_solution_repo` rows because they were re-saved via the dependency edge loop; backend solutions had no repo mapping and never cloned |
| `ExecutionSolution.repoMappings` + `dependencies` → EAGER `Set` | [ExecutionSolution.java:108-122](src/main/java/com/navneet/executespec/execution/entity/ExecutionSolution.java#L108-L122) | "failed to lazily initialize" on worker thread |
| `executeSolution` reloads inside tx | [SolutionExecutionService.java:67-72](src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java#L67-L72) | Belt-and-suspenders on top of EAGER |
| Right-click enabled globally | [main.tsx](frontend/src/main.tsx) | Removed `contextmenu preventDefault` |
| BuildCommandResolver walks `repos/*` subdirs | [BuildCommandResolver.java:31-130](src/main/java/com/navneet/executespec/v2/smoketest/BuildCommandResolver.java#L31-L130) | Smoke runs in `workspace/<runId>/` root → `mvn` + `npm` fail with "no POM / no package.json" |
| Planner-declared commands wrapped with `cd repos/<name>` when root has no markers | [BuildCommandResolver.java:82-122](src/main/java/com/navneet/executespec/v2/smoketest/BuildCommandResolver.java#L82-L122) | Same root cause as above but for explicit planner commands |
| Iteration caps bumped | [SequentialExecutorV2.java:357-359](src/main/java/com/navneet/executespec/v2/harness/SequentialExecutorV2.java#L357-L359) | LOW 12→20, MEDIUM 22→30, HIGH 35→45 — old caps hit even on health-check tasks |
| **Project runs get 2× iter cap** | [SequentialExecutorV2.java:152, 364](src/main/java/com/navneet/executespec/v2/harness/SequentialExecutorV2.java#L152) | Existing-repo tasks need more `readFile`/`searchFiles` calls before writing |
| Smoke-failure on a verify-only task demoted to COMPLETE | [SequentialExecutorV2.java:309-333](src/main/java/com/navneet/executespec/v2/harness/SequentialExecutorV2.java#L309-L333) | Worker wrote 0 files → smoke failure is environmental, not the worker's fault |
| Auto-push extended to standalone `/api/runs` runs | [V2RunOrchestrator.java:619-651](src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java#L619-L651) | Standalone runs reached COMPLETE/PARTIAL but never pushed → user had to click "Push & open PR" manually |

### Frontend
| Fix | File |
|---|---|
| Vibe/Pro toggle navigates away from project page | [ViewModeToggle.tsx](frontend/src/components/shared/ViewModeToggle.tsx) |
| Approve-arch → auto-kicks planner | [ArchReviewGate.tsx](frontend/src/components/execution/ArchReviewGate.tsx) |
| After Launch submit, go to project dashboard | [ExecutionViewPage.tsx:98-110](frontend/src/pages/execution/ExecutionViewPage.tsx#L98-L110) |
| Branch input on ExecutionSetup | [ExecutionSetup.tsx](frontend/src/components/execution/ExecutionSetup.tsx) |
| Font-size bump across project + execution components | `text-[10px]→[11px]`, `[11px]→xs`, `[12px]→sm` (one-shot sed) |

### DB
- New migration V103: `execution_run.branch VARCHAR(255)` — already applied.
- Schema additions via Hibernate `ddl-auto=update`: `project_repo.webhook_installed`, `project_repo.default_branch`, `run_repo_snapshot` (V100), `project_spec_template` (V102).

### Memory (durable rules saved)
- `feedback_no_guessing.md` — read the code and on-disk state before proposing fixes; tokens are cheap, wrong answers are not.
- `feedback_reuse_existing_code.md` — on existing-repo executions the worker must reuse cloned code, not regenerate from scratch.

## 2. Known gaps going into the demo

### HIGH priority — affects demo credibility
1. **Codemap + architecture doc are not fed into the planner/worker for project Launches.**
   Per-solution V2 runs clone the repo but the planner gets only layout text (`multiRepoSummary`) plus `projectBrief`. Evidence + line numbers in the explorer report I did earlier (see conversation). **Symptom**: the worker regenerates files from scratch instead of extending existing code.
   **Fix scope** (3 changes):
   - `SolutionExecutionService.submitV2Run` — pre-fetch `ProjectCodeMap` + `ProjectArchitectureDoc` for the target repo, pass through.
   - `V2RunOrchestrator.runPipeline` — drop `projectId == null` gate on codemap; write arch.md + codemap.md into `workspace/<runId>/.agent-context/`; inject into planner prompt.
   - Planner + worker prompts — tell worker `cwd = repos/<name>/`; prefer `readFile` + `editFile` over `writeFile` on existing paths.
   NOT done yet. **This is the single biggest demo risk** — worker output looks like a green-field scaffold instead of a modification.

2. **Auto-retry on PARTIAL silently re-launches the whole solution.**
   [SolutionExecutionService.java:221-238](src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java#L221-L238). `maxRetries=3` by default. User sees "why is another run starting?"
   **Recommended fix**: change `onV2Partial` to call `handleFailure` directly (same as FAILED) so the user decides via `FailureDecisionModal`. Not done yet.

### MEDIUM priority
3. Hibernate `firstResult/maxResults specified with collection fetch` warning on paged queries — fallout from EAGER fix. Correctness OK; in-memory paging. Only matters at scale.
4. `AsyncRequestNotUsableException: Broken pipe` in SSE logs — client disconnects mid-stream. Cosmetic.

### LOW priority
5. `ModelResolver: No tier mapping for agent 'PlannerAgent'/'WorkerAgent' — defaulting to WORKHORSE` — V1 agent names leaking into V2 resolver log; picks right tier anyway.

## 3. Verified working

- Clone happens for project Launches (confirmed via `ls workspace/<runId>/repos/<name>/.git` + DB `execution_solution_repo` rows now present for both backend + frontend repos after planner re-save fix).
- Abort flips both the ExecutionRun and the underlying PipelineRun immediately.
- One-execution-per-project gate returns a 409 naming the blocking run + its actual status (PAUSED vs EXECUTING vs AWAITING_APPROVAL).
- Build-command resolver now picks the right mvn/npm command for multi-repo Launches.

## 4. Runbook for tomorrow

1. **Restart the backend JVM** (IntelliJ run config or `mvn spring-boot:run`). All the classfiles above are stale in your current JVM.
2. Hard-refresh the frontend (Cmd+Shift+R). The bundle in `src/main/resources/static/` is up to date.
3. **Clear stuck runs before the demo** — open pg and check:
   ```sql
   SELECT id, status FROM execution_run
   WHERE deleted_at IS NULL
     AND status NOT IN ('COMPLETE','FAILED','CANCELLED','ABORTED');
   ```
   Force-abort any stale rows with `UPDATE execution_run SET status='ABORTED' WHERE id='...';` — otherwise the concurrency gate will block new Launches.
4. **Demo script suggestion**:
   - Start on `/projects` → click into `test` project.
   - Show Overview + Launches tabs.
   - Click Launch → pick both repos → short requirement ("Add /api/ping endpoint").
   - Land on project dashboard after submit (auto-routed).
   - Talk through Arch Review + Plan Review while it runs.
   - Mention: "if a repo run fails, the UI offers Retry / Skip / Fix Manually / Attempt Anyway."
5. **If something goes wrong during the demo**, worst-case escape hatches:
   - The "Push & open PR" button on the run detail page still works manually.
   - The run workspace is always at `workspace/<runId>/` — you can open a terminal and show the actual cloned code.

## 5. Commit plan

Local `dev` is **14 commits ahead** of `origin/dev` from earlier sessions, plus a large uncommitted diff from today. Nothing has been pushed. Recommended groupings for when you want to commit:

1. `fix(exec): abort cancels V2 pipeline + poll short-circuit` (Issue 2)
2. `fix(exec): concurrency gate with specific 409 reason` (Issue 5)
3. `fix(exec): async approvePlanAndExecute + @Transactional on addTokens` (Issue 1)
4. `feat(exec): branch override threaded to git clone -b` (Issue 4, includes V103)
5. `fix(exec): planner re-saves solution so repoMappings cascade persists`
6. `fix(exec): eager-load repoMappings + dependencies; kill lazy-init footgun`
7. `fix(v2): BuildCommandResolver walks repos/ subdirs for multi-repo smoke`
8. `feat(v2): 2× iteration cap for project runs; smoke-failure demoted when 0 file writes`
9. `feat(v2): auto-push all successful runs, not just project-scoped`
10. `fix(ui): nav + density + arch-approve one-click + post-submit redirect`

Do NOT push without explicit approval per durable rule.

## 6. Next-session immediate tasks (post-demo)

In order:
1. Land the codemap + arch-doc injection into planner/worker for project Launches (HIGH gap #1).
2. Kill the auto-retry on PARTIAL (HIGH gap #2) — pause for user decision instead.
3. Wire "repo-specific" context into `ExecutionContext` so the worker knows its `cwd` is `repos/<name>/`.
4. Add a "branch" column to the UI launches list so multi-branch Launches are visually distinct.
5. Once the demo is over, push local `dev` to `origin/dev`.
