# Session handoff — 2026-04-17

> Hand-off doc for picking up this work in a fresh thread. Contains
> everything a new Claude (or human) needs to know to continue without
> re-deriving context: branch state, what shipped, what's open, decisions
> needed, and how to resume.

## TL;DR

- Branch: **`feature/validation-v3-pillar1-requirement-graph`** (active).
- Last commit: **`3f546efa`** — *"fix(ux): EmptyState sweep across 5 highest-traffic Project surfaces"*. Pushed.
- Backend: **`mvn compile -q` green**. Frontend: **`npm --prefix frontend run build` green**.
- Servers: **stopped**. Ports 8080 + 5173 confirmed clear.
- LLM spend cumulative this multi-day session: **~$1.50** of a $25 cap (6%).
- 14 commits this session across 3 chapters: validation-v3 P1 follow-ups, project E2E findings + fixes, deep-dive audit + remediation. All shipped to GitHub.

## Where we are

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git status                # clean except built static/index.html (auto-generated)
git log --oneline -15     # last 15 commits below
git branch --show-current # feature/validation-v3-pillar1-requirement-graph
```

```
3f546efa fix(ux): EmptyState sweep across 5 highest-traffic Project surfaces
4656c708 fix(ux): replace inline status pills with <StatusBadge> on Project Dashboard
ed07ada1 fix(router): planner + reviewer resolve via ModelResolver tier matrix
ed706bb7 fix(notify): emit PR_MERGED notification on GitHub webhook
2c2d34d1 fix(frontend): QuotaUsageBar upgrade link + NewRunModal hint placement
ff90d08c fix(frontend): split useIsMobile semantics — add useIsCompactLayout + useIsPhone
87d84419 fix(frontend): drop V1-only run actions + add 410 STATUS_MESSAGES entry
83bb4a54 fix(billing): refund quota on PARTIAL with CLEAN_WITH_INTERMEDIATE_WARNINGS
f2a77a5a fix(exec): reject plan-approve before the plan is populated (bug #3)
efe8d4b2 fix(webhook): GitHub webhook distinguishes parse error vs unexpected
80e2bcdd fix(security): remove /api/v2/runs/** permit-all canary bypass
5baf93e0 fix(threading): @PreDestroy on 3 unmanaged executor pools
26c9fa4d docs(reports): deep-dive-audit — Tech / UX / Product / Usability
c2e4445e fix: phase-A cleanup from project-e2e-bugs.md (#2, #4, #5, #6)
5caa03b4 docs(reports): project-e2e-bugs — 6 findings from live project test
3a17e9aa fix(exec): stuck-EXECUTING bug — LazyInitializationException
8e9524e3 fix(report): align ValidationReportPanel with design system + surface statusReason
```

## What shipped

### Validation v3 / Pillar-1 follow-ups (`f8201b5c`–`8e9524e3`)
- **Dashboard now reads `verifiedScore`** (was `accuracyScore` LLM hallucinations). Hostel project went 63→69 immediately; future clean runs score correctly.
- **6h JWT TTL** (was 1h, broke long sessions).
- **V110** flips `scoring.v2-verified` ON. **V111** adds `last_failure_signature` + `consecutive_failure_count` on `execution_solution` for retry dedup.
- **PARTIAL retry dedup** — `SolutionExecutionService.onV2Partial` short-circuits at 2 attempts when V107 `statusReason` repeats. Saves ~50 % on deterministic failures.
- **Worker-FAILED tasks now carry `failureReason`** via `lastLlmFailure`.
- **`ValidationReportPanel` redesigned** to use design tokens + surface v2.5.10 `statusReason` narrative inline; SignalBreakdown + ScoreCard tokens aligned.

### Live project E2E test (`3a17e9aa`–`c2e4445e`)
- Created project `3b9bcf06`, linked `hostel-be-v3` + `hostel-fe-v3`, ran a Launch end-to-end. **Both PRs raised** (#6 + #10 OPEN), verified=85 each.
- **Stuck-EXECUTING bug fixed** — `LazyInitializationException` on `solution.repoMappings` killed the terminal `setStatus("COMPLETE")` step. Added `findWithRepoMappingsBy...` query method.
- **`solution.prUrl`/`prNumber` sync** from child V2 run (was null even with PRs OPEN).
- **`maxRetries` + V111 dedup fields** added to `ExecutionSolutionDto`.
- **`ModelResolver` strict-provider** respect (CLAUDE.md Bug #1c closed).
- **`/projects/:id` redirect** to `/dashboard` (no longer blank under narrow viewports).

### Deep-dive audit + remediation (`5baf93e0`–`3f546efa`)
- 4 parallel source-level audits (Tech-backend, Tech-frontend, UX, Product) → `docs/reports/deep-dive-audit.md` (597 lines, citations).
- **Backend hardening:** `@PreDestroy` on 3 unmanaged executor pools; removed `/api/v2/runs/**` permit-all; webhook 400/500 split; plan-approve 409 (bug #3); refund on `CLEAN_WITH_INTERMEDIATE_WARNINGS`; PR_MERGED notification; planner + reviewer use `ModelResolver.resolve(tier, provider)` (closes CLAUDE.md known debt).
- **Frontend cleanup:** dropped 7 V1-only endpoints + 3 dead UI surfaces (ClarificationBanner, "Edit Goal & Restart", "Run Again"); added 410 STATUS_MESSAGES entry; split `useIsMobile` into `useIsCompactLayout` + `useIsPhone`; QuotaUsageBar upgrade link + token-aligned colours; NewRunModal hint above textarea.
- **Design-system sweep:** `<StatusBadge>` on Project Dashboard (replaced inline pills + `STATUS_COLOR` map); `<EmptyState>` extended with `compact` + `icon` props; rolled out across 5 high-traffic Project surfaces (`ProjectTaskList`, `ProjectLaunchesList`, `ProjectPrList`, `ProjectRepoManager`, `ProjectDashboardPage` recent-tasks).

## Reports written this session (`docs/reports/`)

| File | Lines | What |
|---|---:|---|
| [`ux-review.md`](ux-review.md) | 180 | Run vs Project visual dialect mismatch with file:line citations |
| [`projects-usability.md`](projects-usability.md) | 231 | Project journeys (empty / existing-repo / multi-launch / chat) + status enum gaps |
| [`eval-scenarios-v2.md`](eval-scenarios-v2.md) | 199 | Strict eval rubric (S1–S5) + provider × model matrix |
| [`eval-results.md`](eval-results.md) | 192 | Live results: 6 runs across Anthropic/OpenAI/Gemini + 5 bugs A–E |
| [`eval-results-v2.md`](eval-results-v2.md) | 158 | Post-fix re-eval: dashboard 63→69, retry dedup at 2 attempts |
| [`persona-non-technical.md`](persona-non-technical.md) | 214 | Maya (PM) walkthrough: Run + Project journeys, jargon mapping table |
| [`synthesis.md`](synthesis.md) | 254 | Top 10 sequenced action items across Product/Tech/Persona |
| [`project-e2e-bugs.md`](project-e2e-bugs.md) | 195 | 6 bugs from the 2-repo Launch test (#1 fixed; #2–#6 documented) |
| [`deep-dive-audit.md`](deep-dive-audit.md) | 597 | Full Tech/UX/Product/Usability audit, ~25 findings each layer |
| `session-handoff-2026-04-17.md` | (this file) | Handoff |

## What's still open

### Backend tech debt (medium effort)

1. **Bug #3 design alternatives still on the table.** This session shipped the 409-on-empty-plan fix. The audit listed two alternatives: (a) make `/plan` synchronous, (b) add `@Version` to `ExecutionRun` for optimistic locking. Pick one if 409 + client polling proves clunky in real use.
2. **AWAITING_APPROVAL gate notification + quota-80% warning notification.** Need new `PipelineEvent` types (`GateOpened`, `QuotaWarning`) — no existing event for either. Listener wiring is straightforward; adding the events is the bigger move.
3. **ArchUnit rule** for `@Async` methods that touch lazy JPA collections. Locks in the 3a17e9aa fix; would catch the next regression. ~1 file.
4. **Refactor `V2RunOrchestrator` (1675 LOC) + `StateStore` (1647 LOC)** into bounded contexts. Strangler pattern; 2-3 weeks.

### Frontend / UX (medium effort)

5. **Extract `<Button variant="primary|secondary|tertiary|ghost">`** primitive + sweep ~30 sites. Closes the remaining hand-rolled button styles flagged in the audit.
6. **Single modal-close + escape-key + focus-trap standard** via `ResponsiveModal` wrapper. NewRunModal, DeployWizardModal, FailureDecisionModal all need migration.
7. **Native checkbox/radio wrapper** so OS defaults stop showing through.
8. **Dashboard tab-strip mobile UX** — overflow-x-auto already present, but a scroll-affordance gradient + collapse-to-dropdown under `sm` would help.
9. **Visual-regression CI** (Playwright + screenshot diff) on five key pages.
10. **Accessibility pass:** `aria-label` on icon buttons; visible focus rings; `react-focus-lock` on modals.

### Product (copy + small engineering)

11. **Port the Launch-time estimate chip to the global New Run modal.** Estimate endpoint exists at `GET /api/projects/{id}/estimate`; New Run modal doesn't call it. ~4 h.
12. **First-run guided tour** for Vibe users.
13. **Vocabulary lockdown** — pick one of {Run, Execution, Launch, Solution} per concept and rewrite all UI strings.
14. **Settings page additions** — default provider, default execution mode, notification opt-ins.

### Eval (when you want to spend more)

15. **Strict eval scenarios S3–S5** (`eval-scenarios-v2.md`) not yet run. S3 medium = ~$1.50/run × 3 providers = ~$4.50. S5 multi-repo = ~$2/run.
16. **Bug A re-test** — Gemini `MALFORMED_FUNCTION_CALL`. Need an S2-class spec to trigger.
17. **Gemini reviewer-hang re-test.** Possibly closed by existing 90/60 s `runPassWithTimeout`; monitor.

## Known good local state

- `application-local.yml` (gitignored) has working API keys for OpenAI / Anthropic / Gemini.
- Postgres + Redis assumed running locally (Postgres on 5432, Redis on 6379).
- Admin user seeded by V5 migration: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE` (SUPER_ADMIN, ENTERPRISE).
- 110+ Flyway migrations apply cleanly on boot. Last applied this session: V111.
- Feature flag `scoring.v2-verified` is `true` in dev DB (V110).
- Feature flag `validation.v3-strict` is `false` in dev DB (V109 default).

## How to resume in a fresh thread

```bash
# Catch up on context (in this order):
docs/reports/deep-dive-audit.md     # the master audit
docs/reports/synthesis.md           # action plan
docs/reports/project-e2e-bugs.md    # what we found in the live test
CLAUDE.md                           # latest project state
.claude/plans/scalable-pondering-church.md  # the most recent approved plan

# Bring servers back up (Claude Preview MCP):
# - executespec backend on :8080
# - vite-dev frontend on :5173
# (launch.json at .claude/launch.json defines both)

# Login:
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@executespec.ai","password":"REDACTED_FOR_PUBLIC_RELEASE"}'

# Create a fresh project for testing (older ones may be archived):
# POST /api/projects → link 2 repos → POST /api/projects/{id}/executions
# See docs/reports/project-e2e-bugs.md for the full sequence.

# Strict-provider mode for eval reproducibility:
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dexecutespec.strict-provider=true"
```

## Decisions outstanding

The current plan is open-ended; the user owns picking the next sprint. Reasonable starting points (with rough effort):

- **"Ship the design-system primitives"** — Button + Modal-wrapper + checkbox. ~3 days frontend. Visible win on every page.
- **"Close the notification gap"** — AWAITING_APPROVAL + quota-80%. ~1 day backend (mostly: add events). Time-sensitive UX.
- **"Make the planner/reviewer routing explicit"** — Already done in `ed07ada1`. Verify it via fresh eval (would cost ~$0.50).
- **"Ship the eval matrix S3–S5"** — Get evidence on medium/large workloads. ~$10-15 spend.
- **"Refactor `V2RunOrchestrator` + `StateStore`"** — Largest debt. 2-3 weeks. Don't start without a real reason.

## Branch + push state

- Branch: `feature/validation-v3-pillar1-requirement-graph`.
- Remote: `origin/feature/validation-v3-pillar1-requirement-graph` is up-to-date with local.
- PR: open as #21 (per CLAUDE.md "v2.5.11 — Validation v3 / Pillar 1, PR #21"). Likely needs description refresh now that 14 more commits landed; check `gh pr view 21`.
- No outstanding rebase needed.
- Working tree clean except for `src/main/resources/static/index.html` which is the built bundle (regenerated by every `npm build`; not relevant to commits).

## Budget reminder

Cumulative LLM spend across all the audits, fixes, reports, and live verification: **~$1.50** of $25 cap. If you re-run the strict eval matrix or kick off another project E2E, expect ~$0.50–$2 per scenario.

## Files touched this session (12 fix commits)

Backend (10 files):
- `src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java` (PreDestroy + dashboard)
- `src/main/java/com/navneet/executespec/v2/rollout/V2RunService.java` (PreDestroy)
- `src/main/java/com/navneet/executespec/v2/integration/PerkyMessenger.java` (PreDestroy)
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
- `src/main/java/com/navneet/executespec/project/pr/GitHubWebhookController.java`
- `src/main/java/com/navneet/executespec/execution/controller/ExecutionController.java`
- `src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java`
- `src/main/java/com/navneet/executespec/project/pr/PullRequestRecordService.java`
- `src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java`
- `src/main/java/com/navneet/executespec/v2/reviewer/ReviewerAgentV3.java`
- `src/main/java/com/navneet/executespec/router/ModelResolver.java`
- `src/main/java/com/navneet/executespec/execution/service/SolutionExecutionService.java`
- `src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java`
- `src/main/java/com/navneet/executespec/execution/repository/ExecutionSolutionRepository.java`
- `src/main/java/com/navneet/executespec/execution/entity/ExecutionSolution.java`
- `src/main/java/com/navneet/executespec/execution/dto/ExecutionRunDto.java`
- `src/main/java/com/navneet/executespec/v2/harness/SequentialExecutorV2.java`
- `src/main/java/com/navneet/executespec/project/dashboard/ProjectDashboardController.java`
- `src/main/resources/db/migration/V110__enable_scoring_v2_verified.sql`
- `src/main/resources/db/migration/V111__solution_failure_signature_dedup.sql`
- `src/main/resources/application.yml` (JWT TTL)

Frontend (12 files):
- `frontend/src/App.tsx` (route fallback)
- `frontend/src/lib/api.ts` (V1 endpoint removal + 410)
- `frontend/src/stores/useRunStore.ts` (V1 action removal)
- `frontend/src/components/runs/RunDetail.tsx` (ClarificationBanner removal)
- `frontend/src/components/runs/VibeRunSummary.tsx` (Run Again removal)
- `frontend/src/components/runs/ValidationReportPanel.tsx` (token-aligned + statusReason)
- `frontend/src/components/runs/SignalBreakdown.tsx` (token-aligned)
- `frontend/src/components/runs/ScoreCard.tsx` (token-aligned + heading rename)
- `frontend/src/components/auth/QuotaUsageBar.tsx` (upgrade link + tokens)
- `frontend/src/components/runs/NewRunModal.tsx` (hint placement)
- `frontend/src/hooks/useIsMobile.ts` (split into 3 hooks)
- `frontend/src/components/shared/EmptyState.tsx` (compact + icon)
- `frontend/src/components/project/ProjectTaskList.tsx` (EmptyState + tokens)
- `frontend/src/components/project/ProjectLaunchesList.tsx` (EmptyState)
- `frontend/src/components/project/ProjectPrList.tsx` (EmptyState + tokens)
- `frontend/src/components/project/ProjectRepoManager.tsx` (EmptyState)
- `frontend/src/components/project/InlineRunDetail.tsx` (statusReason prop)
- `frontend/src/pages/project/ProjectDashboardPage.tsx` (StatusBadge + EmptyState + tokens)

If a future session needs to revert any single commit, each is individually scoped + tested.
