# Pilot v3.0 — execution recap

> **Status**: Shipped (commit `6bbfde9b`, 2026-04-13). Build green: backend
> `mvn compile` + frontend `npm run build`.
> **Migrations**: V81–V86 (V86 added post-commit to repair Hibernate schema
> validation for `pipeline_run.selected_repo_ids`).
> **Branch**: `feature/v2-next`.

---

## Goal

Turn ExecuteSpec from a single-run, single-repo tool into a **project-centric
workbench** where a linked set of repos, a versioned architecture document,
per-run impact assessments, and trended quality scores all live under one
Project.

## Phasing actually executed

| Phase | Capability | Key files |
|---|---|---|
| **0** | Foundation: types, store, route shell, Project settings columns | `frontend/src/types/project.ts`, `useProjectStore.ts`, `pages/project/ProjectDashboardPage.tsx`, `Project.java`, V81 |
| **1** | Multi-repo projects (R2): dual-scope `ProjectRepo` registry, `project_repo_link` join, multi-repo workspace clone | `project/entity/*`, `project/service/ProjectRepoService.java`, `WorkspaceInitService.cloneMultipleRepos`, V81 |
| **2** | Git workflow (R5): per-run PR records + GitHub webhook with HMAC verification | `project/pr/*`, `ScmPushService` (project-settings driven), V82 |
| **3** | Review scoring (R6): six dimensions derived deterministically from review issues + accuracy report | `scoring/ReviewScoringService.java`, `ScoreCard.tsx`, V83 |
| **4** | Codebase intelligence (R3): per-repo `ProjectCodeMap` + project-wide `ProjectArchitectureDoc`, versioned + annotatable | `codeintel/*`, `ArchitectureViewer.tsx`, V84 |
| **5** | Feature assessment (R4) + Figma URL (R7): pre-plan `AssessmentResult`, surfaced above the gate; design-compliance scorer reads `figmaUrl` | `assessment/*`, `AssessmentPanel.tsx`, V85 |
| **6** | Dashboard (R1): metrics + recent runs + recent PRs + score trend | `project/dashboard/ProjectDashboardController.java`, `ProjectScoreTrend.tsx` (recharts) |

## Four deferrals — closed in same commit

| Deferral | What shipped | Files |
|---|---|---|
| **#4** SHA tracking | `getBranchHeadSha`; `last_synced_sha` populated on create + sync; codemaps + arch doc capture per-repo SHAs; `StalenessService` (Caffeine 5-min cache); dashboard `STALE` flag; ArchitectureViewer amber stale badge | `GitHubRepoService.java`, `StalenessService.java`, `ProjectArchitectureService.java`, `CodeMapGenerator.java` |
| **#1** Multi-repo PRs | `pipeline_run.selected_repo_ids` persisted; `ScmPushService.pushMultiRepo` per-subdir push + per-repo PR + cross-link via `updatePullRequestBody`; `pull_request_record.repo_id` populated; failures isolated | `ScmPushService.java`, `GitTool.commitAndPushSubdir`, `GitHubRepoService.updatePullRequestBody`, V86 |
| **#3** Stack refresh + version | Version regex extraction (pom.xml / package.json / pyproject.toml); `StackRefreshJob` `@Scheduled` daily 03:00 with `@SchedulerLock`; Micrometer counters | `StackDetector.java`, `StackRefreshJob.java` |
| **#2** LLM assessment | Anthropic-backed path behind `assessment.llm.enabled` flag (default off); strict JSON parse; silent fallback to heuristic on any failure | `FeatureAssessmentService.java` |

## End-to-end user journey

1. **ORG_ADMIN** opens project dashboard → links repos (ORG or USER scope) →
   tags relationships → generates architecture doc → adds annotations.
2. **Any user** clicks New Run, picks the project, ticks the repos to clone,
   optionally pastes a Figma URL.
3. Backend clones each repo into `workspace/<runId>/repos/<name>/`.
4. `FeatureAssessmentService` runs (heuristic by default; LLM-backed when
   flag is on) → SSE log entry → `AssessmentPanel` shown above the gate.
5. User approves the plan → workers execute across cloned repos.
6. `ScmPushService.pushMultiRepo` opens one PR per dirty subdir + cross-links.
7. `ReviewerAgentV3` + `AccuracyAssessmentService` produce the validation
   report; `ReviewScoringService` adds 6-dimension scores including
   `designComplianceScore` when `figmaUrl` is set.
8. PRs flip status when reviewers merge on GitHub (webhook → HMAC verify →
   `applyWebhookUpdate`).
9. Dashboard reflects everything: metric tiles, recent runs with scores,
   recent PRs with status chips, trend chart, architecture STALE badge.

## Operational checklist before customer demo

| Setting | Action |
|---|---|
| `GITHUB_WEBHOOK_SECRET` | Set env var; register webhook in each linked repo (`pull_request` event, JSON, secret matches) |
| `assessment.llm.enabled` | Toggle via `POST /api/feature-flags/assessment.llm.enabled` when ready to absorb the per-run LLM cost |
| `StackRefreshJob` | Verify Shedlock table exists in target Postgres (uses existing `shedlock` table) |
| Anthropic credentials | Required for #2 LLM path; falls back gracefully if absent |
| Migrations V81–V86 | Apply via Flyway on first boot (idempotent — all use `IF NOT EXISTS`) |

## Known limitations / follow-ups

- **Multi-repo PR cross-link** patches each PR body once at creation. If
  later PRs are added (e.g., a manual rerun), the earlier bodies are not
  re-patched. Acceptable for pilot; revisit if customers flag it.
- **Codemap regeneration** is manual via the dashboard button. No SSE event
  for regenerate progress; `generate()` is synchronous and may take 5–15s
  for projects with many repos.
- **Assessment LLM cost** is per project run, even if the user later rejects
  the plan. Not refunded — flagged in the deployment notes; mitigated by the
  feature flag staying off by default.
- **`/api/repos/{repoId}` DELETE** endpoint exists but no UI surface yet.
- **Figma OAuth** (full file/frame fetch) is post-pilot — current integration
  passes the URL through to scoring + planner only.

---

**Reference commits**
- `6bbfde9b feat: pilot v3.0 — project workspace, multi-repo, codebase intel, scoring`
- (Forthcoming) docs commit covering this file and all CLAUDE.md / docs updates.
