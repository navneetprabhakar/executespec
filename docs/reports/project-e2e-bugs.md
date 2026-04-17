# Project end-to-end — bugs surfaced in the live test

> Date: 2026-04-17. Scope: created a fresh project (`3b9bcf06`) with two
> linked repos (`hostel-be-v3`, `hostel-fe-v3`), submitted a Launch, drove
> it through analyse → arch → plan → execute, waited for completion, then
> verified PRs + scores + the UI dashboard.
>
> End result: **both PRs raised successfully** (be#6 + fe#10 OPEN on GitHub),
> verifiedScore=85 on each child run, build passed, reviewer FIXED_ISSUES.
> But **the user couldn't tell the run was done** because of the bugs below.

## Headline

| # | Bug | Severity | Fixed in this pass |
|---|---|---|:---:|
| 1 | `executeApprovedPlan` LazyInitializationException → ExecutionRun stuck at EXECUTING forever | **Critical** | ✅ `3a17e9aa` |
| 2 | `ExecutionSolution.prUrl` / `prNumber` never sync with the PR that was actually raised | High | — |
| 3 | `/plan/approve` silently accepts 202 when the plan hasn't been generated yet | High | — |
| 4 | `ExecutionSolutionDto.maxRetries` returns `null` (should be `3` from entity default) | Medium | — |
| 5 | `ModelResolver.selectAdaptiveProvider` silently switched OpenAI → Gemini for the planner despite `aiProvider:"OPENAI"` on the exec | Medium | — (CLAUDE.md tech debt #1c, known) |
| 6 | First project dashboard page-load can look "blank" at narrow viewports | Low | — |

## 1. The stuck-EXECUTING bug (fixed in `3a17e9aa`)

### Symptom
Launch `9d5fb7d4` on the fresh project:
- Both solutions: `status=DONE`
- Both child PipelineRuns: `status=COMPLETE`, `verifiedScore=85`, `finalBuildPassed=true`, reviewer=`FIXED_ISSUES`
- PRs #6 (be) and #10 (fe): `OPEN` on GitHub
- **ExecutionRun `status=EXECUTING` permanently.** Launches tab shows
  *"BUILDING · 2/2 done · 445.9K tok"* forever.

### Root cause (from live logs)
```
ERROR o.s.a.i.SimpleAsyncUncaughtExceptionHandler
  Unexpected exception occurred invoking async method:
  ExecutionOrchestrator.approvePlanAndExecute(java.lang.String)
org.hibernate.LazyInitializationException: failed to lazily initialize a
  collection of role: ExecutionSolution.repoMappings: could not initialize
  proxy - no Session
  at ExecutionOrchestrator.executeApprovedPlan(ExecutionOrchestrator.java:254)
```

`executeApprovedPlan` is `@Async` so it doesn't inherit the request's
Hibernate session. The query at line 212-213 is
`findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc` — its
`@EntityGraph(attributePaths = {"dependencies"})` only fetches
`dependencies`, not `repoMappings`. When line 254 iterates
`s.getRepoMappings()`, Hibernate has no session to lazy-load, throws, the
exception propagates up, and `setStatus("COMPLETE")` at line 269 never
fires.

### Fix
Added a sibling query that eager-fetches `repoMappings`:

```java
@EntityGraph(attributePaths = {"repoMappings"})
List<ExecutionSolution> findWithRepoMappingsByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);
```

`executeApprovedPlan` calls the new method before walking the mappings.
Two queries instead of one avoids `MultipleBagFetchException` on the mixed
Set+List collections (`dependencies` Set + `repoMappings` List).

### Verified
Fresh launch `c55de6fc` after the fix:
- Analyse → arch → plan → plan-approve → execute in ~3 min.
- Status transitioned `SETUP → ANALYSING → ARCH_REVIEW → PLANNING → EXECUTING → **COMPLETE**`.
- Dashboard: `completed: 4 · avgScore: 80.0`.

## 2. Solution.prUrl / prNumber never sync (not yet fixed)

### Symptom
The project-level PR rollup shows the PRs correctly:
```
#6  repo=hostel-be-v3 status=OPEN
#10 repo=hostel-fe-v3 status=OPEN
```
But the `ExecutionSolution` DTO has:
```
sol=86bb325f-d36 status=DONE prUrl=None prNum=None
sol=bad4c831-664 status=DONE prUrl=None prNum=None
```

### Root cause (hypothesis)
- `SolutionExecutionService` dispatches a child V2 PipelineRun per solution.
- That V2 run's `ScmPushService` pushes to a branch and opens a PR — the
  PR is persisted as a `PullRequestRecord` (PR table) but **not** written
  back to the parent `ExecutionSolution.prUrl` / `prNumber` columns.
- UI components that read `solution.prUrl` directly (e.g. the Launches
  detail view per-solution row) render "—" even though the PR is clearly
  on GitHub.

### Suggested fix
In `SolutionExecutionService.onV2Complete` (or the post-V2 hook), once the
child V2 run writes its `githubPrUrl`, copy the URL + parsed PR number
back to the owning `ExecutionSolution` and save. A `@EventListener` on
`PullRequestRecord`-created could also close the gap.

## 3. `/plan/approve` race (not yet fixed)

### Symptom
Calling `PUT /api/executions/{id}/plan/approve` ~20 s after `POST /plan`
returns `HTTP 202` but `planApprovedAt` remains `null`. A second approve
call 2+ min later (after the plan is populated) succeeds and advances the
run to EXECUTING.

### Root cause (from source)
`ExecutionOrchestrator.approvePlanAndExecute` is `@Async` and simply does
`findById → setPlanApprovedAt(now) → save → executeApprovedPlan`. No check
that the plan (`executionPlan` blob) has been populated. If the planner
service hasn't finished writing the plan + solution rows yet,
`executeApprovedPlan` finds nothing to iterate, returns silently, and a
concurrent save from the planner thread clobbers the `planApprovedAt`
timestamp (last-write-wins).

### Suggested fix
Either:
- Make the `/plan` endpoint synchronous so it returns only after the plan
  is persisted (simpler, nice UX), OR
- Have `/plan/approve` reject with 409 when `executionPlan == null`,
  forcing the client to poll, OR
- Add `@Version` to `ExecutionRun` so the concurrent save throws
  `OptimisticLockingFailureException` instead of silently clobbering.

## 4. `ExecutionSolutionDto.maxRetries` null

### Symptom
```python
solution keys: ['actualTokens', 'completedAt', 'complexity', 'costUsd',
  'dependencyIds', 'description', 'estimatedTokens', 'failurePhase',
  'failureReason', 'id', 'jiraEnabled', 'jiraTicketId', 'pipelineRunId',
  'prNumber', 'prStatus', 'prUrl', 'repos', 'retryCount',
  'sequenceNumber', 'startedAt', 'status', 'title']
# maxRetries MISSING
```
Even though the JPA entity declares
`@Builder.Default private int maxRetries = 3`, the DTO doesn't include
it. `retryCount` is present but without the ceiling the UI can't show
*"attempt 1 of 3"*.

### Suggested fix
Add `maxRetries` to `ExecutionSolutionDto` (record field +
`from(entity)` mapper) so `retryCount/maxRetries` renders as `1/3`
instead of `1/-`.

## 5. Model matrix silently switched OpenAI → Gemini (known, CLAUDE.md #1c)

### From live logs during this session
```
08:35:14 INFO ModelResolver Adaptive routing: OPENAI → GOOGLE for agent
  PlannerAgentV2 (better success rate)
08:35:14 INFO ExecutionPlannerService run=9d5fb7d4 provider=google
  model=gemini-3-flash-preview
```

The user requested `"aiProvider":"OPENAI"` on the execution. The planner
still ran on Gemini. This is Bug #1c from the CLAUDE.md "Known Tech Debt"
list, documented but not yet fixed.

### Suggested fix (per CLAUDE.md)
Guard `ModelResolver.selectAdaptiveProvider` with the strict-provider
system property OR a per-org opt-out. Today's run shows the symptom is
still reaching production — the planner for this "OPENAI" project
genuinely ran on Gemini, which is not what the user asked for.

## 6. Blank dashboard at narrow viewports (minor, environmental)

While investigating, I hit `/projects/{id}` (without `/dashboard`) at a
464 px viewport and saw only the header — no content, no error. Two
compounding issues:
- React Router has no route for `/projects/:id` (only
  `/projects/:id/dashboard` etc.) so the index route fell through.
- At 464 px the fall-through page's content was clipped off-screen.

Not user-reported in the wild; noting for completeness. A `/projects/:id`
fallback redirect to `.../dashboard` would close this.

## What the user actually sees end-to-end (post-fix `3a17e9aa`)

- **Projects page:** *Projects* list shows every project; card shows name,
  ACTIVE badge, provider tag, description. Open works.
- **Project dashboard:** KPI strip (`ACTIVE 0 · COMPLETED 4 · FAILED 0 ·
  AVG SCORE 80 · TOKENS 1.3M · COST $1.05`). Tabs work. Workspace Health
  + Tech Debt panels populated.
- **Launches tab:** `E2E Test 2026-04-17` rows — two complete runs, each
  with tokens + score visible, **no longer stuck on BUILDING**.
- **PRs tab:** all five PRs (#6, #7, #10, #11, #12) on the two linked
  repos, all OPEN. (The per-solution row still lacks prUrl — next fix.)
- **Chat tab:** answers correctly against the RAG-indexed repos.

## Cost

Total LLM spend for the full E2E: **$1.05** on the project (up from $0.91
earlier), cumulative today **~$1.50**. Cap was $25; 6 % used.
