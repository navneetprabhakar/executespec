# Changelog

All notable changes to ExecuteSpec are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

---

## [1.1.3] — 2026-04-06

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
