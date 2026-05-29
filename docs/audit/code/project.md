# Audit: `project/` — project workbench (repos, PRs, RAG, tech-debt, workspace, templates, estimation)

Status: **DONE** · Updated 2026-05-29 · 42 files / 5,600 lines · all read in full

## Subpackages
- **`pr/`** (9) — PR lifecycle: `PullRequestRecord(155)` + `…Service(309)` + `…Repository(46)` + `…Controller(121)`; `GitHubWebhookController(234)`; `CiFailureAutoFixService(289)`; `PrRefreshService(253)`; `PostMergeCascadeService(225)` ✓
- **`service/`** (5) — `ProjectRepoService(661)`; `StackDetector(168)`; `WorkspaceHealthService(138)`; `StackRefreshJob(93)` ✓
- **`dashboard/`** (1) — `ProjectDashboardController(395)` ✓
- **`workspace/`** (2) — `ProjectWorkspaceService(229)`; `ProjectWorkspaceWarmer(86)` ✓
- **`techdebt/`** (2) — `TechDebtService(154)`; `TechDebtFixService(197)` ✓
- **`rag/`** (9) — `ProjectDocumentService(152)`; `EmbeddingService(113)`; `ChunkStore(87)`; `RetrievalService(66)`; `ChunkingService(58)`; `DocumentExtractor(43)`; `ProjectDocumentController(59)`; `entity/ProjectDocument(69)`; `repository(13)` ✓
- **`template/`** (4) — `SpecTemplateService(146)` + `…Controller(67)` + entity(67) + repo(12) ✓
- **`security/`** (1) — `ProjectAccessGuard(124)` ✓
- **`estimation/`** (1) — `EstimationService(105)` ✓ · **`scores/`** (1) — `ProjectScoresController(60)` ✓
- **`controller/`** (1) — `ProjectRepoController(200)` ✓ · **`entity/`** (2) — `ProjectRepo(211)`, `ProjectRepoLink(44)` ✓ · **`repository/`** (2) + **`dto/`** (4) ✓

## What it does
The customer-facing project workbench: register/link GitHub repos (ORG/USER-scoped), track PRs ExecuteSpec opens (run-scoped + system), auto-fix CI failures, refresh PR/merge state, run a per-project disk+git workspace, roll up tech-debt from validation reports, RAG over uploaded docs (pgvector), spec templates, and a heuristic pre-run estimate. All endpoints tenant-gated via `ProjectAccessGuard`.

## Findings — uniformly well-engineered, incident-hardened. Tenant hygiene is exemplary here.

### Security / tenancy (🟢 — this package is the *good* example)
- 🟢 **`ProjectAccessGuard` is a clean IDOR chokepoint.** `assertCanRead` = org-match OR `project_member` row (V175 cross-org) OR SUPER_ADMIN, fail-closed (404 hides existence, 403 denies, 401 on missing principal). `assertCanWrite` additionally rejects `PROJECT_VIEWER`. Every controller in the package calls it at the **call site** (visible boundary) with the service-layer check kept as defense-in-depth — and the RL-2026-05-040 hardening flipped every mutating POST/PUT/DELETE from `assertCanRead` → `assertCanWrite` (link/unlink repo, doc upload/delete, template create/delete, PR refresh, tech-debt fix). This is the pattern the legacy-drift findings elsewhere should converge to.
- 🟢 **`GitHubWebhookController` is a correct fail-closed HMAC adapter** — `X-Hub-Signature-256` verified with `constantTimeEquals`, **rejects every request when the secret is unconfigured** (401), distinguishes JSON parse-error (400) from backend bug (500) so GitHub's retry storm targets real bugs. Contrast the weak Jira/PagerDuty adapters in [integration.md](integration.md). One of the two best webhook adapters in the codebase (the other is the payment webhooks).
- 🟢 **Path-traversal guards everywhere disk is touched** — `ProjectWorkspaceService.resolve` rejects `/`-prefixed + `\0` + escapes-root; `requireProjectId` rejects `/`+`..`; `ProjectRepoService.parseRootYamlSubdir` rejects absolute + `..` + oversized monorepo markers; `ProjectDocumentService.sanitise` regex-scrubs filenames. `runGit` uses `ProcessBuilder(String[])` (no shell). `ChunkStore` uses parameterized JDBC (no SQLi).
- 🟡 **In-org writes are coarse-grained.** `assertCanWrite` admits *any* in-org member by default (the per-`project_member` role check only gates cross-org collaborators). So any DEVELOPER in the org can mutate any project in that org — the guard's own doc flags per-member granularity (PROJECT_DEVELOPER vs ORG_ADMIN) as an explicit follow-up. Matches the stated security bar ("don't leak to strangers in unrelated orgs") but is worth closing for least-privilege.
- 📝 `/api/prs/snapshot` (`PullRequestController`) has no tenant guard, but uses the **caller's own PAT** so GitHub re-authorizes — you only see PRs your PAT can see. Low risk; the class-level "loose by design" note was already tightened on the run-scoped `/api/runs/{id}/prs` path (now `runAccessGuard`).

### Incident-driven robustness (🟢)
- 🟢 The `pr/` cluster is scarred with documented P0 fixes that all point the right way: `PrRefreshService` **dropped its method-level `@Transactional`** (a cascade failure was marking the outer tx rollback-only → `UnexpectedRollbackException` 500) so each PR saves in its own tx; **dedupes async arch-regen per project** (3 PRs into one project fired 3 sequential 30-60s LLM calls → blew the Cloudflare 100s edge timeout → 524); open-only fast path skips terminal PRs. `PostMergeCascadeService` splits sync (notify+banner, ms) from `@Async` heavy (staleness+arch-regen) by latency profile. `ProjectRepoService.syncRepo` is **deliberately NOT `@Transactional`** + metadata-only (RL-2026-05-026 — the folded-in codemap/arch regen held a Hikari connection across multi-minute LLM calls, exhausting the pool; regen moved to an explicit endpoint).
- 🟢 `CiFailureAutoFixService` — flag-gated (`ci.autofix.enabled`, default OFF), hard-capped at 3 attempts/PR (`MAX_AUTO_FIX_ATTEMPTS`), OPEN-only, seeds the patch prompt with the verbatim failure tail (truncated 4 KB). The unattended-bot path is correctly the most conservative of the four autofix surfaces.

### Cost / efficiency
- 🟢 **Dashboard cost rollup is genuinely complete** — `ProjectDashboardController` sums every LLM-spending surface (runs + launches + code-reviews + pr-reviews + arch-docs + generated-docs) into one `CostBreakdown`, and the review/arch/doc halves use indexed `SUM(cost_usd)` queries. Best cost-visibility surface in the product.
- 🟡 **…but the run/launch halves still heap-scan.** `pipelineTokens`/`pipelineCost` + `execTokens`/`execCost` load up to **2000 + 2000 rows** per dashboard call and stream-sum in memory, while the other four surfaces push `SUM` to the DB. Same regression-shape as the [state.md](state.md) admin-analytics `findAll()` finding (here bounded + project-scoped, so milder). Push these two to `SUM(actualCostUsd)` / `SUM(totalCostUsd)` repo queries like the rest.
- 🟢 `WorkspaceHealthService` Redis-caches origin-SHA per **repo** (not per project) so multiple projects linking one repo share the lookup; `RetrievalService` counts READY docs before spending an embedding call; `EstimationService` is a pure non-LLM heuristic (bounded to 20 recent runs).
- 📝 `EmbeddingService` calls OpenAI `text-embedding-3-small` **directly via REST**, NOT through `RunCostTracker`. Embeddings are out of the tool-calling router's scope and cheap + project-scoped (not run-scoped), so this is the mildest of the uncosted-LLM-spend findings — but it IS dollar spend invisible to any cost ledger. Note alongside the [codeintel.md](codeintel.md) `RunCodeMapService` + [assessment.md](assessment.md) `FeatureAssessmentService` direct-call findings.
- 📝 `StackRefreshJob` does `repoRepo.findAll()` nightly (03:00) — but it's a **ShedLock-guarded cron** paced 200ms/repo, not a request path, so the full-scan is acceptable (will want pagination as `project_repo` grows; low priority).

### Tech-debt (minor)
- 📝 `CiFailureAutoFixService.resolveCallerPat` (private) is **dead** — defined, never called in-file (the finding path resolves the PAT elsewhere). Small dead method.
- 📝 `ChunkStore.emptyVectorList()` appears unused.
- 📝 `ProjectScoresController` maps its `overallScore` field to `accuracyScore` (LLM self-assessment), while `ProjectDashboardController.preferredScore` deliberately prefers `verifiedScore` — the score-trend feed and the dashboard headline can disagree for the same run. Minor consistency gap (the dashboard is the authoritative one per CLAUDE.md).

## Cross-refs
Autofix surfaces routed through → [integration.md](integration.md) (`FindingPatchSubmitter`). Arch regen + staleness consumed → [codeintel.md](codeintel.md). PR records consumed by → [state.md](state.md) (run join). Tech-debt source → [report.md](report.md) (`validation_report.issues_json`). Tier caps → `billing/`+`auth/TierEnforcementService` (Tier 5). Cross-org member model → `auth/ProjectMember` (Tier 5). Uncosted-LLM-spend twins → [codeintel.md](codeintel.md), [assessment.md](assessment.md). Webhook-adapter quality contrast → [integration.md](integration.md). In-memory `findAll()`/heap-scan theme → [state.md](state.md).
