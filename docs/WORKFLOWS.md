# WORKFLOWS

End-to-end flows through the ExecuteSpec platform. Each flow lists the HTTP entry point, the controller that owns it, the services it calls, feature flags / tier gates / approval gates it must cross, and the terminal state it lands in. File paths are absolute under the repo root.

All flows assume a logged-in caller unless noted. JWT auth is enforced by `auth/security/SecurityConfig.java` except for the explicit no-auth surfaces (signup, OAuth callback, webhooks, `/api/shared/**`, `/api/v3/anonymous-runs`, `/api/v3/referrals/lookup`).

---

## Table of contents

1. [Signup to first PR](#1-signup-to-first-pr)
2. [Plan-gate approval](#2-plan-gate-approval)
3. [Multi-repo run](#3-multi-repo-run)
4. [PR Review autofix](#4-pr-review-autofix)
5. [Tech-debt autofix](#5-tech-debt-autofix)
6. [CI failure autofix (webhook)](#6-ci-failure-autofix-webhook)
7. [Code Review severity-bucket autofix](#7-code-review-severity-bucket-autofix)
8. [Architecture regeneration](#8-architecture-regeneration)
9. [Library solution apply](#9-library-solution-apply)
10. [Migration recipe apply](#10-migration-recipe-apply)
11. [Project chat](#11-project-chat)
12. [Cost cap and refund on FAILURE](#12-cost-cap-and-refund-on-failure)
13. [Preview deploy](#13-preview-deploy)
14. [Push to GitHub / Bitbucket](#14-push-to-github--bitbucket)
15. [Sync from origin](#15-sync-from-origin)
16. [Cancel run](#16-cancel-run)
17. [Archive and restore run](#17-archive-and-restore-run)
18. [External project sharing (V198 flag)](#18-external-project-sharing-v198-flag)
19. [BYOK setup](#19-byok-setup)
20. [GDPR export](#20-gdpr-export)

---

## 1. Signup to first PR

Goal: a fresh visitor creates an account, submits a spec against a new GitHub repo, approves the plan, and lands on a PR.

```
register -> verify-email -> auto-session -> submit run
                                              |
                                              v
                            classify -> outline -> plan gate -> execute -> review -> push -> PR
```

1. `POST /api/auth/register` -> `auth/controller/AuthController.register` -> `auth/service/AuthService.register` creates an `app_user` row (status PENDING), bootstraps a personal org (`org.is_personal=true`, V162/V163), creates a FREE `BillingSubscription`, sends a verification email via `MailService`, fires the `USER_REGISTERED` analytics event, and binds any pending referral via `ReferralService` (R-162).
2. `GET /api/auth/verify-email?token=...` (F-29) -> `AuthController.verifyEmailAndAuthenticate` -> `AuthService.verifyEmailAndAuthenticate` flips status to ACTIVE and returns a signed JWT + refresh token in one round-trip.
3. Caller saves the JWT, then `PUT /api/auth/settings/github` -> `AuthController` -> `AuthService.updateGitHubSettings` to store the user PAT (encrypted via `auth/security/EncryptedValueConverter`).
4. `POST /api/runs` -> `api/controller/RunController.submit` -> validates the body with `RunRequest`, scans the spec via `security/InputSanitizer.scan`, atomically increments the FREE-tier monthly quota via `auth/service/TierEnforcementService.assertAndIncrementRunCount`, then dispatches `integration/RunOrchestrator.submitRun` with `repoMode=NEW` so the orchestrator creates a fresh GitHub repo at push time.
5. Orchestrator stamps the new `pipeline_run` row with `status=PLANNING`, persists ownership fields BEFORE the async pipeline future fires, then submits the background pipeline to its `v2-orchestrator` thread pool.
6. Inside `runPipeline` (in `integration/RunOrchestrator.java`): workspace is initialised by `harness/WorkspaceInitService.ensureRunWorkspace`; `SmartClassifierAgent` picks a tier; `OutlinePlannerAgent.synthesizeFullPlan` produces tasks.
7. Plan gate opens. PRODUCTION mode pauses at `AWAITING_APPROVAL`; PROTOTYPE bypasses. See flow 2.
8. After approval, `harness/SequentialExecutor.execute` runs every task with `WorkerAgent`, smoke-tests via `buildProject`, then `reviewer/ReviewerAgent.review` cleans up wiring and lint.
9. `report/ValidationReportService.generateAndPersist` writes a verified score and reviewer verdict. `testgen/TestGenOrchestrator` and `previewService` kick off in the background.
10. Auto-push: `integration/scm/ScmPushService.push` runs because `repoMode=NEW`. `repo/GitHubRepoService.createRepo` provisions the repo under the caller's PAT, the workspace is pushed to a branch, and a PR is opened against the default branch. The PR URL is stamped on `pipeline_run.github_pr_url`.
11. The run terminates as COMPLETE/PARTIAL/FAILED with `statusReason` captured. `PipelineEvent.RunCompleted` fires; in-app notifications go out via `notification/InAppNotificationService`.

Tier gate: FREE = 5 runs/month, 1-credit specs only, no high-complexity plans (`TierEnforcementService.assertComplexityAllowed`).

---

## 2. Plan-gate approval

Goal: stop the pipeline at the plan stage in PRODUCTION mode, charge credits, and let the caller approve or reject.

1. Orchestrator's `runPipeline` calls `harness/GateStore.openPlanGate(runId)` which inserts a PENDING `gate_record` row and seeds Redis at `gate:plan:{runId}` (TTL 4h, see `harness/GateStore.java`).
2. Pipeline status flips to `AWAITING_APPROVAL`; SSE emits `gateOpenedWithCredits` with the credit estimate (`max(1, ceil(bufferedEstimate / $5.0))`).
3. `awaitPlanGate` blocks the pipeline thread, polls Redis at 500ms, and falls back to the DB on Redis outage. Max wait = 24h.
4. Caller hits `POST /api/runs/{runId}/approve` -> `api/controller/RunController.approvePlan` -> validates `creditsConfirmed` matches the live estimate (409 if drifted), runs `auth/service/GovernanceGuard` checks (M-032/M-033), then atomically claims the gate via `GateStore.tryClaimPlanGate` (returns false to the slower of two concurrent approvers, 409).
5. On approval `BillingService` charges credits and `RunOrchestrator` resumes the pipeline at `SequentialExecutor.execute`. On rejection it transitions to CANCELLED.
6. Gate-loss fallback: if `isPlanGateWaiting` returns false (server restarted with no thread polling), approve auto-fails the run with a clear message rather than hanging forever.
7. PROTOTYPE mode skips this entire flow (`executionMode=PROTOTYPE`).

Tier gate: BYOK orgs (`org.key_source_mode=OWN_KEYS`) skip the platform cost cap; HYBRID and SYSTEM stay capped.

---

## 3. Multi-repo run

Goal: a single run that edits two or more linked repos in one workspace and opens one PR per repo, cross-linked in the body.

1. Caller links repos to a project via `POST /api/repos` (`project/controller/ProjectRepoController.createRepo`) then `POST /api/projects/{projectId}/repos/{repoId}` (`project/service/ProjectRepoService.linkToProject`). Each link runs `BootstrapService.bootstrapAsync` to seed `.executespec/`.
2. `POST /api/runs` with `projectId` set and `selectedRepoIds=[uuid1, uuid2, ...]`. `RunController.submit` forwards to `RunOrchestrator.submitRun(...)` with the repo list serialised onto the run row.
3. `WorkspaceInitService.cloneMultipleRepos` (in `harness/WorkspaceInitService.java`) clones each repo under `<workspace>/<runId>/repos/<name>/`, captures the initial SHA per repo into `run_repo_snapshot` (used by sync-original, flow 15), and augments the workspace `.gitignore` via `harness/VendoredPathGuard`.
4. `OutlinePlannerAgent` receives a per-repo summary block prefixed `MULTI-REPO PROJECT WORKSPACE`. `SequentialExecutor.execute` carries the summary in `ExecutionRequest.workspaceSummary` so the worker prompt knows which subdirs exist.
5. `WorkerAgent` writes through tool dispatchers; relative paths inside each `repos/<name>/` subdir keep edits scoped.
6. At terminal, `integration/scm/ScmPushService.pushMultiRepo` iterates each subdir, runs a per-repo `git add/commit/push`, opens a PR per repo via `repo/GitHubRepoService.createPullRequest`, and cross-links the sibling PR list into each PR body via `updatePullRequestBody`. The representative PR URL is persisted on `pipeline_run.github_pr_url`; the full per-repo list lives in `pull_request_record` and is exposed at `GET /api/runs/{id}/prs`.
7. Tier gate: linking respects the V140 per-project repo cap (`ProjectRepoLinkRepository.countByProjectId`). Plan also runs `M-039` allowlist via `auth/service/GovernanceGuard`.

---

## 4. PR Review autofix

Goal: a PR review verdict gets a "Fix N comments" button that opens a fresh patch on the same PR head branch.

1. `POST /api/pr-reviews` (`prreview/PrReviewController.submit`) -> `prreview/PrReviewService.submitReview`. Tier gate via `assertTierAndQuota` (FREE -> 403; PRO monthly quota via `pr_review_monthly_quota`). Idempotent on `(repo, pr, head_sha)`.
2. Service fetches `PullRequestSnapshot` from GitHub (200k char diff cap), persists `pr_review` row as QUEUED, dispatches to the `pr-review-dispatch` pool.
3. Worker thread calls `prreview/PrReviewAgent.review` -> single LLM call at PLANNING tier (Sonnet 4.6), `temperature=0.0`, `maxTokens=8192`, with the strict `submitReview` tool schema. The agent emits `{verdict, comments[]}`.
4. `PrReviewService.completeReview` persists `pr_review_comment` rows and the verdict (APPROVE / REQUEST_CHANGES / COMMENT); SSE fires on channel `pr-review-{id}`.
5. Caller clicks Autofix. `POST /api/pr-reviews/{id}/autofix` (`PrReviewController.autofix`) -> `prreview/PrReviewAutofixService.dispatch`.
6. Service refuses with 409 if the review is not COMPLETE, verdict is APPROVE, there are no comments, the project's repo link is missing, or the PR is closed unmerged. It resolves the linked repo via `ProjectRepoLinkRepository.findByProjectId` (case-insensitive name match), fetches the PR head branch via `repo/GitHubRepoService`.
7. `finding/FindingPatchSubmitter.submit` is called with `FindingType.PR_REVIEW`, dedupe key = the review id, prompt embedding the comments with file:line + severity + category, and `branchOverride=<PR head branch>`.
8. Submitter dispatches `RunOrchestrator.submitRun` with `runMode=PATCH`, `source=finding:PR_REVIEW`. `runPatchPipeline` skips classifier/outline/reviewer and uses `PatchAgent` (`agent/PatchAgent.java`) instead.
9. On terminal `runPatchPipeline` runs `postEditFormatter` (so `prettier --check` / `black --check` lint gates pass) and `scmPushService.push` -> the existing PR head branch advances via `gitTool.pushToExistingBranch` (fast-forward only, no force-push, no new PR).
10. The PR review row stays as-is; the caller sees a new patch commit on the same PR.

---

## 5. Tech-debt autofix

Goal: turn the project's accumulated `validation_report.issues_json` into one PR per ReviewerAgent category.

1. Dashboard fetches `GET /api/projects/{projectId}/tech-debt` (`project/dashboard/ProjectDashboardController.getTechDebt`) -> `project/techdebt/TechDebtService.build`. No LLM, no DB writes. Reads only `validation_report.issues_json` across the project's runs and aggregates into category histogram, severity rollup, top-5 file hotspots, and per-dimension score trend.
2. Caller clicks "Fix" on a category row. `POST /api/projects/{projectId}/tech-debt/fix` (`ProjectDashboardController.fixTechDebt`) -> `project/techdebt/TechDebtFixService.dispatchFixForCategory`.
3. Service caps the prompt at 30 issues (`MAX_ISSUES_IN_PROMPT`), reads every linked repo from `ProjectRepoLinkRepository.findByProjectId`, and builds a `Finding(FindingType.TECH_DEBT, projectId|CATEGORY, projectId, selectedRepoIds, prompt, null, userId, null)`.
4. `FindingPatchSubmitter.submit` dedupes per finding key, dispatches via `RunOrchestrator.submitRun` with `runMode=PATCH`, `source=finding:TECH_DEBT`. Multi-repo runs reuse the workspace clone fan-out from flow 3.
5. PatchAgent runs the thin-agent loop. On terminal, `runPatchPipeline` auto-pushes via `ScmPushService.push` -> one fresh PR per repo on a new branch (one branch per category).
6. Tier gate: PROJECT_VIEWER cannot fire this (audit B #12 -- `dispatch` should be on the write path; current code uses `assertCanRead` on the dashboard controller, flagged as drift).

---

## 6. CI failure autofix (webhook)

Goal: when GitHub posts a `check_run.completed` event with `conclusion=failure` against a PR ExecuteSpec opened, automatically submit a patch run that fixes the failing test on the same PR head branch.

1. GitHub fires `POST /webhooks/github` -> `project/pr/GitHubWebhookController.handleCheckRun`. The controller verifies the HMAC SHA-256 over `X-Hub-Signature-256` using `${github.webhook.secret}`.
2. Controller routes by `X-GitHub-Event` header. For `check_run` events, it parses repo full name, head branch, conclusion, and detail URL.
3. If `conclusion=failure`, it calls `project/pr/CiFailureAutoFixService.onCheckRunFailed`.
4. Service is feature-flag gated: `FeatureFlagService.isEnabled("ci.autofix.enabled")` (default OFF). Off -> WARN log, no-op. The flag stays OFF until SWE-bench Lite results justify unattended-bot mode.
5. With the flag on: service finds the matching `PullRequestRecord` via `findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc`. If `ci_auto_fix_attempts >= MAX_AUTO_FIX_ATTEMPTS (3)`, skip with reason `attempts_exhausted`.
6. Resolves the originating run's `created_by_user_id` and PAT, builds a prompt seeded with the failure summary + check_run URL, then calls `FindingPatchSubmitter.submit` with `FindingType.CI_FAILURE`, `branchOverride=<PR head branch>`, dedupe key on the PR id + check run id.
7. PatchAgent runs, `runPatchPipeline` auto-pushes to the same branch on terminal. The `ci_auto_fix_attempts` counter on `pull_request_record` increments.
8. The webhook returns 200 unconditionally to avoid GitHub retries.

---

## 7. Code Review severity-bucket autofix

Goal: a whole-repo code review scan produces N severity buckets; clicking a bucket opens one PR per severity with every issue in that bucket fixed.

1. `POST /api/code-reviews` (`codereview/CodeReviewController.submit`) -> `codereview/CodeReviewService.submitScan`. Tier gate via `assertTierAndQuota` (FREE -> 403; PRO -> 4 scans/month). Idempotent on `(projectId, repoFullName, head_sha)`.
2. Service resolves the default branch and head SHA via `repo/GitHubRepoService`, persists `code_review_scan` as QUEUED, dispatches to the `code-review-dispatch` pool.
3. Worker thread walks the lifecycle: CLONING (`harness/WorkspaceInitService.cloneFromRepo`) -> STATIC_RUNNING (`codereview/SemgrepRunner.scan` shells `semgrep --config p/ci --json`) -> LLM_RUNNING (`codereview/CodeReviewAgent.review` at PLANNING tier with 16k token budget; samples top-20 most-relevant files via `scoreFile` heuristics).
4. Agent triages static findings (KEEP/REWRITE/DROP) and adds architectural / logic issues semgrep can't see. Emits one `submitCodeReview` tool call with `{summary, issues[], droppedStaticFindings[]}`.
5. `completeScan` writes `code_review_issue` rows with fingerprints (SHA-256 of `ruleId|filePath|lineStart|message|severity`), so re-scans can carry RESOLVED/DISMISSED across runs. Markdown report is rendered by `codereview/CodeReviewMarkdownRenderer.render` and persisted on the scan row.
6. Caller fetches `GET /api/projects/{projectId}/code-reviews/latest?repo=<full_name>` (`CodeReviewController.latest`) to see the severity-bucket counts.
7. Caller clicks a severity chip ("3 CRITICAL"). `POST /api/code-reviews/{id}/autofix` with `{severity:"CRITICAL"}` -> `codereview/CodeReviewAutofixService.dispatchFixForSeverity`.
8. Service validates severity is one of `{CRITICAL, MAJOR, MINOR, INFO}`, asserts scan is COMPLETE, filters OPEN issues by severity, caps prompt at 25 issues with each suggested fix truncated to 600 chars, resolves the linked repo, then `FindingPatchSubmitter.submit` with `FindingType.CODE_REVIEW_ISSUE`, dedupe key = `scanId|severity`.
9. PatchAgent runs, `runPatchPipeline` auto-pushes a fresh PR on a new branch (one branch per severity). The PR body is grouped by file with code-fenced suggested fixes.

---

## 8. Architecture regeneration

Goal: regenerate per-repo HLD/LLD codemaps + per-repo architecture docs + combined project architecture doc.

```
codemap (per repo) -> repo arch (per repo) -> combined project arch
```

1. `POST /api/projects/{projectId}/architecture/generate` (`codeintel/ProjectArchitectureController.generate`) -> `codeintel/ProjectArchitectureService.generate(projectId, branchOverride, patOverride)`. The endpoint asserts `ProjectAccessGuard.assertCanWrite`. Companion alias: `POST /api/projects/{projectId}/analyse?branch=<name>`.
2. Step 1 (`ensureCodeMap`): for each linked repo, run `codeintel/CodeMapGenerator.generateWithSha` which lists the repo's tree via the GitHub Trees API, scans the top 12 route files and top 40 source files with per-language regex (Spring, Express, FastAPI, Go, Rust, Python, TypeScript, Ruby, Kotlin) and `JavaAstSymbolExtractor` for Java (PR-N1). Persisted as `project_code_map` with `generated_from_sha`. Idempotent by SHA -- reuse if SHA matches.
3. Optional Step 1.5: PR-N2 feature flag `codemap.purpose-summaries` adds 2-3 sentence per-file purpose summaries via `codeintel/FilePurposeSummaryService` at LIGHTWEIGHT tier (SHA-256 content-keyed cache).
4. Step 2 (`ensureRepoArchDoc`): for each repo, `codeintel/RepoArchitectureGenerator.generate` runs a LIGHTWEIGHT-tier LLM call against the per-repo codemap and emits a markdown doc with sections HLD / LLD / Surface area / Integration points. Persisted as `repo_architecture_doc`.
5. Step 3: `codeintel/ArchitectureDocGenerator.generate` runs a PLANNING-tier LLM call that synthesises the per-repo arch docs into one cross-repo doc (Overview / Repository layer / Cross-repo data flows / Shared contracts / Open questions). Persisted as `project_architecture_doc` with JSONB SHA + branch fingerprint maps. PR-K cache: short-circuits when fingerprint matches latest non-blank doc.
6. `ProjectArchitectureService.persistToWorkspace` mirrors the markdown to `<agent.workspace>/projects/<projectId>/architecture.md` via `project/workspace/ProjectWorkspaceService.writeAndCommit`. `ProjectWorkspaceWarmer.warmDiskCache` pushes codemaps and arch.md asynchronously so cross-instance workers hit the disk cache.
7. E1.2: dual-write of codemaps to customer repos' `.executespec/arch/codemap.md` via `folder/ExecutespecFolderWriter` (PR by default, DIRECT_PUSH per-repo opt-in via `ProjectRepo.executespecPrMode`). PRs persisted via `PullRequestRecordService.recordCreated` with `Kind.ARCHITECTURE`.
8. `codeintel/StalenessService.invalidate(projectId)` clears the Redis cache so the dashboard "STALE" badge refreshes. `VersionRetentionService` caps every version-tracked artefact at 20 entries.

Tier gate: paid-tier only (per `ProjectRepoService` + chat-tools). Callers must hold `ProjectAccessGuard.assertCanWrite`.

---

## 9. Library solution apply

Goal: instantiate a Readymade Solution ("Login with Google", "Stripe checkout", etc.) into the caller's repos as an execution run.

1. Library is seeded at boot from `classpath:readymades/*.yml` by `readymade/seeder/ReadymadeSeeder.onStartup` (PostConstruct) into `readymade_solution`, `readymade_variant`, `readymade_slot`, `readymade_dependency`. SUPER_ADMIN reseed via `POST /api/admin/readymades/reseed`.
2. Caller browses via `GET /api/library` (`readymade/controller/ReadymadeController.list`) -> `readymade/service/ReadymadeService.list(category, macroGroup, tier, search, callerTier)`. Tier-gating returns `ReadymadeListItemDto(locked, unlockTier)` per row so the UI renders lock icons.
3. `GET /api/library/{slug}` returns the full detail (variants + slots + dependencies) plus tier-lock state.
4. `POST /api/library/{slug}/resolve?projectId=&repoId=` -> `readymade/service/VariantResolver.resolve` reads `project_repo.detected_stack`, normalises via `readymade/service/StackNormalizer`, looks up the matching `(language, framework)` variant, and returns `VariantResolutionDto(bestMatch, availableVariants, detectedLanguage, detectedFramework, reason)` where reason is `OK | NO_VARIANT_FOR_STACK | STACK_NOT_DETECTED | REPO_NOT_LINKED`.
5. Caller fills required slots, then `POST /api/library/{slug}/instantiate` with `{projectId, repoIds, slotValues, variantId, branch}` -> `readymade/service/ReadymadeOrchestrator.instantiate`.
6. Orchestrator validates: 401 anonymous, 404 unknown slug, 403 tier-locked (`FREE<PRO<TEAM<ENTERPRISE`), 400 missing project/repos/slot. Picks the variant (explicit > auto-resolve > first). Composes the spec via `SpecComposer` (substitutes `{{slot_name}}` placeholders in `readymade_variant.spec_template`).
7. Builds a `CreateExecutionRequest`, calls `ExecutionOrchestrator.createExecution` to fan out across `repoIds`, stamps `execution_run.readymade_solution_id` + `readymade_variant_id` for tracking, then triggers analysis.
8. Returns 202 with `{executionRunId, status, message}`. The execution then flows through the standard V2 pipeline (flow 1 from step 5 onwards).
9. Favourites: `POST /api/library/favourite/{slug}` / `DELETE` toggles `readymade_favourite` rows for the caller.

Tier gate: per-solution `tier` column. Library catalog format: see `docs/readymades-catalog-format.md`.

---

## 10. Migration recipe apply

Goal: apply a deterministic+AI migration (Express->Fastify, JS->TS, Mongoose->Prisma) as a two-phase plan-gate: dry-run preview, then apply.

1. Library is seeded at boot from `classpath:migration-recipes/*.yml` by `migration/seeder/MigrationRecipeSeeder.onStartup` into `migration_recipe`, `migration_variant`, `migration_slot`, `migration_dependency`. SUPER_ADMIN reseed via `POST /api/admin/migrations/reseed`.
2. Caller browses via `GET /api/migrations` (`migration/controller/MigrationController.list`) -> `migration/service/MigrationService.list`. Tier-gating returns `MigrationListItemDto(locked, unlockTier)` per row.
3. `GET /api/migrations/{slug}` returns the detail (variants + slots + dependencies + lock state).
4. Phase 1 (dry-run): `POST /api/migrations/{slug}/dry-run` with `{projectId, repoIds}` -> `migration/service/MigrationDryRunService.dryRun`. Tier-gate runs FIRST (audit fix [F-05], 2026-04-30) so FREE users can't compute applicability / sample diff on locked recipes.
5. For each repo, the service reads `project_repo.detected_stack`, normalises, runs `migration/service/MigrationStackMatcher.match` against the recipe's `source_stack` predicate, and resolves cost/file bands and sample diff from `migration_dry_run_cache` (composite key `(recipeId, stackSignature)`). Falls back to recipe-level bands when cache miss. No LLM spend in v1.
6. Response `MigrationDryRunDto(slug, name, estCostLowTotal, estCostHighTotal, estFilesLowTotal, estFilesHighTotal, sampleDiff, perRepo:[{repoId, fullName, status:APPLICABLE|INCOMPATIBLE_STACK|UNKNOWN_STACK|NOT_LINKED, reason, estFilesLow/High, estCostLow/High}])`.
7. Phase 2 (apply): caller reviews, fills slots, then `POST /api/migrations/{slug}/apply` with `{projectId, repoIds, slotValues, optionsSignature, branch}` -> `migration/service/MigrationOrchestrator.apply`.
8. Orchestrator validates (400 missing fields, 401 anon, 403 tier-locked, 404 missing recipe/project), validates slot values via `SlotValidator`, picks variant (`optionsSignature` match preferred), composes spec via `SpecComposer`, builds `CreateExecutionRequest`, dispatches via `ExecutionOrchestrator.createExecution`, stamps `execution_run.migration_recipe_id` + `migration_variant_id`.
9. Returns 202 with `{executionRunId, status, message}`. Execution flows through the standard V2 pipeline.

Library catalog format: see `docs/migration-recipes-catalog-format.md`.

---

## 11. Project chat

Goal: a long-running chat conversation against a project, with project memory + architecture + RAG retrieval injected each turn, and project-action tools the LLM can call.

1. Tier gate: TEAM / ENTERPRISE only. `memory/ProjectChatController.requireChatAccess` checks `project.orgId == user.orgId` AND `TierConfig.executionViewEnabled`. FREE/PRO -> 403 with "Team or Enterprise tier" copy.
2. `GET /api/projects/{projectId}/chat` lists history (`memory/repository/ProjectChatMessageRepository.findByProjectIdOrderByCreatedAtAsc`).
3. `GET /api/projects/{projectId}/chat/info` returns the resolved model name + provider + context window via `ModelResolver.resolve("ProjectChatAgent", provider, PLANNING)` + `ModelRegistry.contextLimitForOrPrefixFallback`.
4. `POST /api/projects/{projectId}/chat` -> `memory/ProjectChatService.send(projectId, orgId, userId, userText, callerPat)` (V169 threads the caller's PAT into project-action tools).
5. Service builds the system prompt: project name + TOOLS enumeration + LIFECYCLE FAQ + PROJECT MEMORY (from `memory/ProjectMemoryProvider.buildPlannerContext` -- compacted snapshot + last 10 ledger entries) + latest project architecture doc + RAG references via `project/rag/RetrievalService.retrieve` (last user message as query, top-3, 1500 token cap) + last 20 messages.
6. Dispatches via `agent/LlmRouter.call` at PLANNING tier with `ProjectChatTools` (9 tools) bound. LlmRouter hardcodes `internalToolExecutionEnabled(false)`, so the service runs a manual tool-execution loop up to `MAX_TOOL_ROUNDS=6`. Each round: parse tool calls, dispatch via matching `ToolCallback`, append `ToolResponseMessage`, re-call LLM.
7. Tools the chat can call (in `memory/chattools/ProjectChatTools.java`): `regenerate_architecture`, `generate_project_doc`, `list_repos`, `list_recent_runs`, `list_pull_requests`, `get_project_memory`, `get_latest_architecture`, `run_code_review`, `get_latest_code_review`. Each tool returns JSON envelopes; errors never throw out of `call()`.
8. Service persists USER + ASSISTANT messages, tracks tokens via `RunCostTracker`, appends "(hit my tool-call limit)" suffix if max rounds reached.
9. `POST /api/projects/{projectId}/chat/compact` summarises everything except the last 6 messages into one ASSISTANT-voice summary (LIGHTWEIGHT tier, atomic delete + insert).
10. `POST /api/projects/{projectId}/chat/compile` distils the last 30 messages into a 1-2 paragraph imperative execution spec for handoff to a run.
11. `DELETE /api/projects/{projectId}/chat` wipes all messages (v2.6.1).

Async note: `regenerate_architecture` and `generate_project_doc` can hold the HTTP request 30-90s -- the surrounding `applyExecutor` (4 threads in `api/controller/RunConversationController`) keeps the Tomcat handler thread free.

---

## 12. Cost cap and refund on FAILURE

Goal: charge credits at plan approval, refund them on FAILURE / clean-PARTIAL, and let callers voluntarily lower the platform cost cap.

1. At submission, `RunRequest.userCostCap` (range $0.10 to $1000.00) is optional. `RunOrchestrator.submitRun` clamps it against `DEFAULT_PATCH_HARD_CAP_USD=8.0` via `clampHardCap`. BYOK orgs (`org.key_source_mode=OWN_KEYS`) skip the platform cap entirely (`runPatchPipeline` exemption); HYBRID and SYSTEM stay capped.
2. Plan gate (flow 2) opens with a live credit estimate via `cost/RunCostTracker.estimateRunTierAware`. Credits formula: `max(1, ceil(bufferedEstimate / $5.0))`.
3. On approval, `BillingService` deducts credits from `BillingUsage.RUNS` via `auth/service/TierEnforcementService.incrementRunCount`. The bonus-credit pool (`RunTopup`) is consumed FIFO via `billing/service/RunTopupService.consumeBonusRun` when the user is at limit.
4. During the pipeline, `RunCostTracker.record` per LLM call accumulates `actualCostUsd`, `totalInputTokens`, `totalOutputTokens`, and `cache_read_input_tokens` (all 3 providers). The live snapshot is exposed at `GET /api/runs/{id}/cost` (`RunController.getLiveCost`).
5. `billing/service/CostOutlierDetector` (`@EventListener` on `RunCompleted`/`RunFailed`) bumps a Micrometer counter `executespec.cost.outlier` when `actualCostUsd > 2.0 x costEstimateHigh`. Signal-only; does not auto-refund.
6. On terminal, `RunOrchestrator.terminate` writes `actualCostUsd`, `statusReason`, and a `RunCostLog` audit row.
7. Refund path: `billing/service/RunQuotaRefundListener.onRunFailed` (`@Async @EventListener`) fires on `PipelineEvent.RunFailed` OR `PipelineEvent.RunCompleted` where `status=PARTIAL && reason=CLEAN_WITH_INTERMEDIATE_WARNINGS`. It calls `TierEnforcementService.decrementRunCount(userId, credits)` and `UsageTrackingService.decrement(userId, "RUNS", credits)`. Refund is exact (`batchCredits` for BATCH runs, `creditsCharged` otherwise).
8. FAILURE shape: `status_reason` enumerated as `CLEAN_COMPLETION | CLEAN_WITH_INTERMEDIATE_WARNINGS | FINAL_BUILD_FAILED | TESTS_FAILED:N_OF_M | REVIEWER_UNRESOLVED | REVIEWER_FAILED | ALL_TASKS_FAILED | LEGACY_UNKNOWN` (`integration/RunOrchestrator.java`).

Feature flag: `scoring.v2-verified` controls whether `verifiedScore` is computed; `statusReason` is derived independent of the flag (audit 2026-05-13).

---

## 13. Preview deploy

Goal: spin up a live preview of a completed run (Cloud Run + Docker Compose + VM-SSH + preview-engine), 5-minute default TTL.

1. `GET /api/runs/{runId}/preview/analyze` (`api/controller/PreviewController.analyze`) -> `deployment/preview/PreviewService.analyze` runs `DatabaseDetector` + `EnvVarDetector` against the workspace to surface required env vars and the detected DB type before start.
2. `POST /api/runs/{runId}/preview/start` -> `PreviewController.start` -> `PreviewService.start(runId, user, envVars)`. Tier-gated concurrency: FREE=1, PRO=3, ENTERPRISE/TEAM=10 (Redis-backed via `deployment/preview/GlobalPreviewLimiter`). Over-limit -> 409.
3. Run-status gate: must be in `{COMPLETE, PARTIAL, WORKING, REVIEWING, AWAITING_MERGE}`. Idempotent: returns the existing session if one is live.
4. Service picks the mode:
   - Docker Compose (local + small dev runs): `buildAndStart` -> `DevDockerfileGenerator.detect` chooses a project type from 10 supported, `DevDockerfileGenerator.generate` writes the Dockerfile, `ComposeFileGenerator` writes compose.yml with DB sidecar if detected, `ComposeExecutor` runs `up --build`, port allocated via `PreviewPortPool`, health check via 30 retries x 2s (accepts <500 as healthy with WARN for 4xx).
   - Cloud Run: `buildAndStartCloudRun` -> builds image, pushes via `CloudRunClient.deployPreview` with `cpu`/`memory`/`envVars`.
   - VM-SSH: `buildAndStartVm` -> SCP workspace to `preview-gw`, run remotely.
   - preview-engine: `buildAndStartViaEngine` -> hands off to `PreviewEngineHandoff`.
5. Preview is exposed at `preview-{shortId}.executespec.ai` via `deployment/preview/PreviewProxyFilter` (HIGHEST_PRECEDENCE servlet filter reverse-proxying to `localhost:{port}`, stripping `X-Frame-Options`/CSP so the iframe renders). `CaddyProxyManager` is the alternative when present.
6. `GET /api/runs/{runId}/preview/status` polls. `GET /api/runs/{runId}/preview/logs?lines=&filter=` streams via `docker compose logs` / `gcloud logging read` / VM `tail`. `POST /api/runs/{runId}/preview/exec` runs an allowlisted command inside the container (Docker only; allowlist = `ls/ps/cat/curl/env/df/free/netstat/ss/ping/whoami/hostname/date/uptime/pwd`, rejects shell metacharacters and path traversal).
7. `DELETE /api/runs/{runId}/preview/stop` tears down via `cleanupCompose` / `cleanupCloudRun`.
8. Scheduled cleanup: `@Scheduled(fixedRate=60000) cleanupExpired` reaps sessions past TTL; `@Scheduled(fixedRate=1_800_000) cleanupOrphans` reconciles via `docker compose ls` + Cloud Run service list.
9. Permanent deploy path (`POST /api/deployments` -> `api/controller/DeploymentController.deploy` -> `deployment/DeploymentService`) follows a different 5-phase pipeline (GitHub fetch -> docker build -> AR push -> Cloud Run deploy -> health check) with rollback support. Server-side tier gate via `TierPermissionService.canDeploy` (F-31 closes the curl-bypass).

Feature flag: `agent.deployment.enabled` controls whether `DeploymentController` and `DeployEngineOrchestrator` (path A in user's GCP project) are loaded at all.

---

## 14. Push to GitHub / Bitbucket

Goal: push the workspace at terminal to GitHub or Bitbucket, single-repo or multi-repo, with per-project branch naming and auto-PR creation.

1. Auto-path: `RunOrchestrator.runPipeline` step 7b. For COMPLETE/PARTIAL with `repoMode=EXISTING`, calls `scmPushService.push(fresh)`. One-shot override flag: `runSkipPushFlag.consume(runId)` (set via `RunRequest.skipAutoPush`).
2. Manual path: `POST /api/runs/{runId}/push-scm?repoUrl=&repoMode=EXISTING` (`api/controller/RunController.pushToScm`) -> `integration/scm/ScmPushService.push(run)`. Legacy alias `POST /api/runs/{runId}/push-github` (`@Deprecated`) honours the same flow with honest no_install/pushed status (BUG-018).
3. ScmPushService picks Bitbucket first if credentials exist, else GitHub. `pushIfConfigured` chains the two.
4. Bitbucket path (`pushToBitbucket`): `gitTool.pushToBranchBitbucket` -> `bitbucketRepoService.createPullRequest`. Honours `project.branchNamingPattern` (audit P #78).
5. GitHub path (`pushToGitHubIfConfigured`): NEW = `gitHubRepoService.createRepo` then push; EXISTING = branch push + `createPullRequest` + `requestReviewers` from `project.defaultReviewers` JSON.
6. Multi-repo path (`pushMultiRepo`): each linked repo cloned under `repos/<name>/` is pushed/PR'd separately, then PRs cross-link via `gitHubRepoService.updatePullRequestBody` with the sibling PR list.
7. Branch naming: `resolveBranchName(project, run)` honours `project.branchNamingPattern` placeholders `{runId}`/`{description}`, default `executespec/<runIdShort>`.
8. Per-repo commit message (`buildPerRepoCommitMessage`): per-repo title + Repo + Run + file list capped at 30 + `Co-Authored-By: ExecuteSpec Agent <admin@executespec.ai>` trailer.
9. Apply-suggestion push (`pushApplyChanges`): `gitTool.pushToExistingBranch` per repo on the existing PR branch, fast-forward only, no force-push, no PR-create. Closes Fix L (run a076c876 -- outer git treated nested clones as submodules).
10. PRs persist to `pull_request_record` via `PullRequestRecordService.recordCreated`. The first PR URL is stamped on `pipeline_run.github_pr_url` for the UI; full per-repo list at `GET /api/runs/{id}/prs`.

---

## 15. Sync from origin

Goal: reset the run's workspace to the current origin HEAD (or to the SHAs captured at run start) so the caller can retry a failed task without re-cloning.

1. `POST /api/runs/{runId}/sync?branch=<name>` (`RunController.syncRun`) -> `state/RunSyncService.syncRun`. Returns `{state:synced|partial|failed, ...}` -- distinct from the prior boolean (BUG-019).
2. Service iterates each repo in the workspace (`<workspace>/<runId>/` for single-repo, `<workspace>/<runId>/repos/<name>/` for multi-repo), runs `git fetch origin` then `git reset --hard origin/<branch>`.
3. `POST /api/runs/{runId}/sync/original` (`RunController.syncRunToOriginal`) -> resets to the SHAs captured at run-start time, persisted in `run_repo_snapshot` by `WorkspaceInitService.cloneMultipleRepos`. Useful when origin has drifted but the run was authored against an older base.
4. Cancellation / interrupted state is preserved -- sync doesn't change `pipeline_run.status`.
5. Tier gate: caller must hold `RunAccessGuard` -- run owner or org member.

---

## 16. Cancel run

Goal: stop an in-flight run, signal any blocked plan gate, interrupt the pipeline thread, and close any in-flight PRs.

1. `DELETE /api/runs/{runId}` (`RunController.cancel`) -> `integration/RunOrchestrator.cancel(runId)`.
2. Orchestrator flips `pipeline_run.status` to CANCELLED, sets `statusReason` to `CANCELLED_BY_USER`.
3. Calls `GateStore.clearPlanGate(runId)` -- if the plan gate is open and awaiting approval, the polling `awaitPlanGate` loop sees the gate cleared and returns `false`, dropping out of the pipeline cleanly.
4. Interrupts the pipeline future (`activeRuns.get(runId).cancel(true)`). `SequentialExecutor.execute` checks `Thread.currentThread().isInterrupted()` at the top of each task loop iteration and breaks; closes dev-run 7483896a regression where cancel mid-T11 cascaded T11->T20 into FAILED.
5. Forced LLM-mid-call abort is bounded by the per-provider client behaviour; the budget cap acts as the second line of defence.
6. PRs opened by this run are NOT auto-closed today (would need a separate GitHub API sweep on `pull_request_record`).
7. Returns 200 immediately. Termination event fires via `PipelineEvent.RunFailed` so the refund listener (flow 12 step 7) credits the user back.

Tier gate: caller must own the run or be ORG_ADMIN.

---

## 17. Archive and restore run

Goal: hide a run from default listings without deleting it, with a 30-day undo window for soft-delete.

1. Archive: `POST /api/runs/{runId}/archive` (`RunController.archiveRun`, V191) sets `pipeline_run.archived_at = now()`. Run is excluded from `GET /api/runs` unless `?archived=true` is passed. Reversible.
2. Unarchive: `POST /api/runs/{runId}/unarchive` (`RunController.unarchiveRun`, V191) clears `archived_at`.
3. Soft-delete: `POST /api/runs/{runId}/soft-delete` (`RunController.softDeleteRun`, V191) sets `deleted_at = now()`. 30-day undo window.
4. Restore: `POST /api/runs/{runId}/restore` (`RunController.restoreRun`, V191) clears `deleted_at`.
5. Soft-delete sweeper (`PR-H 2026-05-13`) reads `findByDeletedAtBefore(thirtyDaysAgo)` and hard-deletes (DB row + GCS workspace zip).
6. Listing visibility uses `RunVisibilitySpec` -- `scope=mine|org|all` (SUPER_ADMIN only), `archived` flag, and the org boundary. Cross-org `ProjectMember` collaborators see runs in the projects they were invited to (V175 + V198, flow 18).

Tier gate: caller must own the run or be ORG_ADMIN.

---

## 18. External project sharing (V198 flag)

Goal: invite a user from a different org to a project as PROJECT_ADMIN / PROJECT_DEVELOPER / PROJECT_VIEWER, gated by a per-org switch that ORG_ADMINs control.

1. Pre-flight: an ORG_ADMIN flips `org.external_sharing_enabled` via `PUT /api/org/me/settings/external-sharing` with `{enabled:true}` (`auth/controller/OrgController.updateExternalSharing`). Audit row `ORG_EXTERNAL_SHARING_CHANGED` is written. Default is `false`. Only ORG_ADMIN-of-own-org or SUPER_ADMIN can toggle.
2. `POST /api/projects/{projectId}/invites` (`sharing/controller/ProjectInviteController.create`) with `{email, projectRole}` -> `sharing/service/ProjectInviteService.createInvite`.
3. Three-gate `assertCanInvite` (V198, 2026-05-16):
   - Tier: caller's org must be PRO+ (FREE/STARTER -> 403).
   - Role: caller must be ORG_ADMIN-of-own-org OR SUPER_ADMIN (PROJECT_ADMIN alone is no longer enough since V198).
   - Org flag: `org.external_sharing_enabled=true`.
4. Service generates a 32-byte URL-safe token, persists `project_invite` (status PENDING, 14-day expiry default), and sends an email via `MailService` (best-effort).
5. Recipient lands on `/invites/preview?token=...` which calls `GET /api/invites/preview` (no auth) -> returns `{email, projectId, projectRole, status, expiresAt}` so the UI can show what's being offered.
6. Recipient registers / logs in if needed, then `POST /api/invites/accept?token=...` (`ProjectInviteController.accept`) -> `ProjectInviteService.accept`. Service requires the caller's email to match the invite, idempotent: re-running on ACCEPTED is a no-op. Writes a `ProjectMember` row (cross-org) with the granted role.
7. Cross-org member now sees the project in `GET /api/projects` (filtered via `project/security/ProjectAccessGuard.assertCanRead` -- org-match OR cross-org `project_member`).
8. PROJECT_VIEWER role is read-only: `ProjectAccessGuard.assertCanWrite` rejects them (V198). Note audit: several controllers still use `assertCanRead` as a write gate (`SpecTemplateController`, `ProjectDocumentController`, `ProjectScoresController`, etc.), see `bucket-project-research.md` for the full list.
9. Public share links (`POST /api/projects/{projectId}/share-links` -> `sharing/service/ProjectShareLinkService.createLink`) are an independent surface and NOT gated by `external_sharing_enabled`. Tier-gated via `TierConfig.shareLinkActiveCap` (-1 = unlimited). Read endpoints at `/api/shared/{token}/{meta,run,validation-report,code-review,pr-review,architecture}` (`ProjectSharePublicController`) are public no-auth.
10. Revoke: `DELETE /api/projects/{projectId}/invites/{inviteId}` flips status to REVOKED.

Feature flag history: prior to V198 (2026-05-16), any PROJECT_ADMIN could create cross-org invites. V198 added the per-org switch + role tightening.

---

## 19. BYOK setup

Goal: an org provides its own Anthropic / OpenAI / Gemini API keys so platform-tier cost caps don't apply and credits aren't charged.

1. `PUT /api/org/me/settings/ai-keys` (`auth/controller/OrgController.updateAiKeySettings`) with `{anthropicKey, openAiKey, geminiKey}` -> `auth/service/OrgService.updateAiKeySettings`. Keys are encrypted at rest via `auth/security/EncryptedValueConverter` on the `org.anthropic_api_key` / `openai_api_key` / `gemini_api_key` columns.
2. `PUT /api/org/me/settings/key-source` -> `OrgController.updateKeySourceSettings` with `{keySourceMode, enabledProviders}`. Modes:
   - `SYSTEM` (default for FREE/PRO): always use platform keys. Cost-capped.
   - `OWN_KEYS`: always use org keys. Cost-cap exempt (BYOK).
   - `HYBRID`: prefer org keys with platform fallback. Cost-capped.
3. Validation: `POST /api/auth/settings/ai-keys/validate` (per-user) or `POST /api/org/me/settings/ai-keys/validate` issues a sanity-check call against each provider to surface bad keys before they reach the pipeline.
4. At submit time, `RunOrchestrator.submitRun` resolves keys via `auth/service/ApiKeyResolver.resolve(orgId, provider)`:
   - OWN_KEYS -> 503 if missing org key for chosen provider.
   - HYBRID -> org key first, platform fallback.
   - SYSTEM -> platform key.
5. `RunKeyContextStore.resolveAndPut(runId, userId)` (V165 fix) snapshots the resolved keys at run-start into Redis so mid-run provider failover stays consistent. `evict(runId)` runs in the orchestrator's `finally` block.
6. Cost cap exemption: `RunOrchestrator.runPatchPipeline` (and BUILD-mode) checks `org.keySourceMode == OWN_KEYS` and skips `clampHardCap` (2026-05-08).
7. Quota still counts: BYOK orgs hit the `pr_review_monthly_quota` / `code_review_monthly_quota` per-tier counters because these gate access to the feature, not the LLM cost. Run quota is also still enforced.

---

## 20. GDPR export

Goal: GDPR Article 15+20 -- give a user a downloadable ZIP of every record about them.

1. `GET /api/auth/me/data-export` (`auth/controller/AuthController.dataExport`) -> `auth/service/UserDataExportService.exportFor(userId)`. Returns `application/zip` with `Content-Disposition: attachment`.
2. Service builds a manifest + payload archive:
   - `manifest.json`: index of every file in the ZIP, schema versions, export timestamp.
   - `README.md`: plain-English description of the export shape.
   - `user.json`: AppUser row (encrypted-at-rest columns decrypted).
   - `orgs/`: every org the user is a member of, full Org row.
   - `runs/`: every PipelineRun owned by the user, including AgentTask rows, ValidationReport snapshots.
   - `projects/`: every Project where the user is the creator OR member.
   - `payments/`: PaymentAudit rows, BillingSubscription, Invoice rows, BillingProfile.
   - `notifications/`: in-app notification rows.
   - `activity-audit/`: hash-chained ActivityAudit rows (M-019) where the user is the actor.
3. Repository scans intentionally include tombstones: `auth/repository/ProjectRepository.findByOrgId(String)` (includes soft-deleted) is the GDPR variant.
4. Right-to-erasure: `DELETE /api/auth/me/data` -> `AuthController.deleteMyData` -> `AuthService.softDeleteUser`. Soft-deletes the user, anonymises personal fields (email/name -> `deleted-{uuid}@anonymised.local`), and tombstones (does not hard-delete) the run history so audit chains stay verifiable.
5. Real hard-delete is deferred 30 days via the `PR-H` sweeper (flow 17 step 5).
6. JWT invalidation: the user's session epoch (`AppUser.jwtEpoch`) is bumped so all outstanding access + refresh tokens are immediately rejected by the filter chain.

---

## Cross-cutting notes

**Async dispatch.** Long-running flows (PR Review, Code Review, Architecture regen, project chat tools) all share a pattern: a fast-path enqueue (sync controller -> 202) plus a dedicated daemon thread pool (`pr-review-dispatch`, `code-review-dispatch`, `run-apply`, `slack-notif-`, `v2-orchestrator`). Each pool is sized via `PoolSizing.coreMultiple(...)`. Virtual threads (`Thread.ofVirtual().start(...)`) wrap snapshots from `ContextSnapshotFactory.captureAll()` so traceId + RequestAttributes survive thread boundaries.

**Tenant boundaries.** Every project-scoped endpoint flows through `project/security/ProjectAccessGuard.assertCanRead` or `assertCanWrite`. Every run-scoped endpoint flows through `auth/security/RunAccessGuard`. `arch/AccessGuardCoverageTest` pins every controller method touching `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant guard; ten known-legacy sites remain in `KNOWN_LEGACY_DRIFT`.

**Feature flags.** All flag checks go through `config/featureflag/FeatureFlagService.isEnabled(key)` -- never hardcode toggles. Live flags relevant to flows above: `ci.autofix.enabled` (flow 6, OFF), `codemap.purpose-summaries` (flow 8, OFF default), `v2.pause.enabled` (pause/resume, OFF default), `scoring.v2-verified` (flow 12), `lint.autofix.enabled` (run-time lint pass), `harness.v2.batch_enabled` (batch path scaffold), `security.guardrail_level` (LOW/MEDIUM/HIGH).

**Webhook auth.** GitHub (`POST /webhooks/github`), Razorpay (`POST /api/webhooks/razorpay`), Stripe (`POST /api/webhooks/stripe`), Slack, Jira, Confluence, PagerDuty, ServiceNow, and Bitbucket all verify HMAC SHA-256 signatures via `MessageDigest.isEqual` (constant-time). Webhook idempotency goes through `WebhookEventLog` (gateway, eventId) dedup or `IntegrationEventLog` for external sources.

**SSE.** Every long-running flow exposes an SSE channel: `/api/runs/{runId}/events`, `pr-review-{id}`, `code-review-{id}`. Tokens are scoped 5-min (`GET /api/runs/{id}/sse-token` -> `api/controller/SseController.getSseToken`). The frontend reconnects with `Last-Event-ID`.

**Cost tracking.** Every LLM call flows through `cost/RunCostTracker.record(bucket, provider, model, response)`. Buckets are run-scoped (`runId`) or feature-scoped (`pr-review-{id}`, `code-review-{id}`, `project-chat-{projectId}-{timestamp}`). Cache-aware pricing reads from `application.yml model-matrix.model-pricing`. Cache-read input tokens are recorded for all three providers.

**Workspace ephemerality.** Disk is ephemeral on Cloud Run / GCE; the DB blobs are authoritative. Terminal-state workspaces archive to GCS at `{env}/workspace/{runId}.zip` via `deployment/WorkspaceArchiveJob` (`@Scheduled cron=0 0 * * * *`, ShedLock-protected). `deployment/WorkspaceRestoreService.ensureWorkspace` lazy-restores on demand (sync, retry, file-download endpoints).
