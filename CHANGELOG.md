# Changelog

All notable changes to ExecuteSpec are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

---

## [1.5.1] — 2026-04-09

### Added
- **Billing plan restructure** — 4 new plans (Pro Monthly INR/USD, Max Monthly INR/USD). MAX tier with BYOK, unlimited runs, 3 concurrent. Old STARTER/PRO plans soft-deactivated. PLATFORM key mode renamed to MANAGED. (V62 migration)
- **Topup package restructure** — 6 split INR/USD packages (5/10/20 runs each). Old packages deactivated. Currency toggle on BillingPage. GST label on INR prices. (V63 migration)
- **Prorated refund on upgrade** — When upgrading (e.g. PRO → MAX), unused runs refunded automatically. Refund = MIN(runs-based, time-based) to prevent gaming. Razorpay partial refund issued, credit note generated, refund email sent.
- **BYOK enforcement for MAX tier** — `ApiKeyResolver.validateKeyAvailability()` blocks MAX users without API keys. Frontend: warning banner in NewRunModal, Launch button disabled, provider pills greyed out with lock icon for unconfigured providers.
- **Top-up button** — Sidebar and Header show "Top-up Runs" for PRO users (hidden for MAX/ENTERPRISE — unlimited runs). Links to `/billing#topup` with auto-scroll.
- **RBAC map** — Comprehensive `docs/RBAC.md` documenting all roles, tiers, endpoint authorization, permission matrix, and frontend access controls.
- **GST in invoice email** — Invoice email body now shows subtotal, GST (18%), and total amount. Thymeleaf template updated with conditional GST row.

### Changed
- **Provider naming: Google → Gemini** — Backend `ModelController.PROVIDER_LABELS` and all frontend references (OrgProjectsPage, PrivacyPage, TermsPage, DpaPage) now display "Gemini" instead of "Google" for the AI provider.
- **Sidebar buttons restyled** — Upgrade and Top-up buttons now use `bg-accent-blue` solid fill (matching Launch Run button) instead of muted grey/translucent styles.
- **Unlimited runs display** — BillingPage shows "X runs used (Unlimited)" instead of "X / -1" for MAX tier. Progress bar hidden for unlimited metrics.
- **Upgrade pill for PRO** — Header shows Upgrade pill for both FREE and PRO users.
- **`canManageAiKeys` for solo MAX users** — `TierPermissionService.freeUserPermissions()` now grants `canManageAiKeys` for MAX/ENTERPRISE solo users (was always false).
- **Buy Extra Runs hidden for MAX** — Top-up section on BillingPage hidden for MAX/ENTERPRISE tiers.
- **Right-click disabled** — Context menu prevented across entire UI.

### Fixed
- **Duplicate email on upgrade** — `upgradeTier()` no longer sends `sendTierUpgradeEmail`; `activateSubscription()` already sends `sendSubscriptionActivated`. Single email per activation.
- **Refresh token blocklist race condition** — `INSERT ... ON CONFLICT DO NOTHING` atomic upsert prevents duplicate key errors on concurrent logout.
- **Upgrade blocked for PRO → MAX** — Tier rank comparison now allows upgrades to higher tiers. Same/lower tier blocked with "Cancel first" message.
- **Refund policy in Terms** — DisclaimerModal item 4 updated with full prorated refund policy. New item 5 for non-refundable top-ups.

---

## [1.5.0] — 2026-04-09

### Added
- **Batch auto-retry** — Failed batch tasks are automatically retried in real-time after batch results are processed. `onBatchComplete()` queries for FAILED tasks, resets them to PENDING, and executes via `SequentialTaskExecutor` before compile.
- **Manual test execution (Beta)** — New "Run Tests" button in PreviewTab allows manual test execution against a running preview for batch runs. `POST /api/runs/{runId}/run-tests` validates preview is RUNNING and test plan is COMPILED, then triggers `TestPreviewCoordinator.triggerManualExecution()`.
- **Batch badge** — Emerald pill badge on RunCard (list view) and RunDetailTabs (detail view) indicating batch execution mode. `approvedExecutionMode` field added to `RunSummary` DTO.
- **Batch model attribution** — `BatchResumeService` now resolves the WORKHORSE-tier model via `ModelResolver` and sets `model`/`provider` on each completed task, fixing "unknown" display in token usage.
- **`StateStore.resetTaskForRetry()`** — JPQL update resetting task status to PENDING with cleared failure fields for batch retry flow.
- **`StateStore.setTaskModelProvider()`** — Sets model/provider on tasks after batch resume without coupling to `markTaskComplete()`.

### Changed
- **Batch runs skip auto-preview** — `TestPreviewCoordinator.onTestsReady()` detects batch runs and publishes an empty `TestExecutionCompletedEvent` instead of starting auto-preview. Unblocks validation report generation without wasting preview resources.
- **Batch debug logging removed** — Removed raw API response dumps from `AnthropicBatchClient`, `GoogleBatchClient`, and `OpenAiBatchClient` (status responses, result bodies, truncated previews). Removed unused `truncate()` helpers.

---

## [1.4.0] — 2026-04-07

### Added
- **Phase-based subtask decomposition** — Complex single-file tasks (MEDIUM/HIGH complexity) now decompose into method-level phase subtasks. MEDIUM → 2 phases, HIGH → 3 phases. Phase 1 creates the file skeleton; subsequent phases read and extend it. Complements existing file-based decomposition for multi-file tasks.
- **Subtask write nudge** — WorkerAgent injects a forced write instruction when a subtask has consumed 5+ tool calls with zero `writeFile` dispatches. Breaks the read-loop pattern (Gemini reads repeatedly without writing). Nudge includes the owned file name and remaining budget.
- **Subtask iteration limit** — `SUBTASK_ITERATION_LIMIT = 15` for subtasks (vs 35 for normal tasks). 15 iterations gives headroom for context reads (CODEBASE_CONTEXT, task-assignments, file-locks, listFiles) + writeFile.
- **Fabrication escalation on iteration-limit failures** — `isFabricationFailure()` in `SequentialTaskExecutor` now also detects iteration-limit failures (`"tool-call iterations"`). Subtasks that hit the iteration cap are retried with PLANNING tier model.
- **Per-response tool call cap** — `MAX_TOOL_CALLS_PER_RESPONSE = 15` prevents Gemini's 100+ call batches from exhausting the iteration budget in one response.
- **Read-only tool dedup cache** — Caches results of read-only tools (readFile, listFiles, searchFiles, git_status, git_diff). Cache hits are FREE — don't count toward `toolCallCount`. Invalidated on file writes.
- **10s polling on run details** — RunsPage polls `fetchRun()` every 10 seconds while the run is in an active status.

### Changed
- **Status color mapping** — Distinct colors per semantic status group: Gray (inactive), Blue (executing), Purple (thinking/review), Amber (waiting), Green (success), Orange (partial), Red (failure), Pink (blocked). Updated across constants.ts, tokens.css, tailwind.config.ts, TaskGraph.tsx, TokenBubbleChart.tsx, ActivityFeed.tsx.
- **Logging levels** — Agent/compiler/reviewer/testgen loggers set to INFO (was DEBUG). Spring AI chat client set to WARN. Removes verbose LLM input/output noise from logs.
- **Worker prompts (all 3 variants)** — Added PROJECT STRUCTURE CONSISTENCY section to `worker-gemini.txt` and `worker-openai.txt` (was already in `worker.txt`). Language-specific rules for Java, Python, Node.js, Go, Rust to prevent cross-task package/module mismatches.
- **Reviewer prompt** — Added 5-axis REVIEW FLAGS, dynamic tool budget, FAST EXIT GUARD, 4-tier severity rules, verification story requirement.
- **Compiler prompt** — Added DEBUGGING METHODOLOGY, AMBIGUOUS SYMBOL RESOLUTION, CASCADING FAILURE AWARENESS, ERROR PRIORITY table.
- **Test planner prompt** — Added TEST STRATEGY PRINCIPLES, IMPLICIT TEST CASES, TEST ANTI-PATTERNS sections.
- **Test worker prompt** — Added PROVE-IT PRINCIPLE, AAA structure guidance, REAL OVER MOCK preference ordering.
- **Test fix suggestions prompt** — Added CLASSIFICATION taxonomy, CASCADING FAILURE DETECTION, increased word limit 60→80.
- **Validation report prompt** — Added PARTIAL RUN HANDLING section.

---

## [1.3.0] — 2026-04-06

### Added
- **Bitbucket SCM integration** — Full Bitbucket support as a second SCM provider alongside GitHub. `BitbucketRepoService` handles clone, branch creation, and PR via Bitbucket REST API v2 with Basic auth. `BitbucketAdapter` (InboundAdapter + OutboundAdapter) under `integration/bitbucket/` for webhook ingestion and outbound PR comments on run completion. App password encrypted at rest via `EncryptedValueConverter` (AES-256-GCM).
- **SCM-agnostic pipeline push** — `PipelineHarness.pushToScmIfConfigured()` replaces `pushToGitHubIfConfigured()` at all 4 call sites. Routes to GitHub or Bitbucket based on user credentials. New `scm_pr_url` and `scm_provider` columns on `pipeline_run` (backward compat: `github_pr_url` retained).
- **Bitbucket API endpoints** — `PUT /api/auth/settings/bitbucket` (save credentials), `POST /api/auth/settings/bitbucket/validate` (test connection), `POST /api/runs/{runId}/push-scm` (SCM-agnostic push).
- **Bitbucket frontend integration** — Bitbucket card on IntegrationsPage with credential form (username, app password, workspace, repo slug), test connection, and save. SCM-agnostic PR link and push button on RunDetail (dynamic label: "View on GitHub" / "View on Bitbucket").
- **V59 migration** — `bitbucket_username`, `bitbucket_app_password`, `bitbucket_workspace`, `bitbucket_repo_slug` on `app_user` and `pipeline_run`; `scm_pr_url`, `scm_provider` on `pipeline_run`.
- **Responsive frontend design** — Comprehensive mobile/tablet support across all pages:
  - New shared utilities: `useBreakpoint()` hook (xs/sm/md/lg/xl), `ResponsiveModal` (full-screen on mobile), `ResponsiveTable` (cards on mobile, table on desktop)
  - RunDetailTabs: scrollable tab bar, responsive metadata grid
  - TaskGraph: vertical card list on mobile, SVG graph on desktop
  - NewRunModal: full-screen sheet on mobile
  - DeployWizardModal: full-screen on mobile, responsive step indicator
  - AdminPage: `overflow-x-auto` tables, hidden secondary columns on mobile, responsive filters
  - BillingPage, PlansPage, SettingsPage, AuditPage, InvoicesPage, AccountingPage, DeploymentsPage: responsive grids, stacking layouts, touch-friendly buttons
  - CommandPalette: mobile-sized results list, 44px touch targets
  - PreviewTab: responsive iframe height, wrapped controls

### Changed
- **Planner prompt** — Added "SINGLE-FILE COMPLEXITY TRAP" rule: complex single-file components (calendars, dashboards, form wizards with 4+ features) must be split into sequential tasks building on each other in the same file. Prevents 35-iteration exhaustion on monolithic UI tasks.
- **GitTool** — Added `pushToBranchWithBasicAuth()` for Bitbucket URL format (`username:appPassword@bitbucket.org`)

### Deprecated
- `pushToGitHubIfConfigured()` in PipelineHarness (use `pushToScmIfConfigured()`)
- `POST /api/runs/{runId}/push-github` endpoint (use `/push-scm`)
- `github_pr_url` field on PipelineRun (use `scmPrUrl`)

---

## [1.2.2] — 2026-04-06

### Changed
- **Google PLANNING model** — Upgraded from `gemini-2.5-flash` → `gemini-3-flash-preview` based on eval results (9.5 overall, 5/7 pass rate, 22s avg latency vs 9.1/4/7/51s). WORKHORSE and LIGHTWEIGHT remain `gemini-2.5-flash`.
- **Model matrix YAML** — Added `gemini-3-flash-preview` to canonical model registry, updated legacy aliases

---

## [1.2.1] — 2026-04-06

### Fixed
- **Gitignore** — Fixed `eval-output/` → `eval-outputs/` to match actual folder name

---

## [1.2.0] — 2026-04-06

### Added
- **Subtask decomposition** — `SubtaskDecomposer` splits multi-file tasks into per-file subtasks at execution time. Each subtask produces exactly one `writeFile` call, eliminating fabrication and reducing token waste on retries. Tracked via `parentTaskId`, `subtaskIndex`, `subtaskTotal` on `AgentTask`.
- **Self-play prevention** — `FABRICATED_TOOL_RESULT_PATTERN` strips fabricated `<tool_result>` blocks from Anthropic model responses before tool call parsing. Prevents model from simulating entire tool conversations in a single response.
- **XML-first parsing for PATH B** — WorkerAgent now uses `parseXmlToolCalls()` first for PATH B providers (Anthropic/Google), with `parseJsonToolCalls()` as fallback. Fixes JSON short-circuit that lost 2nd/3rd tool calls in multi-tool responses.
- **Task graph subtask grouping** — Frontend `TaskGraph.tsx` rewritten with subtask groups: smaller nodes (164×36) grouped under parent with dashed border, expand/collapse chevron, progress bar ("N/M"), and aggregate status dot. Backward compatible with non-subtask runs.
- **V58 migration** — `parent_task_id`, `subtask_index`, `subtask_total` columns on `agent_task`

### Changed
- **Model matrix** — Fixed CLAUDE.md and README.md model matrix tables to match actual YAML config (Anthropic WORKHORSE = `claude-haiku-4-5-20251001`, Google = `gemini-2.5-flash`)
- **Anthropic tool calling** — Moved from native tool calling (broken in Spring AI 1.1.0) to XML prompt-based PATH B with self-play prevention

### Removed
- **LlmRouter debug logging** — Removed temporary Anthropic request/response debug logs (~30 lines)

### Files Added
- `harness/SubtaskDecomposer.java` — Multi-file task decomposition
- `resources/db/migration/V58__subtask_support.sql` — Subtask schema

### Files Modified
- `agent/WorkerAgent.java` — Self-play prevention, XML-first parsing, truncation salvage fix
- `agent/LlmRouter.java` — Removed debug logging
- `api/controller/RunController.java` — TaskGraphNode includes parentTaskId/subtaskIndex/subtaskTotal
- `frontend/src/lib/api.ts` — TaskGraphNode type updated with subtask fields
- `frontend/src/components/runs/TaskGraph.tsx` — Full rewrite with subtask grouping
- `state/entity/AgentTask.java` — Subtask decomposition fields
- `harness/SequentialTaskExecutor.java` — SubtaskDecomposer integration

---

## [1.1.0] — 2026-04-04

### Added
- **Design system integration** — 54 company design systems (Stripe, Linear, Notion, Supabase, Spotify, etc.) loaded from filesystem-backed DESIGN.md files with Caffeine caching (30min TTL)
- **3-signal UI style resolution** — explicit user hint > planner `ui_style` > project-type heuristic; covers planned and single-shot paths
- **Token-budget truncation** — design files capped at 2500 tokens, keeping priority sections (theme, colors, typography, components, layout)
- **Fuzzy design matching** — exact, lowercase, contains, base-name (.app/.ai strip), and prefix matching against 54 available designs
- **Planner UI style detection** — planner prompt updated to output `ui_style` field with curated design list
- **GCS design sync** — design files synced from GCS bucket to GCE instance on every deploy and boot (`gsutil -m rsync`)
- **CSP `frame-src` directive** — allows Cloud Run preview iframes (`*.run.app`) on the parent page
- **21 unit tests** for design system features (UiStyleResolverDesignTest)

### Changed
- **Default AI provider** — Launch New Run always defaults to OpenAI (ignores localStorage persistence)
- **Worker prompt injection** — replaced static `css-styling.txt` with dynamic DESIGN.md content per run
- **PipelineHarness** — 3-signal resolution wired after planning and in single-shot path

### Removed
- **Model override dropdown** — removed from Launch New Run modal (auto model selection only)
- **css-styling.txt** — replaced by filesystem-backed DESIGN.md files

### Files Added
- `harness/DesignSystemProperties.java` — config properties for design system
- `test/.../UiStyleResolverDesignTest.java` — 21 unit tests

### Files Modified
- `harness/UiStyleResolver.java` — major refactor: filesystem loading, cache, fuzzy match, 3-signal resolution
- `agent/WorkerAgent.java` — design content loading per run
- `agent/model/TaskPlan.java` — added `uiStyle` field
- `harness/PipelineHarness.java` — 3-signal resolution wiring
- `resources/prompts/planner.txt` — UI STYLE DETECTION instruction
- `resources/application.yml` — `executespec.designs` config block
- `config/SecurityProperties.java` — added `frame-src` CSP directive
- `frontend/.../ProviderModelSelector.tsx` — default to OpenAI, removed model override
- `.github/workflows/deploy.yml` — GCS design sync step
- `infra/scripts/startup.sh` — GCS design sync on boot

---

## [1.0.0] — 2026-04-03

### Initial Production Release

- **Multi-agent pipeline** — PlannerAgent → Plan Gate → SequentialTaskExecutor (tiered parallel) → CompilerAgent → Auto-merge → COMPLETE → Auto-preview → Background validation
- **3-provider LLM routing** — Anthropic, Google, OpenAI with YAML model matrix, Redis-backed circuit breaker (5 failures → open, 60s recovery)
- **Agent tiers** — PLANNING (Opus/GPT-5.4/Gemini Pro), WORKHORSE (Sonnet/GPT-5.4-mini/Flash), LIGHTWEIGHT (Haiku/GPT-5.4-mini/Flash)
- **Progressive merge** — each task merged to main immediately after completion
- **Request triage** — single-shot fast path for simple requests
- **Background validation** — ReviewerAgentV2 + TestGenOrchestrator + ValidationReport run after COMPLETE
- **Dual-mode previews** — Cloud Run (dev/prod, 5-min TTL) and Docker Compose (local, Caddy proxy)
- **JWT auth + OAuth2** — Google and GitHub login, RBAC with 6 roles, scoped SSE tokens
- **Multi-gateway billing** — Razorpay + Stripe via PaymentGatewayRouter, subscriptions, top-up credits, GST invoicing
- **Feature flags** — DB-backed with Redis pub/sub cache invalidation
- **Rate limiting** — Redis-backed per-tenant sliding window
- **8-layer security** — request validation, input sanitization, LLM prompt defense, code abuse scanning, shell sandbox, file sandbox, process isolation, container hardening
- **Integrations** — JIRA, Confluence, Slack, GitHub (branch + PR creation), ServiceNow, PagerDuty
- **Eval framework** — model evaluation across tiers and providers
- **GCP infrastructure** — GCE + Cloud SQL + Redis + GCS + Load Balancer + Cloud Run, Terraform-managed
- **CI/CD** — GitHub Actions with Workload Identity Federation, GCS JAR releases, health checks
- **Observability** — Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)
- **56 Flyway migrations** (V1–V56)
